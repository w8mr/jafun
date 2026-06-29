package nl.w8mr.jafun.compiler.ir2jvm

import nl.w8mr.jafun.OperandType
import nl.w8mr.jafun.Type
import nl.w8mr.jafun.compiler.ExpressionNode
import nl.w8mr.jafun.compiler.IdentifierCache
import nl.w8mr.jafun.compiler.Parameter
import nl.w8mr.jafun.compiler.effectiveJvmType
import nl.w8mr.jafun.compiler.expandAssignmentIfNeeded
import nl.w8mr.jafun.compiler.expandParameterRecursively
import nl.w8mr.jafun.compiler.expandVariable
import nl.w8mr.jafun.compiler.flattenType
import nl.w8mr.jafun.compiler.reconstructVCFromExpanded
import nl.w8mr.jafun.compiler.shouldExpandVC
import nl.w8mr.jafun.compiler.unboxSingleFieldVCType

object VCBinder {

    fun handle(context: IRBuilder.ClassContext): IRBuilder.ClassContext {
        // Phase 1: Transform all instructions (assignment expansion, field resolution, etc.)
        // Phase 1: Transform all instructions (assignment expansion, field resolution, etc.)
        for (methodIndex in context.methods.indices) {
            val method = context.methods[methodIndex]
            val newInstructions = mutableListOf<ExpressionNode.Phase2_3Expression>()

            for (instruction in method.instructions) {
                newInstructions.add(transformInstruction(instruction))
            }

            if (newInstructions != method.instructions) {
                context.methods[methodIndex] = method.copy(instructions = newInstructions.toMutableList())
            }
        }

        // Phase 2: Build method return type map from current (unmodified) return types
        val methodReturnTypes = mutableMapOf<String, OperandType<*>>()
        for (method in context.methods) {
            methodReturnTypes[method.name] = method.returnType
        }

        // Phase 3: Unbox single-field VC return types in method definitions
        for (methodIndex in context.methods.indices) {
            val method = context.methods[methodIndex]
            val innerType = unboxSingleFieldVCType(method.returnType)
            if (innerType != null) {
                context.methods[methodIndex] = method.copy(returnType = innerType)
                methodReturnTypes[method.name] = innerType

                // Unbox the last instruction (return value) if it's a CI for the VC
                val instructions = context.methods[methodIndex].instructions.toMutableList()
                if (instructions.isNotEmpty()) {
                    val lastIdx = instructions.lastIndex
                    val unboxed = tryUnboxCI(instructions[lastIdx], method.returnType as Type.JFClass)
                    if (unboxed != null) {
                        instructions[lastIdx] = unboxed
                        context.methods[methodIndex] = context.methods[methodIndex].copy(instructions = instructions)
                    }
                }
            }
        }

        // Phase 4: Update MethodInvocation return types to match unboxed method return types
        for (methodIndex in context.methods.indices) {
            val method = context.methods[methodIndex]
            val updatedInstructions = method.instructions.map {
                updateMethodInvocationReturnType(it, methodReturnTypes)
            }
            context.methods[methodIndex] = method.copy(instructions = updatedInstructions.toMutableList())
        }

        // Phase 5: Expand VC function parameters and transform bodies + call sites
        val expandedMethodParams = mutableMapOf<String, List<Parameter>>()
        for (i in context.methods.indices) {
            val method = context.methods[i]
            val expandedParams = method.parameters.flatMap { expandParameter(it, method.returnType) }
                .map { p -> if (effectiveJvmType(p.type) != p.type) Parameter(effectiveJvmType(p.type), p.varName) else p }
            val expandedReturnType = effectiveJvmType(method.returnType)
            if (expandedParams == method.parameters && expandedReturnType == method.returnType) continue

            val paramFieldMap = buildParamFieldMap(method.parameters)
            val newInstructions = if (paramFieldMap.isNotEmpty()) {
                method.instructions.map { resolveParamFieldAccess(it, paramFieldMap) }.toMutableList()
            } else {
                method.instructions
            }
            context.methods[i] = method.copy(
                parameters = expandedParams,
                returnType = expandedReturnType,
                instructions = newInstructions
            )
            expandedMethodParams[method.name] = expandedParams
        }
        // Phase 5b: Expand call sites to match expanded method parameters
        if (expandedMethodParams.isNotEmpty()) {
            for (i in context.methods.indices) {
                val method = context.methods[i]
                val updatedInstructions = method.instructions.map { expandCallSite(it, expandedMethodParams) }
                context.methods[i] = method.copy(instructions = updatedInstructions.toMutableList())
            }
        }

        return context
    }

