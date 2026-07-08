# Migration Plan: SymbolMap → SymbolTable

## Dependency Graph

```
Compiler.compile()
  ├── SymbolMapManager.reset()           → SymbolTable (fresh instance)
  ├── StdlibLoader.load(symbolMap)       → symbolTable.registerType() / registerMethod()
  ├── Phase1Parser(symbolMap)            → needs variable scope + method registration
  │   └── symbolMapManager.push/pop      → symbolTable.pushScope()/popScope()
  │   └── symbolMapManager.newVarSymb.   → symbolTable.addVariable()
  ├── ParserJafun(symbolMap)
  │   └── structurePass                  → symbolTable.registerMethod()
  │   └── parseBodies                    → symbolTable.actualReturnType()
  │   └── symbolMapManager.override()    → ??? (two-phase needs)
  ├── AST2IR                             → symbolTable.lookupMethodById()
  ├── VCFlattening                       → variable metadata (no scope ops)
  ├── VCBinder                           → symbolTable.lookupType()
  ├── JVMBackend                         → symbolTable.currentScopeId
  └── ...
```

**Key insight:** The old and new systems share the same conceptual operations (register type, find method, push scope, add variable). The main differences are API shape and the `replaceType`/`override` semantics that the new system eliminates.

---

## Can This Be Phased?

**Partially.** Concern boundaries are:

| Concern | Old type | New type | Isolatable? |
|---------|----------|----------|-------------|
| Local variables | `SymbolMapManager.add/find/replaceVar` | `SymbolTable.addVariable/lookupVariable` | **Yes** — no cross-phase dependency |
| Type registry | `IdentifierCache.findClass/addClass` | `SymbolTable.registerType/lookupType` | **Yes** — write-once during stdlib load |
| Method registry | `IdentifierCache.replaceType()` | `SymbolTable.registerMethod()` | **Hard** — `replaceType` semantics differ |
| Return type inference | `rtnLookup` reads from global method map | `actualReturnType(methodDef)` | **Tied to method registry** |
| JVM reflection | `expect fun findMethodsInClass()` | `JvmTypeResolver` | **Yes** — can coexist |
| Scope override | `symbolMapManager.override()` | Not needed | **Removed entirely** |

**The hard blocker:** `replaceType()` in `structurePass` writes methods into the GLOBAL `IdentifierCache.null` entry (overwriting). This is the root cause bug. The new system registers each method directly by FQDN — no overwrite. These two semantics are incompatible: you can't partially phase out `replaceType` because the old system corrupts the global state that the new system would rely on.

---

## Recommended Migration: Two-Phase Cutover

### Phase 1 — Parallel run, local variables only (safe)

Goal: Swap `SymbolMapManager` variable ops for `SymbolTable` scope ops without changing semantics. Both systems run side by side; the old system is still authoritative for type/method lookup.

**Changes:**

1. **Add `SymbolTable` field to `Compiler`, `Phase1Parser`, `ParserJafun`**
   ```kotlin
   class Compiler {
       val symbolTable = SymbolTable()
       val symbolMap = SymbolMapManager()
   }
   ```

2. **Replace `symbolMapManager.newVariableSymbol()` with `symbolTable.addVariable()`**
   - `Phase1Parser.valDeclaration` / `varDeclaration`:
     ```kotlin
     // old:
     symbolMapManager.newVariableSymbol(name, type, mutable)
     // new:
     symbolTable.addVariable(name, VariableDef(name, type, mutable))
     ```
   - `ParserJafun.funDeclaration` param registration: same pattern

3. **Replace `symbolMapManager.push()/pop()` with `symbolTable.pushScope()/popScope()`**
   - `Phase1Parser.betweenCurly1`: push at `{`, pop at `}`

4. **Replace `symbolMapManager.replaceVariableSymbol()` with `symbolTable.replaceVariable()`**
   - Variable reassignment parsing

5. **Replace `symbolMapManager.find(null, name)` with `symbolTable.lookupVariable(name)`**
   - All variable lookups in `Phase1Parser` and `ParserJafun`

6. **Add `currentScopeId` read in `JVMBackend`**
   - `JFVariableSymbol.symbolMap.symbolMapId` → `symbolTable.currentScopeId`

**Risks:**
- The old `SymbolMapManager` still tracks variables in its own maps. This means variables are stored TWICE (old + new). Memory overhead is negligible, but consistency must be verified.
- `JFVariableSymbol.symbolMap` is still read by `Inliner`, `VCFlattening`, `AST2IR`. These don't use the scope stack directly — they read the stored reference on the variable symbol. If we remove `symbolMap` from `JFVariableSymbol`, these break.
- The `JVMBackend` reads `symbolMap.symbolMapId` for unique naming. With the new system, this comes from `symbolTable.currentScopeId`, but `JFVariableSymbol` would need a `scopeId` field instead of `symbolMap`.

