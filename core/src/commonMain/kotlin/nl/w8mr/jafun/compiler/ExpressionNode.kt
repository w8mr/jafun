package nl.w8mr.jafun.compiler

import nl.w8mr.jafun.OperandType
import nl.w8mr.jafun.Type
import nl.w8mr.jafun.debug.Printable

sealed interface ExpressionNode: Printable {

    // Phase1 node implementations
    object Dot: Phase1Token
    object Colon: Phase1Token
    object Equals: Phase1Token
    object Dollar: Phase1Token
    object DoubleQoute: Phase1Token
    object SingleQoute: Phase1Token
    object LeftParen: Phase1Token
    object RightParen: Phase1Token
    object LeftCurly: Phase1Token
    object RightCurly: Phase1Token
    object Comma: Phase1Token

    data class Identifier(val value: String, val operator: Boolean = false): Phase1Token

    data class Whitespace(val value: String): Phase1Token
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

    data class CurlyBlock(val tokens: List<Phase1Token>) : Phase1Token {
        constructor(vararg tokens: Phase1Token) : this(tokens.toList())
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
    }

    data class Invocation(
        val method: Type.JFMethod,
        val field: Type.InvocationTarget?,
        val arguments: List<Phase2_3Expression>
    ) : Phase2Expression {
        override fun type() = method.rtn
    }

    data class Constructor(
        val cons: Type.JFConstructor,
        val arguments: List<Phase2_3Expression>
    ) : Phase2Expression {
        override fun type() = (cons.parent as? Type.JFClass) ?: error("Constructor ${cons.parent} is not an instance of Type.JFClass")
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

    data class Function(val symbol: Type.JFMethod, val block: List<Phase2_3Expression>) : Phase2Expression {
        override fun type() = OperandType.Unit
    }

    data class Convert(
        val expression: Phase2_3Expression,
        val from: OperandType<*>,
        val to: OperandType<*>
    ) : Phase2_3Expression {
        override fun type(): OperandType<*> = to
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

    interface Phase4 : ExpressionNode // StackIR
}

