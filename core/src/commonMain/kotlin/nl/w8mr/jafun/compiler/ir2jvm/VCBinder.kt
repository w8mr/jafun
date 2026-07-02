package nl.w8mr.jafun.compiler.ir2jvm

import nl.w8mr.jafun.OperandType
import nl.w8mr.jafun.Type
import nl.w8mr.jafun.compiler.ExpressionNode
import nl.w8mr.jafun.compiler.IdentifierCache
import nl.w8mr.jafun.compiler.Parameter
import nl.w8mr.jafun.compiler.expandAssignmentIfNeeded
import nl.w8mr.jafun.compiler.expandParameterRecursively
import nl.w8mr.jafun.compiler.expandVariable
import nl.w8mr.jafun.compiler.flattenType
import nl.w8mr.jafun.compiler.FlattenedField
import nl.w8mr.jafun.compiler.isMultiFieldVC
import nl.w8mr.jafun.compiler.transformTree
import nl.w8mr.jafun.compiler.unboxSingleFieldVCType

object VCBinder {

    fun handle(context: IRBuilder.ClassContext): IRBuilder.ClassContext {
        val methodSigs = context.methods.associate { method ->
            val expandedParams = method.parameters.flatMap { param ->
                expandParameterForVC(param)
            }
            method.name to Triple(method.parameters, expandedParams, method.returnType)
        }

        val updatedMethods = context.methods.map { method ->
            val (originalParams, expandedParams, _) = methodSigs[method.name]!!

            // Step 1: R1 - expand CI assignments
            val expanded = method.instructions.flatMap { instruction ->
                when (instruction) {
                    is ExpressionNode.ValAssignment -> expandAssignmentIfNeeded(instruction)
                    is ExpressionNode.VarAssignment -> expandAssignmentIfNeeded(instruction)
                    else -> listOf(instruction)
                }
            }

            // Step 1b: Propagate expandedFields to parameter Variable symbols only.
            // A Variable is a parameter reference iff its name is not the target of any
            // ValAssignment/VarAssignment in this method body.
            val assignedVarNames = collectAssignedVarNames(expanded)
            val withParamFields = expanded.map { instruction ->
                instruction.transformTree { node ->
                    if (node is ExpressionNode.Variable
                        && node.variableSymbol.expandedFields == null
                        && node.variableSymbol.name !in assignedVarNames
                    ) {
                        setExpandedFieldsOnSymbol(node.variableSymbol)
                    }
                    node
                }
            }

            // Step 2: R3 - rewrite FieldAccess to scalar Variables, expand call site args
            val resolved = withParamFields.map { instruction ->
                instruction.transformTree { node ->
                    resolveFieldAccessToScalar(node)
                        ?: expandCallSiteArgs(node, methodSigs)
                        ?: node
                }
            }

            // Step 2c: Update variable symbol effectiveType when assignment RHS
            // expression type changed (e.g., after R5 return unboxing, a ValAssignment's
            // MI expression returns Int but the symbol still has type Id).
            // This sets effectiveType on the shared symbol reference, so Variable nodes
            // (which delegate to effectiveType ?: type) report the correct type, and
            // Step 2b can fix Convert.from values.
            val updatedVarTypes = resolved.map { instruction ->
                when (instruction) {
                    is ExpressionNode.ValAssignment -> {
                        val exprType = instruction.expression.type()
                        val sym = instruction.variableSymbol
                        if (exprType != sym.type && exprType != sym.effectiveType) {
                            sym.effectiveType = exprType
                        }
                        instruction
                    }
                    is ExpressionNode.VarAssignment -> {
                        val exprType = instruction.expression.type()
                        val sym = instruction.variableSymbol
                        if (exprType != sym.type && exprType != sym.effectiveType) {
                            sym.effectiveType = exprType
                        }
                        instruction
                    }
                    else -> instruction
                }
            }

            // Step 2d (R2): Eliminate FieldAccess on single-field VC call results.
            // When a MethodInvocation's return type was unboxed from Id to Int,
            // `makeId().value` becomes FieldAccess(MI(int), "value") — the .value
            // field access is now redundant since the return IS the value.
            // Same for `id.value` when id's effectiveType was updated to Int.
            val r2Resolved = updatedVarTypes.map { instruction ->
                instruction.transformTree { node ->
                    resolveFieldAccessOnCallResult(node, methodSigs) ?: node
                }
            }

            // Step 2b: Update Convert.from when inner expression type changed
            // (e.g., after R5 unboxes a return type, Convert nodes wrapping call
            //  sites need their `from` updated so the JVM backend emits correct boxing)
            val fixedConverts = r2Resolved.map { instruction ->
                instruction.transformTree { node ->
                    if (node is ExpressionNode.Convert) {
                        val actualType = node.expression.type()
                        if (actualType != node.from) {
                            ExpressionNode.Convert(node.expression, actualType, node.to)
                        } else node
                    } else node
                }
            }

            // Step 3: R5 - unbox single-field VC returns
            val currentReturnType = method.returnType
            val unboxedReturnType = unboxSingleFieldVCType(currentReturnType)
            val withReturnUnboxing = if (unboxedReturnType != null) {
                fixedConverts.map { instruction ->
                    unboxSingleFieldReturnExpr(instruction)
                }
            } else {
                fixedConverts
            }

            method.copy(
                parameters = expandedParams,
                returnType = unboxedReturnType ?: currentReturnType,
                instructions = withReturnUnboxing.toMutableList()
            )
        }
        return context.copy(methods = updatedMethods.toMutableList())
    }

