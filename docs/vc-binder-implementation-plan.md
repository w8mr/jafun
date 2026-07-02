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
|---|---|---|---|
| `expandAssignmentIfNeeded` | `ValAssignment`/`VarAssignment` | `List<Phase2_3Expression>` | 4 (R1) |
| `expandParameterRecursively` | `OperandType`, baseName | `List<Parameter>` | 2 (paramExpand) |
| `resolveFieldAccessToScalar` | `Phase2_3Expression` | `Phase2_3Expression?` | 4 (R3) |
| `setExpandedFieldsOnSymbol` | `JFVariableSymbol` | Unit (side-effect on symbol) | 5 |
| `flattenCIArgs` | `ConstructorInvocation` | `List<Phase2_3Expression>?` | 4 |
| `expandArgForCallSite` | arg, param | `List<Phase2_3Expression>?` | 4 |
| `expandCallSiteArgs` | node, methodSigs | `Phase2_3Expression?` | 9 |
| `unboxSingleFieldReturnExpr` | `Phase2_3Expression` | `Phase2_3Expression` | 4 |
| `referentialListDiff` | `List<T>`, `List<T>` | `Boolean` | 4 |

### Known failures (7 CompilerTest)

| Test | Rule | Symptom |
|---|---|---|
| `vcReturnSingleFieldWithVal` | R5 | `val id = makeId()` stores int, but `println id` expects Object (missing boxing) |
| `vcReturnSingleFieldFieldAccess` | R5 + R3 | `println makeId().value` — field access on call result |
| `vcReturnSingleFieldFieldAccessWithVal` | R5 + R3 | Same with val assignment |
| `testBoxSingleField` | R5 + R2 | Single-field VC param + field access on function call result |
| `testBoxSingleFieldWorking` | R5 + R2 | Same, inline CI arg |
| `testMultiArgReconstruction` | R4 | Reconstruct boxed VC from scalars at call site |
| `vcFieldAccessOnCallResult` | R2 | Field access on function call result |

All 7 fail because variable type propagation after R5 unboxing is not implemented
(the variable's declared type stays `Id` but runtime value is `int`, so `Convert(from=Id, to=Object)`
is stale). Fixing this requires either updating variable symbol types after the return type
change, or adjusting the call site to insert the correct boxing Convert.

### Test results (current)

- `VCBinderTests[jvm]` — **35 tests passing** (R1, R3, paramExpand, setExpandedFieldsOnSymbol,
  flattenCIArgs, expandArgForCallSite, expandCallSiteArgs, unboxSingleFieldReturnExpr,
  referentialListDiff, transformTree integration)
- `CompilerTest[jvm]` — **7 tests failing** (see table above; was 12 before this commit, 5 fixed:
  `vcReturnSingleField`, plus the 4 `@Disabled` tests that were never enabled)
- All non-VC tests — green (no regressions)

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

### Open issues (remaining)

1. **R5 variable propagation** — When R5 unboxes a method's return type, call sites
   that assign to val/var need the variable's type updated AND all references to it
   need correct Convert.from values. Currently `Convert(from=Id, to=Object)` stays
   stale because the Variable's declared type is still `Id`.
2. **R4** — reconstruct boxed VC from scalars at call site.
3. **R2** — lazy unboxing on function call result.

## Module structure

All VC binding logic lives in a single file:
- `VCBinder.kt` (`commonMain`) — the `handle()` pipeline and all pure functions
- `VCBinderTests.kt` (`commonTest`) — 30 unit tests covering each function in isolation

Helpers reused from `VCFlattening.kt`:
- `flattenType`, `expandVariable`, `expandAssignmentIfNeeded`, `expandParameterRecursively`,
  `isMultiFieldVC`

## Next Steps

1. **Fix variable type propagation after R5** — When `makeId()` unboxes to return `int`, the
   call site `val id = makeId()` stores an int, but `id`'s declared type stays `Id`. The
   Convert `Convert(from=Id, to=Object)` wrapping `Variable(id)` is therefore stale — it
   should be `Convert(from=Int, to=Object)`. Two approaches:
   - **Approach A**: After R5 unboxes a return, update the variable symbol's type and all
     `Convert.from` values at its uses.
   - **Approach B**: Instead of unboxing at the function definition, unbox at each call site
     via `expandCallSiteArgs` and let variable types follow naturally.

2. **Implement R4** — `reconstructFromScalars(node)` to rebuild a boxed VC from scalar args
   at a call site that expects the boxed type.

3. **Implement R2** — `resolveFieldAccessOnCallResult(node)` for `getPoint(b).x` patterns.

4. **Write unit tests** for each new function before integrating in `handle()`.
