# SymbolTable — Proposed Design

## Principles

1. **Separate maps for separate concerns** — no shared `Map<TypeSymbol?, Map<String, Set<TypeSymbol>>>`
2. **Method registry keyed by name** (`"+"`, `"println"`) not by type — the type/qualifier is data within the candidate, resolved during later phases
3. **Types have FQDNs** — `jafun.lang.Int`, not bare `"Int"`. Implicit imports (`jafun.lang.*`) provide short names, just like `java.lang.*` in Java/Kotlin.
4. **Declared return type takes precedence** — `MethodDef.rtn` is the contract. Inferred types only matter when no explicit return type was declared.
5. **Local scope** is a stack of variable-only maps (unchanged from current approach)
6. **Manager auto-fills** from available resources (stdlib .jf files, JVM reflection via `TypeResolver`)

---

## 1. The Maps (Separated Concerns)

### 1a. Type Registry — `Map<FQDN, TypeEntry>`

Stores mapping from FQDN to operand type / class info. One entry per type.

```kotlin
data class TypeEntry(
    val fqdn: FQDN,                        // e.g. "jafun.lang.Int"
    val operandType: OperandType<*>,        // e.g. SInt32
    val jvmName: String? = null,           // e.g. "java.lang.String" for StringType
)
```

Primitive types use their full package path. `"jafun.lang.*"` is implicitly imported (like `java.lang.*`), so users write just `Int`.

**Contents after stdlib load:**
```
"jafun.lang.Int"        → TypeEntry(SInt32)
"jafun.lang.Long"       → TypeEntry(SInt64)
"jafun.lang.String"     → TypeEntry(StringType, jvmName="java.lang.String")
"jafun.lang.Char"       → TypeEntry(CharType)
"jafun.lang.Boolean"    → TypeEntry(UInt1)
"java.lang.String"      → TypeEntry(StringType)          (JVM bridge alias)
"jafun.lang.IntKt"      → TypeEntry(JFClass)
...
```

### 1b. Method Registry — `Map<String, Map<FQDN, MethodDef>>`

Keyed by **method name** (e.g. `"+"`, `"println"`), NOT by type. The type/qualifier is part of the candidate data. This allows lookup before type information is fully known — during early phases you see `+` and search by name, later you filter by parameter types.

```kotlin
data class MethodDef(
    val id: FQDN,                           // fully qualified: "jafun.lang.Int.+"
    val name: String,                        // short name: "+"
    val parentFqdn: FQDN?,                   // module/class: "jafun.lang.Int"
    val parameters: List<Parameter>,          // name + OperandType
    val rtn: OperandType<*>,                 // DECLARED return type (from source)
    val static: Boolean = false,
    val operator: Boolean = false,
    val associativity: Associativity = Associativity.PREFIX,
    val precedence: Int = 10,
    val inline: Boolean = false,
)
```

A function with no explicit return type in source gets `rtn = Unknown`.

**Contents after stdlib load:**
```
"+" → {
  "jafun.lang.Int.+"   → MethodDef(id, name="+", params=[SInt32,SInt32], rtn=SInt32),
  "jafun.lang.Long.+"  → MethodDef(id, name="+", params=[SInt64,SInt64], rtn=SInt64),
}
"println" → {
  "jafun.io.ConsoleKt.println" → MethodDef(name="println", params=[String], rtn=Unit),
}
```

**JVM methods are disambiguated with parameter descriptors:**
```
"contains" → {
  "java.lang.String.contains(java.lang.CharSequence)" → MethodDef(...),
}
"println" (on PrintStream, from JVM) → {
  "java.io.PrintStream.println(java.lang.String)" → MethodDef(...),
  "java.io.PrintStream.println(int)" → MethodDef(...),
  "java.io.PrintStream.println()" → MethodDef(...),
}
```

Key insight: `"+"` maps to TWO candidates under different inner keys. They coexist because `"jafun.lang.Int.+"` ≠ `"jafun.lang.Long.+"`. No overwrite.

### 1c. Inferred Return Types — `MutableMap<FQDN, OperandType<*>>`

Stores what type inference has determined so far. Starts at `Unknown` (widest — "anything possible"). Narrows monotonically as the fixpoint progresses. Represents the **lower bound** of what inference has proven.

```kotlin
// Key: same as MethodDef.id (FQDN)
// Value: currently inferred type (starts at Unknown, narrows over iterations)
// Codegen reads:
//   if (methodDef.rtn != OperandType.Unknown) methodDef.rtn
//   else inferredReturnTypes[id] ?: OperandType.Unknown
```

**Semantics** — inference narrows the possible types as more information becomes available:

