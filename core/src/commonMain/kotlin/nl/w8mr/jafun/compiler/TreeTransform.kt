package nl.w8mr.jafun.compiler

fun <T> referentialListDiff(a: List<T>, b: List<T>): Boolean =
    a.size != b.size || a.withIndex().any { (i, elem) -> elem !== b[i] }

fun ExpressionNode.Phase2_3Expression.transformTree(
    onNode: (ExpressionNode.Phase2_3Expression) -> ExpressionNode.Phase2_3Expression
): ExpressionNode.Phase2_3Expression {
    val result = onNode(this)
    if (result !== this) return result.transformTree(onNode)

    return when (this) {
        is ExpressionNode.ValAssignment -> {
            val newExpr = expression.transformTree(onNode)
            if (newExpr !== expression) ExpressionNode.ValAssignment(variableSymbol, newExpr) else this
        }
        is ExpressionNode.VarAssignment -> {
            val newExpr = expression.transformTree(onNode)
            if (newExpr !== expression) ExpressionNode.VarAssignment(variableSymbol, newExpr) else this
        }
        is ExpressionNode.MethodInvocation -> {
            val newArgs = arguments.map { it.transformTree(onNode) }
            if (referentialListDiff(newArgs, arguments)) ExpressionNode.MethodInvocation(methodName, parentPath, parameters, rtnLookup, field, newArgs) else this
        }
        is ExpressionNode.ConstructorInvocation -> {
            val newArgs = arguments.map { it.transformTree(onNode) }
            if (referentialListDiff(newArgs, arguments)) ExpressionNode.ConstructorInvocation(cons, newArgs) else this
        }
        is ExpressionNode.FieldAccess -> {
            val newInstance = instance.transformTree(onNode)
            if (newInstance !== instance) ExpressionNode.FieldAccess(newInstance, fieldName, fieldIndex, fieldType, arguments) else this
        }
        is ExpressionNode.ExpressionList -> {
            val newExprs = expressions.map { it.transformTree(onNode) }
            if (referentialListDiff(newExprs, expressions)) ExpressionNode.ExpressionList(newExprs) else this
        }
        is ExpressionNode.WhilePhase3 -> {
            val newCond = condition.transformTree(onNode)
            val newBody = expressions.transformTree(onNode)
            if (newCond !== condition || newBody !== expressions) ExpressionNode.WhilePhase3(newCond, newBody) else this
        }
        is ExpressionNode.WhenPhase3 -> {
            val newMatches = matches.map { (cond, expr) -> cond.transformTree(onNode) to expr.transformTree(onNode) }
            if (newMatches.asSequence().withIndex().any { (idx, p) -> p.first !== matches[idx].first || p.second !== matches[idx].second })
                ExpressionNode.WhenPhase3(newMatches) else this
        }
        is ExpressionNode.Function -> {
            val newBlock = block.map { it.transformTree(onNode) }
            if (referentialListDiff(newBlock, block)) ExpressionNode.Function(symbol, newBlock) else this
        }
        is ExpressionNode.Convert -> {
            val newExpr = expression.transformTree(onNode)
            if (newExpr !== expression) ExpressionNode.Convert(newExpr, from, to) else this
        }
        else -> this
    }
}
