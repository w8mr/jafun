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
            if (referentialListDiff(newArgs, arguments)) ExpressionNode.ConstructorInvocation(cons, targetClass, newArgs) else this
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
        is ExpressionNode.IRBlock -> {
            val newParams = parameters.map { it.transformTree(onNode) }
            val newOp = operation.transformTree(onNode) as ExpressionNode.Phase3Expression
            if (referentialListDiff(newParams, parameters) || newOp !== operation)
                ExpressionNode.IRBlock(newParams, newOp) else this
        }
        is ExpressionNode.Add -> {
            val newLeft = left.transformTree(onNode)
            val newRight = right.transformTree(onNode)
            if (newLeft !== left || newRight !== right) ExpressionNode.Add(newLeft, newRight) else this
        }
        is ExpressionNode.Sub -> {
            val newLeft = left.transformTree(onNode)
            val newRight = right.transformTree(onNode)
            if (newLeft !== left || newRight !== right) ExpressionNode.Sub(newLeft, newRight) else this
        }
        is ExpressionNode.Mul -> {
            val newLeft = left.transformTree(onNode)
            val newRight = right.transformTree(onNode)
            if (newLeft !== left || newRight !== right) ExpressionNode.Mul(newLeft, newRight) else this
        }
        is ExpressionNode.Div -> {
            val newLeft = left.transformTree(onNode)
            val newRight = right.transformTree(onNode)
            if (newLeft !== left || newRight !== right) ExpressionNode.Div(newLeft, newRight) else this
        }
        is ExpressionNode.CmpEq -> {
            val newLeft = left.transformTree(onNode)
            val newRight = right.transformTree(onNode)
            if (newLeft !== left || newRight !== right) ExpressionNode.CmpEq(newLeft, newRight) else this
        }
        is ExpressionNode.CmpLt -> {
            val newLeft = left.transformTree(onNode)
            val newRight = right.transformTree(onNode)
            if (newLeft !== left || newRight !== right) ExpressionNode.CmpLt(newLeft, newRight) else this
        }
        is ExpressionNode.CmpLe -> {
            val newLeft = left.transformTree(onNode)
            val newRight = right.transformTree(onNode)
            if (newLeft !== left || newRight !== right) ExpressionNode.CmpLe(newLeft, newRight) else this
        }
        is ExpressionNode.CmpGt -> {
            val newLeft = left.transformTree(onNode)
            val newRight = right.transformTree(onNode)
            if (newLeft !== left || newRight !== right) ExpressionNode.CmpGt(newLeft, newRight) else this
        }
        is ExpressionNode.CmpGe -> {
            val newLeft = left.transformTree(onNode)
            val newRight = right.transformTree(onNode)
            if (newLeft !== left || newRight !== right) ExpressionNode.CmpGe(newLeft, newRight) else this
        }
        else -> this
    }
}