    private fun tryUnboxCI(expr: ExpressionNode.Phase2_3Expression, vcType: Type.JFClass): ExpressionNode.Phase2_3Expression? {
        if (expr is ExpressionNode.ConstructorInvocation && expr.type() == vcType) {
            if (expr.arguments.size == 1) {
                return expr.arguments[0]
            }
        }
        if (expr is ExpressionNode.FieldAccess && expr.type() == vcType) {
            val innerFieldType = resolveFieldAccessToPrimitive(expr)
            if (innerFieldType != null) {
                return ExpressionNode.FieldAccess(
                    instance = expr.instance,
                    fieldName = expr.fieldName,
                    fieldIndex = expr.fieldIndex,
                    fieldType = innerFieldType,
                    arguments = expr.arguments,
                )
            }
        }
        if (expr is ExpressionNode.ExpressionList && expr.expressions.isNotEmpty()) {
            val lastIdx = expr.expressions.lastIndex
            val unboxed = tryUnboxCI(expr.expressions[lastIdx], vcType)
            if (unboxed != null) {
                val newExprs = expr.expressions.toMutableList()
                newExprs[lastIdx] = unboxed
                return ExpressionNode.ExpressionList(newExprs)
            }
        }
        return null
    }

    /**
     * If a FieldAccess's fieldType is a single-field VC, finds the innermost
     * primitive type by recursively looking through the VC chain.
     */
    private fun resolveFieldAccessToPrimitive(fa: ExpressionNode.FieldAccess): OperandType<*>? {
        val fieldType = fa.fieldType
        if (fieldType !is Type.JFClass || fieldType.kind != Type.ClassKind.VALUE_CLASS) return null
        val cons = fieldType.constructor ?: return null
        if (cons.parameters.size != 1) return null
        val innerType = cons.parameters[0].type
        if (innerType is Type.JFClass && innerType.kind == Type.ClassKind.VALUE_CLASS) {
            // Recursive case: nested single-field VC (e.g., Box(topLeft: Point))
            return resolveFieldAccessToPrimitive(
                ExpressionNode.FieldAccess(
                    instance = fa,
                    fieldName = cons.parameters[0].name,
                    fieldIndex = 0,
                    fieldType = innerType,
                    arguments = emptyList(),
                )
            ) ?: innerType
        }
        return innerType
    }

    private fun updateMethodInvocationReturnType(
        expr: ExpressionNode.Phase2_3Expression,
        methodReturnTypes: Map<String, OperandType<*>>
    ): ExpressionNode.Phase2_3Expression {
        return when (expr) {
            is ExpressionNode.MethodInvocation -> {
                val unboxedType = methodReturnTypes[expr.methodName]
                val currentType = expr.type()
                if (unboxedType != null && unboxedType != currentType) {
                    ExpressionNode.MethodInvocation(
                        methodName = expr.methodName,
                        parentPath = expr.parentPath,
                        parameters = expr.parameters,
                        rtnLookup = { unboxedType },
                        field = expr.field,
                        arguments = expr.arguments.map { updateMethodInvocationReturnType(it, methodReturnTypes) },
                    )
                } else {
                    val newArgs = expr.arguments.map { updateMethodInvocationReturnType(it, methodReturnTypes) }
                    if (newArgs !== expr.arguments) {
                        ExpressionNode.MethodInvocation(
                            methodName = expr.methodName,
                            parentPath = expr.parentPath,
                            parameters = expr.parameters,
                            rtnLookup = expr.rtnLookup,
                            field = expr.field,
                            arguments = newArgs,
                        )
                    } else expr
                }
            }
            is ExpressionNode.ValAssignment -> {
                val newExpr = updateMethodInvocationReturnType(expr.expression, methodReturnTypes)
                if (newExpr !== expr.expression) {
                    expr.variableSymbol.effectiveType = newExpr.type()
                    ExpressionNode.ValAssignment(expr.variableSymbol, newExpr)
                } else expr
            }
            is ExpressionNode.VarAssignment -> {
                val newExpr = updateMethodInvocationReturnType(expr.expression, methodReturnTypes)
                if (newExpr !== expr.expression) {
                    expr.variableSymbol.effectiveType = newExpr.type()
                    ExpressionNode.VarAssignment(expr.variableSymbol, newExpr)
                } else expr
            }
            is ExpressionNode.ExpressionList -> {
                val newExprs = expr.expressions.map { updateMethodInvocationReturnType(it, methodReturnTypes) }
                if (newExprs !== expr.expressions) ExpressionNode.ExpressionList(newExprs) else expr
            }
            is ExpressionNode.Convert -> {
                val newExpr = updateMethodInvocationReturnType(expr.expression, methodReturnTypes)
                val newType = newExpr.type()
                if (newExpr !== expr.expression || newType != expr.from) {
                    ExpressionNode.Convert(newExpr, newType, expr.to)
                } else expr
            }
            is ExpressionNode.ConstructorInvocation -> {
                val newArgs = expr.arguments.map { updateMethodInvocationReturnType(it, methodReturnTypes) }
                if (newArgs !== expr.arguments) ExpressionNode.ConstructorInvocation(expr.cons, newArgs) else expr
            }
            is ExpressionNode.FieldAccess -> {
                val newInstance = updateMethodInvocationReturnType(expr.instance, methodReturnTypes)
                val instanceType = newInstance.type()
                if (instanceType !is Type.JFClass) {
                    return newInstance
                }
                if (newInstance !== expr.instance) {
                    ExpressionNode.FieldAccess(
                        instance = newInstance,
                        fieldName = expr.fieldName,
                        fieldIndex = expr.fieldIndex,
                        fieldType = expr.fieldType,
                        arguments = expr.arguments,
                    )
                } else expr
            }
            is ExpressionNode.Function -> {
                val newBlock = expr.block.map { updateMethodInvocationReturnType(it, methodReturnTypes) }
                if (newBlock !== expr.block) ExpressionNode.Function(expr.symbol, newBlock) else expr
            }
            is ExpressionNode.WhilePhase3 -> {
                val newCond = updateMethodInvocationReturnType(expr.condition, methodReturnTypes)
                val newBody = updateMethodInvocationReturnType(expr.expressions, methodReturnTypes)
                if (newCond !== expr.condition || newBody !== expr.expressions) {
                    ExpressionNode.WhilePhase3(newCond, newBody)
                } else expr
            }
            is ExpressionNode.WhenPhase3 -> {
                val newMatches = expr.matches.map { (cond, e) ->
                    updateMethodInvocationReturnType(cond, methodReturnTypes) to updateMethodInvocationReturnType(e, methodReturnTypes)
                }
                if (newMatches !== expr.matches) ExpressionNode.WhenPhase3(newMatches) else expr
            }
            is ExpressionNode.Variable -> expr
            else -> expr
        }
    }

