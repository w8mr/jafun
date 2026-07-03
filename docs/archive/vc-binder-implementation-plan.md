# VCBinder Implementation Plan

## Goal

Rebuild `VCBinder` following `docs/vc-unboxing-process.md` so that Jafun value classes
unbox to scalar JVM types wherever possible, while preserving type safety.

## Approach (Phased, Tested)

We rebuild `VCBinder` as a composition of **pure functions**, each tested in
isolation in `core/src/commonTest/kotlin/nl/w8mr/jafun/compiler/ir2jvm/VCBinderTests.kt`.

Each function takes IR nodes (or data) and returns IR nodes — they don't compile
classes, just transform trees.

## Reusable Helpers (already exist in `VCFlattening.kt`)

- `flattenType(type, prefix)` — list of `(path, type)` for a VC type, recursing into nested VCs.
- `expandVariable(symbol)` — list of `JFVariableSymbol` for a variable's flattened components.
- `isMultiFieldVC(type)` — true for VC with >1 field, OR single-field VC wrapping a multi-field VC.
- `expandParameterRecursively(type, baseName)` — list of `Parameter` for an expanded type.
- `expandAssignmentIfNeeded(assignment)` — expands `ValAssignment/VarAssignment` with CI RHS to scalar assignments; populates `expandedFields` and `expandedFieldSymbols` on the original symbol.
- `effectiveJvmType(type)` — unwraps single-field VCs to inner types.
- `unboxSingleFieldVCType(type)` — innermost type of a single-field VC chain.

## Concepts to Implement (mapped to doc rules)

| Concept | Pure function | Doc rule |
|---|---|---|
| Eager unbox at assignment with CI RHS | (reused `expandAssignmentIfNeeded`) | R1 |
| FieldAccess on unboxed VC → scalar Variable | `resolveFieldAccessToScalar(node)` walks a chained FieldAccess, builds path string, looks up in `expandedFieldSymbols` | R3 |
| Multi-field VC param → scalar params | `expandParameterForVC(param)` (uses `expandParameterRecursively`) | n/a |
| Call site arg expansion | `expandCallSiteArgs(node, methodSigs)` — when calling an expanded method, expand args to scalars | bridge between methods |
| Reconstruct boxed VC at call site | `reconstructFromScalars(node)` — when called with unboxed scalars but method expects boxed, build a `ConstructorInvocation` from scalars | R4 |
| Single-field VC return unboxing | `unboxSingleFieldReturn(node, originalReturnType, unboxedReturn)` — at function boundary, replace return of CI(VC) with the scalar arg | R5 |

## handle() = composition

```kotlin
fun handle(context) = context.copy(
    methods = context.methods.map { method ->
        // 1. Compute expanded params + unboxed return type  (Phase 1)
        val expandedParams = method.parameters.flatMap { expandParameterForVC(it) }
        val unboxedReturn = unboxSingleFieldRetType(method.returnType)
        val methodSigs = (current method's params and return updated)

        // 2. Walk body, apply pure transformations
        method.instructions
            .flatMap(R1::expandCIAssignment)     // eager unbox at CI assignment
            .map(instr -> R5::unboxReturn)        // unbox return at boundary
            .map(transformTree(R3, R4, callExpand))  // field access + call site
    }
)
```

## Status (current build state)