    // ---- Helpers ----

    /**
     * Collect the set of variable names that are targets of ValAssignment or VarAssignment
     * in the given instruction list. Used to distinguish parameter references from local
     * variables.
     */
    internal fun collectAssignedVarNames(
        instructions: List<ExpressionNode.Phase2_3Expression>
    ): Set<String> {
        val names = mutableSetOf<String>()
        fun walk(node: ExpressionNode.Phase2_3Expression) {
            when (node) {
                is ExpressionNode.ValAssignment -> names.add(node.variableSymbol.name)
                is ExpressionNode.VarAssignment -> names.add(node.variableSymbol.name)
                is ExpressionNode.ExpressionList -> node.expressions.forEach { walk(it) }
                else -> {}
            }
        }
        instructions.forEach { walk(it) }
        return names
    }

    // ---- Parameter expansion ----

    /**
     * Expand a Parameter: any VC type (single or multi-field) -> recursively unwrapped
     * list of scalar Parameters. Non-VC types are returned unchanged.
     */
    private fun expandParameterForVC(param: Parameter): List<Parameter> {
        val type = param.type
        if (type is Type.JFClass && type.kind == Type.ClassKind.VALUE_CLASS) {
            return expandParameterRecursively(type, param.varName)
        }
        return listOf(param)
    }

    // ---- expandedFields propagation ----

