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
import nl.w8mr.jafun.compiler.transformTree
import nl.w8mr.jafun.compiler.unboxSingleFieldVCType

object VCBinder {

    fun handle(context: IRBuilder.ClassContext): IRBuilder.ClassContext {
        val methodReturnTypes = context.methods.associate { method ->
            method.name to (unboxSingleFieldVCType(method.returnType) ?: method.returnType)
        }

        val expandedMethodParams = context.methods.associate { method ->
            val expandedParams = method.parameters.flatMap { expandParameter(it, method.returnType) }
                .map { p -> if (effectiveJvmType(p.type) != p.type) Parameter(effectiveJvmType(p.type), p.varName) else p }
            method.name to expandedParams
        }.filter { (name, params) ->
            val orig = context.methods.first { it.name == name }
            params != orig.parameters
        }

        val expandMI: ((ExpressionNode.MethodInvocation) -> ExpressionNode.MethodInvocation)? =
            if (expandedMethodParams.isNotEmpty()) {
                { mi -> expandedMethodParams[mi.methodName]?.let { expandMethodInvocation(mi, it) } ?: mi }
            } else null

        for (i in context.methods.indices) {
            val method = context.methods[i]

            val expandedParams = method.parameters.flatMap { expandParameter(it, method.returnType) }
                .map { p -> if (effectiveJvmType(p.type) != p.type) Parameter(effectiveJvmType(p.type), p.varName) else p }
            val expandedReturnType = methodReturnTypes[method.name] ?: method.returnType
            val hasParamChanges = expandedParams != method.parameters || expandedReturnType != method.returnType

            val paramFieldMap = buildParamFieldMap(method.parameters)

            val combinedInstructions = method.instructions.map { instruction ->
                instruction
                    .transformTree { node ->
                        val afterPhase1 = onPhase1(node, paramFieldMap.takeIf { it.isNotEmpty() }, expandMI)
                        if (afterPhase1 !== node) return@transformTree afterPhase1
                        node
                            .let { onExpandCallSite(it, expandedMethodParams) }
                            .let { onUpdateReturnType(it, methodReturnTypes) }
                    }
            }.toMutableList()

            val unboxedType = unboxSingleFieldVCType(method.returnType)
            if (unboxedType != null && combinedInstructions.isNotEmpty()) {
                val unboxed = tryUnboxCI(combinedInstructions.last(), method.returnType as Type.JFClass)
                if (unboxed != null) combinedInstructions[combinedInstructions.lastIndex] = unboxed
            }

            if (hasParamChanges) {
                context.methods[i] = method.copy(
                    parameters = expandedParams,
                    returnType = expandedReturnType,
                    instructions = combinedInstructions
                )
            } else if (combinedInstructions != method.instructions) {
                context.methods[i] = method.copy(instructions = combinedInstructions)
            }
        }

        return context
    }

