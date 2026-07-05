package nl.w8mr.jafun.compiler.ast2ir

import nl.w8mr.jafun.compiler.ir2jvm.IRBuilder
import nl.w8mr.jafun.OperandType
import nl.w8mr.jafun.Type
import nl.w8mr.jafun.compiler.SymbolMap
import nl.w8mr.jafun.compiler.Parameter
import nl.w8mr.jafun.compiler.compileMethod
import nl.w8mr.jafun.compiler.ExpressionNode
import nl.w8mr.jafun.compiler.IdentifierCache
import nl.w8mr.jafun.compiler.LocalSymbolMap

fun compileAsCodeBlock(
    builder: IRBuilder.CodeBlockDSL,
    expression: ExpressionNode.Phase2_3Expression,
): ExpressionNode.Phase2_3Expression {
    val instructions = IRBuilder.CodeBlockDSL(mutableListOf(), builder.parent).apply {
        compileExpressionNode(expression, this)
    }.instructions
    return when (instructions.size) {
        0 -> ExpressionNode.ExpressionList(emptyList())
        1 -> instructions[0]
        else -> ExpressionNode.ExpressionList(instructions)
    }
}


fun loadArguments(
    builder: IRBuilder.CodeBlockDSL,
    arguments: List<ExpressionNode.Phase2_3Expression>,
    parameters: List<OperandType<*>>,
) = arguments.zip(parameters).map { (argument, parameter) ->
        if (argument.type() == parameter) {
            compileAsCodeBlock(builder, argument)
        } else if ((argument.type() == OperandType.StringType) && (parameter is Type.JFClass) && (parameter.name=="java.lang.Object")) {
            compileAsCodeBlock(builder, argument)
        }
        else {
            ExpressionNode.Convert(
                compileAsCodeBlock(builder, argument),
                argument.type(),
                parameter
            )
        }
    }




private fun createWhenConditionExpression(
    variable: Type.JFVariableSymbol?,
    condition: ExpressionNode.Phase2_3Expression,
): ExpressionNode.Phase2_3Expression {
    return variable?.let { subjVar ->
        val symbol = IdentifierCache.findMethod(subjVar.type, "==", listOf(subjVar.type, condition.type()))
        ExpressionNode.MethodInvocation(
            methodName = symbol.name,
            parentPath = symbol.parentPath,
            parameters = symbol.parameters,
            rtnLookup = { symbol.rtn },
            field = null,
            arguments = listOf(ExpressionNode.Variable(subjVar), condition),
        )
    } ?: condition // If no subject variable, the condition is used directly
}

fun compileExpressionNode(
    node: ExpressionNode.Phase2_3Expression,
    builder: IRBuilder.CodeBlockDSL,
) {
    when (node) {
        is ExpressionNode.MethodInvocation -> {
            val arguments = loadArguments(builder, node.arguments, node.parameters.map { it.type })
            builder.add(ExpressionNode.MethodInvocation(
                methodName = node.methodName,
                parentPath = node.parentPath,
                parameters = node.parameters,
                rtnLookup = node.rtnLookup,
                field = node.field,
                arguments = arguments,
            ))
        }
        is ExpressionNode.ConstructorInvocation -> {
            val arguments = loadArguments(builder, node.arguments, node.cons.parameters.map(Type.JFVariableSymbol::type))
            builder.add(ExpressionNode.ConstructorInvocation(node.cons, node.cons.parent as Type.JFClass, arguments))
        }
        is ExpressionNode.When -> {
            val subjectVariable =
                node.subject?.let { subj ->
                    when (subj) {
                        is ExpressionNode.Variable -> subj.variableSymbol
                        is ExpressionNode.ValAssignment -> {
                            // Compile the assignment statement (no return value needed here)
                            compileExpressionNode(subj, builder)
                            subj.variableSymbol
                        }
                        is ExpressionNode.VarAssignment -> { // Added VarAssignment case
                            compileExpressionNode(subj, builder)
                            subj.variableSymbol
                        }
                        else -> {
                            // Create and assign to a temporary variable
                            val tmpVariable =
                                Type.JFVariableSymbol(
                                    "tmp",
                                    subj.type(),
                                    LocalSymbolMap(IdentifierCache), // TODO: Need to check how to get the right scope here
                                )
                            // Compile the assignment to the temporary variable
                            compileExpressionNode(ExpressionNode.ValAssignment(tmpVariable, subj), builder)
                            tmpVariable
                        }
                    }
                }

            val lastIndex = node.matches.size - 1
            var unreachable = false //TODO: Add handling

            val conditionAndBodyPairs = node.matches.mapIndexed { index, (condition, expression) ->
                when(condition) {
                    ExpressionNode.BooleanLiteral(true) -> {

                        val bodyInstructions = compileAsCodeBlock(builder, expression)
                        if (index != lastIndex) unreachable = true
                        ExpressionNode.BooleanLiteral(true) to bodyInstructions
                    }
                    else -> {
                        // Create the actual condition expression (e.g., subject == value)
                        val conditionExpression = createWhenConditionExpression(subjectVariable, condition)
                        val conditionInstructions = compileAsCodeBlock(builder, conditionExpression)
                        val bodyInstructions = compileAsCodeBlock(builder, expression)
                        conditionInstructions to bodyInstructions
                    }
                }
            }
            builder.add(ExpressionNode.WhenPhase3(conditionAndBodyPairs))
        }
        is ExpressionNode.Function -> {
            val symbol = node.symbol
            val paramNames = symbol.parameters.map { it.name }.toSet()
            val paramRefSymbolMap = node.block.mapNotNull { e -> findParamSymbolMap(e, paramNames) }.firstOrNull()
            val actualSymbolMapId = paramRefSymbolMap?.symbolMapId
            val naiveParameters = symbol.parameters.map { param ->
                val varName = if (actualSymbolMapId != null) "${actualSymbolMapId}.${param.name}" else null
                Parameter(param.type, varName)
            }
            compileMethod(
                builder.parent.parent,
                node.block,
                symbol.name,
                symbol.rtn,
                naiveParameters,
            )
        }
        is ExpressionNode.ValAssignment -> {
            val expr = compileAsCodeBlock(builder, node.expression)
            builder.add(ExpressionNode.ValAssignment(node.variableSymbol, expr))
        }
        is ExpressionNode.VarAssignment -> {
            val expr = compileAsCodeBlock(builder, node.expression)
            builder.add(ExpressionNode.VarAssignment(node.variableSymbol, expr))
        }
        is ExpressionNode.ExpressionList -> {
            builder.add(ExpressionNode.ExpressionList(node.expressions.map { compileAsCodeBlock(builder, it) }))
        }
        is ExpressionNode.While -> {
            builder.add(ExpressionNode.WhilePhase3(
                compileAsCodeBlock(builder, node.condition),
                compileAsCodeBlock(builder, node.expressions),
            ))
        }
        is ExpressionNode.FieldAccess -> {
            val instance = compileAsCodeBlock(builder, node.instance)
            builder.add(ExpressionNode.FieldAccess(
                instance = instance,
                fieldName = node.fieldName,
                fieldIndex = node.fieldIndex,
                fieldType = node.fieldType,
                arguments = emptyList(),
            ))
        }
        is ExpressionNode.Variable -> {
            builder.add(node)
        }
        is ExpressionNode.Phase2_3Expression -> {
            builder.add(node)
        }
    }
}

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


