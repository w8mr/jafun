package nl.w8mr.jafun.compiler

import nl.w8mr.jafun.Phase1Parser
import nl.w8mr.jafun.compiler.ExpressionNode.Colon
import nl.w8mr.jafun.compiler.ExpressionNode.CurlyBlock
import nl.w8mr.jafun.compiler.ExpressionNode.Dollar
import nl.w8mr.jafun.compiler.ExpressionNode.Dot
import nl.w8mr.jafun.compiler.ExpressionNode.DoubleQoute
import nl.w8mr.jafun.compiler.ExpressionNode.Identifier
import nl.w8mr.jafun.compiler.ExpressionNode.IntegerLiteral
import nl.w8mr.jafun.compiler.ExpressionNode.Keyword
import nl.w8mr.jafun.compiler.ExpressionNode.LeftCurly
import nl.w8mr.jafun.compiler.ExpressionNode.LeftParen
import nl.w8mr.jafun.compiler.ExpressionNode.Newline
import nl.w8mr.jafun.compiler.ExpressionNode.RightCurly
import nl.w8mr.jafun.compiler.ExpressionNode.RightParen
import nl.w8mr.jafun.compiler.ExpressionNode.StringLiteral
import nl.w8mr.jafun.compiler.ExpressionNode.Whitespace
import nl.w8mr.parsek.text.parse
import kotlin.test.Test
import kotlin.test.assertEquals

class Phase1SimpleParserTest {
    @Test
    fun valAssignmentNoType() {
        assertEquals(listOf(
            Keyword("val"),
            Whitespace(" "),
            Identifier("a"),
            Whitespace(" "),
            Identifier("=", true),
            Whitespace(" "),
        ), Phase1Parser().valDeclaration.parse("val a = 1").flatten())
    }

    @Test
    fun valAssignmentSimpleType() {
        assertEquals(listOf(
            Keyword("val"),
            Whitespace(" "),
            Identifier("a"),
            Colon,
            Whitespace(" "),
            Identifier("Int"),
            Whitespace(" "),
            Identifier("=", true),
            Whitespace(" "),
        ), Phase1Parser().valDeclaration.parse("val a: Int = 1").flatten())
    }

    @Test
    fun valAssignmentComplexType() {
        assertEquals(listOf(
            Keyword("val"),
            Whitespace(" "),
            Identifier("a"),
            Colon,
            Whitespace(" "),
            Identifier("kotlin"),
            Dot,
            Identifier("collections"),
            Dot,
            Identifier("List"),
            Whitespace(" "),
            Identifier("=", true),
            Whitespace(" "),
        ), Phase1Parser().valDeclaration.parse("val a: kotlin.collections.List = emptyList()").flatten())
    }

    @Test
    fun stringNoDollar() {
        assertEquals(listOf(
            DoubleQoute,
            StringLiteral("abc"),
            DoubleQoute,
        ), Phase1Parser().stringLiteral_term.parse(""""abc"""").flatten())
    }

    @Test
    fun stringSimpleDollar() {
        assertEquals(listOf(
            DoubleQoute,
            Dollar,
            Identifier("abc"),
            DoubleQoute,
        ), Phase1Parser().stringLiteral_term.parse(""""${'$'}abc"""").flatten())
    }

    @Test
    fun stringComplexDollar() {
        assertEquals(listOf(
            DoubleQoute,
            Dollar,
            CurlyBlock(IdentifierCache,
                LeftCurly,
                Identifier("a"),
                Identifier("+", true),
                IntegerLiteral(1),
                RightCurly,
            ),
            DoubleQoute,
        ), Phase1Parser().stringLiteral_term.parse(""""${'$'}{a+1}"""").flatten())
    }


    @Test
    fun parserValPrint() {
       assertEquals(listOf(
           Keyword("val"),
           Whitespace(" "),
           Identifier("str1"),
           Whitespace(" "),
           Identifier("=", true),
           Whitespace(" "),
           DoubleQoute,
           StringLiteral("Hello World"),
           DoubleQoute,
           Newline("\n"),
           Identifier("println"),
           LeftParen,
           Identifier("str1"),
           RightParen,
       ), Phase1Parser().parse("""
           |val str1 = "Hello World"
           |println(str1)
       """.trimMargin()).first)
    }

    @Test
    fun parserFunDeclaration() {
        assertEquals(listOf(
            Keyword("fun"),
            Whitespace(" "),
            Identifier("test"),
            Whitespace(" "),
            LeftParen,
            Whitespace(" "),
            Identifier("a"),
            Colon,
            Whitespace(" "),
            Identifier("Int"),
            RightParen,
            Whitespace(" "),
            Colon,
            Identifier("Int"),
            CurlyBlock(
                IdentifierCache,
                LeftCurly,
                Identifier("println"),
                Whitespace(" "),
                DoubleQoute,
                StringLiteral("Hello World"),
                DoubleQoute,
                Newline("\n"),
                Identifier("a"),
                Newline("\n"),
                RightCurly,
            )
        ), Phase1Parser().parse("""
           |fun test ( a: Int) :Int{println "Hello World"
           |a
           |}""".trimMargin()).first)
    }

}