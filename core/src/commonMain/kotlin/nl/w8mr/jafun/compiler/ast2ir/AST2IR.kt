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
import nl.w8mr.jafun.compiler.flattenType
import nl.w8mr.jafun.compiler.expandVariable
import nl.w8mr.jafun.compiler.isMultiFieldVC
import nl.w8mr.jafun.compiler.createNestedFieldAccess
import nl.w8mr.jafun.compiler.shouldExpandVC
import nl.w8mr.jafun.compiler.reconstructVCFromExpanded
import nl.w8mr.jafun.compiler.expandParameterRecursively
import nl.w8mr.jafun.compiler.effectiveJvmType

import nl.w8mr.jafun.compiler.expandValueClassParams
import nl.w8mr.jafun.compiler.findParamSymbolMap
import nl.w8mr.jafun.compiler.buildFunctionParameters
import nl.w8mr.jafun.compiler.setExpandedFieldsOnParameterVariables

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

/**
 * Expand an Assignment of a multi-field VC into multiple Assignment nodes
 * of primitive types (val for val, var for var).
 * 
 * Example: val b = Box(Point(1,2), Point(3,4)) with type Box(topLeft: Point, bottomRight: Point)
 * becomes:
 *   val b_topLeft_x = <expr>
 *   val b_topLeft_y = <expr>
 *   val b_bottomRight_x = <expr>
 *   val b_bottomRight_y = <expr>
 *   
 * The original symbol is updated with expandedFields storing the hierarchical structure
 * so that b.topLeft resolves correctly and knows that topLeft has x and y.
 */
internal fun expandAssignmentIfNeeded(
    assignment: ExpressionNode.Assignment,
): List<ExpressionNode.Phase2_3Expression> {
    val varType = assignment.variableSymbol.type
    
    // Only expand VC types
    if (varType !is Type.JFClass || varType.kind != Type.ClassKind.VALUE_CLASS) {
        return listOf(assignment as ExpressionNode.Phase2_3Expression)
    }
    // We can only expand when the RHS is a constructor invocation with known values
    if (assignment.expression !is ExpressionNode.ConstructorInvocation) {
        return listOf(assignment as ExpressionNode.Phase2_3Expression)
    }
    val ci = assignment.expression as ExpressionNode.ConstructorInvocation
    
    // Get the flattened fields for the entire structure
    val flattened = flattenType(varType)
    val expandedVars = expandVariable(assignment.variableSymbol)
    
    // Store the hierarchical structure on the original symbol
    val constructorParams = (varType as Type.JFClass).constructor!!.parameters
    
    // Build a map from flattened field paths to their expanded symbols
    val fieldPathToSymbol = mutableMapOf<String, Type.JFVariableSymbol>()
    expandedVars.forEachIndexed { index, expandedVar ->
        fieldPathToSymbol[flattened[index].path] = expandedVar
    }
    
    assignment.variableSymbol.expandedFields = constructorParams.map { param ->
        // Find all flattened fields belonging to this parameter
        val paramFlattened = flattened.filter { field ->
            field.path.startsWith(param.name + "_") || field.path == param.name
        }
        
        // For nested VCs, extract the sub-structure
        val sourceVCFields = if (isMultiFieldVC(param.type) && paramFlattened.size > 1) {
            paramFlattened.map { field ->
                // Strip the parameter name prefix to get local path
                val localPath = if (field.path.startsWith(param.name + "_")) {
                    field.path.substring((param.name + "_").length)
                } else {
                    field.path
                }
                localPath to field.type
            }
        } else {
            null
        }
        
        // Find the actual symbol if this is a direct field (not nested)
        val actualSymbol = if (paramFlattened.size == 1 && sourceVCFields == null) {
            fieldPathToSymbol[paramFlattened[0].path]
        } else {
            null
        }
        
        Type.ExpandedField(
            name = param.name,
            type = param.type,
            sourceVC = if (isMultiFieldVC(param.type)) param.type as? Type.JFClass else null,
            sourceVCFields = sourceVCFields,
            actualSymbol = actualSymbol,
        )
    }
    
    // Also store the expanded symbols mapping in the original variable for nested access
    assignment.variableSymbol.expandedFieldSymbols = fieldPathToSymbol
    
    // For each flattened field, create a nested field access expression
    val expandedArgs = flattened.map { field ->
        val pathComponents = field.path.split("_")
        
        // Find which constructor argument this path belongs to
        val matchingArgIndex = constructorParams.indexOfFirst { param ->
            field.path.startsWith(param.name + "_") || field.path == param.name
        }
        
        if (matchingArgIndex >= 0 && matchingArgIndex < ci.arguments.size) {
            val arg = ci.arguments[matchingArgIndex]
            
            // Remove the first component if it matches the arg parameter name
            val param = constructorParams[matchingArgIndex]
            val remainingPath = if (field.path.startsWith(param.name + "_")) {
                field.path.substring((param.name + "_").length).split("_")
            } else if (field.path == param.name) {
                emptyList()
            } else {
                field.path.split("_")
            }
            
            if (remainingPath.isEmpty()) {
                arg
            } else {
                // Resolve through expanded field symbols when the arg is a variable
                val resolvedFromVariable = if (arg is ExpressionNode.Variable) {
                    val fieldPath = remainingPath.joinToString("_")
                    arg.variableSymbol.expandedFieldSymbols?.get(fieldPath)
                        ?.let { ExpressionNode.Variable(it) }
                } else {
                    null
                }
                // When arg is a ConstructorInvocation, extract the matching constructor argument
                val resolvedFromConstructor = if (arg is ExpressionNode.ConstructorInvocation && resolvedFromVariable == null) {
                    val fieldName = remainingPath[0]
                    val fieldIndex = arg.cons.parameters.indexOfFirst { it.name == fieldName }
                    if (fieldIndex >= 0 && fieldIndex < arg.arguments.size) {
                        val fieldArg = arg.arguments[fieldIndex]
                        if (remainingPath.size == 1) fieldArg
                        else createNestedFieldAccess(remainingPath.drop(1), fieldArg)
                    } else null
                } else null
                resolvedFromVariable ?: resolvedFromConstructor ?: createNestedFieldAccess(remainingPath, arg)
            }
        } else {
            error("Could not match field path ${field.path} to constructor arguments")
        }
    }
    
    // Create separate Assignment for each expanded variable (val for val, var for var)
    return expandedVars.mapIndexed { index, expandedVar ->
        if (assignment.variableSymbol.mutable) {
            ExpressionNode.VarAssignment(expandedVar, expandedArgs[index])
        } else {
            ExpressionNode.ValAssignment(expandedVar, expandedArgs[index])
        }
    }
}