    private fun transformInstruction(node: ExpressionNode.Phase2_3Expression): ExpressionNode.Phase2_3Expression {
        return when (node) {
            is ExpressionNode.ValAssignment -> {
                val expanded = expandAssignmentIfNeeded(node)
                if (expanded.size > 1 || (expanded.singleOrNull() != node)) {
                    ExpressionNode.ExpressionList(expanded.map { e ->
                        if (e is ExpressionNode.ValAssignment) {
                            val ci = e.expression as? ExpressionNode.ConstructorInvocation
                            if (ci != null) {
                                e.variableSymbol.constructorArgs = ci.arguments
                            }
                        }
                        e
                    })
                } else {
                    val newExpr = transformInstruction(node.expression)
                    if (newExpr != node.expression) {
                        node.variableSymbol.effectiveType = newExpr.type()
                        ExpressionNode.ValAssignment(node.variableSymbol, newExpr)
                    } else {
                        node
                    }
                }
            }
            is ExpressionNode.VarAssignment -> {
                val expanded = expandAssignmentIfNeeded(node)
                if (expanded.size > 1 || (expanded.singleOrNull() != node)) {
                    ExpressionNode.ExpressionList(expanded.map { e ->
                        if (e is ExpressionNode.VarAssignment) {
                            val ci = e.expression as? ExpressionNode.ConstructorInvocation
                            if (ci != null) {
                                e.variableSymbol.constructorArgs = ci.arguments
                            }
                        }
                        e
                    })
                } else {
                    val newExpr = transformInstruction(node.expression)
                    if (newExpr != node.expression) {
                        ExpressionNode.VarAssignment(node.variableSymbol, newExpr)
                    } else {
                        node
                    }
                }
            }
            is ExpressionNode.FieldAccess -> resolveFieldAccess(node)
            is ExpressionNode.Variable -> resolveVariable(node)
            is ExpressionNode.MethodInvocation -> {
                val newArgs = node.arguments.map { transformInstruction(it) }
                if (newArgs != node.arguments) {
                    ExpressionNode.MethodInvocation(
                        methodName = node.methodName,
                        parentPath = node.parentPath,
                        parameters = node.parameters,
                        rtnLookup = node.rtnLookup,
                        field = node.field,
                        arguments = newArgs,
                    )
                } else {
                    node
                }
            }
            is ExpressionNode.ConstructorInvocation -> {
                val newArgs = node.arguments.map { transformInstruction(it) }
                if (newArgs != node.arguments) {
                    ExpressionNode.ConstructorInvocation(node.cons, newArgs)
                } else {
                    node
                }
            }
            is ExpressionNode.ExpressionList -> {
                val newExprs = node.expressions.map { transformInstruction(it) }
                if (newExprs != node.expressions) {
                    ExpressionNode.ExpressionList(newExprs)
                } else {
                    node
                }
            }
            is ExpressionNode.WhilePhase3 -> {
                val newCond = transformInstruction(node.condition)
                val newBody = transformInstruction(node.expressions)
                if (newCond != node.condition || newBody != node.expressions) {
                    ExpressionNode.WhilePhase3(newCond, newBody)
                } else {
                    node
                }
            }
            is ExpressionNode.WhenPhase3 -> {
                val newMatches = node.matches.map { (cond, expr) ->
                    transformInstruction(cond) to transformInstruction(expr)
                }
                if (newMatches != node.matches) {
                    ExpressionNode.WhenPhase3(newMatches)
                } else {
                    node
                }
            }
            is ExpressionNode.Function -> {
                val newBlock = node.block.map { transformInstruction(it) }
                if (newBlock != node.block) {
                    ExpressionNode.Function(node.symbol, newBlock)
                } else {
                    node
                }
            }
            is ExpressionNode.Convert -> {
                val newExpr = transformInstruction(node.expression)
                if (newExpr != node.expression) {
                    ExpressionNode.Convert(newExpr, node.from, node.to)
                } else {
                    node
                }
            }
            else -> node
        }
    }

