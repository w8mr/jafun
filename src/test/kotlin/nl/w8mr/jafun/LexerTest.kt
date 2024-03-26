package nl.w8mr.jafun.nl.w8mr.jafun

import nl.w8mr.jafun.Token
import nl.w8mr.jafun.Token.Assignment
import nl.w8mr.jafun.Token.Colon
import nl.w8mr.jafun.Token.Comma
import nl.w8mr.jafun.Token.Dot
import nl.w8mr.jafun.Token.Fun
import nl.w8mr.jafun.Token.Identifier
import nl.w8mr.jafun.Token.IntegerLiteral
import nl.w8mr.jafun.Token.LCurl
import nl.w8mr.jafun.Token.LParen
import nl.w8mr.jafun.Token.Newline
import nl.w8mr.jafun.Token.RCurl
import nl.w8mr.jafun.Token.RParen
import nl.w8mr.jafun.Token.StringLiteral
import nl.w8mr.jafun.Token.WS
import nl.w8mr.jafun.lexer
import kotlin.test.Test
import kotlin.test.assertContentEquals

class LexerTest {
    @Test
    fun helloWorldLexer() {
        test(
            "println \"Hello World\"\r\n",
            Identifier("println"),
            WS,
            StringLiteral("Hello World"),
            Newline,
        )
    }

    @Test
    fun integerLexer() {
        test(
            "println 12_234_2\r\n",
            Identifier("println"),
            WS,
            IntegerLiteral(122342),
            Newline,
        )
    }

    @Test
    fun twoArgFunLexer() {
        test(
            "join(\"Hello\", \"World\")\r\n",
            Identifier("join"), LParen, StringLiteral("Hello"), Comma, WS, StringLiteral("World"), RParen, Newline,
        )
    }

    @Test
    fun simpleFunDeclaration() {
        test(
            "fun test() {}\r\n",
            Fun, WS, Identifier("test"), LParen, RParen, WS, LCurl, RCurl, Newline,
        )
    }

    @Test
    fun simpleFunDeclarationWithExpression() {
        test(
            "fun test() = 1\r\n",
            Fun, WS, Identifier("test"), LParen, RParen, WS, Assignment, WS, IntegerLiteral(1), Newline,
        )
    }

    @Test
    fun complexFunDeclaration() {
        test(
            """|fun test(int: Int, string: String): String {
                |  int.toString + string
                |}
            """.trimMargin(),
            Fun, WS, Identifier("test"), LParen, Identifier("int"), Colon, WS, Identifier("Int"), Comma, WS,
            Identifier("string"), Colon, WS, Identifier("String"), RParen,
            Colon, WS, Identifier("String"), WS, LCurl, Newline,
            WS, Identifier("int"), Dot, Identifier("toString"), WS, Identifier("+", true),
            WS, Identifier("string"), Newline,
            RCurl,
        )
    }

    private fun test(
        input: String,
        vararg tokens: Token,
    ) {
        val lexed = lexer.parse(input)
        println(lexed)
        assertContentEquals(tokens.toList(), lexed)
    }
}
