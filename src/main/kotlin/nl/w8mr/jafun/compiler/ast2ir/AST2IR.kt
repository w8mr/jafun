package nl.w8mr.jafun.compiler.ast2ir

import nl.w8mr.jafun.ASTNode
import nl.w8mr.jafun.IR
import nl.w8mr.jafun.IRBuilder
import nl.w8mr.jafun.Type
import nl.w8mr.jafun.compileMethod
import nl.w8mr.jafun.compiler.IdentifierCache
import nl.w8mr.jafun.compiler.LocalSymbolMap

private fun getSubBuilder(builder: IRBuilder.CodeBlockDSL): IRBuilder.CodeBlockDSL =
    IRBuilder.CodeBlockDSL(mutableListOf(), builder.parent)

fun compileAsCodeBlock(
    builder: IRBuilder.CodeBlockDSL,
    expression: ASTNode.Expression,
    returnValue: Boolean = true
): List<IR.Instruction> {
    val subBuilder = getSubBuilder(builder)
    compileExpressionNode(expression, subBuilder, returnValue)
    return subBuilder.instructions
}

val integerValueOf = IdentifierCache.findMethod("java.lang.Integer.valueOf", listOf(Type.SInt32))
val characterValueOf = IdentifierCache.findMethod("java.lang.Character.valueOf", listOf(Type.CharType))
val booleanValueOf = IdentifierCache.findMethod("java.lang.Boolean.valueOf", listOf(Type.UInt1))


fun loadArguments(
    builder: IRBuilder.CodeBlockDSL,
    arguments: List<ASTNode.Expression>,
    parameters: List<Type.OperandType<*>>,
) {
    arguments.zip(parameters).forEach { (argument, parameter) ->
        if (argument.type() == parameter) {
            compileExpressionNode(argument, builder, true)
        } else {
            val argType = argument.type()
            compileExpressionNode(argument, builder, true)
            when (parameter) {
                is Type.JFClass -> {
                    when (argType) {
                        is Type.StringType, is Type.JFClass -> {}
                        Type.SInt32 -> builder.invoke(integerValueOf, null)
                        Type.CharType -> builder.invoke(characterValueOf, null)
                        Type.UInt1 -> builder.invoke(booleanValueOf, null)
                        else -> TODO("Unhandled type mismatch during argument loading: $argType vs $parameter")
                    }
                }
                is Type.Array -> {
                    when (argType) {
                        is Type.Array -> {}
                        else -> TODO("Unhandled type mismatch during argument loading: $argType vs $parameter")
                    }
                }
                else -> TODO("Unhandled type mismatch during argument loading: $argType vs $parameter")
            }
        }
    }
}


private fun createWhenConditionExpression(
    variable: Type.JFVariableSymbol?,
    condition: ASTNode.Expression,
): ASTNode.Expression {
    return variable?.let { subjVar ->
        val symbol = IdentifierCache.findMethod("==", listOf(subjVar.type, condition.type()))
        ASTNode.Invocation(symbol, null, listOf(ASTNode.Variable(subjVar), condition))
    } ?: condition // If no subject variable, the condition is used directly
}


