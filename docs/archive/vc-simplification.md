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

### Candidate 1: Remove `paramFieldMap` by setting `expandedFields` on parameter symbols

**Problem**: `paramFieldMap` is a separate mechanism to resolve `p.field` → expanded variable for method parameters. It exists because parameter variable symbols don't have `expandedFields` set — only `val`/`var` assignment symbols do.

**Solution**: Set `expandedFields` and `expandedFieldSymbols` on parameter symbols during pre-processing (or lazily when first accessed). Then `resolveExpandedFieldAccessInstr` handles them the same way as local variables.

**Impact**: Deletes ~80 lines: `resolveViaParamFieldMap`, `buildParamFieldMap`, `extractFieldPath`, `getInnermostVariable`, the `paramFieldMap` parameter threading through `onPhase1`. Unified resolution path.

**Risk**: Method parameters are shared across all call sites. Mutating them during pipeline execution could cause subtle issues if a method is visited multiple times. Would need a lazy or copy-on-first-write approach.

---

### Candidate 2: Restore chain-resolution in Phase 1a + move identity shortcut there

**Problem**: Phase 4b exists because Phase 1a's `resolveFieldAccess` doesn't resolve chains fully. When `FieldAccess(FieldAccess(Variable(b), "topLeft"), "x")` is encountered, the old code resolved `b.topLeft` to `b_topLeft`, then immediately resolved `.x` on the result. The current code resolves `b.topLeft` to `b_topLeft` but returns a new `FieldAccess(Variable(b_topLeft), "x")` without resolving `.x`, relying on Phase 4b to catch it.

**Solution**: Make Phase 1a's `resolveFieldAccess` recursively resolve through FieldAccess chains (as the old code did), and move `resolveIdentityShortcut` from Phase 4b to Phase 1a. This removes the need for the FieldAccess/Variable handling in Phase 4b, leaving it only to catch nodes created by Phase 2 (call site expansion).

**Impact**: Phase 4b shrinks from ~65 lines to ~25 lines (only handles nodes created by call site expansion, plus residual cases from `ExpressionList` processing). No new mechanism needed — restoring old behavior plus moving one function.

**Risk**: Low — restores a pattern that was working before unification. The old code had this exact chain-resolution and it passed all tests.

---

### Candidate 3: Eliminate the ExpressionList special case

**Problem**: Lines 48-60 manually recurse into `ExpressionList` children with a nested `transformTree` call, duplicating the transform logic. This exists because `transformTree` returns immediately when `onNode` returns a different node, so the `ExpressionList` children wouldn't be processed otherwise.

**Solution**: Instead of returning `ExpressionList` from `onPhase1` (which triggers the early return in `transformTree`), delay the expansion: mark the assignment for expansion and let a later phase handle it. Or restructure the assignment expansion to emit separate top-level instructions rather than an `ExpressionList`.

**Impact**: Deletes ~15 lines of special-case code. Simpler transform loop. Could also eliminate the need for early `return@transformTree` entirely.

**Risk**: Medium — assignment expansion timing is delicate. If delayed, later phases (call site expansion, return type update) might see un-expanded assignments. Would need careful ordering.