```
fun test() { ... }
  → structurePass:        inferredReturnTypes["Script.test"] = Unknown
  → parseBodies iteration 1: callees not yet resolved → still Unknown
  → parseBodies iteration 2: callees resolved, body is `1+2` → SInt32
  → parseBodies iteration 3: stable → SInt32 (no change → done)
```

The **declared** return type always takes precedence. The inferred map is only a fallback:

```kotlin
fun actualReturnType(id: FQDN, methodDef: MethodDef): OperandType<*> =
    if (methodDef.rtn != OperandType.Unknown) methodDef.rtn
    else inferredReturnTypes[id] ?: OperandType.Unknown
```

Example — `fun test() { 1+2 }` (no explicit return type):
1. structurePass: `MethodDef(rtn = Unknown)` for `"Script.test"`
2. parseBodies infers `SInt32` → `inferredReturnTypes["Script.test"] = SInt32`
3. `actualReturnType()` → `methodDef.rtn == Unknown` → use `inferredReturnTypes["Script.test"] = SInt32`

Example — `fun add(a: Int, b: Int): Int = a + b` (explicit return type):
1. structurePass: `MethodDef(rtn = SInt32)` for `"Script.add"`
2. parseBodies infers `SInt32` → `inferredReturnTypes["Script.add"] = SInt32`
3. `actualReturnType()` → `methodDef.rtn == SInt32 ≠ Unknown` → use `methodDef.rtn = SInt32`

The map is **write-only during inference**, read-only during codegen. This decouples the mutable inference process from the immutable method definitions.

### 1d. JVM Bridge Map — `Map<String, FQDN>`

Maps JVM class names to jafun FQDNs. Used by `invokevirtual`/`invokestatic` and Java reflection bridge.

```kotlin
"java.lang.String"     → "jafun.lang.String"
"java.lang.System"     → "java.lang.System"
"java.io.PrintStream"  → "java.io.PrintStream"
"java.lang.Integer"    → "java.lang.Integer"
```

### 1e. Package Tree

Hierarchical tree structuring all known packages:

```kotlin
data class PackageNode(
    val name: String,                           // simple name
    val parent: PackageNode?,
    val fqdn: FQDN,
    val subPackages: MutableMap<String, PackageNode>,
    val classes: MutableMap<String, FQDN>,       // simple name → type FQDN
    val functions: MutableMap<String, Set<FQDN>>, // simple name → method IDs
)
```

Tree structure:
```
(root)
├── jafun
│   ├── lang
│   │   ├── classes: Int, Long, String, Char, Boolean
│   │   └── functions:
│   │       +  → {jafun.lang.Int.+, jafun.lang.Long.+}
│   │       -  → {jafun.lang.Int.-, jafun.lang.Long.-}
│   │       *  → {jafun.lang.Int.*, jafun.lang.Long.*}
│   │       /  → {jafun.lang.Int./, jafun.lang.Long./}
│   │       == → {jafun.lang.Int.==, jafun.lang.Long.==, jafun.lang.Char.==}
│   ├── io
│   │   └── functions: println → {jafun.io.ConsoleKt.println}
│   └── test
│       └── functions: <=> → {jafun.test.TestKt.<=>}
│       └── classes: POJO
└── java
    └── lang
        └── classes: String, System, Integer, Long, Boolean, Character
            (created lazily by TypeResolver fallback)
```

### 1f. Import Map — `Set<PackageRef>`

Per compilation unit. Determines which packages are searched when resolving short names.

```kotlin
// Implicit imports (always present, like Java's java.lang.*):
//   jafun.lang.*
//   jafun.io.*          (for println etc.)
//   java.lang.*         (for JVM bridge types)
//
// User imports (from 'import' statements):
//   com.example.mylib.*
```

### 1g. Local Scope Stack

Unchanged from current approach — a stack of variable-only maps. Does NOT contain type or method entries.

```kotlin
class LocalScope(
    val parent: LocalScope? = null,
    val id: Int = nextId,         // globally unique per scope, replaces symbolMapId
) {
    companion object {
        private var counter = 0
        private val nextId: Int get() = counter++
    }
}
```

Each scope has a globally unique `id` (auto-incremented counter) to replace the old `SymbolMap.symbolMapId`. Accessed via `SymbolTable.currentScopeId` — used by JVMBackend for unique variable naming (`"${scopeId}.${name}"`).

### 1h. TypeResolver Interface

Lazy runtime fallback for types, methods, fields, and constructors not found in the registry. Provides JVM reflection-based resolution that auto-populates the SymbolTable on first access.

