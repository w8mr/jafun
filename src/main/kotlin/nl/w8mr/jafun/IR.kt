package nl.w8mr.jafun

import jafun.compiler.BooleanType
import jafun.compiler.IntegerType
import jafun.compiler.JFClass
import jafun.compiler.JFField
import jafun.compiler.JFMethod
import jafun.compiler.JFVariableSymbol
import jafun.compiler.TypeSig
import jafun.compiler.VoidType

class IR {
    sealed interface OperandType<J> {
        fun operand1(instruction: OneOperand<*, *>): J = instruction.operand1 as J
    }

    sealed interface Instruction

    sealed interface OneOperand<J, T : OperandType<J>> : Instruction {
        val operand1: J
        val type: T

        fun operand() = this.operand1
    }

    object StringType : OperandType<String>

    open class Reference<T>(val type: String) : OperandType<T>

    class Array<T>(val type: OperandType<T>) : OperandType<T>

    object SInt32 : OperandType<Int>

    object UInt1 : OperandType<Boolean>

    object Unit : Reference<jafun.Unit>("jafun.Unit")

    data class LoadConstant<J, T : OperandType<J>>(override val operand1: J, override val type: T) : OneOperand<J, T>

    data class Store<J>(val registerName: String, val type: OperandType<J>) : Instruction

    data class Load<J>(val registerName: String, val type: OperandType<J>) : Instruction

    data class Invoke(val method: JFMethod, val field: JFField?) : Instruction

    data class GetStatic(val className: String, val fieldName: String, val type: String) : Instruction

    data object Pop : Instruction

    data object Dup : Instruction

    data class Return<J>(val type: OperandType<J>) : Instruction

    companion object {
        fun operandType(variableSymbol: JFVariableSymbol): IR.OperandType<out Any?> = operandType(variableSymbol.type)

        fun operandType(typeSig: TypeSig): IR.OperandType<out Any?> {
            return when (typeSig) {
                is JFClass -> IR.Reference<Any?>(typeSig.path)
                is IntegerType -> IR.SInt32
                is BooleanType -> IR.UInt1
                is VoidType -> IR.Unit
                else -> TODO("Need to implement types")
            }
        }

        fun signature(type: IR.OperandType<*>): String =
            when (type) {
                is Array -> "[${signature(type.type)}"
                is Unit -> "V"
                is StringType -> "Ljava/lang/String;"
                is Reference -> "L${type.type.replace('.', '/')};"
                is SInt32 -> "I"
                is UInt1 -> "Z"
            }
    }
}