The current `VCBinder` already has:
- **R1** — `expandAssignmentIfNeeded` reused on ValAssignment/VarAssignment
- **R3** — `resolveFieldAccessToScalar` resolves a chained FieldAccess to a scalar Variable
- **Param expansion** — `expandParameterForVC` flattens multi-field VC params
- **`setExpandedFieldsOnSymbol`** — propagates `expandedFields`/`expandedFieldSymbols` to any
  `JFVariableSymbol` whose type is a multi-field VC (solves issue #1)
- **`expandCallSiteArgs`** — rewrites `MethodInvocation` nodes to expand args when the target
  method had expanded params. Handles Variable args (via `expandedFieldSymbols`) and
  ConstructorInvocation args (via `flattenCIArgs`).

### Pure functions (tested in isolation in VCBinderTests)

| Function | Input | Output | Tests |
|---|---|---|---|---|
| `expandAssignmentIfNeeded` | `ValAssignment`/`VarAssignment` | `List<Phase2_3Expression>` | 4 (R1) |
| `expandParameterRecursively` | `OperandType`, baseName | `List<Parameter>` | 2 (paramExpand) |
| `resolveFieldAccessToScalar` | `Phase2_3Expression` | `Phase2_3Expression?` | 4 (R3) |
| `setExpandedFieldsOnSymbol` | `JFVariableSymbol` | Unit (side-effect on symbol) | 5 |
| `flattenCIArgs` | `ConstructorInvocation` | `List<Phase2_3Expression>?` | 4 |
| `expandArgForCallSite` | arg, param | `List<Phase2_3Expression>?` | 4 |
| `expandCallSiteArgs` | node, methodSigs | `Phase2_3Expression?` | 9 |
| `unboxSingleFieldReturnExpr` | `Phase2_3Expression` | `Phase2_3Expression` | 4 |
| `referentialListDiff` | `List<T>`, `List<T>` | `Boolean` | 4 |
| `resolveFieldAccessOnCallResult` | node, methodSigs | `Phase2_3Expression?` | 6 (R2) |

Also added inline:
- **Multi-field VC reconstruction** (in `resolveFieldAccessToScalar`) — when a FieldAccess
  on an expanded parameter targets a multi-field VC (e.g. `box.topLeft` where `Point` has
  2 fields), reconstruct via `ConstructorInvocation(Point.constructor, [scalar...])`.
- **Variable type propagation** (Step 2c in `handle()`) — after R5 unboxes a method's return
  type, Step 2c updates the variable symbol's `effectiveType` to match the expression type,
  so all `Variable` nodes report the correct type and Step 2b can fix `Convert.from`.

### CompilerTest results: all 8 VC tests now pass

All 8 previously-failing VC-specific CompilerTest tests now pass:

| Test | What it covers | Fixed by |
|---|---|---|
| `vcReturnSingleField` | R5: MI return unboxed | `unboxSingleFieldReturnExpr` + `expandCallSiteArgs` rtnLookup + Step 2b |
| `vcReturnSingleFieldWithVal` | R5 + val assignment type propagation | Step 2c: variable `effectiveType` |
| `vcReturnSingleFieldFieldAccess` | R5 + R3: `makeId().value` | R2 `resolveFieldAccessOnCallResult` |
| `vcReturnSingleFieldFieldAccessWithVal` | R5 + val + field access | Step 2c + R2 |
| `testBoxSingleField` | Multi-field VC param expansion + body reconstruction | Extended `resolveFieldAccessToScalar` multi-field VC reconstruction |
| `testBoxSingleFieldWorking` | Same, inline CI arg | Same as above |
| `testMultiArgReconstruction` | R4: reconstruct boxed VC from scalars | Same as above (body reconstruction effectively implements R4) |
| `vcFieldAccessOnCallResult` | R2: field access on multi-field call result | Multi-field VC reconstruction + `resolveFieldAccessOnCallResult` guard |

### Test results (current)

- `VCBinderTests[jvm]` — **44 tests passing** (+6 new R2 `resolveFieldAccessOnCallResult` tests,
  +3 new from multi-field reconstruction path)
- `CompilerTest[jvm]` — **100 tests, 0 failures** (all 8 previously-failing VC tests now pass)
- All other suites — green (no regressions)
- **Grand total: 246 tests, 0 failures**

### Fixed issues

1. ~~Parameter `expandedFields` not set on body Variable symbols~~ — Solved by
   `setExpandedFieldsOnSymbol`, called from `handle()` during Step 1b.

2. ~~Call site arg expansion not implemented~~ — Solved by `expandCallSiteArgs`,
   `expandArgForCallSite`, and `flattenCIArgs`. Integrated in `handle()` Step 2.

3. ~~`transformTree` list comparison uses structural equality (`!=`) instead of
   referential (`!==`), so `MethodInvocation.rtnLookup` changes (which are excluded
   from `equals()`) silently disappear~~ — Fixed by replacing `!=` with
   `referentialListDiff` (element-wise `!==`) in `TreeTransform.kt`.

4. ~~R5 basic (direct call site without variable)~~ — `vcReturnSingleField` now passes.
   `unboxSingleFieldReturnExpr` implemented + `expandCallSiteArgs` updates `rtnLookup`
   for return type changes. Step 2b (`fixedConverts`) updates `Convert.from` when
   inner expression type changes after R5.

5. ~~R5 variable type propagation (Step 2c)~~ — After R5 unboxes a return type,
   ValAssignments/VarAssignments update their variable symbol's `effectiveType`.
   All `Variable` nodes delegate to `effectiveType ?: type`, so references see the
   correct type and Step 2b fixes `Convert.from`.

6. ~~R2 field access on unboxed call result (Step 2d)~~ — `resolveFieldAccessOnCallResult`
   eliminates redundant `FieldAccess` on single-field VC call results that have been
   unboxed (e.g. `makeId().value` → `makeId()` when `makeId` returns `Int`).
   Guarded by `unboxSingleFieldVCType` to only apply when the VC is actually unboxable.

7. ~~Multi-field VC body reconstruction~~ — Extended `resolveFieldAccessToScalar` to
   reconstruct multi-field VCs from expanded parameter scalars. When `box: Box` is expanded
   to `[box_topLeft_x, box_topLeft_y]` and the body does `box.topLeft` (accessing a
   multi-field `Point`), the field access is replaced by
   `ConstructorInvocation(Point.constructor, [Variable(box_topLeft_x), Variable(box_topLeft_y)])`.
   This effectively implements R4 (reconstruction from scalars) for function bodies.

### Remaining known issues

None — all 8 previously-failing VC tests now pass.

## Module structure

All VC binding logic lives in a single file:
- `VCBinder.kt` (`commonMain`) — the `handle()` pipeline and all pure functions
- `VCBinderTests.kt` (`commonTest`) — 44 unit tests covering each function in isolation

Helpers reused from `VCFlattening.kt`:
- `flattenType`, `expandVariable`, `expandAssignmentIfNeeded`, `expandParameterRecursively`,
  `isMultiFieldVC`

## Edge cases not yet tested

- Nested multi-field VC access (e.g. `box.topLeft.someMultiFieldVC`) — the reconstruction
  only handles single-level field access; deeper nesting where the inner VC is also
  multi-field is not yet tested.