    /**
     * Set expandedFields and expandedFieldSymbols on a JFVariableSymbol whose type
     * is a VC. This enables resolveFieldAccessToScalar to resolve field chains
     * (e.g., `param.field.subfield`) on parameter references.
     */
    internal fun setExpandedFieldsOnSymbol(symbol: Type.JFVariableSymbol) {
        val type = symbol.type
        if (type !is Type.JFClass || type.kind != Type.ClassKind.VALUE_CLASS) return
        if (symbol.expandedFields != null) return

        val cons = type.constructor ?: return
        val flattened = flattenType(type)
        val expandedVars = expandVariable(symbol)

        val fieldPathToSymbol = mutableMapOf<String, Type.JFVariableSymbol>()
        expandedVars.forEachIndexed { index, expandedVar ->
            fieldPathToSymbol[flattened[index].path] = expandedVar
        }

        symbol.expandedFields = cons.parameters.map { param ->
            val paramFlattened = flattened.filter { field ->
                field.path.startsWith(param.name + "_") || field.path == param.name
            }
            val sourceVCFields = if (isMultiFieldVC(param.type) && paramFlattened.size > 1) {
                paramFlattened.map { field ->
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
                actualSymbol = actualSymbol
            )
        }
        symbol.expandedFieldSymbols = fieldPathToSymbol
    }

    // ---- R3: FieldAccess resolution ----

    internal fun resolveFieldAccessToScalar(
        node: ExpressionNode.Phase2_3Expression
    ): ExpressionNode.Phase2_3Expression? {
        if (node !is ExpressionNode.FieldAccess) return null

        val pathFromRoot = mutableListOf<String>()
        var current: ExpressionNode.Phase2_3Expression = node
        while (current is ExpressionNode.FieldAccess) {
            pathFromRoot.add(0, current.fieldName)
            current = current.instance
        }
        if (current !is ExpressionNode.Variable) return null

        val rootSym = current.variableSymbol
        val pathMap = rootSym.expandedFieldSymbols ?: return null
        val pathKey = pathFromRoot.joinToString("_")
        val targetScalar = pathMap[pathKey] ?: return null

        return ExpressionNode.Variable(targetScalar)
    }

    // ---- R2: Eliminate FieldAccess on single-field VC call result ----

    /**
     * When a single-field VC's return is unboxed (R5), call sites that access
     * the single field become redundant: `makeId().value` → just `makeId()`
     * (since makeId returns Int directly). Similarly for variables whose
     * effectiveType was updated by Step 2c: `id.value` → `id`.
     *
     * This function checks if a FieldAccess targets the single field of a
     * single-field VC whose instance expression has been (or will be) unboxed.
     * If so, returns the instance expression directly (eliminating the access).
     */
    internal fun resolveFieldAccessOnCallResult(
        node: ExpressionNode.Phase2_3Expression,
        methodSigs: Map<String, Triple<List<Parameter>, List<Parameter>, OperandType<*>>>
    ): ExpressionNode.Phase2_3Expression? {
        if (node !is ExpressionNode.FieldAccess) return null

        val originalVCType = when (val instance = node.instance) {
            is ExpressionNode.MethodInvocation -> {
                methodSigs[instance.methodName]?.third as? Type.JFClass
            }
            is ExpressionNode.Variable -> {
                instance.variableSymbol.type as? Type.JFClass
            }
            else -> null
        }
        val vcClass = originalVCType ?: return null
        if (vcClass.kind != Type.ClassKind.VALUE_CLASS) return null
        val cons = vcClass.constructor ?: return null
        if (cons.parameters.size != 1) return null
        if (cons.parameters[0].name != node.fieldName) return null

        return node.instance
    }

    // ---- R5: Single-field VC return unboxing ----

    /**
     * Recursively unwrap a single-field VC expression to its inner scalar.
     * Handles:
     * - ConstructorInvocation(VC, [scalar]): unwrap to [scalar]
     * - Variable(sym) where sym has single-entry expandedFieldSymbols: unwrap to that scalar
     * - ExpressionList: unwrap the last expression
     */
    internal fun unboxSingleFieldReturnExpr(
        expr: ExpressionNode.Phase2_3Expression
    ): ExpressionNode.Phase2_3Expression {
        val result = unboxSingleFieldReturnExprImpl(expr)
        return result ?: expr
    }

    private fun unboxSingleFieldReturnExprImpl(
        expr: ExpressionNode.Phase2_3Expression
    ): ExpressionNode.Phase2_3Expression? {
        when (expr) {
            is ExpressionNode.ConstructorInvocation -> {
                val innerType = unboxSingleFieldVCType(expr.type())
                if (innerType != null && expr.arguments.size == 1) {
                    val inner = unboxSingleFieldReturnExprImpl(expr.arguments[0])
                    return inner ?: expr.arguments[0]
                }
            }
            is ExpressionNode.Variable -> {
                val sym = expr.variableSymbol
                val innerType = unboxSingleFieldVCType(sym.type)
                if (innerType != null && sym.expandedFieldSymbols != null) {
                    if (sym.expandedFieldSymbols!!.size == 1) {
                        val scalarSym = sym.expandedFieldSymbols!!.values.first()
                        return ExpressionNode.Variable(scalarSym)
                    }
                }
            }
            is ExpressionNode.ExpressionList -> {
                val exprs = expr.expressions
                if (exprs.isNotEmpty()) {
                    val unwrapped = unboxSingleFieldReturnExprImpl(exprs.last())
                    if (unwrapped != null) {
                        return ExpressionNode.ExpressionList(exprs.dropLast(1) + unwrapped)
                    }
                }
            }
        }
        return null
    }

    // ---- Issue #2: Call site arg expansion ----

    internal fun expandCallSiteArgs(
        node: ExpressionNode.Phase2_3Expression,
        methodSigs: Map<String, Triple<List<Parameter>, List<Parameter>, OperandType<*>>>
    ): ExpressionNode.Phase2_3Expression? {
        if (node !is ExpressionNode.MethodInvocation) return null
        val (originalParams, expandedParams, originalReturnType) = methodSigs[node.methodName] ?: return null

        val unboxedReturn = unboxSingleFieldVCType(originalReturnType)
        val returnTypeChanged = unboxedReturn != null && unboxedReturn != originalReturnType

        val paramsUnchanged = originalParams.size == expandedParams.size &&
            originalParams.zip(expandedParams).all { (orig, exp) -> orig.type == exp.type }

        if (paramsUnchanged && !returnTypeChanged) return null

        val updatedRtnLookup = if (returnTypeChanged) {
            { unboxedReturn!! }
        } else {
            node.rtnLookup
        }

        if (paramsUnchanged) {
            return ExpressionNode.MethodInvocation(
                methodName = node.methodName,
                parentPath = node.parentPath,
                parameters = node.parameters,
                rtnLookup = updatedRtnLookup,
                field = node.field,
                arguments = node.arguments
            )
        }

        val newArgs = mutableListOf<ExpressionNode.Phase2_3Expression>()
        var argIdx = 0
        for (origParam in originalParams) {
            if (argIdx >= node.arguments.size) break
            val arg = node.arguments[argIdx]
            val expandedForParam = expandParameterForVC(origParam)
            if (expandedForParam.size == 1 && expandedForParam[0].type == origParam.type) {
                newArgs.add(arg)
            } else {
                val expanded = expandArgForCallSite(arg, origParam)
                if (expanded == null) return null
                newArgs.addAll(expanded)
            }
            argIdx++
        }

        val newParams = expandedParams.map { param ->
            Type.JFVariableSymbol(
                name = param.varName ?: "",
                type = param.type,
                symbolMap = IdentifierCache
            )
        }

        return ExpressionNode.MethodInvocation(
            methodName = node.methodName,
            parentPath = node.parentPath,
            parameters = newParams,
            rtnLookup = updatedRtnLookup,
            field = node.field,
            arguments = newArgs
        )
    }

    internal fun expandArgForCallSite(
        arg: ExpressionNode.Phase2_3Expression,
        param: Parameter
    ): List<ExpressionNode.Phase2_3Expression>? {
        val type = param.type
        if (type !is Type.JFClass || type.kind != Type.ClassKind.VALUE_CLASS) return null

        val flattened = flattenType(type)

        when (arg) {
            is ExpressionNode.Variable -> {
                val sym = arg.variableSymbol
                if (sym.expandedFieldSymbols != null) {
                    return flattened.map { field ->
                        val scalarSym = sym.expandedFieldSymbols!![field.path]
                            ?: return null
                        ExpressionNode.Variable(scalarSym)
                    }
                }
                return null
            }
            is ExpressionNode.ConstructorInvocation -> {
                return flattenCIArgs(arg)
            }
        }
        return null
    }

    internal fun flattenCIArgs(ci: ExpressionNode.ConstructorInvocation): List<ExpressionNode.Phase2_3Expression>? {
        val result = mutableListOf<ExpressionNode.Phase2_3Expression>()
        for ((param, arg) in ci.cons.parameters.zip(ci.arguments)) {
            if (param.type is Type.JFClass && param.type.kind == Type.ClassKind.VALUE_CLASS) {
                if (arg is ExpressionNode.ConstructorInvocation) {
                    val inner = flattenCIArgs(arg) ?: return null
                    result.addAll(inner)
                } else {
                    return null
                }
            } else {
                result.add(arg)
            }
        }
        return result
    }
}
