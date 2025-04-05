package nl.w8mr.jafun

sealed class Token(val name: String) {
    override fun toString(): String = name

    object Dot : Token(".")

    object LParen : Token("(")

    object RParen : Token(")")

    object LCurl : Token("{")

    object RCurl : Token("}")

    object Comma : Token(",")

    object Colon : Token(":")

    object Semicolon : Token(";")

    object Assignment : Token("=")

    object Val : Token("val")

    object Fun : Token("fun")

    object When : Token("when")

    object True : Token("true")

    object False : Token("false")

    object WS : Token("WS")

    object Newline : Token("NL")

    data class Identifier(val value: String, val operator: Boolean = false) : Token(value)

    data class StringLiteral(val value: String) : Token(value)

    data class IntegerLiteral(val value: Int) : Token(value.toString())
}
