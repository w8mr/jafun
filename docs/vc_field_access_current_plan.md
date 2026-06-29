---
applyTo: "**"
---

# Value Class Deep Field Access and Expansion: Current Roadmap

## Completed

- **VCBinder (Phase 4 + 5)**: All VC logic is in `VCBinder.kt`, running after Phase 3 (AST2IR) and before JVMBackend:
    - Phase 4: Identity transform for `val` assignments of single-field VCs; `FieldAccess` on single-field VC variable returns instance directly
    - Phase 5a (body transform): `expandParameterRecursively` flattens VC params through all nesting levels; `resolveParamFieldAccess` walks `FieldAccess` chains, maps them to expanded flattened param names, and reconstructs nested VCs with proper CI nesting
    - Phase 5b (call site expansion): `expandCallSite` expands CI args via `flattenCIArgs`, expands Variable args via `expandVariable`, handles `returnsWrappedType` (boxed params passed as-is), and unwraps single-field VC CIs
- **Nested VC field access**: `c.b.a.value` on function params works through arbitrary nesting depth
- **Box class emission**: `buildValueClass()` in `JVMBackend.kt` generates the JVM class for multi-field VC return values
- **All 200 tests pass**, including 7 VC tests:
    - `vcNestedIdAccess`, `vcNestedIdInUser`, `valueClassFunctionParam`, `valueClassTwoParamsReverseFieldOrder`
    - `vcChangeAddress`, `vcFieldAccessOnCallResult`, `vcDeepNesting`

## Known limitations

1. **No compile-time diagnostic for invalid field access**: The parser silently drops unrecognized field access paths (e.g., `.z` on `B` in `c.b.z`). The error surfaces later as a JVMBackend type mismatch rather than a clear field-not-found error. This is a parser-level limitation — Phase 2 `fieldRhs` fails to parse the invalid access.

2. **VC logic remains in AST2IR + JVMBackend**: The original goal of making JVMBackend VC-agnostic was not pursued. `buildValueClass` stays in JVMBackend; `AST2IR.kt` still has some VC awareness (set but unused).

## Constraints
- All expansion MUST be metadata-driven — no hardcoded names beyond type structure
- No user-facing syntax changes relative to the upstream language spec
- `expandParameterRecursively` flattens through single-field VCs (e.g., `Box(topLeft: Point)` → `[b_topLeft_x: Int, b_topLeft_y: Int]`)

## Next Steps

1. Add parser-level validation of field existence for better error messages on invalid access chains
2. Write pipeline walkthrough and architecture documentation for VCBinder phases

----

*Last updated: 2026-06-29*