    private fun onPhase1(
        node: ExpressionNode.Phase2_3Expression,
        paramFieldMap: Map<String, Map<String, Pair<String, OperandType<*>>>>?,
        expandMI: ((ExpressionNode.MethodInvocation) -> ExpressionNode.MethodInvocation)? = null,
    ): ExpressionNode.Phase2_3Expression {
        when (node) {
            is ExpressionNode.ValAssignment -> {
                val expanded = expandAssignmentIfNeeded(node, expandMI)
                if (expanded.size > 1 || expanded.singleOrNull() !== node) {
                    return ExpressionNode.ExpressionList(expanded.map { e ->
                        if (e is ExpressionNode.ValAssignment) {
                            val ci = e.expression as? ExpressionNode.ConstructorInvocation
                            if (ci != null) e.variableSymbol.constructorArgs = ci.arguments
                        }
                        e
                    })
                }
            }
            is ExpressionNode.VarAssignment -> {
                val expanded = expandAssignmentIfNeeded(node, expandMI)
                if (expanded.size > 1 || expanded.singleOrNull() !== node) {
                    return ExpressionNode.ExpressionList(expanded.map { e ->
                        if (e is ExpressionNode.VarAssignment) {
                            val ci = e.expression as? ExpressionNode.ConstructorInvocation
                            if (ci != null) e.variableSymbol.constructorArgs = ci.arguments
                        }
                        e
                    })
                }
            }
            is ExpressionNode.FieldAccess -> {
                val resolved = resolveFieldAccess(node)
                if (resolved !== node) return resolved
            }
            is ExpressionNode.Variable -> {
                val resolved = resolveVariable(node)
                if (resolved !== node) return resolved
            }
            else -> {}
        }

        if (paramFieldMap != null) {
            when (node) {
                is ExpressionNode.FieldAccess -> {
                    val pathMatch = extractFieldPath(node)
                    if (pathMatch != null) {
                        val (baseName, fieldPath) = pathMatch
                        val fields = paramFieldMap[baseName]
                        if (fields != null && fieldPath in fields) {
                            val (expandedName, fieldType) = fields[fieldPath]!!
                            val symbolMap = getInnermostVariable(node)?.variableSymbol?.symbolMap
                            return ExpressionNode.Variable(
                                Type.JFVariableSymbol(expandedName, fieldType, symbolMap = symbolMap ?: IdentifierCache, initialized = true)
                            )
                        }
                        if (fields != null && fieldPath !in fields) {
                            val innermostVar = getInnermostVariable(node)
                            if (innermostVar != null) {
                                val varType = innermostVar.variableSymbol.type
                                if (varType is Type.JFClass) {
                                    val cons = varType.constructor
                                    if (cons != null && cons.parameters.size == 1 && cons.parameters[0].name == fieldPath) {
                                        val sm = innermostVar.variableSymbol.symbolMap
                                        return ExpressionNode.Variable(
                                            Type.JFVariableSymbol(
                                                name = innermostVar.variableSymbol.name,
                                                type = cons.parameters[0].type,
                                                symbolMap = sm ?: IdentifierCache,
                                                initialized = true
                                            )
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
                is ExpressionNode.Variable -> {
                    val cleanName = node.variableSymbol.name
                    val fields = paramFieldMap[cleanName]
                    if (fields != null) {
                        val vcType = node.variableSymbol.type
                        if (vcType is Type.JFClass) {
                            val cons = vcType.constructor
                            if (cons != null) {
                                val symbolMap = node.variableSymbol.symbolMap
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
                }
                else -> {}
            }
        }

        return node
    }

    // --- Phase 1: Field resolution helpers ---

    private fun resolveFieldAccess(node: ExpressionNode.FieldAccess): ExpressionNode.Phase2_3Expression {
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
        }

        val resolvedInstance = when (node.instance) {
            is ExpressionNode.FieldAccess -> resolveFieldAccess(node.instance as ExpressionNode.FieldAccess)
            is ExpressionNode.Variable -> node.instance
            else -> node.instance
        }

        if (resolvedInstance !== node.instance) {
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
        if (expandedFieldSymbols != null && varSymbol.type is Type.JFClass) {
            val vcType = varSymbol.type as Type.JFClass
            val reconstructed = reconstructVCFromExpanded(vcType, expandedFieldSymbols)
            if (reconstructed != null) {
                return reconstructed
            }
        }
        return node
    }

    // --- Phase 2: Update MethodInvocation return types ---

    private fun onUpdateReturnType(
        node: ExpressionNode.Phase2_3Expression,
        methodReturnTypes: Map<String, OperandType<*>>
    ): ExpressionNode.Phase2_3Expression = when (node) {
        is ExpressionNode.MethodInvocation -> {
            val unboxedType = methodReturnTypes[node.methodName]
            val currentType = node.type()
            if (unboxedType != null && unboxedType != currentType) {
                ExpressionNode.MethodInvocation(
                    methodName = node.methodName,
                    parentPath = node.parentPath,
                    parameters = node.parameters,
                    rtnLookup = { unboxedType },
                    field = node.field,
                    arguments = node.arguments,
                )
            } else {
                node
            }
        }
        is ExpressionNode.ValAssignment -> {
            val newExpr = node.expression.transformTree { onUpdateReturnType(it, methodReturnTypes) }
            if (newExpr !== node.expression) {
                node.variableSymbol.effectiveType = newExpr.type()
            }
            node
        }
        is ExpressionNode.VarAssignment -> {
            val newExpr = node.expression.transformTree { onUpdateReturnType(it, methodReturnTypes) }
            if (newExpr !== node.expression) {
                node.variableSymbol.effectiveType = newExpr.type()
            }
            node
        }
        is ExpressionNode.Convert -> {
            val newExpr = node.expression.transformTree { onUpdateReturnType(it, methodReturnTypes) }
            val newType = newExpr.type()
            if (newExpr !== node.expression || newType != node.from) {
                ExpressionNode.Convert(newExpr, newType, node.to)
            } else node
        }
        is ExpressionNode.FieldAccess -> {
            val newInstance = node.instance.transformTree { onUpdateReturnType(it, methodReturnTypes) }
            val instanceType = newInstance.type()
            if (instanceType !is Type.JFClass) return newInstance
            if (newInstance !== node.instance) {
                ExpressionNode.FieldAccess(newInstance, node.fieldName, node.fieldIndex, node.fieldType, node.arguments)
            } else node
        }
        else -> node
    }

    // --- Phase 1: Return value unboxing ---

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

    private fun resolveFieldAccessToPrimitive(fa: ExpressionNode.FieldAccess): OperandType<*>? {
        val fieldType = fa.fieldType
        if (fieldType !is Type.JFClass || fieldType.kind != Type.ClassKind.VALUE_CLASS) return null
        val cons = fieldType.constructor ?: return null
        if (cons.parameters.size != 1) return null
        val innerType = cons.parameters[0].type
        if (innerType is Type.JFClass && innerType.kind == Type.ClassKind.VALUE_CLASS) {
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

    // --- Phase 2: Parameter field map building ---

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

    private fun getInnermostVariable(expr: ExpressionNode.Phase2_3Expression): ExpressionNode.Variable? {
        return when (expr) {
            is ExpressionNode.Variable -> expr
            is ExpressionNode.FieldAccess -> getInnermostVariable(expr.instance)
            else -> null
        }
    }

    // --- Phase 3: Call site expansion ---

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

    private fun onExpandCallSite(
        node: ExpressionNode.Phase2_3Expression,
        expandedMethodParams: Map<String, List<Parameter>>
    ): ExpressionNode.Phase2_3Expression = when (node) {
        is ExpressionNode.MethodInvocation -> {
            val expandedParams = expandedMethodParams[node.methodName]
            if (expandedParams != null) expandMethodInvocation(node, expandedParams) else node
        }
        else -> node
    }

    private fun expandMethodInvocation(
        expr: ExpressionNode.MethodInvocation,
        expandedParams: List<Parameter>
    ): ExpressionNode.MethodInvocation {
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
                            Type.JFVariableSymbol(name = ep.varName ?: "p$expandedIdx", type = ep.type, initialized = true)
                        )
                        expandedIdx++
                    }
                }
            } else {
                val arg = if (argIdx < expr.arguments.size) expr.arguments[argIdx] else null
                if (isVCParam && paramWasExpanded && arg is ExpressionNode.Variable) {
                    val varSymbol = arg.variableSymbol
                    val expanded = expandVariable(varSymbol)
                    newArgs.addAll(expanded.map { sym -> ExpressionNode.Variable(sym) })
                    for (j in expanded.indices) {
                        if (expandedIdx < expandedParams.size) {
                            val ep = expandedParams[expandedIdx]
                            newParamSymbols.add(
                                Type.JFVariableSymbol(name = ep.varName ?: "p$expandedIdx", type = ep.type, initialized = true)
                            )
                            expandedIdx++
                        }
                    }
                } else {
                    val unwrappedArg = if (arg is ExpressionNode.ConstructorInvocation &&
                        !paramWasExpanded && isVCParam && paramExpandedType != null) {
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
                            Type.JFVariableSymbol(name = ep.varName ?: "p$expandedIdx", type = ep.type, initialized = true)
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
        return if (newArgs != expr.arguments || newParamSymbols != expr.parameters) {
            ExpressionNode.MethodInvocation(
                expr.methodName, expr.parentPath, newParamSymbols, expr.rtnLookup, expr.field, newArgs
            )
        } else expr
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
