package nl.w8mr.jafun.compiler.ir2jvm

import nl.w8mr.jafun.Type
import nl.w8mr.jafun.compiler.effectiveJvmType
import nl.w8mr.jafun.compiler.ast2ir.expandAssignmentIfNeeded
import nl.w8mr.jafun.compiler.ExpressionNode

object ValExpansionPhase {

    fun handle(context: IRBuilder.ClassContext): IRBuilder.ClassContext {
        for (methodIndex in context.methods.indices) {
            val method = context.methods[methodIndex]
            val newInstructions = mutableListOf<ExpressionNode.Phase2_3Expression>()

            for (instruction in method.instructions) {
                if (instruction is ExpressionNode.ValAssignment &&
                    instruction.variableSymbol.expandedFieldSymbols != null
                ) {
                    val expansions = expandAssignmentIfNeeded(instruction)
                    for (expanded in expansions) {
                        if (expanded is ExpressionNode.ValAssignment) {
                            val varType = expanded.variableSymbol.type
                            expanded.variableSymbol.effectiveType = effectiveJvmType(varType)
                        }
                        newInstructions.add(expanded)
                    }
                } else if (instruction is ExpressionNode.VarAssignment &&
                    instruction.variableSymbol.expandedFieldSymbols != null
                ) {
                    val expansions = expandAssignmentIfNeeded(instruction)
                    for (expanded in expansions) {
                        if (expanded is ExpressionNode.VarAssignment) {
                            val varType = expanded.variableSymbol.type
                            expanded.variableSymbol.effectiveType = effectiveJvmType(varType)
                        }
                        newInstructions.add(expanded)
                    }
                } else {
                    newInstructions.add(instruction)
                }
            }

            if (newInstructions != method.instructions) {
                val updatedMethod = method.copy(instructions = newInstructions)
                context.methods[methodIndex] = updatedMethod
            }
        }
        // Phase 4b: resolve VC field accesses and variable shortcuts on all instructions
        for (methodIndex in context.methods.indices) {
            val method = context.methods[methodIndex]
            val resolvedInstructions = method.instructions.map { resolveVCFieldAccesses(it) }
            if (resolvedInstructions != method.instructions) {
                System.err.println("Phase 4b: Resolved field accesses in method $methodIndex")
                for ((i, instr) in resolvedInstructions.withIndex()) {
                    System.err.println("  instr$i: ${instr::class.simpleName} ${debugExpr(instr)}")
                }
                context.methods[methodIndex] = method.copy(instructions = resolvedInstructions.toMutableList())
            }
        }
        return context
    }
}

private fun debugExpr(expr: ExpressionNode.Phase2_3Expression): String = when (expr) {
    is ExpressionNode.ValAssignment -> "val ${expr.variableSymbol.name} = ${debugExpr(expr.expression)}"
    is ExpressionNode.VarAssignment -> "var ${expr.variableSymbol.name} = ${debugExpr(expr.expression)}"
    is ExpressionNode.Variable -> "${expr.variableSymbol.name}:${expr.type()}"
    is ExpressionNode.FieldAccess -> "FA(${debugExpr(expr.instance)}.${expr.fieldName})"
    is ExpressionNode.MethodInvocation -> "call ${expr.methodName}(${expr.arguments.map { debugExpr(it) }})"
    is ExpressionNode.ConstructorInvocation -> "new ${expr.cons.parent?.let { (it as? Type.JFClass)?.name } ?: "?"}(${expr.arguments.map { debugExpr(it) }})"
    is ExpressionNode.IntegerLiteral -> "${expr.value}"
    is ExpressionNode.StringLiteral -> "\"${expr.value}\""
    is ExpressionNode.Convert -> "Convert(${debugExpr(expr.expression)})"
    is ExpressionNode.ExpressionList -> "EL(${expr.expressions.map { debugExpr(it) }})"
    is ExpressionNode.Function -> "fun ${expr.symbol.name}"
    is ExpressionNode.WhenPhase3 -> "when(...)"
    is ExpressionNode.WhilePhase3 -> "while(...)"
    else -> expr.toString()
}

