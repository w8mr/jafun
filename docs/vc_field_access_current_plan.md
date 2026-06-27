---
applyTo: "**"
---

# Value Class Deep Field Access and Expansion: Current Roadmap

## Completed

- `VCFlattening.kt` extracted — pure helpers (`shouldExpandVC`, `reconstructVCFromExpanded`, `expandParameterRecursively`, `flattenType`, etc.)
- `ParameterExpansionPhase` class created — encapsulates parameter expansion logic
- `ValExpansionPhase` class created — encapsulates val/var assignment expansion
- Recursive `tryResolveExpandedFieldAccess` logic (one-level-at-a-time) proven to work for arbitrary nesting
- All 200+ tests pass:
    - `vcNestedIdAccess` — two-level nested VC access
    - `vcChainedNestedAccess` — nested VC field access on function param
    - `vcDeepNesting` — three-level nested VC access
    - `vcFieldAccessOnCallResult` — field access on function return value
- Previously reported bugs (VerifyError, ClassCastException) resolved

## Extracted from AST2IR.kt (this session)

The following VC logic was moved out:

1. ✅ **`setExpandedFieldsInExpression`** — tree-walking metadata propagator → `VCFlattening.kt`
2. ✅ **`tryResolveExpandedFieldAccess`** — one-level field resolution → `ValExpansionPhase.kt`
3. ✅ **`expandValueClassParams`** — call-site parameter expansion → `VCFlattening.kt`
4. ✅ **`findParamSymbolMap`** — AST symbol map lookup → `VCFlattening.kt`
5. ✅ **Identity shortcut** — `isInlineValueClass` short-circuit → `tryInlineValueClassFieldAccess()` in `ValExpansionPhase.kt`
6. ✅ **Variable single-field shortcut** — `expandedFields.size == 1` → `trySingleExpandedFieldVariable()` in `ValExpansionPhase.kt`
7. ✅ Dead field `constructorArgs` removed from `Types.kt`

AST2IR.kt reduced from 692 to 467 lines (32% smaller).

## Still Inline in AST2IR.kt

1. **`buildFunctionParameters`** (~73 lines) — parameter expansion and metadata setup. Duplicates some logic with `ParameterExpansionPhase.expandParameter`. Full delegation deferred — the two operate at different levels (AST symbol metadata vs IR signature expansion).

## Known Bug

**Parser silently drops unconsumed `.identifier` tokens** — when `fieldRhs` fails (no getter found), the pratt parser breaks out of the loop and unconsumed tokens are silently discarded. The expression `c.b.z` is parsed as just `c.b`, so the invalid field `z` never reaches AST2IR for error reporting. (Test `vcDeepInvalidAccess` is `@kotlin.test.Ignore` tracking this.)

## Outstanding work

1. **Extraction**: Finish moving the 6 remaining inline sections from AST2IR.kt into the dedicated phases.
2. **Parser bug**: Fix `fieldRhs` to consume `.identifier` always and produce a `FieldAccess` node, then let AST2IR report missing-field errors.
3. **Diagnostics**: Error messages should be clear for invalid nested access chains.
4. **Performance**: Confirm no regression from metadata/variable generation in deeply nested chains.
5. **Docs/Examples**: Document the pipeline phases and their invariants with a worked example.
6. **Edge-case tests**:
    - Malformed chains (e.g., `c.b.z` where `z` is not a field)
    - Chains with intermediate nulls/defaults
    - Chains using inherited VC members or generic VCs

## Constraints
- All expansion MUST be metadata-driven — no hardcoded names in AST2IR or field access logic
- No user-facing syntax changes relative to the upstream language spec
- All plugin and backend APIs should see either fully expanded fields or (optionally) raw fields, never half-resolved states

---

*Last updated: 2026-06-27*
