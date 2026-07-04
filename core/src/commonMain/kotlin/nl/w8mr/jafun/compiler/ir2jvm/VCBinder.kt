package nl.w8mr.jafun.compiler.ir2jvm

import nl.w8mr.jafun.OperandType
import nl.w8mr.jafun.Type
import nl.w8mr.jafun.compiler.ExpressionNode
import nl.w8mr.jafun.compiler.IdentifierCache
import nl.w8mr.jafun.compiler.Parameter
import nl.w8mr.jafun.compiler.expandAssignmentIfNeeded
import nl.w8mr.jafun.compiler.expandParameterRecursively
import nl.w8mr.jafun.compiler.computeExpandedInfo
import nl.w8mr.jafun.compiler.flattenType
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

            // Collect assigned variable names to distinguish local variables from
            // parameter references. R1 populates expandedInfo for VC CI targets
            // as a side effect, so those are covered regardless.
            val assignedVarNames = mutableSetOf<String>()
            fun walk(node: ExpressionNode.Phase2_3Expression) {
                when (node) {
                    is ExpressionNode.ValAssignment -> assignedVarNames.add(node.variableSymbol.name)
                    is ExpressionNode.VarAssignment -> assignedVarNames.add(node.variableSymbol.name)
                    is ExpressionNode.ExpressionList -> node.expressions.forEach { walk(it) }
                    else -> {}
                }
            }
            method.instructions.forEach { walk(it) }

            // Read-only scan: compute symbol replacements from methodSigs directly.
            val symbolReplacements = computeSymbolReplacements(method.instructions, methodSigs)
            val unboxedReturnType = unboxSingleFieldVCType(method.returnType)

            // R1 + unified walk + R5: expand CI assignments, then eagerly populate
            // expandedInfo + R3 + expandCallSiteArgs + R4 + Variable substitution
            // + R2 + Convert fix, then unbox single-field VC return.
            val instructions = method.instructions.flatMap { instruction ->
                val expanded = when (instruction) {
                    is ExpressionNode.ValAssignment -> expandAssignmentIfNeeded(instruction, expandedInfo)
                    is ExpressionNode.VarAssignment -> expandAssignmentIfNeeded(instruction, expandedInfo)
                    else -> listOf(instruction)
                }
                expanded.map { instr ->
                    var result = instr.transformTree { node ->
                        eagerlyExpandVariableIfNeeded(node, expandedInfo, assignedVarNames)

                        when {
                            node is ExpressionNode.Variable -> {
                                val replacement = symbolReplacements[node.variableSymbol.name]
                                if (replacement != null && replacement !== node.variableSymbol) {
                                    ExpressionNode.Variable(replacement)
                                } else {
                                    reconstructFromScalars(node, expandedInfo) ?: node
                                }
                            }
                            node is ExpressionNode.Convert -> {
                                val effectiveFrom = expectedExpressionType(node.expression, methodSigs, symbolReplacements)
                                if (effectiveFrom != node.from) ExpressionNode.Convert(node.expression, effectiveFrom, node.to)
                                else node
                            }
                            else -> {
                                resolveFieldAccessToScalar(node, expandedInfo)
                                    ?: expandCallSiteArgs(node, methodSigs, expandedInfo)
                                    ?: resolveFieldAccessOnCallResult(node, methodSigs)
                                    ?: node
                            }
                        }
                    }
                    if (unboxedReturnType != null) unboxSingleFieldReturnExpr(result, expandedInfo) else result
                }
            }

            method.copy(
                parameters = expandedParams,
                returnType = unboxedReturnType ?: method.returnType,
                instructions = instructions.toMutableList()
            )
        }
        return context.copy(methods = updatedMethods.toMutableList())
    }

    // ---- Parameter expansion ----

    /**
     * Expand a Parameter: any VC type (single or multi-field) -> recursively unwrapped
     * list of scalar Parameters. Non-VC types are returned unchanged.
     */
    private fun expandParameterForVC(param: Parameter): List<Parameter> {
        return expandParameterRecursively(param.type, param.varName)
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
        if (symbol.name in expandedInfo) return
        val info = computeExpandedInfo(symbol) ?: return
        expandedInfo[symbol.name] = info
    }

    // ---- R3: FieldAccess resolution ----

    /**
     * Walk up a chain of FieldAccess nodes to collect the full path from root.
     * Returns the path components (in order from root to leaf) and the root
     * Variable. Returns null if the chain root is not a Variable.
     */
    private fun walkUpFieldAccessChain(node: ExpressionNode.FieldAccess): Pair<List<String>, ExpressionNode.Variable>? {
        val pathFromRoot = mutableListOf<String>()
        var current: ExpressionNode.Phase2_3Expression = node
        while (current is ExpressionNode.FieldAccess) {
            pathFromRoot.add(0, current.fieldName)
            current = current.instance
        }
        if (current !is ExpressionNode.Variable) return null
        return pathFromRoot to current
    }

    internal fun resolveFieldAccessToScalar(
        node: ExpressionNode.Phase2_3Expression,
        expandedInfo: Map<String, Type.ExpandedInfo>
    ): ExpressionNode.Phase2_3Expression? {
        if (node !is ExpressionNode.FieldAccess) return null

        val (pathFromRoot, root) = walkUpFieldAccessChain(node) ?: return null

        val rootSym = root.variableSymbol
        val info = expandedInfo[rootSym.name] ?: return null
        val pathMap = info.fieldSymbols ?: return null
        val pathKey = pathFromRoot.joinToString("_")
        val targetScalar = pathMap[pathKey]
        if (targetScalar != null) return ExpressionNode.Variable(targetScalar)

        // Case 2: Multi-field VC reconstruction.
        return reconstructVCFromScalars(info, pathFromRoot)
    }

    /**
     * When the field access targets a multi-field VC (e.g. `box.topLeft` where
     * Point has 2 fields), reconstruct the VC from its component scalars.
     */
    private fun reconstructVCFromScalars(
        info: Type.ExpandedInfo,
        pathFromRoot: List<String>
    ): ExpressionNode.ConstructorInvocation? {
        val fields = info.fields ?: return null
        val ef = fields.find { it.name == pathFromRoot.firstOrNull() } ?: return null
        val sourceVC = ef.sourceVC ?: return null
        val scFields = ef.sourceVCFields ?: return null
        val cons = sourceVC.constructor ?: return null

        val remaining = pathFromRoot.drop(1)
        if (remaining.isNotEmpty()) return null

        val args = cons.parameters.map { param ->
            val fullPath = "${ef.name}_${param.name}"
            val scalarSym = info.fieldSymbols?.get(fullPath) ?: return null
            ExpressionNode.Variable(scalarSym) as ExpressionNode.Phase2_3Expression
        }
        return ExpressionNode.ConstructorInvocation(cons, args.toMutableList())
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

    // ---- Symbol replacement computation (read-only scan) ----

    internal fun computeSymbolReplacements(
        instructions: List<ExpressionNode.Phase2_3Expression>,
        methodSigs: Map<String, Triple<List<Parameter>, List<Parameter>, OperandType<*>>>
    ): Map<String, Type.JFVariableSymbol> {
        val replacements = mutableMapOf<String, Type.JFVariableSymbol>()
        fun walk(node: ExpressionNode.Phase2_3Expression) {
            when (node) {
                is ExpressionNode.ValAssignment -> checkAssignment(node, replacements, methodSigs)
                is ExpressionNode.VarAssignment -> checkAssignment(node, replacements, methodSigs)
                is ExpressionNode.ExpressionList -> node.expressions.forEach { walk(it) }
                else -> {}
            }
        }
        instructions.forEach { walk(it) }
        return replacements
    }

    private fun checkAssignment(
        assignment: ExpressionNode.Assignment,
        replacements: MutableMap<String, Type.JFVariableSymbol>,
        methodSigs: Map<String, Triple<List<Parameter>, List<Parameter>, OperandType<*>>>
    ) {
        val expectedType = expectedExpressionType(assignment.expression, methodSigs)
        val sym = assignment.variableSymbol
        if (expectedType != sym.type) {
            replacements[sym.name] = sym.copy(type = expectedType)
        }
    }

    internal fun expectedExpressionType(
        expr: ExpressionNode.Phase2_3Expression,
        methodSigs: Map<String, Triple<List<Parameter>, List<Parameter>, OperandType<*>>>,
        symbolReplacements: Map<String, Type.JFVariableSymbol> = emptyMap()
    ): OperandType<*> {
        return when (expr) {
            is ExpressionNode.Variable -> {
                val replacement = symbolReplacements[expr.variableSymbol.name]
                replacement?.type ?: expr.type()
            }
            is ExpressionNode.MethodInvocation -> {
                val rtnType = methodSigs[expr.methodName]?.third
                if (rtnType == null) expr.type()
                else unboxSingleFieldVCType(rtnType) ?: expr.type()
            }
            is ExpressionNode.FieldAccess -> {
                if (expr.instance is ExpressionNode.MethodInvocation) {
                    val mi = expr.instance
                    val rtnType = methodSigs[mi.methodName]?.third
                    // Only recurse into the MI when it returns a single-field VC
                    // that gets unboxed; for multi-field VCs the field's own type applies.
                    if (rtnType != null && unboxSingleFieldVCType(rtnType) != null) {
                        expectedExpressionType(mi, methodSigs, symbolReplacements)
                    } else {
                        expr.type()
                    }
                } else {
                    expr.type()
                }
            }
            else -> expr.type()
        }
    }

    // ---- Eager expandedInfo population ----

    internal fun eagerlyExpandVariableIfNeeded(
        node: ExpressionNode.Phase2_3Expression,
        expandedInfo: MutableMap<String, Type.ExpandedInfo>,
        assignedVarNames: Set<String>
    ) {
        when (node) {
            is ExpressionNode.Variable -> {
                maybeExpandField(node.variableSymbol, expandedInfo, assignedVarNames)
            }
            is ExpressionNode.FieldAccess -> {
                val (_, root) = walkUpFieldAccessChain(node) ?: return
                maybeExpandField(root.variableSymbol, expandedInfo, assignedVarNames)
            }
            is ExpressionNode.MethodInvocation -> {
                for (arg in node.arguments) {
                    eagerlyExpandVariableIfNeeded(arg, expandedInfo, assignedVarNames)
                }
            }
            is ExpressionNode.ConstructorInvocation -> {
                for (arg in node.arguments) {
                    eagerlyExpandVariableIfNeeded(arg, expandedInfo, assignedVarNames)
                }
            }
            else -> {}
        }
    }

    private fun maybeExpandField(
        sym: Type.JFVariableSymbol,
        expandedInfo: MutableMap<String, Type.ExpandedInfo>,
        assignedVarNames: Set<String>
    ) {
        if (sym.name !in expandedInfo && sym.name !in assignedVarNames) {
            setExpandedFieldsOnSymbol(sym, expandedInfo)
        }
    }
}