fun compileExpressionNode(
    node: ExpressionNode.Phase2_3Expression,
    builder: IRBuilder.CodeBlockDSL,
) {
    when (node) {
        is ExpressionNode.MethodInvocation -> {
            val (expandedParams, expandedRawArgs) = expandValueClassParams(node.parameters, node.arguments)
            val arguments = loadArguments(builder, expandedRawArgs, expandedParams.map { it.effectiveType ?: it.type })
            val originalRtnLookup = node.rtnLookup
            builder.add(ExpressionNode.MethodInvocation(
                methodName = node.methodName,
                parentPath = node.parentPath,
                parameters = expandedParams,
                rtnLookup = { effectiveJvmType(originalRtnLookup()) },
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
            // Keep side effects (sets expandedFields, skipExpansion, effectiveType on symbol params)
            buildFunctionParameters(symbol, node)

            // Create naive (unexpanded) parameters matching the original method signature
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
            // Keep side effects: sets expandedFields, expandedFieldSymbols on original variable symbol
            expandAssignmentIfNeeded(node)

            // For VCs where Phase 4b will expand the ValAssignment (expandedFieldSymbols != null),
            // keep the original CI expression so the expander sees the full argument structure.
            // This is necessary because compileAsCodeBlock unwraps inline VC constructor arguments
            // (e.g., Id(42) → Lit(42)), which would prevent Phase 4b from extracting nested fields.
            val expr = if (node.variableSymbol.expandedFieldSymbols != null) {
                node.expression
            } else {
                compileAsCodeBlock(builder, node.expression)
            }
            node.variableSymbol.effectiveType = effectiveJvmType(node.variableSymbol.type)
            builder.add(ExpressionNode.ValAssignment(node.variableSymbol, expr))
        }
        is ExpressionNode.VarAssignment -> {
            // Keep side effects: sets expandedFields, expandedFieldSymbols on original variable symbol
            expandAssignmentIfNeeded(node)

            // For VCs where Phase 4c will expand the VarAssignment (expandedFieldSymbols != null),
            // keep the original CI expression so the expander sees the full argument structure.
            val expr = if (node.variableSymbol.expandedFieldSymbols != null) {
                node.expression
            } else {
                compileAsCodeBlock(builder, node.expression)
            }
            node.variableSymbol.effectiveType = effectiveJvmType(node.variableSymbol.type)
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




