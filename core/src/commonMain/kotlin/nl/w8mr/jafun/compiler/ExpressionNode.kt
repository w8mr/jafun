package nl.w8mr.jafun.compiler

import nl.w8mr.jafun.OperandType
import nl.w8mr.jafun.Type
import nl.w8mr.jafun.debug.Printable

sealed interface ExpressionNode: Printable {

    // Phase1 node implementations
    object Dot: Phase1Token
    object Colon: Phase1Token
    object SemiColon: Phase1Token
    object DoubleQoute: Phase1Token
    object SingleQoute: Phase1Token
    object LeftParen: Phase1Token
    object RightParen: Phase1Token
    object LeftCurly: Phase1Token
    object RightCurly: Phase1Token
    object Comma: Phase1Token

    data class Identifier(val value: String, val operator: Boolean = false): Phase1Token, Phase2Expression {
        override fun type(): OperandType<*> = OperandType.Unknown
    }

    data class Whitespace(val value: String): Phase1Token
    data class Newline(val value: String): Phase1Token
    data class Keyword(val value: String): Phase1Token

    data class Phase1List(val tokens: List<Phase1Token>) : Phase1Token {
        constructor(vararg tokens: Phase1Token) : this(tokens.toList())
        fun flatten(): List<Phase1Token> = tokens.flatMap {
            when(it) {
                is Phase1List -> it.flatten()
                is Whitespace -> if (it.value.isEmpty()) emptyList() else listOf(it)
                else -> listOf(it)
            }
        }
    }

    data class CurlyBlock(val symbolMap: SymbolMap, val tokens: List<Phase1Token>) : Phase1Token {
        constructor(symbolMap: SymbolMap, vararg tokens: Phase1Token) : this(symbolMap, tokens.toList())

        /**
         * override equals to ignore symbolMap
         */
        override fun equals(other: Any?): Boolean =
            (other as? CurlyBlock)?.tokens == tokens

        override fun hashCode(): Int = tokens.hashCode()
    }

    data class StringLiteral(val value: String) : Phase1Token, Phase2Expression {
        override fun type() = OperandType.StringType
    }

    data class StringTemplate(val expressions: List<Phase2Expression>) : Phase1Token, Phase2Expression {
        override fun type() = OperandType.StringType
    }

    data class CharLiteral(val value: Char) : Phase1Token, Phase2Expression {
        override fun type() = OperandType.CharType
    }

    data class IntegerLiteral(val value: Int) : Phase1Token, Phase2Expression {
        override fun type() = OperandType.SInt32
    }

    data class BooleanLiteral(val value: Boolean) : Phase1Token, Phase2Expression {
        override fun type() = OperandType.UInt1
    }

    data class ExpressionList(val expressions: List<Phase2_3Expression>) : Phase2Expression {
        override fun type() = expressions.lastOrNull()?.type() ?: OperandType.Unit
        fun simplify(): Phase2_3Expression = if (expressions.size == 1) expressions[0] else this
    }

    interface Invocation : Phase2Expression {
        val arguments: List<Phase2_3Expression>
    }