```kotlin
interface TypeResolver {
    fun resolveClass(fqdn: String): TypeEntry?
    fun resolveMethods(classFqdn: String, methodName: String): List<MethodDef>
    fun resolveField(classFqdn: String, fieldName: String): VariableDef?
    fun resolveMember(classFqdn: String, memberName: String): MemberResult?
    fun resolveConstructors(classFqdn: String): List<MethodDef>
}
```

**JvmTypeResolver** implements this via `Class.forName` and reflection:

- `resolveClass` — maps JVM primitives to jafun operand types (e.g. `int` → `SInt32`, `java.lang.Integer` → `SInt32`, `java.lang.String` → `StringType`); wraps reference types as `Type.JFClass`
- `resolveMethods` — `Class.getMethods()`, filtered by name. Method FQDNs are disambiguated with a `(param1,param2)` descriptor suffix so overloads on the same class have unique keys
- `resolveField` — `Class.getField()`, maps field type to operand type

**Registration on access** — when `lookupType(fqdn)` misses the registry and the resolver finds the class:

1. `TypeEntry` is registered in `typeRegistry`
2. Package tree entries are created (e.g. `java.lang` → class `String`)
3. Subsequent short-name resolution via imports picks it up

This means `lookupType` is the single entry point for lazy loading — the resolver is never consulted directly by consumer code.

### 1i. MemberResult and ResolutionResult

The chain resolver needs a way to express what a resolved member is:

```kotlin
sealed class MemberResult {
    data class Method(val def: MethodDef) : MemberResult()
    data class Field(val def: VariableDef, val fieldTypeFqdn: String) : MemberResult()
}
```

The final result of a dotted name resolution:

```kotlin
sealed class ResolutionResult {
    data class Type(val entry: TypeEntry) : ResolutionResult()
    data class Method(val def: MethodDef, val parentType: TypeEntry) : ResolutionResult()
}
```

Note: `Field` is deliberately absent from `ResolutionResult` — `resolveDottedName` returns either a type or a method call. Field access at the end of a chain returns null; the parser handles fields as intermediate steps, not terminal.

---

## 2. Lookup Flow

### Operator resolution: `1 + 2`

```
1. Parse '1' → IntegerLiteral, type = SInt32
2. See '+' token
3. Query methodRegistry by name: methodRegistry["+"]
   → { "jafun.lang.Int.+" → MethodDef(params=[SInt32,SInt32]),
       "jafun.lang.Long.+" → MethodDef(params=[SInt64,SInt64]) }
4. Filter by firstParamType == SInt32
   → "jafun.lang.Int.+" matches
5. Resolve return type: methodDef.rtn (= SInt32) is declared → use directly
```

### Function call: `println("hello")`

```
1. See 'println' token
2. Query methodRegistry: methodRegistry["println"]
   → { "jafun.io.ConsoleKt.println" → MethodDef(...) }
3. Single candidate, parse arguments to verify type match
4. Create MethodInvocation
```

### Dot notation: `input.length`

```
1. Parse 'input' → Variable, type = StringType
2. See '.' token
3. Resolve StringType to FQDN via typeRegistry
   → typeRegistry.findByOperandType(StringType) = "jafun.lang.String"
4. Resolve "jafun.lang.String" to its JVM class via jvmBridge or package tree
   → "java.lang.String"
5. Look up "length" in that class's method scope
   → MethodDef from JVM reflection
```

### Dotted chain: `java.lang.System.out.println`

The `resolveDottedName(dottedName)` method implements a backwards-walk algorithm to find the longest matching type prefix in a chain, then resolves remaining members via the TypeResolver:

```
resolveDottedName("java.lang.System.out.println"):
1. Split on '.' → ["java", "lang", "System", "out", "println"]
2. Walk backwards:
   a. Try "java.lang.System.out.println" as type → ClassNotFoundException
   b. Try "java.lang.System.out" as type → ClassNotFoundException
   c. Try "java.lang.System" as type → Class.forName("java.lang.System") → found!
      - Registers type in typeRegistry and package tree
3. Resolve member chain starting at System with members ["out", "println"]:
   a. "out" → resolver.resolveMember("java.lang.System", "out")
      → MemberResult.Field(def, fieldTypeFqdn="java.io.PrintStream")
   b. "println" → resolver.resolveMember("java.io.PrintStream", "println")
      → MemberResult.Method(def)
4. Return ResolutionResult.Method(def, parentType=System)
```

For pure type references:
```
resolveDottedName("java.lang.String"):
1. Try "java.lang.String" as type → Class.forName → found!
2. No remaining members → ResolutionResult.Type(StringTypeEntry)
```

For type + single method:
```
resolveDottedName("java.lang.String.contains"):
1. Find "java.lang.String" as type
2. Resolve member "contains" → MemberResult.Method(def)
3. Return ResolutionResult.Method(def, parentType=String)
```