    private fun resolveFieldAccess(node: ExpressionNode.FieldAccess): ExpressionNode.Phase2_3Expression {
        // Check if the original (unresolved) instance is a Variable with expanded fields
        // that can resolve the field. This must happen BEFORE resolving the variable itself,
        // because resolving a single-field VC prematurely loses the expanded field metadata.
        val instanceVariable = node.instance as? ExpressionNode.Variable
        if (instanceVariable != null) {
            val varSymbol = instanceVariable.variableSymbol
            val constructorArgs = varSymbol.constructorArgs
            if (constructorArgs != null) {
                val value = constructorArgs.getOrNull(node.fieldIndex)
                if (value != null) return value
            }
            val expandedFields = varSymbol.expandedFields
            if (expandedFields != null) {
                val field = expandedFields.find { it.name == node.fieldName }
                if (field != null) {
                    if (field.actualSymbol != null) {
                        return ExpressionNode.Variable(field.actualSymbol!!)
                    }
                    if (field.sourceVCFields != null) {
                        val syntheticVar = Type.JFVariableSymbol(
                            name = "${varSymbol.name}_${field.name}",
                            type = field.type,
                            symbolMap = varSymbol.symbolMap,
                            initialized = true,
                        )
                        val parentSymbols = varSymbol.expandedFieldSymbols
                        syntheticVar.expandedFields = field.sourceVCFields.map { (subFieldPath, subFieldType) ->
                            val fullPath = "${field.name}_$subFieldPath"
                            val actualSymbol = parentSymbols?.get(fullPath)
                            Type.ExpandedField(
                                name = subFieldPath,
                                type = subFieldType,
                                sourceVC = null,
                                sourceVCFields = null,
                                actualSymbol = actualSymbol,
                            )
                        }
                        return ExpressionNode.Variable(syntheticVar)
                    } else {
                        val expandedVar = Type.JFVariableSymbol(
                            name = "${varSymbol.name}_${field.name}",
                            type = field.type,
                            symbolMap = varSymbol.symbolMap,
                            initialized = true,
                        )
                        return ExpressionNode.Variable(expandedVar)
                    }
                }
                error("Field '${node.fieldName}' not found in ${varSymbol.name}")
            }
            // Don't resolve field access on VCs without expanded fields — leave as-is
            // so JVMBackend generates a real getfield. This handles function parameters
            // and other cases where the VC value is still boxed.
        }

        // For non-Variable instances (e.g., FieldAccess chains), recursively resolve
        val resolvedInstance = when (node.instance) {
            is ExpressionNode.FieldAccess -> resolveFieldAccess(node.instance as ExpressionNode.FieldAccess)
            is ExpressionNode.Variable -> node.instance // Don't eagerly resolve variable here
            else -> transformInstruction(node.instance)
        }

        if (resolvedInstance != node.instance) {
            // After resolving the inner field access, check if the result has expanded fields
            if (resolvedInstance is ExpressionNode.Variable) {
                val resolvedSymbol = resolvedInstance.variableSymbol
                val resolvedExpanded = resolvedSymbol.expandedFields
                if (resolvedExpanded != null) {
                    val resolvedField = resolvedExpanded.find { it.name == node.fieldName }
                    if (resolvedField != null) {
                        if (resolvedField.actualSymbol != null) {
                            return ExpressionNode.Variable(resolvedField.actualSymbol!!)
                        }
                        val newSynthetic = Type.JFVariableSymbol(
                            name = "${resolvedSymbol.name}_${resolvedField.name}",
                            type = resolvedField.type,
                            symbolMap = resolvedSymbol.symbolMap,
                            initialized = true,
                        )
                        return ExpressionNode.Variable(newSynthetic)
                    }
                }
            }
            return ExpressionNode.FieldAccess(
                instance = resolvedInstance,
                fieldName = node.fieldName,
                fieldIndex = node.fieldIndex,
                fieldType = node.fieldType,
                arguments = node.arguments,
            )
        }
        return node
    }

