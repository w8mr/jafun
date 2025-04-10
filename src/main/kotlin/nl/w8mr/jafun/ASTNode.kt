package nl.w8mr.jafun


// --- Centralized Compile Function ---

sealed interface ASTNode {
    abstract class Expression : ASTNode {
        abstract fun type(): IR.OperandType<*>
    }

    data class StringLiteral(val value: String) : Expression() {
        override fun type() = IR.StringType
    }

    data class CharLiteral(val value: Char) : Expression() {
        override fun type() = IR.CharType
    }

    data class IntegerLiteral(val value: Int) : Expression() {
        override fun type() = IR.SInt32
    }

    data class BooleanLiteral(val value: Boolean) : Expression() {
        override fun type() = IR.UInt1
    }

    data class ExpressionList(val expressions: List<Expression>) : Expression() {
        override fun type() = expressions.lastOrNull()?.type() ?: IR.Unit
    }

    data class Invocation(val method: IR.JFMethod, val field: IR.JFField?, val arguments: List<Expression>) : Expression() {
        override fun type() = method.rtn
    }

    data class When(val subject: Expression?, val matches: List<Pair<Expression, Expression>>) : Expression() {
        override fun type() = matches.firstOrNull()?.second?.type() ?: IR.Unit // Type is based on first branch? Or common type? Original used last. Let's use first non-null or Unit.
    }

    data class While(val condition: Expression, val expressions: ExpressionList) : Expression() {
        override fun type() = IR.Unit
    }

    data class ValAssignment(val variableSymbol: IR.JFVariableSymbol, val expression: Expression) : Expression() {
        override fun type() = expression.type()
    }

    data class VarAssignment(val variableSymbol: IR.JFVariableSymbol, val expression: Expression) : Expression() {
        override fun type() = expression.type()
    }

    data class Variable(val variableSymbol: IR.JFVariableSymbol) : Expression() {
        override fun type() = variableSymbol.type
    }

    data class Function(val symbol: IR.JFMethod, val block: List<Expression>) : Expression() {
        override fun type() = IR.Unit
    }
}