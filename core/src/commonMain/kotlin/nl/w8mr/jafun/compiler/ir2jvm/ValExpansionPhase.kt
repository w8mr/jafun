package nl.w8mr.jafun.compiler.ir2jvm

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
                            val ci = expanded.expression as? ExpressionNode.ConstructorInvocation
                            if (ci != null) {
                                expanded.variableSymbol.constructorArgs = ci.arguments
                            }
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
                            val ci = expanded.expression as? ExpressionNode.ConstructorInvocation
                            if (ci != null) {
                                expanded.variableSymbol.constructorArgs = ci.arguments
                            }
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
        return context
    }
}