    class MethodInvocation(
        val methodName: String,
        val parentPath: String,
        val parameters: List<Type.JFVariableSymbol>,
        val rtnLookup: () -> OperandType<*>,
        val field: Type.InvocationTarget?,
        override val arguments: List<Phase2_3Expression>
    ) : Invocation {
        override fun type(): OperandType<*> = rtnLookup()
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is MethodInvocation) return false
            return methodName == other.methodName &&
                    parentPath == other.parentPath &&
                    parameters == other.parameters &&
                    field == other.field &&
                    arguments == other.arguments
        }
        override fun hashCode(): Int {
            var result = methodName.hashCode()
            result = 31 * result + parentPath.hashCode()
            result = 31 * result + parameters.hashCode()
            result = 31 * result + (field?.hashCode() ?: 0)
            result = 31 * result + arguments.hashCode()
            return result
        }
        override fun toString(): String =
            "MethodInvocation(methodName=$methodName, parentPath=$parentPath, parameters=$parameters, field=$field, arguments=$arguments)"
    }

    data class ConstructorInvocation(
        val cons: Type.JFConstructor,
        val targetClass: Type.JFClass,
        override val arguments: List<Phase2_3Expression>
    ) : Invocation {
        override fun type() = targetClass
    }


    data class FieldAccess(
        val instance: Phase2_3Expression,
        val fieldName: String,
        val fieldIndex: Int,
        val fieldType: OperandType<*>,
        override val arguments: List<Phase2_3Expression>,
    ) : Invocation {
        override fun type(): OperandType<*> = fieldType
    }

    data class When(val subject: Phase2_3Expression?, val matches: List<Pair<Phase2_3Expression, Phase2_3Expression>>) : Phase2Expression {
        override fun type() = matches.firstOrNull()?.second?.type() ?: OperandType.Unit
        // Type is based on first branch? Or common type? Original used last. Let's use first non-null or Unit.
    }

    data class WhenPhase3(val matches: List<Pair<Phase2_3Expression, Phase2_3Expression>>) : Phase3Expression {
        override fun type() = matches.firstOrNull()?.second?.type() ?: OperandType.Unit
        // Type is based on first branch? Or common type? Original used last. Let's use first non-null or Unit.
    }

    data class DoWhile(val condition: Phase2_3Expression, val expressions: Phase2_3Expression) : Phase2Expression {
        override fun type() = OperandType.Unit
    }

    data class While(val condition: Phase2_3Expression, val expressions: Phase2_3Expression) : Phase2Expression {
        override fun type() = OperandType.Unit
    }

    data class WhilePhase3(val condition: Phase2_3Expression, val expressions: Phase2_3Expression) : Phase3Expression {
        override fun type() = OperandType.Unit
    }

    interface Assignment : Phase2Expression  {
        val variableSymbol: Type.JFVariableSymbol
        val expression: Phase2_3Expression
    }

    data class ValAssignment(override val variableSymbol: Type.JFVariableSymbol, override val expression: Phase2_3Expression) : Assignment {
        override fun type() = expression.type()
    }

    data class VarAssignment(override val variableSymbol: Type.JFVariableSymbol, override val expression: Phase2_3Expression) : Assignment {
        override fun type() = expression.type()
    }

    data class Variable(val variableSymbol: Type.JFVariableSymbol) : Phase2Expression {
        override fun type() = variableSymbol.type
    }

    data class Function(
        val symbol: Type.JFMethod,
        val block: List<Phase2_3Expression>,
        val inline: Boolean = false,
    ) : Phase2Expression {
        override fun type() = OperandType.Unit
    }

    data class Convert(
        val expression: Phase2_3Expression,
        val from: OperandType<*>,
        val to: OperandType<*>
    ) : Phase2_3Expression {
        override fun type(): OperandType<*> = to
    }

    // ── IR operation nodes (type-polymorphic, one per concept) ──

    data class Mul(
        val left: Phase2_3Expression,
        val right: Phase2_3Expression,
    ) : Phase3Expression {
        override fun type() = left.type()
    }

    data class Add(
        val left: Phase2_3Expression,
        val right: Phase2_3Expression,
    ) : Phase3Expression {
        override fun type() = left.type()
    }

    data class Sub(
        val left: Phase2_3Expression,
        val right: Phase2_3Expression,
    ) : Phase3Expression {
        override fun type() = left.type()
    }

    data class Div(
        val left: Phase2_3Expression,
        val right: Phase2_3Expression,
    ) : Phase3Expression {
        override fun type() = left.type()
    }

    data class CmpEq(
        val left: Phase2_3Expression,
        val right: Phase2_3Expression,
    ) : Phase3Expression {
        override fun type() = OperandType.UInt1
    }

    data class CmpLt(
        val left: Phase2_3Expression,
        val right: Phase2_3Expression,
    ) : Phase3Expression {
        override fun type() = OperandType.UInt1
    }

    data class CmpLe(
        val left: Phase2_3Expression,
        val right: Phase2_3Expression,
    ) : Phase3Expression {
        override fun type() = OperandType.UInt1
    }

    data class CmpGt(
        val left: Phase2_3Expression,
        val right: Phase2_3Expression,
    ) : Phase3Expression {
        override fun type() = OperandType.UInt1
    }

    data class CmpGe(
        val left: Phase2_3Expression,
        val right: Phase2_3Expression,
    ) : Phase3Expression {
        override fun type() = OperandType.UInt1
    }

    data class IRBlock(
        val parameters: List<Phase2_3Expression>,
        val operation: Phase3Expression,
    ) : Phase2Expression {
        override fun type() = operation.type()
    }

    data class InvokeStatic(
        val className: String,
        val methodName: String,
        val signature: String,
        val arguments: List<Phase2_3Expression>,
    ) : Phase2Expression {
        override fun type() = returnTypeFromSignature(signature)
    }

    data class InvokeVirtual(
        val className: String,
        val methodName: String,
        val signature: String,
        val arguments: List<Phase2_3Expression>,
    ) : Phase2Expression {
        override fun type() = returnTypeFromSignature(signature)
    }

    interface Expression : ExpressionNode {
        fun type(): OperandType<*>
    }

    interface Phase2_3Expression : Expression, Phase2, Phase3

    interface Phase2Expression : Phase2_3Expression
    interface Phase3Expression : Phase2_3Expression

    interface Phase1Token : ExpressionNode // CST

    interface Phase2 : ExpressionNode // AST

    interface Phase3 : ExpressionNode // TreeIR
}

private fun returnTypeFromSignature(signature: String): OperandType<*> {
    val returnDesc = signature.substringAfter(')')
    return when {
        returnDesc == "V" -> OperandType.Unit
        returnDesc == "I" -> OperandType.SInt32
        returnDesc == "C" -> OperandType.CharType
        returnDesc == "Z" -> OperandType.UInt1
        returnDesc == "J" -> OperandType.Unknown // SInt64 not yet supported
        returnDesc == "D" -> OperandType.Unknown // SDouble not yet supported
        returnDesc == "F" -> OperandType.Unknown // SFloat not yet supported
        returnDesc.startsWith("L") -> OperandType.StringType
        else -> OperandType.Unknown
    }
}

