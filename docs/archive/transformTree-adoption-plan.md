# `transformTree` Adoption Plan

## Current usage

`transformTree` is used in `VCBinder.kt` by 4 visitor functions:

| Function | Lines | Interesting nodes |
|----------|-------|-------------------|
| `onTransformInstruction` | 33 | ValAssignment, VarAssignment, FieldAccess, Variable |
| `onUpdateReturnType` | 51 | MethodInvocation, ValAssignment, VarAssignment, Convert, FieldAccess |
| `onResolveParamFieldAccess` | 88 | FieldAccess, Variable |
| `onExpandCallSite` | 10 | MethodInvocation |

## Candidate: `findParamSymbolMap` in `AST2IR.kt`

```kotlin
// Lines 192–225, 34 lines, fully manual when over 11 subtypes
private fun findParamSymbolMap(
    expr: ExpressionNode.Phase2_3Expression,
    paramNames: Set<String>,
): SymbolMap? {
    return when (expr) {
        is ExpressionNode.Variable -> {
            if (expr.variableSymbol.name in paramNames) expr.variableSymbol.symbolMap else null
        }
        is ExpressionNode.MethodInvocation -> {
            expr.arguments.firstNotNullOfOrNull { findParamSymbolMap(it, paramNames) }
        }
        is ExpressionNode.ValAssignment -> findParamSymbolMap(expr.expression, paramNames)
        is ExpressionNode.VarAssignment -> findParamSymbolMap(expr.expression, paramNames)
        is ExpressionNode.ExpressionList -> {
            expr.expressions.firstNotNullOfOrNull { findParamSymbolMap(it, paramNames) }
        }
        is ExpressionNode.ConstructorInvocation -> {
            expr.arguments.firstNotNullOfOrNull { findParamSymbolMap(it, paramNames) }
        }
        is ExpressionNode.FieldAccess -> findParamSymbolMap(expr.instance, paramNames)
        is ExpressionNode.Convert -> findParamSymbolMap(expr.expression, paramNames)
        is ExpressionNode.WhenPhase3 -> {
            expr.matches.firstNotNullOfOrNull { (_, e) -> findParamSymbolMap(e, paramNames) }
        }
        is ExpressionNode.Function -> {
            findParamSymbolMap(ExpressionNode.ExpressionList(expr.block), paramNames)
        }
        is ExpressionNode.WhilePhase3 -> {
            findParamSymbolMap(expr.expressions, paramNames)
                ?: findParamSymbolMap(expr.condition, paramNames)
        }
        else -> null
    }
}
```

### Problem

This is a **search** function, not a **transform** function — `transformTree` is designed for tree rewriting, but this function searches for the first matching `SymbolMap`. Using `transformTree` directly would look like:

```kotlin
private fun findParamSymbolMap(
    expr: ExpressionNode.Phase2_3Expression,
    paramNames: Set<String>,
): SymbolMap? {
    var result: SymbolMap? = null
    expr.transformTree { node ->
        if (result != null) return@transformTree node  // still recurses, no early exit
        if (node is ExpressionNode.Variable && node.variableSymbol.name in paramNames) {
            result = node.variableSymbol.symbolMap
        }
        node
    }
    return result
}
```

This works but:
1. No early termination — `transformTree` still visits every node after finding the result
2. The `result != null` check on every node is overhead
3. The original code is already compact and readable

### Verdict

**Don't convert.** The manual `when` in `findParamSymbolMap` is simpler and more natural for a tree search than forcing it into a tree-rewriting API. The current `transformTree` is worth keeping because it eliminates *boilerplate*, but this function has no boilerplate — each branch is a one-liner tailored to the node type's child structure.

## What would need to change to make `transformTree` useful for search

If `transformTree` supported a **short-circuit** return, it would naturally handle both transform and search:

```kotlin
// Hypothetical API with early exit:
fun <T> ExpressionNode.Phase2_3Expression.visitTree(
    onNode: (ExpressionNode.Phase2_3Expression) -> VisitResult
): VisitResult

sealed interface VisitResult {
    data class Replace(val node: ExpressionNode.Phase2_3Expression) : VisitResult
    data object Recurse : VisitResult
    data class Done(val result: Any?) : VisitResult  // early exit
}
```

But this adds complexity for one caller. Not worth it.

## Non-candidates

| Function | Reason |
|----------|--------|
| `JVMBackend.Context.compile` | Code generator (side-effect on builder), not tree rewriter |
| `AST2IR.compileExpressionNode` | Code generator (side-effect on builder), not tree rewriter |
| `resolveFieldAccess` | Single node type, not a general tree walk |
| `resolveVariable` | Single node type |
| `ValExpansionPhase` | Already uses its own focused walk |

## Summary

`transformTree` is already used in all four of VCBinder's tree-walking functions. The only other candidate in the codebase (`findParamSymbolMap`) is a **search**, not a **transform**, and the manual `when` is already compact (1–2 lines per type). Converting it would add ceremony without eliminating real boilerplate.

**Recommendation**: Keep `transformTree` focused on tree rewriting where it provides value. The codebase has no other transform-style tree walks that would benefit.