// ---- Phase 4: VC field access resolution on IR instructions ----

/**
 * Recursively walks an expression tree and resolves VC FieldAccess/Variable
 * nodes. Handles the identity shortcut, expanded-field resolution, and
 * single-field Variable shortcut — all previously done inline in Phase 3.
 */
fun resolveVCFieldAccesses(expr: ExpressionNode.Phase2_3Expression): ExpressionNode.Phase2_3Expression {
    val resolved = when (expr) {
        is ExpressionNode.FieldAccess -> {
            // Try expanded field resolution FIRST (function param field accesses need this
            // to resolve to the correct expanded variable name instead of being identity-shortcutted
            // to a Variable that the JVM backend can't load with the right type).
            val viaExpanded = (expr.instance as? ExpressionNode.Variable)?.let {
                val r = resolveExpandedFieldAccessInstr(expr, it)
                System.err.println("  Phase4b expanded: ${expr.fieldName} on ${it.variableSymbol.name} -> ${r}")
                r
            }
            if (viaExpanded != null) return resolveVCFieldAccesses(viaExpanded)
            
            val shortcutResult = resolveIdentityShortcut(expr)
            if (shortcutResult != null) {
                System.err.println("  Phase4b shortcut: ${expr.fieldName} -> ${shortcutResult}")
                return resolveVCFieldAccesses(shortcutResult)
            }
            
            // Fall through: just resolve the instance
            val resolvedInstance = resolveVCFieldAccesses(expr.instance)
            if (resolvedInstance !== expr.instance) expr.copy(instance = resolvedInstance) else expr
        }
        is ExpressionNode.Variable -> {
            resolveSingleFieldVariable(expr) ?: expr
        }
        is ExpressionNode.MethodInvocation -> {
            val resolvedArgs = expr.arguments.map { resolveVCFieldAccesses(it) }
            if (resolvedArgs != expr.arguments) {
                ExpressionNode.MethodInvocation(
                    expr.methodName, expr.parentPath, expr.parameters,
                    expr.rtnLookup, expr.field, resolvedArgs,
                )
            } else expr
        }
        is ExpressionNode.ConstructorInvocation -> {
            val resolvedArgs = expr.arguments.map { resolveVCFieldAccesses(it) }
            if (resolvedArgs != expr.arguments) expr.copy(arguments = resolvedArgs) else expr
        }
        is ExpressionNode.ValAssignment -> {
            val resolvedExpr = resolveVCFieldAccesses(expr.expression)
            if (resolvedExpr !== expr.expression) expr.copy(expression = resolvedExpr) else expr
        }
        is ExpressionNode.VarAssignment -> {
            val resolvedExpr = resolveVCFieldAccesses(expr.expression)
            if (resolvedExpr !== expr.expression) expr.copy(expression = resolvedExpr) else expr
        }
        is ExpressionNode.ExpressionList -> {
            val resolvedExprs = expr.expressions.map { resolveVCFieldAccesses(it) }
            if (resolvedExprs != expr.expressions) ExpressionNode.ExpressionList(resolvedExprs) else expr
        }
        is ExpressionNode.Convert -> {
            val resolvedInner = resolveVCFieldAccesses(expr.expression)
            if (resolvedInner !== expr.expression) expr.copy(expression = resolvedInner) else expr
        }
        is ExpressionNode.WhenPhase3 -> {
            val resolvedMatches = expr.matches.map { (c, e) -> resolveVCFieldAccesses(c) to resolveVCFieldAccesses(e) }
            if (resolvedMatches != expr.matches) ExpressionNode.WhenPhase3(resolvedMatches) else expr
        }
        is ExpressionNode.WhilePhase3 -> {
            val resolvedCond = resolveVCFieldAccesses(expr.condition)
            val resolvedBody = resolveVCFieldAccesses(expr.expressions)
            if (resolvedCond !== expr.condition || resolvedBody !== expr.expressions)
                ExpressionNode.WhilePhase3(resolvedCond, resolvedBody)
            else expr
        }
        is ExpressionNode.Function -> {
            val resolvedBlock = expr.block.map { resolveVCFieldAccesses(it) }
            if (resolvedBlock != expr.block) ExpressionNode.Function(expr.symbol, resolvedBlock) else expr
        }
        else -> expr
    }
    return resolved
}

