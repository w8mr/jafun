package nl.w8mr.jafun.compiler

import nl.w8mr.jafun.Type
import nl.w8mr.jafun.compiler.ir2jvm.IRBuilder

class Inliner(private val inlineFunctions: List<ExpressionNode.Function>) : Compiler.Phase3Plugin {

    override fun handle(context: IRBuilder.ClassContext): IRBuilder.ClassContext {
        var current = context
        repeat(10) {
            current = current.copy(
                methods = current.methods.map { method ->
                    method.copy(
                        instructions = method.instructions.map { inlineCall(it, method.name) }.toMutableList()
                    )
                }.toMutableList()
            )
        }
        return current
    }

    private fun inlineCall(
        node: ExpressionNode.Phase2_3Expression,
        currentMethod: String,
    ): ExpressionNode.Phase2_3Expression = when (node) {
        is ExpressionNode.MethodInvocation -> {
            val newArgs = node.arguments.map { inlineCall(it, currentMethod) }
            val callee = findCalleeFunction(node)
            if (callee != null && callee.symbol.inline && callee.symbol.name != currentMethod) {
                substituteArguments(callee.block, callee.symbol.parameters, newArgs)
                    .let { if (it.size == 1) it[0] else ExpressionNode.ExpressionList(it) }
            } else if (referentialListDiff(newArgs, node.arguments)) {
                ExpressionNode.MethodInvocation(
                    node.methodName, node.parentPath, node.parameters,
                    node.rtnLookup, node.field, newArgs
                )
            } else node
        }
        is ExpressionNode.ExpressionList -> node.copy(
            expressions = node.expressions.map { inlineCall(it, currentMethod) }
        )
        is ExpressionNode.ValAssignment -> node.copy(
            expression = inlineCall(node.expression, currentMethod)
        )
        is ExpressionNode.VarAssignment -> node.copy(
            expression = inlineCall(node.expression, currentMethod)
        )
        is ExpressionNode.When -> node.copy(
            subject = node.subject?.let { inlineCall(it, currentMethod) },
            matches = node.matches.map { (cond, body) ->
                inlineCall(cond, currentMethod) to inlineCall(body, currentMethod)
            }
        )
        is ExpressionNode.WhenPhase3 -> node.copy(
            matches = node.matches.map { (cond, body) ->
                inlineCall(cond, currentMethod) to inlineCall(body, currentMethod)
            }
        )
        is ExpressionNode.DoWhile -> node.copy(
            condition = inlineCall(node.condition, currentMethod),
            expressions = inlineCall(node.expressions, currentMethod),
        )
        is ExpressionNode.While -> node.copy(
            condition = inlineCall(node.condition, currentMethod),
            expressions = inlineCall(node.expressions, currentMethod),
        )
        is ExpressionNode.WhilePhase3 -> node.copy(
            condition = inlineCall(node.condition, currentMethod),
            expressions = inlineCall(node.expressions, currentMethod),
        )
        is ExpressionNode.Function -> node.copy(
            block = node.block.map { inlineCall(it, node.symbol.name) }
        )
        is ExpressionNode.Mul -> node.copy(
            left = inlineCall(node.left, currentMethod),
            right = inlineCall(node.right, currentMethod),
        )
        is ExpressionNode.Add -> node.copy(
            left = inlineCall(node.left, currentMethod),
            right = inlineCall(node.right, currentMethod),
        )
        is ExpressionNode.Sub -> node.copy(
            left = inlineCall(node.left, currentMethod),
            right = inlineCall(node.right, currentMethod),
        )
        is ExpressionNode.Div -> node.copy(
            left = inlineCall(node.left, currentMethod),
            right = inlineCall(node.right, currentMethod),
        )
        is ExpressionNode.CmpEq -> node.copy(
            left = inlineCall(node.left, currentMethod),
            right = inlineCall(node.right, currentMethod),
        )
        is ExpressionNode.CmpLt -> node.copy(
            left = inlineCall(node.left, currentMethod),
            right = inlineCall(node.right, currentMethod),
        )
        is ExpressionNode.CmpLe -> node.copy(
            left = inlineCall(node.left, currentMethod),
            right = inlineCall(node.right, currentMethod),
        )
        is ExpressionNode.CmpGt -> node.copy(
            left = inlineCall(node.left, currentMethod),
            right = inlineCall(node.right, currentMethod),
        )
        is ExpressionNode.CmpGe -> node.copy(
            left = inlineCall(node.left, currentMethod),
            right = inlineCall(node.right, currentMethod),
        )
        is ExpressionNode.IRBlock -> node.copy(
            operation = inlineCall(node.operation, currentMethod) as ExpressionNode.Phase3Expression,
        )
        is ExpressionNode.Convert -> node.copy(
            expression = inlineCall(node.expression, currentMethod),
        )
        is ExpressionNode.StringTemplate -> node.copy(
            expressions = node.expressions.map { inlineCall(it, currentMethod) as ExpressionNode.Phase2Expression }
        )
        is ExpressionNode.ConstructorInvocation -> node.copy(
            arguments = node.arguments.map { inlineCall(it, currentMethod) }
        )
        is ExpressionNode.FieldAccess -> node.copy(
            instance = inlineCall(node.instance, currentMethod),
            arguments = node.arguments.map { inlineCall(it, currentMethod) },
        )
        else -> node
    }

