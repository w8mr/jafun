# Value Class Pipeline Plan

## Goal

Make the JVM backend completely VC-agnostic by moving all VC unboxing logic into a dedicated post-Phase 3 pass. Phase 3 (AST2IR) treats value classes as normal classes; the new pass transforms the "dumb" IR into unboxed IR before JVMBackend sees it.

## Principles

1. **Parser stays**: `value class` keyword → `VALUE_CLASS` class kind, `isInlineValueClass`, constructor/field access syntax
2. **Phase 3 is VC-naive**: constructors, field accesses, method params/calls all emit normal IR nodes
3. **New pass is sole VC unboxer**: runs after Phase 3, before JVMBackend
4. **JVMBackend is VC-free**: no `effectiveJvmType`, no `isInlineValueClass`, no `buildValueClass`, no VC unwrapping
5. **Helper infra preserved**: `VCFlattening.kt`, `ValExpansionPhase.kt`, `ParameterExpansionPhase.kt` kept
6. **Non-VC tests always pass**

---

## Current State (`c26eb4e`)

### Test count: 201 total, 1 VC failure (`vcInvalidDeepField`)

### Where VC code lives today

| File | VC code | Notes |
|---|---|---|
| `Phase1Parser.kt` | `VALUE_CLASS` kind | Keep |
| `ParserJafun.kt` | VC field-access syntax | Keep |
| `Types.kt` | `ClassKind.VALUE_CLASS`, `isInlineValueClass`, `ExpandedField`, `constructorArgs` | Keep (data only) |
| **AST2IR.kt** | `expandAssignmentIfNeeded`, `buildFunctionParameters`, `expandValueClassParams`, `tryResolveExpandedFieldAccess`, inline constructor unwrap, inline FieldAccess identity shortcut | **Remove** |
| **JVMBackend.kt** | `isInlineValueClass` in `conversion()` (L206), `buildValueClass` (L275), `isInlineValueClass` in return-type check (L336), `valueClasses` in `compileAll` (L269) | **Remove** |
| **Compiler.kt** | `extractValueClasses`, `runVCPhases`, `valueClasses` in builder | **Refactor** |
| `VCFlattening.kt` | All helper functions | Keep |
| `ValExpansionPhase.kt` | val/var expansion | Keep |
| `ParameterExpansionPhase.kt` | JVM signature expansion | Keep |

---

## Target Pipeline

```
Parser (Phase 1) → Structure (Phase 2) → AST2IR (Phase 3) 
    → Phase 4a: VCBinder [NEW]
    → Phase 4b: ValExpansionPhase [KEPT]
    → Phase 4c: ParameterExpansionPhase [KEPT]
    → JVMBackend [CLEANED]
```

### Phase 4a (NEW): `VCBinder`

Transforms VC-naive IR into unboxed IR. Operates on `IRBuilder.ClassContext` (same signature as existing phases).

Capabilities:
1. **VC Constructor → unbox**: `C(B(A(42)))` → `Lit(42)` (single-field) or expanded assignments (multi-field)
2. **Field access on VC variable → direct variable access**: `c.b.a.value` where `c` is `C(b: B(a: A(value: Int)))` → `Variable(c_b_a_value)`
3. **VC function-parameter expansion**: method params of VC type → flattened list of primitive params
4. **VC call-site unboxing**: pass primitives instead of VC objects
5. **VC return-value boxing**: wrap primitive return into VC box class if needed
6. **val/var assignment unboxing**: expand multi-field VC assignment to per-field assignments

---

## Work Phases

### Phase A — Clean AST2IR

Remove all VC-specific code from `AST2IR.kt`:

- [ ] Remove `expandAssignmentIfNeeded` (body)
- [ ] Remove inline constructor-unwrap in `compileExpressionNode` (L238–243)
- [ ] Remove inline FieldAccess identity shortcut in `compileExpressionNode` (L357–363)
- [ ] Remove call to `tryResolveExpandedFieldAccess` (L367–368, L371–372)
- [ ] Remove inline single-field Variable shortcut (L391–400)
- [ ] Remove `buildFunctionParameters` body — strip to just naive params (no flatMap, no `shouldExpandVC`, no `skipExpansion`)
- [ ] Remove `expandValueClassParams` — replace with identity pass-through
- [ ] Remove `tryResolveExpandedFieldAccess` function
- [ ] Remove unused imports (`flattenType`, `expandVariable`, `shouldExpandVC`, `createNestedFieldAccess`, `reconstructVCFromExpanded`, `expandParameterRecursively`, `effectiveJvmType`, `isMultiFieldVC`, `ExpandedField`)

After: AST2IR treats every type uniformly — VCs are just classes.

**Verification**: run `./gradlew jvmTest` — same 201/1 result.

### Phase B — Clean JVMBackend

Remove all VC-specific code from `JVMBackend.kt`:

- [ ] Remove `isInlineValueClass` loop in `conversion()` (L205–208) — `effectiveFrom = from`
- [ ] Remove `isInlineValueClass` loop in return-type check of `buildClass` (L335–338) — `effectiveLastType = lastType`
- [ ] Remove `buildValueClass` function (L275–299)
- [ ] Remove `valueClasses` iteration from `compileAll` (L269–271)
- [ ] Remove `IRBuilder.ValueClassDef` dependency

