package nl.w8mr.jafun

import jafun.compiler.JFField
import jafun.compiler.JFMethod

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

    open class Reference<T> : OperandType<T>

    object SInt32 : OperandType<Int>

    object UInt1 : OperandType<Boolean>

    object Unit : Reference<kotlin.Unit>()

    data class LoadConstant<J, T : OperandType<J>>(override val operand1: J, override val type: T) : OneOperand<J, T>

    data class Store<J>(val registerName: String, val type: OperandType<J>) : Instruction

    data class Load<J>(val registerName: String, val type: OperandType<J>) : Instruction

    data class Invoke(val method: JFMethod, val field: JFField?) : Instruction

    data class GetStatic(val className: String, val fieldName: String, val type: String) : Instruction

    data object Pop : Instruction

    data object Dup : Instruction

    data class Return<J>(val type: OperandType<J>) : Instruction
}