**Migration option for `JFVariableSymbol.symbolMap`:**
```kotlin
// Keep the field but point it to something compatible, or
// Add a companion field:
data class JFVariableSymbol(
    ...
    val scopeId: Int = 0,  // new, replaces symbolMap.symbolMapId
)
```
But `JFVariableSymbol` is a `Type` nested class — changing it affects the entire system. The alternative is to not change `JFVariableSymbol` in Phase 1 and keep reading `symbolMap.symbolMapId` from the old system for JVMBackend.

### Phase 2 — Type registry cutover

Goal: Stop using `IdentifierCache` for type lookups. Use `SymbolTable.typeRegistry` instead.

**Changes:**

1. **Rewrite `StdlibLoader.load()`** — instead of:
   ```kotlin
   // old:
   val intClass = symbolMap.findFromPath("jafun.lang.Int", null)
   IdentifierCache.replaceType(...)
   // new:
   val intFqdn = FQDN("jafun.lang.Int")
   symbolTable.registerType(intFqdn, TypeEntry(intFqdn, OperandType.SInt32))
   symbolTable.registerMethod(MethodDef(id=FQDN("jafun.lang.Int.+"), ...))
   ```

2. **Replace `symbolMap.findFromPath(path)` with `symbolTable.resolveDottedName(path)`**
   - Used in `ParserJafun` for type references in annotations, value classes, etc.

3. **Replace `IdentifierCache.findClass(name, parent)` with `symbolTable.lookupType(fqdn)`**
   - In `ParserJafun` for explicit type resolution

4. **Replace `findClassInPackage` (expect/actual) with `JvmTypeResolver.resolveClass()`**
   - Remove from `IdentifierCacheJvm.kt` and `IdentifierCacheJs.kt`
   - Implement `JvmTypeResolver.resolveConstructors()` (already done)

**Dependency:** Phase 1 must be complete first, because `StdlibLoader` creates the initial type registration and Phase 1's variable parsing may reference those types.

### Phase 3 — Method registry + return type cutover

Goal: Eliminate `replaceType()` and `rtnLookup`. Use `methodRegistry` and `inferredReturnTypes`.

**Changes:**

1. **`structurePass`** — instead of:
   ```kotlin
   // old:
   symbolMapManager.replaceType(name, methodClosure)
   // new:
   symbolTable.registerMethod(MethodDef(id=fqdn, name=methodName, ...))
   ```

2. **`rtnLookup` elimination** — wherever `findSingleOrNull(null, name)` is called for return type resolution:
   ```kotlin
   // old:
   val rtn = rtnLookup(obj, methodName)
   // new:
   val method = symbolTable.lookupMethodById(methodFqdn)
   val rtn = symbolTable.actualReturnType(method)
   ```

3. **`ParserJafun.parseBodies`** — after parsing a function body, update the inferred return type:
   ```kotlin
   symbolTable.setInferredReturnType(methodId, inferredType)
   ```

4. **Operator resolution** — `1 + 2` during parsing:
   ```kotlin
   // old:
   val candidates = symbolMapManager.findSingle(null, "+")
   // new:
   val candidates = symbolTable.findMethodByFirstParamType("+", lhsType)
   ```

**Dependency:** Phase 2 must be complete (types must be registered before methods can reference them).

### Phase 4 — Cleanup

