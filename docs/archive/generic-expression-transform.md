# Generic Expression Tree Transformation

## Problem

Multiple compiler phases walk the expression tree and transform specific node types. Each walker must enumerate every possible `ExpressionNode` subtype to recurse into children — this is tedious boilerplate and a maintenance hazard when new node types are added.

Current offenders in `VCBinder.kt`:

| Function | Lines | Interesting nodes | Boilerplate nodes |
|----------|-------|-------------------|-------------------|
| `transformInstruction` | 115 | 5 (ValAssignment, VarAssignment, FieldAccess, Variable, MethodInvocation) | ~8 |
| `updateMethodInvocationReturnType` | 96 | 1 (MethodInvocation) | ~10 |
| `resolveParamFieldAccess` | 148 | 3 (Variable, FieldAccess, MethodInvocation) | ~8 |
| `expandCallSite` | 143 | 1 (MethodInvocation) | ~8 |

~500 lines of nearly identical visitor boilerplate across 4 functions.

## Solution

A single `transformTree` extension function on `ExpressionNode.Phase2_3Expression`:

```kotlin
/**
 * Walks the expression tree top-down.
 *
 * For each node, calls [onNode]. If [onNode] returns the same reference
 * (identity), the walk recurses into children and reconstructs the node
 * if any child changed. If [onNode] returns a different node, that
 * replacement is used as-is (no recursion into its children).
 */
fun ExpressionNode.Phase2_3Expression.transformTree(
    onNode: (ExpressionNode.Phase2_3Expression) -> ExpressionNode.Phase2_3Expression
): ExpressionNode.Phase2_3Expression
```

### Semantics

- **Return `node` (identity)** → "keep this node, recurse into children"
- **Return new node** → "replace and stop"
- The function handles all ~15 node types internally, recursing into every child expression
- Uses `!==` identity checks to detect changes and reconstruct parent nodes only when needed
- Works for `Phase2_3Expression` — the common supertype used across phases

### Usage pattern

Before:
```kotlin
private fun transformInstruction(node: ...): ... = when (node) {
    is InterestingType -> {
        // logic
        result
    }
    is NodeA -> {
        val newChild = transformInstruction(node.child)
        if (newChild !== node.child) NodeA(newChild) else node
    }
    is NodeB -> {
        val newC1 = transformInstruction(node.c1)
        val newC2 = transformInstruction(node.c2)
        if (newC1 !== node.c1 || newC2 !== node.c2) NodeB(newC1, newC2) else node
    }
    // ... 10 more identical cases
    else -> node
}
```

After:
```kotlin
private fun onTransformInstruction(node: ...): ... = when (node) {
    is InterestingType -> result  // replacement, no recursion
    else -> node                   // identity → framework recurses
}

// call site:
transformTree(instruction, ::onTransformInstruction)
```

### Where it lives

Defined as a top-level extension function in a shared location (e.g., `ExpressionNode.kt` or a new `TreeTransform.kt`), so all phases can use it.

## Expected impact on VCBinder

| Function | Before | After | Reduction |
|----------|--------|-------|-----------|
| `transformInstruction` → `onTransformInstruction` | 115 lines | ~50 lines | 57% |
| `updateMethodInvocationReturnType` → `onUpdateReturnType` | 96 lines | ~40 lines | 58% |
| `resolveParamFieldAccess` + `onResolveParamFieldAccess` | 148 lines | ~85 lines | 43% |
| `expandCallSite` → `onExpandCallSite` + `expandMethodInvocation` | 143 lines | ~100 lines | 30% |
| **Total** | **~500 lines** | **~275 lines** | **~45%** |

The functions keep their core logic and simply stop enumerating node types they don't care about.

## Applicability beyond VCBinder

Other phases that walk the tree and could use `transformTree`:

- `ValExpansionPhase.kt` — currently has its own walking logic
- `ParameterExpansionPhase.kt` — walks method parameters
- Any future IR transformation phase

If a phase needs pre-order vs post-order, or wants to replace AND recurse into the replacement, it can call `transformTree` explicitly on sub-expressions within `onNode`.

## Non-goals

- No changes to `ExpressionNode` sealed interface or its subtypes
- No new traversal order variants (pre/post) — single top-down is sufficient for current needs
- No mutation tracking — `onNode` is a pure mapping function with no side effects
