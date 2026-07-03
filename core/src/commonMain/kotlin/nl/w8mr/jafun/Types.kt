package nl.w8mr.jafun

import nl.w8mr.jafun.compiler.Associativity
import nl.w8mr.jafun.compiler.IdentifierCache
import nl.w8mr.jafun.compiler.SymbolMap
import nl.w8mr.jafun.debug.Printable

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

    enum class ClassKind { NORMAL, VALUE_CLASS }

    data class JFClass(override val name: String, override val parent: ClassParent? = null, val kind: ClassKind = ClassKind.NORMAL) :
        Type, OperandType<Any?>, HasParent<ClassParent?>, MethodParent, FieldParent {
        var constructor: JFConstructor? = null
    }

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

    data class JFConstructor(
        val parameters: List<JFVariableSymbol>,
        override val parent: MethodParent,
        override val name: String = "<init>",
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

    /**
     * Represents an expanded field from a value class parameter.
     * Tracks both the direct type and source VC metadata for nested field access.
     * Can optionally store a reference to the actual JFVariableSymbol for direct access.
     *
     * Example: When User(id: Id, name: String) is expanded:
     * - ExpandedField("id", SInt32, sourceVC=IdClass, sourceVCFields=[("value", SInt32)])
     * - ExpandedField("name", StringType, sourceVC=null, sourceVCFields=null)
     */
    data class ExpandedField(
        val name: String,
        val type: OperandType<*>,
        val sourceVC: JFClass? = null,
        val sourceVCFields: List<Pair<String, OperandType<*>>>? = null,
        val actualSymbol: JFVariableSymbol? = null  // Reference to actual stored symbol
    )

    data class JFVariableSymbol(
        override val name: String,
        val type: OperandType<*>,
        val symbolMap: SymbolMap = IdentifierCache,
        val mutable: Boolean = false,
        val initialized: Boolean = true
    ) : Type, InvocationTarget, Printable {
        var expandedFields: List<ExpandedField>? = null
        var expandedFieldSymbols: Map<String, JFVariableSymbol>? = null  // Maps field paths to actual symbols
        var effectiveType: OperandType<*>? = null  // Unwrapped JVM type set by Phase 3 expansion

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
    object StringType : OperandType<String> {
        override fun toString() = "StringType"
    }

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

    object Unit : OperandType<jafun.Unit> {
        override fun toString() = "UnitType"
    }

    object Unknown : OperandType<Any> {
        override fun toString() = "UnknownType"
    }

}