fun compileExpressionNode(
    node: ASTNode.Expression,
    builder: IRBuilder.CodeBlockDSL,
    returnValue: Boolean = true,
) {
    when (node) {
        is ASTNode.StringLiteral -> {
            builder.loadConstant(node.value, Type.StringType)
        }
        is ASTNode.CharLiteral -> {
            builder.loadConstant(node.value, Type.CharType)
        }
        is ASTNode.IntegerLiteral -> {
            builder.loadConstant(node.value, Type.SInt32)
        }
        is ASTNode.BooleanLiteral -> {
            builder.loadConstant(node.value, Type.UInt1)
        }
        is ASTNode.ExpressionList -> {
            val lastIndex = node.expressions.size - 1
            node.expressions.forEachIndexed { index, statement ->
                val compileReturnValue = returnValue && (lastIndex == index)
                // Recursive call to the new centralized function
                compileExpressionNode(statement, builder, compileReturnValue)
            }
        }
        is ASTNode.Invocation -> {
            with(builder) {
                if (node.field != null) {
                    if (node.field.path == "this") {
                        load("this", Type.Reference<Any?>(node.field.path))
                    } else {
                        val fieldClassName = node.field.parent.path
                        val fieldTypeSig = Type.Reference<Any?>(node.field.path)
                        getStatic(fieldClassName, node.field.name, fieldTypeSig)
                    }
                }

                loadArguments(builder, node.arguments, node.method.parameters.map(Type.JFVariableSymbol::type))
                invoke(node.method, node.field)
                if (!returnValue && (node.method.rtn != Type.Unit)) pop()
            }
        }
        is ASTNode.When -> {
            val subjectVariable =
                node.subject?.let { subj ->
                    when (subj) {
                        is ASTNode.Variable -> subj.variableSymbol
                        is ASTNode.ValAssignment -> {
                            // Compile the assignment statement (no return value needed here)
                            compileExpressionNode(subj, builder, false)
                            subj.variableSymbol
                        }
                        is ASTNode.VarAssignment -> { // Added VarAssignment case
                            compileExpressionNode(subj, builder, false)
                            subj.variableSymbol
                        }
                        else -> {
                            // Create and assign to a temporary variable
                            val tmpVariable = Type.JFVariableSymbol(
                                "tmp",
                                subj.type(),
                                LocalSymbolMap(IdentifierCache) // Assuming LocalSymbolMap is appropriate
                            )
                            // Compile the assignment to the temporary variable
                            compileExpressionNode(ASTNode.ValAssignment(tmpVariable, subj), builder, false)
                            tmpVariable
                        }
                    }
                }

            val lastIndex = node.matches.size - 1
            var elseBranchInstructions: List<IR.Instruction>? = null
            val conditionAndBodyPairs = mutableListOf<Pair<List<IR.Instruction>, List<IR.Instruction>>>()

            node.matches.forEachIndexed { index, (condition, expression) ->
                when {
                    // Check for explicit 'else' (BooleanLiteral(true) as the last condition)
                    (index == lastIndex) && (condition == ASTNode.BooleanLiteral(true)) -> {
                        elseBranchInstructions = compileAsCodeBlock(builder, expression, returnValue)
                    }
                    else -> {
                        // Create the actual condition expression (e.g., subject == value)
                        val conditionExpression = createWhenConditionExpression(subjectVariable, condition)
                        val conditionInstructions = compileAsCodeBlock(builder, conditionExpression, true)
                        val bodyInstructions = compileAsCodeBlock(builder, expression, returnValue)
                        conditionAndBodyPairs.add(conditionInstructions to bodyInstructions)
                    }
                }
            }
            builder.`when`(conditionAndBodyPairs, elseBranchInstructions)
        }

        is ASTNode.While -> {
            val conditionInstructions = compileAsCodeBlock(builder, node.condition, true)
            val expressionInstructions = compileAsCodeBlock(builder, node.expressions, false)
            builder.`while`(conditionInstructions, expressionInstructions)
        }
        is ASTNode.ValAssignment -> {
            compileExpressionNode(node.expression, builder, true)
            if (returnValue) builder.dup() // Keep the value on stack if assignment should return it
            builder.store(
                "${node.variableSymbol.symbolMap.symbolMapId}.${node.variableSymbol.name}",
                node.variableSymbol.type,
            )
        }
        is ASTNode.VarAssignment -> {
            compileExpressionNode(node.expression, builder, true) // Expression must be evaluated
            if (returnValue) builder.dup() // Keep the value on stack if assignment should return it
            builder.store(
                "${node.variableSymbol.symbolMap.symbolMapId}.${node.variableSymbol.name}",
                node.variableSymbol.type,
            )
        }
        is ASTNode.Variable -> {
            if (returnValue) {
                builder.load("${node.variableSymbol.symbolMap.symbolMapId}.${node.variableSymbol.name}", node.variableSymbol.type)
            } else {
                // If not returning value, loading it might be unnecessary side effect, depends on context.
                // For now, assume load only happens if value is needed.
            }
        }
        is ASTNode.Function -> {
            compileMethod(
                builder.parent.parent, // This assumes specific builder nesting
                node.block,
                node.symbol.name,
                node.symbol.rtn,
                node.symbol.parameters.map(Type.JFVariableSymbol::type),
            )
        }
    }
}