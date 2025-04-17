package nl.w8mr.jafun.nl.w8mr.jafun

import nl.w8mr.jafun.ASTNode
import nl.w8mr.jafun.OperandType
import nl.w8mr.jafun.ParserJafun
import nl.w8mr.jafun.Type
import nl.w8mr.jafun.compiler.Associativity
import nl.w8mr.jafun.compiler.IdentifierCache
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
            listOf(
                Type.JFMethod(
                    listOf(Type.JFVariableSymbol("param1", OperandType.SInt32), Type.JFVariableSymbol("param2", OperandType.SInt32)),
                    IdentifierCache.findClass("jafun.lang.IntKt"),
                    "==",
                    OperandType.UInt1,
                    true,
                    true,
                    Associativity.INFIXL,
                    40,
                ),
                Type.JFMethod(
                    listOf(Type.JFVariableSymbol("param1", OperandType.CharType), Type.JFVariableSymbol("param2", OperandType.CharType)),
                    IdentifierCache.findClass("jafun.lang.CharKt"),
                    "==",
                    OperandType.UInt1,
                    true,
                    true,
                    Associativity.INFIXL,
                    40,
                ),
            ),
            2,
        )
    }

    @Test
    fun `Spaceship`() {
        // TODO: should be operator (not sure if it matters)
        testSingleParser(
            ParserJafun.complexIdentifier,
            "<=>4",
            listOf(
                Type.JFMethod(
                    listOf(Type.JFVariableSymbol("param1", OperandType.SInt32)),
                    IdentifierCache.findClass("jafun.test.TestKt"),
                    "<=>",
                    OperandType.SInt32,
                    true,
                ),
            ),
            3,
        )
    }

    @Test
    fun `Simple Identifier`() {
        testSingleParser(
            ParserJafun.complexIdentifier,
            "println()",
            listOf(
                Type.JFMethod(
                    listOf(Type.JFVariableSymbol("param1", Type.JFClass("java.lang.Object"))),
                    IdentifierCache.findClass("jafun.io.ConsoleKt"),
                    "println",
                    OperandType.Unit,
                    true,
                ),
            ),
            7,
        )
    }

    @Test
    fun `Complex Identifier`() {
        testSingleParser(
            ParserJafun.complexIdentifier,
            "java.lang.System.out.println()",
            listOf(
                Type.JFFieldMethod(
                    Type.JFField("out", IdentifierCache.findClass("java.lang.System"), IdentifierCache.findClass("java.io.PrintStream")),
                    Type.JFMethod(
                        listOf(Type.JFVariableSymbol("param1", OperandType.StringType)),
                        IdentifierCache.findClass("java.io.PrintStream"),
                        "println",
                        OperandType.Unit,
                    ),
                ),
            ),
            28,
        )
    }

    @Test
    fun `Simple Variable`() {
        val varSymbol = ParserJafun.symbolMap.newVariableSymbol("abcd", OperandType.SInt32, true)
        testSingleParser(
            ParserJafun.methodLhs(0),
            "abcd+3",
            ASTNode.Variable(varSymbol),
            4,
        )
    }

    @Test
    fun `Simple Variable not found`() {
        ParserJafun.symbolMap.newVariableSymbol("abcd", OperandType.SInt32, true)
        testSingleParserFailed(
            ParserJafun.methodLhs(0),
            "bcd+3",
            "Combinator failed, parser number 1 with error: No single method found",
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
            ASTNode.ValAssignment(Type.JFVariableSymbol("abc", OperandType.SInt32, IdentifierCache, false), ASTNode.IntegerLiteral(5)),
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
            ASTNode.ValAssignment(Type.JFVariableSymbol("abc", OperandType.SInt32, IdentifierCache, false), ASTNode.IntegerLiteral(5)),
            13,
        )
    }

    @Test
    fun `var assignment whitespace`() {
        testSingleParser(
            ParserJafun.initVarAssignment,
            """|var 
               |abc = 
               |5
            """.trimMargin(),
            ASTNode.VarAssignment(Type.JFVariableSymbol("abc", OperandType.SInt32, IdentifierCache, true), ASTNode.IntegerLiteral(5)),
            13,
        )
    }

    @Test
    fun `var re-assignment whitespace`() {
        ParserJafun.symbolMap.newVariableSymbol("abc", OperandType.SInt32, true)
        testSingleParser(
            ParserJafun.varAssignment,
            """|abc = 
               |5
            """.trimMargin(),
            ASTNode.VarAssignment(Type.JFVariableSymbol("abc", OperandType.SInt32, IdentifierCache, true), ASTNode.IntegerLiteral(5)),
            8,
        )
    }

    @Test
    fun `empty function compact`() {
        testSingleParser(
            ParserJafun.function,
            """|fun test(){}""".trimMargin(),
            ASTNode.Function(
                Type.JFMethod(emptyList(), Type.JFClass("Script"), "test", OperandType.Unit, true),
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
                Type.JFMethod(emptyList(), Type.JFClass("Script"), "test", OperandType.Unit, true),
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
        ParserJafun.symbolMap.reset()
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
        ParserJafun.symbolMap.reset()
        assertEquals(
            expected,
            failureMessage,
        )
        assertEquals(source.index, afterIndex)
    }
}