    private fun resolveVariable(node: ExpressionNode.Variable): ExpressionNode.Phase2_3Expression {
        val varSymbol = node.variableSymbol
        val expandedFields = varSymbol.expandedFields
        val expandedFieldSymbols = varSymbol.expandedFieldSymbols
        if (expandedFields != null && expandedFields.size == 1) {
            val singleField = expandedFields[0]
            if (singleField.actualSymbol != null) {
                return ExpressionNode.Variable(singleField.actualSymbol!!)
            }
        }
        // If the variable was expanded and is used as a value (e.g., function arg),
        // reconstruct the VC object from its expanded fields
        if (expandedFieldSymbols != null && varSymbol.type is Type.JFClass) {
            val vcType = varSymbol.type as Type.JFClass
            val reconstructed = reconstructVCFromExpanded(vcType, expandedFieldSymbols)
            if (reconstructed != null) {
                return reconstructed
            }
        }
        return node
    }

    // --- Phase 5 helpers: parameter expansion + body transform + call site expansion ---

    private fun buildParamFieldMap(
        parameters: List<Parameter>
    ): Map<String, Map<String, Pair<String, OperandType<*>>>> {
        val result = mutableMapOf<String, Map<String, Pair<String, OperandType<*>>>>()
        for (param in parameters) {
            val cleanName = param.varName?.substringAfterLast(".") ?: continue
            val type = param.type
            if (type !is Type.JFClass || type.kind != Type.ClassKind.VALUE_CLASS) continue
            if (!shouldExpandVC(type)) continue
            val cons = type.constructor ?: continue
            if (cons.parameters.size == 1 && cons.parameters[0].type == param.type) continue
            val fields = flattenType(type).associate { field ->
                field.path to ("${cleanName}_${field.path}" to field.type)
            }
            result[cleanName] = fields
        }
        return result
    }

    private fun extractFieldPath(expr: ExpressionNode.Phase2_3Expression): Pair<String, String>? {
        val parts = mutableListOf<String>()
        var current = expr
        while (current is ExpressionNode.FieldAccess) {
            parts.add(0, current.fieldName)
            current = current.instance
        }
        if (current !is ExpressionNode.Variable) return null
        return current.variableSymbol.name to parts.joinToString("_")
    }

