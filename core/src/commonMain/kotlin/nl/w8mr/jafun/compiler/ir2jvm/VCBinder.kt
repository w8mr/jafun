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

            val expandedInfo = mutableMapOf<String, Type.ExpandedInfo>()

            // Step 1: R1 - expand CI assignments
            val expanded = method.instructions.flatMap { instruction ->
                when (instruction) {
                    is ExpressionNode.ValAssignment -> expandAssignmentIfNeeded(instruction, expandedInfo)
                    is ExpressionNode.VarAssignment -> expandAssignmentIfNeeded(instruction, expandedInfo)
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
                        && node.variableSymbol.name !in expandedInfo
                        && node.variableSymbol.name !in assignedVarNames
                    ) {
                        setExpandedFieldsOnSymbol(node.variableSymbol, expandedInfo)
                    }
                    node
                }
            }

            // Step 2: R3 - rewrite FieldAccess to scalar Variables, expand call site args,
            // R4 - reconstruct boxed VCs from scalar components when a Variable with
            // expandedFieldSymbols is used directly (e.g. `println a` where a: Address
            // was expanded to a_street and a_number).
            val resolved = withParamFields.map { instruction ->
                instruction.transformTree { node ->
                    resolveFieldAccessToScalar(node, expandedInfo)
                        ?: expandCallSiteArgs(node, methodSigs, expandedInfo)
                        ?: reconstructFromScalars(node, expandedInfo)
                        ?: node
                }
            }

    val symbolReplacements = mutableMapOf<String, Type.JFVariableSymbol>()
    val updatedVarTypes = resolved.map { instruction ->
                when (instruction) {
                    is ExpressionNode.ValAssignment -> {
                        val exprType = instruction.expression.type()
                        val sym = instruction.variableSymbol
                        if (exprType != sym.type) {
                            val newSym = sym.copy(type = exprType)
                            symbolReplacements[sym.name] = newSym
                            instruction.copy(variableSymbol = newSym)
                        } else instruction
                    }
                    is ExpressionNode.VarAssignment -> {
                        val exprType = instruction.expression.type()
                        val sym = instruction.variableSymbol
                        if (exprType != sym.type) {
                            val newSym = sym.copy(type = exprType)
                            symbolReplacements[sym.name] = newSym
                            instruction.copy(variableSymbol = newSym)
                        } else instruction
                    }
                    else -> instruction
                }
            }

            // Step 2c+2d+2b: Variable substitution + R2 + Convert fix in one walk.
            val fixedConverts = updatedVarTypes.map { instruction ->
                instruction.transformTree { node ->
                    when {
                        node is ExpressionNode.Variable -> {
                            val replacement = symbolReplacements[node.variableSymbol.name]
                            if (replacement != null && replacement !== node.variableSymbol) ExpressionNode.Variable(replacement)
                            else node
                        }
                        node is ExpressionNode.Convert -> {
                            val effectiveFrom = when (val expr = node.expression) {
                                is ExpressionNode.Variable -> {
                                    val replacement = symbolReplacements[expr.variableSymbol.name]
                                    replacement?.type ?: expr.type()
                                }
                                else -> expr.type()
                            }
                            if (effectiveFrom != node.from) ExpressionNode.Convert(node.expression, effectiveFrom, node.to)
                            else node
                        }
                        else -> resolveFieldAccessOnCallResult(node, methodSigs) ?: node
                    }
                }
            }

            // Step 3: R5 - unbox single-field VC returns
            val currentReturnType = method.returnType
            val unboxedReturnType = unboxSingleFieldVCType(currentReturnType)
            val withReturnUnboxing = if (unboxedReturnType != null) {
                fixedConverts.map { instruction ->
                    unboxSingleFieldReturnExpr(instruction, expandedInfo)
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
    internal fun setExpandedFieldsOnSymbol(
        symbol: Type.JFVariableSymbol,
        expandedInfo: MutableMap<String, Type.ExpandedInfo>
    ) {
        val type = symbol.type
        if (type !is Type.JFClass || type.kind != Type.ClassKind.VALUE_CLASS) return
        if (symbol.name in expandedInfo) return

        val cons = type.constructor ?: return
        val flattened = flattenType(type)
        val expandedVars = expandVariable(symbol)

        val fieldPathToSymbol = mutableMapOf<String, Type.JFVariableSymbol>()
        expandedVars.forEachIndexed { index, expandedVar ->
            fieldPathToSymbol[flattened[index].path] = expandedVar
        }

        val fields = cons.parameters.map { param ->
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
        expandedInfo[symbol.name] = Type.ExpandedInfo(fields, fieldPathToSymbol)
    }

    // ---- R3: FieldAccess resolution ----

    internal fun resolveFieldAccessToScalar(
        node: ExpressionNode.Phase2_3Expression,
        expandedInfo: Map<String, Type.ExpandedInfo>
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
        val info = expandedInfo[rootSym.name] ?: return null
        val pathMap = info.fieldSymbols ?: return null
        val pathKey = pathFromRoot.joinToString("_")
        val targetScalar = pathMap[pathKey]
        if (targetScalar != null) return ExpressionNode.Variable(targetScalar)

        // Case 2: Multi-field VC reconstruction.
        // When the field access targets a multi-field VC (e.g. `box.topLeft` where
        // Point has 2 fields), reconstruct the VC from its component scalars.
        val fields = info.fields ?: return null
        val ef = fields.find { it.name == pathFromRoot.firstOrNull() } ?: return null
        val sourceVC = ef.sourceVC ?: return null
        val scFields = ef.sourceVCFields ?: return null
        val cons = sourceVC.constructor ?: return null

        // The remaining path from the first component, e.g. for
        // `box.topLeft` -> ["topLeft"] -> remaining = empty.
        val remaining = pathFromRoot.drop(1)
        if (remaining.isNotEmpty()) return null

        val args = cons.parameters.map { param ->
            val fullPath = "${ef.name}_${param.name}"
            val scalarSym = pathMap[fullPath] ?: return null
            ExpressionNode.Variable(scalarSym) as ExpressionNode.Phase2_3Expression
        }
        return ExpressionNode.ConstructorInvocation(
            cons = cons,
            arguments = args.toMutableList()
        )
    }

    // ---- R2: Eliminate FieldAccess on single-field VC call result ----

    /**
     * When a single-field VC's return is unboxed, call sites that access
     * the single field become redundant: `makeId().value` → just `makeId()`
     * (since makeId returns Int directly). Similarly for variables whose
     * type was corrected by Step 2c: `id.value` → `id`.
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
  val singleFieldType = unboxSingleFieldVCType(vcClass) ?: return null

  // R2-corrected: instead of mutating a side-channel effectiveType on the symbol,
  // R2 eliminates the redundant field access here while variable type substitutions
  // below are still visible via methodSigs.
  // effect on usages. Here we only strip the redundant field access when there's
  // a single constructor parameter whose name matches the field access.
  val cons = vcClass.constructor ?: return null
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
        expr: ExpressionNode.Phase2_3Expression,
        expandedInfo: Map<String, Type.ExpandedInfo>
    ): ExpressionNode.Phase2_3Expression {
        val result = unboxSingleFieldReturnExprImpl(expr, expandedInfo)
        return result ?: expr
    }

    private fun unboxSingleFieldReturnExprImpl(
        expr: ExpressionNode.Phase2_3Expression,
        expandedInfo: Map<String, Type.ExpandedInfo>
    ): ExpressionNode.Phase2_3Expression? {
        when (expr) {
            is ExpressionNode.ConstructorInvocation -> {
                val innerType = unboxSingleFieldVCType(expr.type())
                if (innerType != null && expr.arguments.size == 1) {
                    val inner = unboxSingleFieldReturnExprImpl(expr.arguments[0], expandedInfo)
                    return inner ?: expr.arguments[0]
                }
            }
            is ExpressionNode.Variable -> {
                val sym = expr.variableSymbol
                val innerType = unboxSingleFieldVCType(sym.type)
                val info = expandedInfo[sym.name]
                if (innerType != null && info?.fieldSymbols != null) {
                    if (info.fieldSymbols.size == 1) {
                        val scalarSym = info.fieldSymbols.values.first()
                        return ExpressionNode.Variable(scalarSym)
                    }
                }
            }
            is ExpressionNode.ExpressionList -> {
                val exprs = expr.expressions
                if (exprs.isNotEmpty()) {
                    val unwrapped = unboxSingleFieldReturnExprImpl(exprs.last(), expandedInfo)
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
        methodSigs: Map<String, Triple<List<Parameter>, List<Parameter>, OperandType<*>>>,
        expandedInfo: Map<String, Type.ExpandedInfo>
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

        // Idempotency guard: if rtnLookup already returns the target type, skip
        if (returnTypeChanged && node.type() == updatedRtnLookup()) return null

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
                val expanded = expandArgForCallSite(arg, origParam, expandedInfo)
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
        param: Parameter,
        expandedInfo: Map<String, Type.ExpandedInfo>
    ): List<ExpressionNode.Phase2_3Expression>? {
        val type = param.type
        if (type !is Type.JFClass || type.kind != Type.ClassKind.VALUE_CLASS) return null

        val flattened = flattenType(type)

        when (arg) {
            is ExpressionNode.Variable -> {
                val sym = arg.variableSymbol
                val info = expandedInfo[sym.name]
                if (info?.fieldSymbols != null) {
                    return flattened.map { field ->
                        val scalarSym = info.fieldSymbols!![field.path]
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

    // ---- R4: Reconstruct boxed VC from scalars ----

    /**
     * When a Variable with expandedFieldSymbols is used directly (not as the instance
     * of a FieldAccess), reconstruct the boxed VC from its component scalars.
     *
     * Example: `show(a: Address)` where Address(street, number) was expanded to
     * `a_street: String, a_number: Int`. The body `println a` references the original
     * parameter, which no longer exists — so we reconstruct:
     *   println ConstructorInvocation(Address.constructor, [a_street, a_number])
     *
     * Returns the reconstructed ConstructorInvocation, or null if the node is not a
     * reconstructable Variable.
     */
    internal fun reconstructFromScalars(
        node: ExpressionNode.Phase2_3Expression,
        expandedInfo: Map<String, Type.ExpandedInfo>
    ): ExpressionNode.Phase2_3Expression? {
        if (node !is ExpressionNode.Variable) return null
        val sym = node.variableSymbol
        val info = expandedInfo[sym.name] ?: return null
        val pathMap = info.fieldSymbols ?: return null
        val vcType = sym.type as? Type.JFClass ?: return null
        if (vcType.kind != Type.ClassKind.VALUE_CLASS) return null
        val cons = vcType.constructor ?: return null
        val fields = info.fields ?: return null

        val args = cons.parameters.map { param ->
            val scalarSym = pathMap[param.name]
            if (scalarSym != null) {
                ExpressionNode.Variable(scalarSym) as ExpressionNode.Phase2_3Expression
            } else {
                // Constructor param is a nested VC (e.g. Box.topLeft: Point).
                // Find the matching ExpandedField and look up sub-fields.
                val ef = fields.find { it.name == param.name && it.sourceVC != null } ?: return null
                val sourceCons = ef.sourceVC!!.constructor ?: return null
                val subArgs = ef.sourceVCFields?.map { (localPath, _) ->
                    val fullPath = "${param.name}_$localPath"
                    val subSym = pathMap[fullPath] ?: return null
                    ExpressionNode.Variable(subSym) as ExpressionNode.Phase2_3Expression
                } ?: return null
                ExpressionNode.ConstructorInvocation(sourceCons, subArgs.toMutableList())
            }
        }

        return ExpressionNode.ConstructorInvocation(cons, args.toMutableList())
    }
}
