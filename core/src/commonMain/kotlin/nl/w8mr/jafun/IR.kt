package nl.w8mr.jafun

import nl.w8mr.jafun.IR.OneOperand
import nl.w8mr.jafun.Type.JFMethod
import nl.w8mr.jafun.compiler.Associativity
import nl.w8mr.jafun.compiler.IdentifierCache
import nl.w8mr.jafun.compiler.SymbolMap

interface TypeSymbol

interface Type : TypeSymbol {
    val name: String
    val path: String get() = "${(this as? HasParent<*>)?.parent?.path?.let{"$it."} ?: ""}$name"

    interface Parent : Type

    interface InvocationTarget

    interface HasParent<T : Parent?> : Type {
        val parent: T
        val parentPath: String get() = parent?.path ?: ""
    }

    interface MethodParent : Parent

    interface FieldParent : Parent

    interface ClassParent : Parent

    interface PackageParent : Parent

    data class JFClass(override val name: String, override val parent: ClassParent? = null) :
        Type, OperandType<Any?>, HasParent<ClassParent?>, MethodParent, FieldParent

    data class JFPackage(override val name: String, override val parent: PackageParent? = null) :
        Type, HasParent<PackageParent?>, MethodParent, FieldParent, ClassParent, PackageParent

    data class JFField(override val name: String, override val parent: FieldParent, val type: OperandType<*>?) :
        Type, HasParent<FieldParent>, InvocationTarget

    data class JFMethod(
        val parameters: List<JFVariableSymbol>,
        override val parent: MethodParent,
        override val name: String,
        val rtn: OperandType<*>,
        val static: Boolean = false,
        val operator: Boolean = false,
        val associativity: Associativity = Associativity.PREFIX,
        val precedence: Int = 10,
    ) : Type, HasParent<MethodParent>

    data class JFFieldMethod(
        val field: JFField,
        val method: JFMethod,
    ) : Type {
        override val name get() = "${field.path}.${method.name}"
    }

    data class JFVariableMethod(
        val variable: JFVariableSymbol,
        val method: JFMethod,
    ) : Type {
        override val name get() = "${variable.path}.${method.name}"
    }

    data class JFVariableSymbol(
        override val name: String,
        val type: OperandType<*>,
        val symbolMap: SymbolMap = IdentifierCache,
        val mutable: Boolean = false,
    ) : Type, InvocationTarget {
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

sealed interface OperandType<J> : TypeSymbol {
    fun operand1(instruction: OneOperand<*>) = instruction.operand1 as J

    object StringType : OperandType<String> {
        override fun toString() = "StringType"
    }

    open class Reference<T>(val type: String) : OperandType<T>

    abstract class Generic(vararg val genericTypes: OperandType<*>) : OperandType<Any>

    data class Array(val genericType: OperandType<*>) : Generic(genericType)

    object SInt32 : OperandType<Int> {
        override fun toString() = "Int32Type"
    }

    object UInt1 : OperandType<Boolean> {
        override fun toString() = "BooleanType"
    }

    object CharType : OperandType<Char> {
        override fun toString() = "CharType"
    }

    object Unit : Reference<kotlin.Unit>("kotlin.Unit") {
        override fun toString() = "UnitType"
    }
}

class IR {
    sealed interface Instruction

    sealed interface OneOperand<J> : Instruction {
        val operand1: J
        val type: OperandType<J>
    }

    data class LoadConstant<J>(override val operand1: J, override val type: OperandType<J>) : OneOperand<J>

    data class Store<J>(val registerName: String, val type: OperandType<J>) : Instruction

    data class Load<J>(val registerName: String, val type: OperandType<J>) : Instruction

    data class Invoke(val method: JFMethod, val field: Type.InvocationTarget?) : Instruction

    data class GetStatic(val className: String, val fieldName: String, val type: OperandType<*>) : Instruction

    data object Pop : Instruction

    data object Dup : Instruction

    data class Return<J>(val type: OperandType<J>) : Instruction

    data class When(val cases: List<WhenCase>) : Instruction {
        sealed interface WhenCase

        data class WhenConditionCase(val condition: List<Instruction>, val execution: List<Instruction>) : WhenCase

        data class WhenElseCase(val execution: List<Instruction>) : WhenCase
    }

    data class DoWhile(val condition: List<Instruction>, val expressions: List<Instruction>) : Instruction

    data class While(val condition: List<Instruction>, val expressions: List<Instruction>) : Instruction
}
