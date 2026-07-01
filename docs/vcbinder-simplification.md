# VCBinder Simplification Analysis

## Current Pipeline

VCBinder.transformTree applies 3 phases in a single top-down tree walk, then a separate bottom-up walk (Phase 4b):

```
transformTree (top-down, pre-order):
  1a. onPhase1       - assignment expansion, FieldAccess/Variable resolution, paramFieldMap
  2.  onExpandCallSite - expand VC args at call sites
  3.  onUpdateReturnType - fix return types

Post-walk:
  4b. resolveVCFieldAccesses - catch remaining unresolved FieldAccess/Variable nodes
  tryUnboxCI - unbox last instruction return value
```

### Why Phase 4b exists

`transformTree` is top-down (pre-order): it calls `onNode(node)` first, and if a new node is returned, children of the new node are not visited. This means when Phase 1a resolves `FieldAccess(FieldAccess(Variable(b), "topLeft"), "x")` to `FieldAccess(Variable(b_topLeft), "x")`, the new `Variable(b_topLeft)` child inside the result is never visited by Phase 1a. Phase 4b's separate recursive walk catches these.

### Where each resolution lives

| Resolution | Phase 1a | Phase 4b |
|-----------|----------|----------|
| val/var VC assignment expansion | ✓ expandAssignmentIfNeeded | - |
| FieldAccess → expanded field var | ✓ resolveFieldAccess → resolveExpandedFieldAccessInstr | ✓ resolveVCFieldAccesses → resolveExpandedFieldAccessInstr |
| Variable → single-field shortcut | ✓ inline via resolveSingleFieldVariable | ✓ resolveVCFieldAccesses → resolveSingleFieldVariable |
| Variable → reconstruct multi-field VC | ✓ inline via reconstructVCFromExpanded | - |
| FieldAccess → identity shortcut | - | ✓ resolveIdentityShortcut |
| param field map resolution | ✓ resolveViaParamFieldMap | - |

### Duplication burden

- `resolveExpandedField` (removed) and `resolveExpandedFieldAccessInstr` were near-identical
- `resolveVariable` (removed) and `resolveSingleFieldVariable` overlap
- `paramFieldMap` (resolveViaParamFieldMap) duplicates what resolveExpandedFieldAccessInstr does, for method parameters
- Identity shortcut only in Phase 4b, could be in Phase 1a

---

## Simplification Candidates

### Candidate 1: Set `expandedFields` on synthetic symbols in resolveViaParamFieldMap

**Status**: Partially implemented (2026-07-01)

**What was done**: Added `setExpandedFieldsFromType()` helper that sets `expandedFields` on synthetic symbols created by `resolveViaParamFieldMap` when the field type is a multi-field VC. This fixes nested field access like `box.topLeft.x` on parameters.

**Remaining work**: The full candidate would set `expandedFields` on parameter symbols during preprocessing, allowing `resolveExpandedFieldAccessInstr` to handle them the same way as local variables. This would eliminate `resolveViaParamFieldMap` entirely. However, initial attempts at this caused parameter type corruption because `transformTree` returns early when `onNode` returns a different node, bypassing subsequent phase processing.

**Key insight**: The preprocessing approach failed because modifying parameter symbols' `expandedFields` during preprocessing affected subsequent phase processing in subtle ways. A lazy or copy-on-first-write approach is needed.

---

### Candidate 2: Restore chain-resolution in Phase 1a + move identity shortcut

**Status**: Failed

**Why it failed**: Moving `resolveIdentityShortcut` to Phase 1 causes it to short-circuit before `resolveViaParamFieldMap` can resolve `p.field` → `p_field` for method parameters. The two resolutions conflict.

**Prerequisite**: Complete Candidate 1 (removing `paramFieldMap`) before this can work.

---

### Candidate 3: Eliminate the ExpressionList special case

**Status**: Deferred (too invasive)

**Why deferred**: The ExpressionList special case exists because `transformTree` returns early when `onNode` returns a different node. The nested `transformTree` call in the special case is necessary to properly apply all 3 phases to each expanded child expression before returning.

Simply inlining the logic (without `transformTree`) breaks tests because `transformTree` handles recursive child processing in a specific way that the inline version doesn't replicate.

**Alternative approach**: "Delay the expansion" - mark assignments for expansion and handle in a later phase. This requires restructuring the pipeline significantly and was deemed too risky for now.
