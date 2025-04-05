package nl.w8mr.jafun

import jafun.compiler.Associativity
import jafun.compiler.HasPath
import jafun.compiler.IdentifierCache
import jafun.compiler.SymbolMap

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

    object StringType : OperandType<String> { override fun toString() = "StringType" }

    open class Reference<T>(val type: String) : OperandType<T>

    class Array<T>(val type: OperandType<T>) : OperandType<T>

    object SInt32 : OperandType<Int> { override fun toString() = "Int32Type" }

    object UInt1 : OperandType<Boolean> { override fun toString() = "BooleanType" }

    object Unit : Reference<jafun.Unit>("jafun.Unit") { override fun toString() = "UnitType" }

    data class LoadConstant<J, T : OperandType<J>>(override val operand1: J, override val type: T) : OneOperand<J, T>

    data class Store<J>(val registerName: String, val type: OperandType<J>) : Instruction

    data class Load<J>(val registerName: String, val type: OperandType<J>) : Instruction

    data class Invoke(val method: JFMethod, val field: JFField?) : Instruction

    data class GetStatic(val className: String, val fieldName: String, val type: OperandType<*>) : Instruction

    data object Pop : Instruction

    data object Dup : Instruction

    data class Return<J>(val type: OperandType<J>) : Instruction

    data class When(val cases: List<WhenCase>): Instruction {
        sealed interface WhenCase
        data class WhenConditionCase(val condition: List<Instruction>, val execution: List<Instruction>): WhenCase
        data class WhenElseCase(val execution: List<Instruction>): WhenCase

    }

    data class JFClass(override val path: String) : OperandType<Any?>, HasPath

    data class JFField(val parent: JFClass, override val path: String, val name: String) : HasPath

    data class JFMethod(
        val parameters: List<JFVariableSymbol>,
        val parent: HasPath,
        val name: String,
        val rtn: OperandType<*>,
        val static: Boolean = false,
        val operator: Boolean = false,
        val associativity: Associativity = Associativity.PREFIX,
        val precedence: Int = 10,
    ) : OperandType<Any?>

    data class JFVariableSymbol(
        val name: String,
        val type: OperandType<*>,
        val symbolMap: SymbolMap = IdentifierCache,
        val mutable: Boolean = false
    ) : OperandType<Any?> {
        override fun equals(other: Any?): Boolean =
            when (other) {
                null -> false
                is JFVariableSymbol -> name == other.name && type == other.type
                else -> super.equals(other)
            }

        override fun hashCode(): Int {
            var result = name.hashCode()
            result = 31 * result + type.hashCode()
            return result
        }
    }
}