    private fun resolveParamFieldAccess(
        expr: ExpressionNode.Phase2_3Expression,
        paramMap: Map<String, Map<String, Pair<String, OperandType<*>>>>
    ): ExpressionNode.Phase2_3Expression {
        // Try to match full field access paths against flattened param fields
        val pathMatch = extractFieldPath(expr)
        if (pathMatch != null) {
            val (baseName, fieldPath) = pathMatch
            val fields = paramMap[baseName]
            if (fields != null && fieldPath in fields) {
                val (expandedName, fieldType) = fields[fieldPath]!!
                // Get symbolMap from the innermost variable
                val symbolMap = getInnermostVariable(expr)?.variableSymbol?.symbolMap
                return ExpressionNode.Variable(
                    Type.JFVariableSymbol(expandedName, fieldType, symbolMap = symbolMap ?: IdentifierCache, initialized = true)
                )
            }
            // If param is in the map but field path doesn't match, check if the param was
            // unwrapped from a single-field VC (e.g., Box → Point, field access .topLeft)
            if (fields != null && fieldPath !in fields) {
                val innermostVar = getInnermostVariable(expr)
                if (innermostVar != null) {
                    val varType = innermostVar.variableSymbol.type
                    if (varType is Type.JFClass) {
                        val cons = varType.constructor
                        if (cons != null && cons.parameters.size == 1 && cons.parameters[0].name == fieldPath) {
                            val symbolMap = innermostVar.variableSymbol.symbolMap
                            return ExpressionNode.Variable(
                                Type.JFVariableSymbol(
                                    name = innermostVar.variableSymbol.name,
                                    type = cons.parameters[0].type,
                                    symbolMap = symbolMap ?: IdentifierCache,
                                    initialized = true
                                )
                            )
                        }
                    }
                }
            }
        }

        return when (expr) {
            is ExpressionNode.Variable -> {
                val cleanName = expr.variableSymbol.name
                val fields = paramMap[cleanName]
                if (fields != null) {
                    val vcType = expr.variableSymbol.type
                    if (vcType is Type.JFClass) {
                        val cons = vcType.constructor
                            if (cons != null) {
                            val symbolMap = expr.variableSymbol.symbolMap
                            val ciArgs = cons.parameters.map { fieldParam ->
                                val flatFieldsFromParam = flattenType(fieldParam.type, fieldParam.name)
                                val fieldPaths = flatFieldsFromParam.map { it.path }
                                when {
                                    fieldPaths.isEmpty() -> null
                                    fieldPaths.size == 1 -> {
                                        val path = fieldPaths[0]
                                        fields[path]?.let { (expandedName, fieldType) ->
                                            ExpressionNode.Variable(
                                                Type.JFVariableSymbol(expandedName, fieldType, symbolMap = symbolMap, initialized = true)
                                            )
                                        }
                                    }
                                    else -> {
                                        // Multiple flattened fields — reconstruct inner CI for nested VCs
                                        val innerCons = (fieldParam.type as? Type.JFClass)?.constructor
                                        if (innerCons != null) {
                                            val innerArgs = fieldPaths.mapNotNull { path ->
                                                fields[path]?.let { (expandedName, fieldType) ->
                                                    ExpressionNode.Variable(
                                                        Type.JFVariableSymbol(expandedName, fieldType, symbolMap = symbolMap, initialized = true)
                                                    )
                                                }
                                            }
                                            if (innerArgs.size == fieldPaths.size) {
                                                ExpressionNode.ConstructorInvocation(innerCons, innerArgs)
                                            } else null
                                        } else null
                                    }
                                }
                            }
                            if (ciArgs.none { it == null }) {
                                return ExpressionNode.ConstructorInvocation(cons, ciArgs.filterNotNull())
                            }
                        }
                    }
                }
                expr
            }
            is ExpressionNode.FieldAccess -> {
                val newInstance = resolveParamFieldAccess(expr.instance, paramMap)
                val result = if (newInstance != expr.instance) {
                    ExpressionNode.FieldAccess(newInstance, expr.fieldName, expr.fieldIndex, expr.fieldType, expr.arguments)
                } else expr
                // Validate field exists on the instance type
                if (result is ExpressionNode.FieldAccess) {
                    val accessType = result.instance.type()
                    if (accessType is Type.JFClass) {
                        val fieldExists = accessType.constructor?.parameters?.any { it.name == result.fieldName } ?: false
                        if (!fieldExists) {
                            error("Field '${result.fieldName}' not found in ${accessType.name}")
                        }
                    }
                }
                result
            }
            is ExpressionNode.MethodInvocation -> {
                val newArgs = expr.arguments.map { resolveParamFieldAccess(it, paramMap) }
                if (newArgs != expr.arguments) {
                    ExpressionNode.MethodInvocation(expr.methodName, expr.parentPath, expr.parameters, expr.rtnLookup, expr.field, newArgs)
                } else expr
            }
            is ExpressionNode.ExpressionList -> {
                val newExprs = expr.expressions.map { resolveParamFieldAccess(it, paramMap) }
                if (newExprs != expr.expressions) ExpressionNode.ExpressionList(newExprs) else expr
            }
            is ExpressionNode.ValAssignment -> {
                val newExpr = resolveParamFieldAccess(expr.expression, paramMap)
                if (newExpr != expr.expression) ExpressionNode.ValAssignment(expr.variableSymbol, newExpr) else expr
            }
            is ExpressionNode.VarAssignment -> {
                val newExpr = resolveParamFieldAccess(expr.expression, paramMap)
                if (newExpr != expr.expression) ExpressionNode.VarAssignment(expr.variableSymbol, newExpr) else expr
            }
            is ExpressionNode.ConstructorInvocation -> {
                val newArgs = expr.arguments.map { resolveParamFieldAccess(it, paramMap) }
                if (newArgs != expr.arguments) ExpressionNode.ConstructorInvocation(expr.cons, newArgs) else expr
            }
            is ExpressionNode.Function -> {
                val newBlock = expr.block.map { resolveParamFieldAccess(it, paramMap) }
                if (newBlock != expr.block) ExpressionNode.Function(expr.symbol, newBlock) else expr
            }
            is ExpressionNode.WhilePhase3 -> {
                val newCond = resolveParamFieldAccess(expr.condition, paramMap)
                val newBody = resolveParamFieldAccess(expr.expressions, paramMap)
                if (newCond != expr.condition || newBody != expr.expressions) ExpressionNode.WhilePhase3(newCond, newBody) else expr
            }
            is ExpressionNode.WhenPhase3 -> {
                val newMatches = expr.matches.map { (c, e) -> resolveParamFieldAccess(c, paramMap) to resolveParamFieldAccess(e, paramMap) }
                if (newMatches != expr.matches) ExpressionNode.WhenPhase3(newMatches) else expr
            }
            is ExpressionNode.Convert -> {
                val newExpr = resolveParamFieldAccess(expr.expression, paramMap)
                if (newExpr != expr.expression) ExpressionNode.Convert(newExpr, expr.from, expr.to) else expr
            }
            else -> expr
        }
    }

