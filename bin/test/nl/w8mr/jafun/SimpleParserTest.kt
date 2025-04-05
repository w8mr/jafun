package nl.w8mr.jafun.nl.w8mr.jafun

import jafun.compiler.Associativity
import nl.w8mr.jafun.ASTNode
import nl.w8mr.jafun.IR
import nl.w8mr.jafun.ParserJafun
import nl.w8mr.jafun.Token
import nl.w8mr.parsek.Parser
import nl.w8mr.parsek.text.CharSequenceSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.fail

class SimpleParserTest {
    @Test
    fun `Equality`() {
        testSingleParser(
            ParserJafun.complexIdentifier,
            "==4",
            listOf(Token.Identifier("==", true)),
            2,
        )
    }

    @Test
    fun `Spaceship`() {
        testSingleParser(
            ParserJafun.complexIdentifier,
            "<=>4",
            listOf(Token.Identifier("<=>", true)),
            3,
        )
    }

    @Test
    fun `Simple Identifier`() {
        testSingleParser(
            ParserJafun.complexIdentifier,
            "println()",
            listOf(Token.Identifier("println", false)),
            7,
        )
    }

    @Test
    fun `Complex Identifier`() {
        testSingleParser(
            ParserJafun.complexIdentifier,
            "java.lang.System.out.println()",
            listOf(
                Token.Identifier("java", false),
                Token.Identifier("lang", false),
                Token.Identifier("System", false),
                Token.Identifier("out", false),
                Token.Identifier("println", false),
            ),
            28,
        )
    }

    @Test
    fun `Simple Variable`() {
        val varSymbol = IR.JFVariableSymbol("abcd", IR.SInt32, ParserJafun.currentSymbolMap)
        ParserJafun.currentSymbolMap.add("abcd", varSymbol)
        testSingleParser(
            ParserJafun.variableIdentifier,
            "abcd+3",
            ASTNode.Variable(varSymbol),
            4,
        )
    }

    @Test
    fun `Simple Variable not found`() {
        val varSymbol = IR.JFVariableSymbol("abcd", IR.SInt32, ParserJafun.currentSymbolMap)
        ParserJafun.currentSymbolMap.add("abcd", varSymbol)
        testSingleParserFailed(
            ParserJafun.variableIdentifier,
            "bcd+3",
            "Combinator failed, parser number 1 with error: no variable identifier",
            0,
        )
    }

    @Test
    fun `Empty block compact`() {
        testSingleParser(
            ParserJafun.curlBlock,
            "{}",
            ASTNode.ExpressionList(emptyList()),
            2,
        )
    }

    @Test
    fun `Empty block whitespace`() {
        testSingleParser(
            ParserJafun.curlBlock,
            """|{  
               |}
            """,
            ASTNode.ExpressionList(emptyList()),
            5,
        )
    }

    @Test
    fun `val assignment compact`() {
        testSingleParser(
            ParserJafun.initValAssignment,
            """|val abc=5""".trimMargin(),
            ASTNode.ValAssignment(IR.JFVariableSymbol("abc", IR.SInt32, ParserJafun.currentSymbolMap), ASTNode.IntegerLiteral(5)),
            9,
        )
    }

    @Test
    fun `val assignment whitespace`() {
        testSingleParser(
            ParserJafun.initValAssignment,
            """|val 
               |abc = 
               |5
            """.trimMargin(),
            ASTNode.ValAssignment(IR.JFVariableSymbol("abc", IR.SInt32, ParserJafun.currentSymbolMap), ASTNode.IntegerLiteral(5)),
            13,
        )
    }

    @Test
    fun `empty function definition compact`() {
        testSingleParser(
            ParserJafun.functionDefinition,
            """|fun test(){ }""".trimMargin(),
            ParserJafun.FunctionDef(Token.Identifier("test"), emptyList(), null),
            10,
        )
    }

    @Test
    fun `empty function compact`() {
        testSingleParser(
            ParserJafun.function,
            """|fun test(){}""".trimMargin(),
            ASTNode.Function(
                IR.JFMethod(emptyList(), IR.JFClass("Script"), "test", IR.Unit, true, false, Associativity.SOLO, 10),
                emptyList(),
            ),
            12,
        )
    }

    @Test
    fun `empty function whitespace`() {
        testSingleParser(
            ParserJafun.function,
            """|fun 
                |test 
                |( 
                |) 
                |{ 
                |}
            """.trimMargin(),
            ASTNode.Function(
                IR.JFMethod(emptyList(), IR.JFClass("Script"), "test", IR.Unit, true, false, Associativity.SOLO, 10),
                emptyList(),
            ),
            21,
        )
    }

    private fun <R> testSingleParser(
        parser: Parser<Char, R>,
        input: String,
        expected: R,
        afterIndex: Int = 1,
    ) {
        // val lexed = lexer.parse(input).filter { it !is Token.WS }
        val source = CharSequenceSource(input.trimMargin())
        val parsed = parser.apply(source)
        if (parsed is Parser.Failure) {
            println("Tree: \n${parser.parseTree(source).second}")
        }
        val success = (parsed as? Parser.Success ?: fail("Parse not successful ${(parsed as Parser.Failure).message}")).value
        assertEquals(
            expected,
            success,
        )
        assertEquals(source.index, afterIndex)
    }

    private fun <R> testSingleParserFailed(
        parser: Parser<Char, R>,
        input: String,
        expected: String,
        afterIndex: Int = 1,
    ) {
        // val lexed = lexer.parse(input).filter { it !is Token.WS }
        val source = CharSequenceSource(input)
        val failureMessage = (parser.apply(source) as? Parser.Failure ?: fail("Parse not failed")).message
        assertEquals(
            expected,
            failureMessage,
        )
        assertEquals(source.index, afterIndex)
    }
}