private fun resolveIdentityShortcut(node: ExpressionNode.FieldAccess): ExpressionNode.Phase2_3Expression? {
    val instanceType = node.instance.type()
    if (instanceType is Type.JFClass && instanceType.isInlineValueClass) {
        val singleFieldName = instanceType.constructor?.parameters?.singleOrNull()?.name
        if (singleFieldName != null && node.fieldName != singleFieldName) {
            error("Field '${node.fieldName}' not found in value class ${instanceType.name}")
        }
        return node.instance
    }
    return null
}

private fun resolveSingleFieldVariable(node: ExpressionNode.Variable): ExpressionNode.Phase2_3Expression? {
    val expandedFields = node.variableSymbol.expandedFields
    if (expandedFields != null && expandedFields.size == 1) {
        val singleField = expandedFields[0]
        if (singleField.actualSymbol != null) {
            return ExpressionNode.Variable(singleField.actualSymbol!!)
        }
    }
    return null
}

private fun resolveExpandedFieldAccessInstr(
    node: ExpressionNode.FieldAccess,
    instance: ExpressionNode.Variable,
): ExpressionNode.Phase2_3Expression? {
    val varSymbol = instance.variableSymbol
    val expandedFields = varSymbol.expandedFields
    if (expandedFields != null) {
        val field = expandedFields.find { it.name == node.fieldName }
            ?: error("Field '${node.fieldName}' not found in value class ${varSymbol.type}")
        System.err.println("  DEBUG expandedFields for ${varSymbol.name}.${node.fieldName}: expandedFields=${expandedFields}, field=${field}, actualSymbol=${field.actualSymbol}, sourceVCFields=${field.sourceVCFields}, sourceVC=${field.sourceVC}")
        if (field.actualSymbol != null) {
            return ExpressionNode.Variable(field.actualSymbol)
        }
        if (field.sourceVCFields != null) {
            val syntheticVar = Type.JFVariableSymbol(
                name = "${varSymbol.name}_${field.name}",
                type = field.type,
                symbolMap = varSymbol.symbolMap,
                initialized = true,
            )
            syntheticVar.effectiveType = effectiveJvmType(field.type)
            val parentSymbols = varSymbol.expandedFieldSymbols
            val hasAnyActual = field.sourceVCFields.any { (subFieldPath, _) ->
                val fullPath = "${field.name}_$subFieldPath"
                parentSymbols?.get(fullPath) != null
            }
            if (hasAnyActual) {
                syntheticVar.expandedFields = field.sourceVCFields.map { (subFieldPath, subFieldType) ->
                    val fullPath = "${field.name}_$subFieldPath"
                    val actualSymbol = parentSymbols?.get(fullPath)
                    Type.ExpandedField(
                        name = subFieldPath, type = subFieldType,
                        sourceVC = null, sourceVCFields = null,
                        actualSymbol = actualSymbol,
                    )
                }
            }
            return ExpressionNode.Variable(syntheticVar)
        } else {
            val expandedVar = Type.JFVariableSymbol(
                name = "${varSymbol.name}_${field.name}",
                type = field.type,
                symbolMap = varSymbol.symbolMap,
                initialized = true,
            )
            expandedVar.effectiveType = effectiveJvmType(field.type)
            return ExpressionNode.Variable(expandedVar)
        }
    }
    return null
}
