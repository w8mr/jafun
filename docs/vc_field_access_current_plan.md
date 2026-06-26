---
applyTo: "**"
---

# Value Class Deep Field Access and Expansion: Current Roadmap

## Completed

- All value class (VC) flattening, parameter/assignment expansions, and nested VC field access logic is now in dedicated pipeline phases post-Phase3, pre-JVMBackend.
    - `ParameterExpansionPhase`: Expands all VC parameters
    - `ValExpansionPhase`: Expands all VC `val` assignments
    - Mutability support (`var` expansion): Now handled identically to `val`
- All expansion logic is now removed from AST2IR, which only manages VC metadata/expandedFields
- All 200+ tests pass:
    - `vcNestedIdAccess` — two-level nested VC access
    - `vcChainedNestedAccess` — nested VC field access on function param
    - `vcDeepNesting` — three-level nested VC access (new)
    - `vcFieldAccessOnCallResult` — field access on function return value (new)
- All previously reported bugs (VerifyError, ClassCastException, etc.) are resolved
- Recursive `tryResolveExpandedFieldAccess` logic (one-level-at-a-time) proven to work for arbitrary nesting

## Outstanding work

1. **Diagnostics**: Error messages should be clear for invalid nested access chains (currently could be ClassCastException, not a compiler error)

2. **Performance**: Confirm no regression from extra metadata/variable generation in deeply nested chains

3. **Docs/Examples**: Document the pipeline phases and their invariants, including a worked example (showing AST2IR, phase expansion, and resulting JVM bytecode for a deep VC chain)

4. **Future-Proofing**: If plugin or multi-backend architectures need raw/unexpanded VC IR, refactor pipeline to split and expose the representations cleanly

5. **Test for edge-cases**:
    - Malformed chains (e.g., `c.b.z` where `z` is not a field)
    - Chain with intermediate nulls/defaults
    - Chains using inherited VC members or generic VCs

## Constraints
- All expansion MUST be metadata-driven — no hardcoded names in AST2IR or field access logic
- No user-facing syntax changes relative to the upstream language spec
- All plugin and backend APIs should see either fully expanded fields or (optionally) raw fields, never half-resolved states

## Next Steps

1. Add failing test for invalid chain `c.b.z` (expecting a proper error message)
2. Add slow-path performance regression test (measure and assert for deeply nested chains)
3. Write example pipeline walkthrough, commit to docs
4. Refactor error handling in `tryResolveExpandedFieldAccess` for user-friendly diagnostics (not just throw)

----

*Last updated: 2026-06-26*