After: JVMBackend has zero knowledge of value classes.

**Verification**: run `./gradlew jvmTest` — same 201/1 result (VC tests that pass today may break; non-VC tests unaffected).

### Phase C — Refactor Compiler Pipeline

- [ ] Remove `extractValueClasses` and `valueClasses` from `ast2ir` builder
- [ ] Flatten `runVCPhases` to include new Phase 4a before existing phases
- [ ] Order: `VCBinder → ValExpansionPhase → ParameterExpansionPhase`

### Phase D — Implement VCBinder (Phase 4a)

Design and implement the new pass. Key sub-steps:

#### D1. VC constructor unboxing

Given:
```
val c = C(B(A(42)))  // type C(b: B), B(a: A), A(value: Int)
```
AST2IR currently emits:
```
ValAssignment(c, ConstructorInvocation(C, [ConstructorInvocation(B, [ConstructorInvocation(A, [Lit(42)])])]))
```

VCBinder detects:
- assignment to `c` of type `C` (`VALUE_CLASS`)
- RHS is `ConstructorInvocation` with matching type

Transforms to:
- Single-field chain `C → B → A → Int` → unbox to `Lit(42)`
- Multi-field `Point(x: Int, y: Int)` → expand to `val p_x = <expr.x>; val p_y = <expr.y>`

#### D2. Field access on VC variable

Given:
```
c.b.a.value  // where c: C(b: B(a: A(value: Int)))
```
AST2IR currently emits:
```
FieldAccess(FieldAccess(FieldAccess(Variable(c), b), a), value)
```

VCBinder detects:
- `Variable(c)` where `c.type` is `VALUE_CLASS` and `c` has `expandedFields`
- Builds path `c_b_a_value` → resolves through `expandedFieldSymbols`
- If resolved: emit `Variable(c_b_a_value)` directly
- If unresolved (e.g., chained access on non-variable): replace with ConstructorInvocation → FieldAccess on the constructor arg

#### D3. Field access on VC call result

Given:
```
createPoint().x  // createPoint() returns Point
```
AST2IR emits:
```
FieldAccess(MethodInvocation(createPoint), x)
```

VCBinder: cannot unbox a method call result inline. Leave as:
```
FieldAccess(MethodInvocation(createPoint), x)
```
JVM handles this as a normal field access on the boxed object.

#### D4. VC function parameters

Given:
```
fun getX(p: Point): Int { p.x }
```
AST2IR emits function body with `p` as `Point` type and body `FieldAccess(Variable(p), x)`.

VCBinder:
- For single-field VCs (containing primitives): expand `p:x: Int` into the function body references
- For multi-field VCs: leave as-is (JVM passes object)
- Store mapping from original param to expanded params on the method context

#### D5. VC call-site unboxing

Given:
```
getX(Point(1, 2))
```
AST2IR emits `MethodInvocation(getX, [ConstructorInvocation(Point, [Lit(1), Lit(2)])])`.

VCBinder:
- Look up function's signature expansion
- If param is expanded: unbox the argument inline (extract field from CI or FieldAccess from var)
- If param is not expanded: pass as-is

#### D6. VC return value handling

Given:
```
fun getId(): Id { return Id(42) }
```
AST2IR emits function returning VC type `Id`.

VCBinder:
- For function definition: leave return type as `Id`
- For call site `getId()`: if used in primitive context → unwrap; if assigned to `val x: Id` → keep boxed
- JVM's `buildClass` handles the return type via `signature(type)` which works for any `JFClass`

### Phase E — Tests

- [ ] All non-VC tests still pass
- [ ] VC tests pass (or are expected failures pending Phase D completion)
- [ ] Add unit tests for VCBinder

---

## File Change Summary

| File | Change |
|---|---|
| `AST2IR.kt` | Remove ~200 lines of VC-specific code |
| `JVMBackend.kt` | Remove ~30 lines of VC-specific code (~15 lines replaced) |
| `VCBinder.kt` | NEW: ~300-400 lines |
| `Compiler.kt` | Simplify: remove `extractValueClasses`, update `runVCPhases` |
| `IRBuilder.kt` | Possibly remove `ValueClassDef` |
| `ValExpansionPhase.kt` | Reduced scope (binder handles VC construction) |
| `ParameterExpansionPhase.kt` | Unchanged |
| `VCFlattening.kt` | Unchanged (used by binder) |
| `Types.kt` | Unchanged |

---

## Risk & Mitigation

| Risk | Mitigation |
|---|---|
| Non-VC tests break from VC code removal | Every VC code path is guarded by `isInlineValueClass`/`VALUE_CLASS` checks — never reached by non-VC code. Verify after each removal. |
| VCBinder too complex in one pass | Split into: (1) val/var expansion, (2) field access resolution, (3) method expansion, (4) return-type handling |
| Box class generation still needed for JVM | `ParameterExpansionPhase` already generates the box class definition via `IRBuilder` |
| Order of phases matters | ValExpansionPhase must run AFTER VCBinder (VCBinder produces the expanded assignments that ValExpansionPhase may need to further process) |

---

## Verification Strategy

1. After each Phase A/B/C: `./gradlew jvmTest` — expect same non-VC pass rate
2. After Phase D: commit-by-commit test of specific VC scenarios
3. Final: all 201 tests + new VC-specific tests
