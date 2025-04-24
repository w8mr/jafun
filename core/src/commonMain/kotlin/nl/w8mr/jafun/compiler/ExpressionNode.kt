package nl.w8mr.jafun.compiler

import nl.w8mr.jafun.OperandType
import nl.w8mr.jafun.Type
import nl.w8mr.jafun.debug.Printable

sealed interface ExpressionNode: Printable {

    data class StringLiteral(val value: String) : Phase2Expression {
        override fun type() = OperandType.StringType
    }

    data class StringTemplate(val expressions: List<Phase2Expression>) : Phase2Expression {
        override fun type() = OperandType.StringType
    }

    data class CharLiteral(val value: Char) : Phase2Expression {
        override fun type() = OperandType.CharType
    }

    data class IntegerLiteral(val value: Int) : Phase2Expression {
        override fun type() = OperandType.SInt32
    }

    data class BooleanLiteral(val value: Boolean) : Phase2Expression {
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

    data class Statement(val expression: Phase2_3Expression): Phase2_3Expression {
        override fun type() = expression.type()
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

    interface Phase1 : ExpressionNode // CST

    interface Phase2 : ExpressionNode // AST

    interface Phase3 : ExpressionNode // TreeIR

    interface Phase4 : ExpressionNode // StackIR
}