    private fun findCalleeFunction(node: ExpressionNode.MethodInvocation): ExpressionNode.Function? {
        val matched = inlineFunctions.firstOrNull { fn ->
            fn.symbol.name == node.methodName &&
                fn.symbol.parameters.size == node.parameters.size &&
                fn.symbol.parameters.zip(node.parameters).all { (a, b) -> a.type == b.type } &&
                (fn.symbol.parentPath == node.parentPath || fn.symbol.inline)
        }
        return matched
    }

    private fun substituteArguments(
        body: List<ExpressionNode.Phase2_3Expression>,
        params: List<Type.JFVariableSymbol>,
        args: List<ExpressionNode.Phase2_3Expression>,
    ): List<ExpressionNode.Phase2_3Expression> {
        val paramToArg = params.zip(args).toMap()
        return substituteVariables(body, paramToArg)
    }

    private fun substituteVariables(
        exprs: List<ExpressionNode.Phase2_3Expression>,
        paramToArg: Map<Type.JFVariableSymbol, ExpressionNode.Phase2_3Expression>,
    ): List<ExpressionNode.Phase2_3Expression> {
        return exprs.map { substituteVariable(it, paramToArg) }
    }

    @Suppress("CyclomaticComplexMethod")
    private fun substituteVariable(
        node: ExpressionNode.Phase2_3Expression,
        paramToArg: Map<Type.JFVariableSymbol, ExpressionNode.Phase2_3Expression>,
    ): ExpressionNode.Phase2_3Expression = when (node) {
        is ExpressionNode.Variable -> {
            paramToArg[node.variableSymbol]?.let { deepCopy(it) } ?: node
        }
        is ExpressionNode.ExpressionList -> node.copy(
            expressions = substituteVariables(node.expressions, paramToArg)
        )
        is ExpressionNode.MethodInvocation -> {
            val newArgs = node.arguments.map { substituteVariable(it, paramToArg) }
            if (referentialListDiff(newArgs, node.arguments)) {
                ExpressionNode.MethodInvocation(
                    node.methodName, node.parentPath, node.parameters,
                    node.rtnLookup, node.field, newArgs
                )
            } else node
        }
        is ExpressionNode.ValAssignment -> node.copy(
            expression = substituteVariable(node.expression, paramToArg)
        )
        is ExpressionNode.VarAssignment -> node.copy(
            expression = substituteVariable(node.expression, paramToArg)
        )
        is ExpressionNode.When -> node.copy(
            subject = node.subject?.let { substituteVariable(it, paramToArg) },
            matches = node.matches.map { (cond, body) ->
                substituteVariable(cond, paramToArg) to substituteVariable(body, paramToArg)
            }
        )
        is ExpressionNode.WhenPhase3 -> node.copy(
            matches = node.matches.map { (cond, body) ->
                substituteVariable(cond, paramToArg) to substituteVariable(body, paramToArg)
            }
        )
        is ExpressionNode.DoWhile -> node.copy(
            condition = substituteVariable(node.condition, paramToArg),
            expressions = substituteVariable(node.expressions, paramToArg),
        )
        is ExpressionNode.While -> node.copy(
            condition = substituteVariable(node.condition, paramToArg),
            expressions = substituteVariable(node.expressions, paramToArg),
        )
        is ExpressionNode.WhilePhase3 -> node.copy(
            condition = substituteVariable(node.condition, paramToArg),
            expressions = substituteVariable(node.expressions, paramToArg),
        )
        is ExpressionNode.Mul -> node.copy(
            left = substituteVariable(node.left, paramToArg),
            right = substituteVariable(node.right, paramToArg),
        )
        is ExpressionNode.Add -> node.copy(
            left = substituteVariable(node.left, paramToArg),
            right = substituteVariable(node.right, paramToArg),
        )
        is ExpressionNode.Sub -> node.copy(
            left = substituteVariable(node.left, paramToArg),
            right = substituteVariable(node.right, paramToArg),
        )
        is ExpressionNode.Div -> node.copy(
            left = substituteVariable(node.left, paramToArg),
            right = substituteVariable(node.right, paramToArg),
        )
        is ExpressionNode.CmpEq -> node.copy(
            left = substituteVariable(node.left, paramToArg),
            right = substituteVariable(node.right, paramToArg),
        )
        is ExpressionNode.CmpLt -> node.copy(
            left = substituteVariable(node.left, paramToArg),
            right = substituteVariable(node.right, paramToArg),
        )
        is ExpressionNode.CmpLe -> node.copy(
            left = substituteVariable(node.left, paramToArg),
            right = substituteVariable(node.right, paramToArg),
        )
        is ExpressionNode.CmpGt -> node.copy(
            left = substituteVariable(node.left, paramToArg),
            right = substituteVariable(node.right, paramToArg),
        )
        is ExpressionNode.CmpGe -> node.copy(
            left = substituteVariable(node.left, paramToArg),
            right = substituteVariable(node.right, paramToArg),
        )
        is ExpressionNode.IRBlock -> node.copy(
            operation = substituteVariable(node.operation, paramToArg) as ExpressionNode.Phase3Expression,
        )
        is ExpressionNode.Convert -> node.copy(
            expression = substituteVariable(node.expression, paramToArg),
        )
        is ExpressionNode.StringTemplate -> node.copy(
            expressions = node.expressions.map { substituteVariable(it, paramToArg) as ExpressionNode.Phase2Expression }
        )
        is ExpressionNode.ConstructorInvocation -> node.copy(
            arguments = node.arguments.map { substituteVariable(it, paramToArg) }
        )
        is ExpressionNode.FieldAccess -> node.copy(
            instance = substituteVariable(node.instance, paramToArg),
            arguments = node.arguments.map { substituteVariable(it, paramToArg) },
        )
        is ExpressionNode.Function -> node.copy(
            block = node.block.map { substituteVariable(it, paramToArg) }
        )
        else -> node
    }

    private fun <T> referentialListDiff(newList: List<T>, oldList: List<T>): Boolean =
        newList.size != oldList.size || newList.asSequence().zip(oldList.asSequence()).any { (a, b) -> a !== b }

    private fun deepCopy(node: ExpressionNode.Phase2_3Expression): ExpressionNode.Phase2_3Expression =
        substituteVariable(node, emptyMap<Type.JFVariableSymbol, ExpressionNode.Phase2_3Expression>())
}
