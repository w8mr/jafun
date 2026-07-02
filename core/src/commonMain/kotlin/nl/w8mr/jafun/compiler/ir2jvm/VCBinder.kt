package nl.w8mr.jafun.compiler.ir2jvm

import nl.w8mr.jafun.OperandType
import nl.w8mr.jafun.Type
import nl.w8mr.jafun.compiler.ExpressionNode
import nl.w8mr.jafun.compiler.IdentifierCache
import nl.w8mr.jafun.compiler.Parameter
import nl.w8mr.jafun.compiler.expandAssignmentIfNeeded
import nl.w8mr.jafun.compiler.expandParameterRecursively
import nl.w8mr.jafun.compiler.flattenType
import nl.w8mr.jafun.compiler.FlattenedField
import nl.w8mr.jafun.compiler.isMultiFieldVC
import nl.w8mr.jafun.compiler.transformTree

object VCBinder {

    /**
     * Phase 2 (R1): eager unbox at assignment with Constructor Invocation on RHS.
     * Phase 2 (R3 partial): field access on unboxed VC variables resolves to scalar Variables.
     * Phase 2 (Param expansion): multi-field VC parameters unbox to scalars.
     * Phase 2 (Call site expansion): when calling a function with expanded params,
     *         pass scalar args instead of boxed VC.
     *
     * R2/R4/R5 are not yet implemented.
     */
    fun handle(context: IRBuilder.ClassContext): IRBuilder.ClassContext {
        // First, compute expanded params and unboxed signature for each method
        val methodSigs: Map<String, Pair<List<Parameter>, OperandType<*>>> = context.methods
            .associate { method ->
                val expandedParams = method.parameters.flatMap { param ->
                    expandParameterForVC(param)
                }
                method.name to (expandedParams to method.returnType)
            }

        val updatedMethods = context.methods.map { method ->
            val (expandedParams, _) = methodSigs[method.name]!!

            // Step 1: R1 - expand CI assignments
            val expanded = method.instructions.flatMap { instruction ->
                when (instruction) {
                    is ExpressionNode.ValAssignment -> expandAssignmentIfNeeded(instruction)
                    is ExpressionNode.VarAssignment -> expandAssignmentIfNeeded(instruction)
                    else -> listOf(instruction)
                }
            }
            // Step 2: R3 - rewrite FieldAccess to scalar Variables where applicable
            val resolved = expanded.map { instruction ->
                instruction.transformTree { node ->
                    resolveFieldAccessToScalar(node)
                        ?: expandCallSiteArgs(node, methodSigs)
                        ?: node
                }
            }
            // Step 3: Expand call site args for invoked methods with expanded params
            method.copy(
                parameters = expandedParams,
                instructions = resolved.toMutableList()
            )
        }
        return context.copy(methods = updatedMethods.toMutableList())
    }

    /**
     * Expand a Parameter: multi-field VC -> list of scalar Parameters.
     * Single-field VC -> single Parameter with inner type.
     * Other -> unchanged.
     */
    private fun expandParameterForVC(param: Parameter): List<Parameter> {
        val type = param.type
        if (type is Type.JFClass && type.kind == Type.ClassKind.VALUE_CLASS) {
            if (isMultiFieldVC(type)) {
                return expandParameterRecursively(type, param.varName)
            } else {
                // Single-field VC: unwrap to inner type
                val innerType = (type.constructor?.parameters?.singleOrNull()?.type) ?: type
                return listOf(Parameter(innerType, param.varName))
            }
        }
        return listOf(param)
    }

    /**
     * R3 (pure): given a FieldAccess like `b.topLeft.x` whose root instance is
     * a Variable that was unboxed via R1 (has `expandedFieldSymbols`), build the
     * dotted path, look up the scalar symbol, return Variable(scalar).
     *
     * Returns null when no resolution applies.
     *
     * This is the unit-testable pure helper for FieldAccess resolution on
     * already-unboxed VC variables.
     */
    internal fun resolveFieldAccessToScalar(
        node: ExpressionNode.Phase2_3Expression
    ): ExpressionNode.Phase2_3Expression? {
        if (node !is ExpressionNode.FieldAccess) return null

        // Walk the FieldAccess chain collecting field names that lead down to a Variable.
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
}

