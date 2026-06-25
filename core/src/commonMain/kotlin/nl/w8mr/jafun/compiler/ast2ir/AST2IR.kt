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
            val (expandedParams, expandedRawArgs) = expandValueClassParams(node.parameters, node.arguments)
            val arguments = loadArguments(builder, expandedRawArgs, expandedParams.map { it.type })
            builder.add(ExpressionNode.MethodInvocation(
                methodName = node.methodName,
                parentPath = node.parentPath,
                parameters = expandedParams,
                rtnLookup = node.rtnLookup,
                field = node.field,
                arguments = arguments,
            ))
        }
        is ExpressionNode.ConstructorInvocation -> {
            val arguments = loadArguments(builder, node.arguments, node.cons.parameters.map(Type.JFVariableSymbol::type))
            val classType = node.cons.parent as? Type.JFClass
            if (classType != null && classType.isInlineValueClass) {
                builder.add(arguments.single())
            } else {
                builder.add(ExpressionNode.ConstructorInvocation(node.cons, arguments))
            }
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
            val parameters = buildFunctionParameters(symbol, node)
            val returnType = if (symbol.rtn is Type.JFClass) {
                val vc = symbol.rtn as Type.JFClass
                if (vc.isInlineValueClass) vc.constructor!!.parameters.single().type
                else symbol.rtn
            } else symbol.rtn
            compileMethod(
                builder.parent.parent,
                node.block,
                symbol.name,
                returnType,
                parameters,
            )
        }
        is ExpressionNode.ValAssignment -> {
            val expr = compileAsCodeBlock(builder, node.expression)
            val varType = node.variableSymbol.type
            if (varType is Type.JFClass && varType.kind == Type.ClassKind.VALUE_CLASS && expr is ExpressionNode.ConstructorInvocation) {
                node.variableSymbol.constructorArgs = expr.arguments
                return
            }
            builder.add(ExpressionNode.ValAssignment(node.variableSymbol, expr))
        }
        is ExpressionNode.VarAssignment -> {
            builder.add(ExpressionNode.VarAssignment(node.variableSymbol, compileAsCodeBlock(builder, node.expression)))
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
            val ciShortcut: ExpressionNode.Phase2_3Expression? =
                if (node.instance is ExpressionNode.ConstructorInvocation) {
                    val ci = node.instance
                    val vcType = ci.type()
                    if (vcType is Type.JFClass && vcType.isInlineValueClass) {
                        ci.arguments[node.fieldIndex]
                    } else null
                } else null
            if (ciShortcut != null) {
                builder.add(compileAsCodeBlock(builder, ciShortcut))
                return
            }
            // For single-field VC instances, field access is identity
            val instanceType = node.instance.type()
            if (instanceType is Type.JFClass && instanceType.isInlineValueClass) {
                builder.add(compileAsCodeBlock(builder, node.instance))
                return
            }
            val instance = compileAsCodeBlock(builder, node.instance)
            if (instance is ExpressionNode.Variable) {
                val varName = instance.variableSymbol.name
                val constructorArgs = instance.variableSymbol.constructorArgs
                if (constructorArgs != null) {
                    val value = constructorArgs.getOrNull(node.fieldIndex)
                    if (value != null) {
                        builder.add(value)
                        return
                    }
                }
                val expandedFields = instance.variableSymbol.expandedFields
                if (expandedFields != null) {
                    val (fieldName, fieldType) = expandedFields.getOrNull(node.fieldIndex)
                        ?: error("Field index ${node.fieldIndex} not found in expansion of $varName")
                    builder.add(
                        ExpressionNode.Variable(
                            Type.JFVariableSymbol(
                                name = "${varName}_$fieldName",
                                type = fieldType,
                                symbolMap = instance.variableSymbol.symbolMap,
                                initialized = true,
                            )
                        )
                    )
                    return
                }
            }
            builder.add(ExpressionNode.FieldAccess(
                instance = instance,
                fieldName = node.fieldName,
                fieldIndex = node.fieldIndex,
                fieldType = node.fieldType,
                arguments = emptyList(),
            ))
        }
        is ExpressionNode.Phase2_3Expression -> {
            builder.add(node)
        }
    }
}

private fun findConstructor(vc: Type.JFClass): Type.JFConstructor? {
    return vc.constructor
}

private fun buildFunctionParameters(
    symbol: Type.JFMethod,
    node: ExpressionNode.Function,
): List<Parameter> {
    val paramNames = symbol.parameters.map { it.name }.toSet()
    val paramRefSymbolMap = node.block.mapNotNull { e -> findParamSymbolMap(e, paramNames) }.firstOrNull()
    val actualSymbolMapId = paramRefSymbolMap?.symbolMapId
    return symbol.parameters.flatMap { param ->
        val type = param.type
        if (type is Type.JFClass && type.kind == Type.ClassKind.VALUE_CLASS) {
            val cons = findConstructor(type)
            if (cons != null) {
                val fieldExpansions = cons.parameters.map { it.name to it.type }
                param.expandedFields = fieldExpansions
                if (paramRefSymbolMap != null) {
                    (paramRefSymbolMap.findSingleOrNull(null, param.name) as? Type.JFVariableSymbol)
                        ?.expandedFields = fieldExpansions
                }
                fieldExpansions.map { (fieldName, fieldType) ->
                    val varName = if (actualSymbolMapId != null) "${actualSymbolMapId}.${param.name}_$fieldName" else null
                    Parameter(fieldType, varName)
                }
            } else emptyList()
        } else {
            val varName = if (actualSymbolMapId != null) "${actualSymbolMapId}.${param.name}" else null
            listOf(Parameter(type, varName))
        }
    }
}

private fun expandValueClassParams(
    parameters: List<Type.JFVariableSymbol>,
    arguments: List<ExpressionNode.Phase2_3Expression>,
): Pair<List<Type.JFVariableSymbol>, List<ExpressionNode.Phase2_3Expression>> {
    val (expandedParams, expandedRawArgs) = parameters.zip(arguments).flatMap { (param, arg) ->
        val cons = (param.type as? Type.JFClass)?.takeIf { it.kind == Type.ClassKind.VALUE_CLASS }?.let { findConstructor(it) }
        cons?.parameters?.mapIndexed { fieldIndex, fieldParam ->
            param.copy(name = "${param.name}_${fieldParam.name}", type = fieldParam.type) to
                ExpressionNode.FieldAccess(
                    instance = arg, fieldName = fieldParam.name,
                    fieldIndex = fieldIndex, fieldType = fieldParam.type,
                    arguments = emptyList(),
                )
        } ?: listOf(param to arg)
    }.unzip()
    return Pair(expandedParams, expandedRawArgs)
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