If the chain ends at a field (e.g. `java.lang.System.out`), `resolveDottedName` returns `null` — the compiler/parser handles field access as a separate concern. The `TypeResolver.resolveMember` API provides both `MemberResult.Method` and `MemberResult.Field` for callers that need field resolution.

### Infix with receiver: `a + b`

Same as operator resolution: find by name `"+"`, filter by `parameters[0].type == a.type`.

---

## 3. Registration Flow

### StdlibLoader (loading Int.jf)

```
1. Parse .jf file → List<Function>
2. For each function:
   a. Determine FQDN: "jafun.lang.Int.+" (classPath.name)
   b. Create MethodDef with declared rtn from .jf source
   c. Store: methodRegistry["+"]["jafun.lang.Int.+"] = MethodDef
   d. Add to package tree: jafun/lang/functions["+"] += "jafun.lang.Int.+"
```

### structurePass (user file)

```
1. Parse `fun test() { ... }`
2. Determine FQDN: "Script.test" (moduleName.name)
3. Create MethodDef (rtn = parsed return type or Unknown)
4. Store: methodRegistry["test"]["Script.test"] = MethodDef
5. No package tree entry (user module, not a library)
```

### parseBodies (return type inference)

```
1. Parse function body, infer type from last expression
2. Update: inferredReturnTypes["Script.test"] = SInt32
3. No changes to methodRegistry (MethodDef is immutable)
```

### JVM class resolution (runtime fallback, on-demand)

```
lookupType(FQDN("java.lang.String")):
1. Miss in typeRegistry → consult TypeResolver
2. JvmTypeResolver.resolveClass("java.lang.String"):
   a. Class.forName("java.lang.String") → Class<String>
   b. Map to OperandType.StringType
   c. Return TypeEntry(FQDN("java.lang.String"), StringType, jvmName="java.lang.String")
3. Register: typeRegistry["java.lang.String"] = TypeEntry(...)
4. Create package tree: java → lang → addClass("String", FQDN("java.lang.String"))
5. Return TypeEntry

Subsequent resolveTypeByShortName("String") (with import java.lang):
1. Find package "java.lang" → found (created by step 4)
2. Find class "String" → FQDN("java.lang.String")
3. lookupType("java.lang.String") → hit in typeRegistry (cached by step 3)
```

### Constructor resolution (runtime fallback)

```
resolveConstructors("java.lang.String"):
1. Class.forName("java.lang.String") → Class<String>
2. Reflect jClass.constructors for each:
   - Parameters mapped via jvmClassToOperandType
   - MethodDef with name="<init>", parentFqdn=String, rtn=String
   - FQDN disambiguated: "java.lang.String.<init>()"
                     or: "java.lang.String.<init>(java.lang.String)" (copy ctor)
   - Return type is the class itself
```

---

## 4. What Changes vs What Stays the Same

### Stays the same:
- `JFMethod`, `JFVariableSymbol`, `JFClass`, `JFConstructor`, etc.
- Local scope push/pop for `{}` blocks
- `newVariableSymbol` / `replaceVariableSymbol`
- `Phase1Parser` tokenization
- `ExpressionNode` hierarchy
- Parser combinators infrastructure

### Changes:
- **`SymbolMap` interface** → replaced by `SymbolTable` with separated methods
- **`IdentifierCache`** → split into type registry + method registry + JVM bridge
- **`LocalSymbolMap`** → replaced by `LocalScope` (variables only)
- **`SymbolMapManager`** → replaced by `SymbolTableManager` with import resolution
- **`StdlibLoader`** → no more `registerTarget` / `replaceType` logic, just direct store
- **`structurePass`** → no more `replaceType` leaking to global scope
- **`rtnLookup`** → removed; return type resolved from `methodDef.rtn` or `inferredReturnTypes`
- **`TypeResolver` interface + `JvmTypeResolver`** — new; lazy JVM reflection-based resolution for types, methods, fields, constructors not in the registry
- **`resolveDottedName`** — new; backwards-walk chain resolution returning `ResolutionResult.Type` or `.Method`
- **`resolveConstructors`** — new; constructor resolution via JVM reflection, MethodDef with `name="<init>"`
- **`LocalScope.id`** — new; globally unique auto-incrementing scope ID, replaces `symbolMapId` for JVMBackend variable naming

### No longer exist:
- `null` vs non-null scope qualifier (`find(type?, name)`)
- `replaceType` walking up parent chain
- Overwrite conflicts in `IdentifierCache.null`
- `findSingleOrNull(null, name)` for return type resolution
