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

It does NOT yet have:
- **R5** — single-field VC return unboxing
- **R4** — reconstruct boxed VC from scalars at call site
- **Call site arg expansion** — when calling a function with expanded params, pass scalars instead of boxed VC
- **Param expansion side effect** — body Variable symbols matching parameter names don't yet have `expandedFields` set

### Test results (current)

- `VCBinderTests[jvm]` — 10 tests passing (R1, R3, paramExpand unit tests)
- `CompilerTest[jvm]` — 19 tests failing (pre-existing IR bytecode assertions + new
  ones where param/call expansion still incomplete)
- All non-VC tests — green

### Open issues in current state

1. When a parameter is a multi-field VC, the body's `Variable(paramName)` symbol
   does not have `expandedFields` set, so `user.street` chain does not resolve to
   a scalar. **Need**: walk body, patch parameter symbols with `expandedFields`.

2. The call site `invokestatic` carries the original (boxed) MethodInvocation
   parameters + arguments. **Need**: rewrite to use the expanded param list
   with scalar args.

3. R5 (single-field return unboxing) is not yet done.

## Next Steps

(none to add here — fill in as we go)