1. Remove `SymbolMapManager`, `LocalSymbolMap`, `IdentifierCache`
2. Remove `SymbolMap` interface
3. Remove `expect/actual findMethodsInClass`, `findConstructorsInClass`, `findClassInPackage`
4. Remove `symbolMap` field from `JFVariableSymbol` (or keep as deprecated)
5. Remove `SymbolMapManagerTests`, `SymbolMapTests`
6. Rename `docs/symboltable_design.md` → `docs/symboltable.md` (it's no longer proposed)

---

## Detailed File-by-File Change Plan

### Phase 1 (local variables)

| File | Changes |
|------|---------|
| `Compiler.kt` | Add `val symbolTable = SymbolTable()`, pass to constructors |
| `Phase1Parser.kt` | Replace `symbolMapManager.newVariableSymbol()` → `symbolTable.addVariable()`, `push/pop` → `pushScope/popScope`, `find(name)` → `lookupVariable(name)` |
| `ParserJafun.kt` | Same variable-related replacements |
| `SymbolMapManager.kt` | Keep but deprecate addVariable/find/replaceVariable |
| `JVMBackend.kt` | Add `symbolTable.currentScopeId` read, keep legacy `symbolMapId` |
| `JFVariableSymbol` (Types.kt) | No change yet |

### Phase 2 (type registry)

| File | Changes |
|------|---------|
| `StdlibLoader.kt` | Rewrite to use `symbolTable.registerType()` and `registerMethod()` instead of `replaceType()` and `findFromPath()` |
| `IdentifierCache.kt` | Deprecate or freeze `reset()` — types come from SymbolTable now |
| `IdentifierCacheJvm.kt` / `IdentifierCacheJs.kt` | Remove `findClassInPackage`, keep `findMethodsInClass` for Phase 3 |
| `ParserJafun.kt` | Type references: `findFromPath` → `resolveDottedName`, `findClass` → `lookupType` |

### Phase 3 (method registry)

| File | Changes |
|------|---------|
| `ParserJafun.kt` — `structurePass` | `replaceType(name, method)` → `registerMethod(def)` |
| `ParserJafun.kt` — `parseBodies` | `rtnLookup` → `actualReturnType`, add `setInferredReturnType` |
| `IdentifierCache.kt` | Remove `replaceType`, simplify to type-only map |
| `LocalSymbolMap.kt` | Remove `replaceType` override (falls through) |

### Phase 4 (removal)

| File | Action |
|------|--------|
| `SymbolMapManager.kt` | Delete |
| `LocalSymbolMap.kt` | Delete |
| `SymbolMap.kt` | Delete |
| `IdentifierCache.kt` | Delete |
| `IdentifierCacheJvm.kt` | Delete (functionality in `JvmTypeResolver`) |
| `IdentifierCacheJs.kt` | Delete or implement `JsTypeResolver` |
| `Types.kt` | Remove `symbolMap` from `JFVariableSymbol` |
| `ExpressionNode.kt` | Remove `symbolMap` from `CurlyBlock` |
| Various `symbolMap` imports | Clean up |

---

## What Survives the Migration

The following types and concepts are **not affected** — they stay as-is:

- `OperandType` hierarchy (`SInt32`, `SInt64`, `StringType`, `JFClass`, etc.)
- `Type` hierarchy (`JFClass`, `JFMethod`, `JFConstructor`, `JFVariableSymbol`, `JFField`, etc.) — though `JFVariableSymbol.symbolMap` is removed in Phase 4
- `Associativity`, `Phase1Token`, `ExpressionNode` types (except `CurlyBlock.symbolMap`)
- `Phase1Parser` tokenization logic (only symbol management changes)
- `ParserJafun` expression parsing (only name resolution changes)
- `VCFlattening` / `VCBinder` / `Inliner` — only the `symbolMap` field reference changes
- `JVMBackend` — only the `symbolMapId` read changes to `currentScopeId`
- All bytecode generation logic

---

## Test Migration

| Test file | Action |
|-----------|--------|
| `SymbolMapTests.kt` | Remove (tests `LocalSymbolMap` internals) |
| `SymbolMapManagerTests.kt` | Remove (tests old facade) |
| `SymbolTableTest.kt` | Expand (already 56 tests covering new system) |
| `JvmTypeResolverTest.kt` | Keep and expand (19 tests covering JVM fallback) |
| `SimpleParserTest.kt` | Keep — tests parser output, not symbol internals |
| `Phase1SimpleParserTest.kt` | Keep — update `CurlyBlock` expected values |
| `CompilerTestUtils.kt` | Remove `IdentifierCache.reset()` / `findClass` references |

---

## Migration Risks

1. **`JFVariableSymbol.symbolMap` removal in Phase 4** — This field is read by `Inliner.kt:348`, `VCFlattening.kt:86`, `AST2IR.kt:201`. These reads must be replaced with `symbolTable.currentScopeId` or the scope ID carried on the variable symbol itself.

2. **`CurlyBlock.symbolMap` removal** — The old system stores the `SymbolMap` on each `CurlyBlock` so that `ParserJafun` can restore the correct scope when merging two-pass parsing. The new system doesn't need this because the `SymbolTable` is not a hierarchical tree — it's a flat set of maps + a scope stack. But `ParserJafun`'s `override()` mechanism would need to be replaced with `SymbolTable` scope saves/restores or a different approach for two-pass parsing.

3. **`IdentifierCache.reset()` called in `CompilerTestUtils.test()`** — Each test creates a fresh `SymbolTable`, so no reset is needed. This is a simplification, not a risk.

4. **Replacing `symbolMapManager.override()`** — The old system uses this during `structurePass`/`parseBodies` to temporarily install a different `SymbolMap` while parsing sub-trees. The new `SymbolTable` doesn't have this concept because each concern is in a separate map — there's no single "scope" to swap. The equivalent would be passing a sub-scope or saving/restoring the local scope state.