    private fun getInnermostVariable(expr: ExpressionNode.Phase2_3Expression): ExpressionNode.Variable? {
        return when (expr) {
            is ExpressionNode.Variable -> expr
            is ExpressionNode.FieldAccess -> getInnermostVariable(expr.instance)
            else -> null
        }
    }

    private fun flattenCIArgs(ci: ExpressionNode.ConstructorInvocation): List<ExpressionNode.Phase2_3Expression> {
        val cons = ci.cons
        val result = mutableListOf<ExpressionNode.Phase2_3Expression>()
        for ((idx, param) in cons.parameters.withIndex()) {
            val arg = if (idx < ci.arguments.size) ci.arguments[idx] else continue
            if (arg is ExpressionNode.ConstructorInvocation &&
                param.type is Type.JFClass &&
                (param.type as Type.JFClass).kind == Type.ClassKind.VALUE_CLASS) {
                result.addAll(flattenCIArgs(arg))
            } else {
                result.add(arg)
            }
        }
        return result
    }

    private fun expandCallSite(
        expr: ExpressionNode.Phase2_3Expression,
        expandedMethodParams: Map<String, List<Parameter>>
    ): ExpressionNode.Phase2_3Expression {
        return when (expr) {
            is ExpressionNode.MethodInvocation -> {
                val expandedParams = expandedMethodParams[expr.methodName]
                if (expandedParams != null) {
                    val newArgs = mutableListOf<ExpressionNode.Phase2_3Expression>()
                    val newParamSymbols = mutableListOf<Type.JFVariableSymbol>()
                    var argIdx = 0
                    var expandedIdx = 0
                    for (origParam in expr.parameters) {
                        val isVCParam = origParam.type is Type.JFClass &&
                            (origParam.type as Type.JFClass).kind == Type.ClassKind.VALUE_CLASS
                        val paramExpandedType = expandedParams.getOrNull(expandedIdx)?.type
                        val paramWasExpanded = paramExpandedType != null &&
                            (paramExpandedType !is Type.JFClass || (paramExpandedType as Type.JFClass).kind != Type.ClassKind.VALUE_CLASS)
                        if (isVCParam && paramWasExpanded && argIdx < expr.arguments.size &&
                            expr.arguments[argIdx] is ExpressionNode.ConstructorInvocation) {
                            val ci = expr.arguments[argIdx] as ExpressionNode.ConstructorInvocation
                            val flatCArgs = flattenCIArgs(ci)
                            newArgs.addAll(flatCArgs)
                            for (j in flatCArgs.indices) {
                                if (expandedIdx < expandedParams.size) {
                                    val ep = expandedParams[expandedIdx]
                                    newParamSymbols.add(
                                        Type.JFVariableSymbol(
                                            name = ep.varName ?: "p$expandedIdx",
                                            type = ep.type,
                                            initialized = true
                                        )
                                    )
                                    expandedIdx++
                                }
                            }
                        } else {
                            val arg = if (argIdx < expr.arguments.size) expr.arguments[argIdx] else null
                            if (isVCParam && paramWasExpanded && arg is ExpressionNode.Variable) {
                                val varSymbol = arg.variableSymbol
                                val expanded = expandVariable(varSymbol)
                                newArgs.addAll(expanded.map { sym ->
                                    ExpressionNode.Variable(sym)
                                })
                                for (j in expanded.indices) {
                                    if (expandedIdx < expandedParams.size) {
                                        val ep = expandedParams[expandedIdx]
                                        newParamSymbols.add(
                                            Type.JFVariableSymbol(
                                                name = ep.varName ?: "p$expandedIdx",
                                                type = ep.type,
                                                initialized = true
                                            )
                                        )
                                        expandedIdx++
                                    }
                                }
                            } else {
                                val unwrappedArg = if (arg is ExpressionNode.ConstructorInvocation &&
                                    !paramWasExpanded && isVCParam && paramExpandedType != null) {
                                    // Single-field VC wrapping to expected type — unwrap
                                    val origCons = (origParam.type as? Type.JFClass)?.constructor
                                    if (origCons != null && origCons.parameters.size == 1 &&
                                        origCons.parameters[0].type == paramExpandedType &&
                                        arg.arguments.size == 1) {
                                        arg.arguments[0]
                                    } else arg
                                } else arg
                                if (unwrappedArg != null) newArgs.add(unwrappedArg)
                                if (expandedIdx < expandedParams.size) {
                                    val ep = expandedParams[expandedIdx]
                                    newParamSymbols.add(
                                        Type.JFVariableSymbol(
                                            name = ep.varName ?: "p$expandedIdx",
                                            type = ep.type,
                                            initialized = true
                                        )
                                    )
                                } else {
                                    newParamSymbols.add(origParam)
                                }
                                expandedIdx++
                            }
                        }
                        argIdx++
                    }
                    while (argIdx < expr.arguments.size) {
                        newArgs.add(expr.arguments[argIdx])
                        argIdx++
                    }
                    if (newArgs != expr.arguments || newParamSymbols != expr.parameters) {
                        ExpressionNode.MethodInvocation(
                            expr.methodName, expr.parentPath, newParamSymbols, expr.rtnLookup, expr.field, newArgs
                        )
                    } else expr
                } else {
                    val newArgs = expr.arguments.map { expandCallSite(it, expandedMethodParams) }
                    if (newArgs != expr.arguments) {
                        ExpressionNode.MethodInvocation(expr.methodName, expr.parentPath, expr.parameters, expr.rtnLookup, expr.field, newArgs)
                    } else expr
                }
            }
            is ExpressionNode.ExpressionList -> {
                val newExprs = expr.expressions.map { expandCallSite(it, expandedMethodParams) }
                if (newExprs != expr.expressions) ExpressionNode.ExpressionList(newExprs) else expr
            }
            is ExpressionNode.ValAssignment -> {
                val newExpr = expandCallSite(expr.expression, expandedMethodParams)
                if (newExpr != expr.expression) ExpressionNode.ValAssignment(expr.variableSymbol, newExpr) else expr
            }
            is ExpressionNode.VarAssignment -> {
                val newExpr = expandCallSite(expr.expression, expandedMethodParams)
                if (newExpr != expr.expression) ExpressionNode.VarAssignment(expr.variableSymbol, newExpr) else expr
            }
            is ExpressionNode.Function -> {
                val newBlock = expr.block.map { expandCallSite(it, expandedMethodParams) }
                if (newBlock != expr.block) ExpressionNode.Function(expr.symbol, newBlock) else expr
            }
            is ExpressionNode.FieldAccess -> {
                val newInstance = expandCallSite(expr.instance, expandedMethodParams)
                if (newInstance != expr.instance) {
                    ExpressionNode.FieldAccess(newInstance, expr.fieldName, expr.fieldIndex, expr.fieldType, expr.arguments)
                } else expr
            }
            is ExpressionNode.ConstructorInvocation -> {
                val newArgs = expr.arguments.map { expandCallSite(it, expandedMethodParams) }
                if (newArgs != expr.arguments) ExpressionNode.ConstructorInvocation(expr.cons, newArgs) else expr
            }
            is ExpressionNode.WhilePhase3 -> {
                val newCond = expandCallSite(expr.condition, expandedMethodParams)
                val newBody = expandCallSite(expr.expressions, expandedMethodParams)
                if (newCond != expr.condition || newBody != expr.expressions) ExpressionNode.WhilePhase3(newCond, newBody) else expr
            }
            is ExpressionNode.WhenPhase3 -> {
                val newMatches = expr.matches.map { (c, e) -> expandCallSite(c, expandedMethodParams) to expandCallSite(e, expandedMethodParams) }
                if (newMatches != expr.matches) ExpressionNode.WhenPhase3(newMatches) else expr
            }
            is ExpressionNode.Convert -> {
                val newExpr = expandCallSite(expr.expression, expandedMethodParams)
                if (newExpr != expr.expression) ExpressionNode.Convert(newExpr, expr.from, expr.to) else expr
            }
            else -> expr
        }
    }

    private fun expandParameter(param: Parameter, returnType: OperandType<*>): List<Parameter> {
        val type = param.type
        if (type !is Type.JFClass || type.kind != Type.ClassKind.VALUE_CLASS) {
            return listOf(param)
        }
        if (!shouldExpandVC(type)) {
            return listOf(param)
        }
        val cons = type.constructor ?: return listOf(param)
        val singleFieldType = if (cons.parameters.size == 1) cons.parameters[0].type else null
        val returnsWrappedType = singleFieldType != null && singleFieldType == returnType
        if (returnsWrappedType) {
            return listOf(Parameter(effectiveJvmType(type), param.varName))
        }
        val baseName = param.varName
        return cons.parameters.flatMap { fieldParam ->
            val fieldName = fieldParam.name
            val newBaseName = if (baseName != null) "${baseName}_${fieldName}" else fieldName
            expandParameterRecursively(fieldParam.type, newBaseName)
        }
    }
}
