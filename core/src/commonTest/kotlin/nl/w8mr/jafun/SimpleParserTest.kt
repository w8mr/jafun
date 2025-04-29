package nl.w8mr.jafun.nl.w8mr.jafun

import nl.w8mr.jafun.OperandType
import nl.w8mr.jafun.ParserJafun
import nl.w8mr.jafun.Phase1Parser
import nl.w8mr.jafun.Type
import nl.w8mr.jafun.Type.JFClass
import nl.w8mr.jafun.Type.JFField
import nl.w8mr.jafun.Type.JFFieldMethod
import nl.w8mr.jafun.Type.JFMethod
import nl.w8mr.jafun.Type.JFPackage
import nl.w8mr.jafun.Type.JFVariableSymbol
import nl.w8mr.jafun.compiler.Associativity
import nl.w8mr.jafun.compiler.ExpressionNode
import nl.w8mr.jafun.compiler.IdentifierCache
import nl.w8mr.parsek.ListSource
import nl.w8mr.parsek.Parser
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.fail

class SimpleParserTest {
    @Test
    fun `Spaceship`() {
        // TODO: should be operator (not sure if it matters)
        testSingleParser(
            ParserJafun.complexIdentifier,
            "<=>4",
            listOf(
                JFMethod(
                    listOf(JFVariableSymbol("param1", OperandType.SInt32)),
                    IdentifierCache.findClass("jafun.test.TestKt"),
                    "<=>",
                    OperandType.SInt32,
                    true,
                ),
            ),
            1,
        )
    }

    @Test
    fun `Simple Identifier`() {
        testSingleParser(
            ParserJafun.complexIdentifier,
            "println()",
            listOf(
                JFMethod(
                    listOf(JFVariableSymbol("param1", JFClass("java.lang.Object"))),
                    IdentifierCache.findClass("jafun.io.ConsoleKt"),
                    "println",
                    OperandType.Unit,
                    true,
                ),
            ),
            1,
        )
    }

    @Test
    fun `Complex Identifier`() {
        testSingleParser(
            ParserJafun.complexIdentifier,
            "java.lang.System.out.println()",
            listOf(
                JFFieldMethod(
                    JFField(
                        "out",
                        IdentifierCache.findClass("java.lang.System"),
                        IdentifierCache.findClass("java.io.PrintStream")
                    ),
                    JFMethod(
                        listOf(JFVariableSymbol("param1", OperandType.StringType)),
                        IdentifierCache.findClass("java.io.PrintStream"),
                        "println",
                        OperandType.Unit,
                    ),
                ),
            ),
            9,
        )
    }

    @Test
    fun `Simple Variable`() {
        val varSymbol = ParserJafun.symbolMap.newVariableSymbol("abcd", OperandType.SInt32, true)
        testSingleParser(
            ParserJafun.methodLhs(0),
            "abcd+3",
            ExpressionNode.Variable(varSymbol),
            1,
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
            ParserJafun.curlyBlock,
            "{}",
            ExpressionNode.ExpressionList(emptyList()),
            1,
        )
    }

    @Test
    fun `Empty block whitespace`() {
        testSingleParser(
            ParserJafun.curlyBlock,
            """|{  
               |}
            """,
            ExpressionNode.ExpressionList(emptyList()),
            1,
        )
    }

    @Test
    fun `val assignment compact`() {
        testSingleParser(
            ParserJafun.initValAssignment,
            """|val abc=5""".trimMargin(),
            ExpressionNode.ValAssignment(JFVariableSymbol("abc", OperandType.SInt32, IdentifierCache, false), ExpressionNode.IntegerLiteral(5)),
            5,
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
            ExpressionNode.ValAssignment(JFVariableSymbol("abc", OperandType.SInt32, IdentifierCache, false), ExpressionNode.IntegerLiteral(5)),
            9,
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
            ExpressionNode.VarAssignment(JFVariableSymbol("abc", OperandType.SInt32, IdentifierCache, true), ExpressionNode.IntegerLiteral(5)),
            9,
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
            ExpressionNode.VarAssignment(JFVariableSymbol("abc", OperandType.SInt32, IdentifierCache, true), ExpressionNode.IntegerLiteral(5)),
            6,
        )
    }

    @Test
    fun `empty function compact`() {
        testSingleParser(
            ParserJafun.function,
            """|fun test(){}""".trimMargin(),
            ExpressionNode.Function(
                JFMethod(emptyList(), JFClass("Script"), "test", OperandType.Unit, true),
                emptyList(),
            ),
            6,
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
            ExpressionNode.Function(
                JFMethod(emptyList(), JFClass("Script"), "test", OperandType.Unit, true),
                emptyList(),
            ),
            13,
        )
    }

    @Test
    fun `string interpolation text only`() {
        testSingleParser(
            ParserJafun.stringLiteral_term,
            "\"abc\"",
            ExpressionNode.StringLiteral("abc"),
            3,
        )
    }

    @Test
    fun `string interpolation simple variable`() {
        val numVar = ParserJafun.symbolMap.newVariableSymbol("num", OperandType.SInt32, false)
        testSingleParser(
            ParserJafun.stringLiteral_term,
            "\"abc\$num\"",
            ExpressionNode.StringTemplate(listOf(


                ExpressionNode.StringLiteral("abc"),
                ExpressionNode.Variable(numVar)
            )),
            5,
        )
    }

    @Test
    fun `string interpolation simple expression`() {
        testSingleParser(
            ParserJafun.stringLiteral_term,
            "\"abc\${1}\"",
            ExpressionNode.StringTemplate(listOf(
                ExpressionNode.StringLiteral("abc"),
                ExpressionNode.IntegerLiteral(1)
            )),
            5,
        )
    }

    @Test
    fun `string interpolation expression`() {
        testSingleParser(
            ParserJafun.stringLiteral_term,
            "\"abc\${21 + 21}\"",
            ExpressionNode.StringTemplate(listOf(
                ExpressionNode.StringLiteral("abc"),
                ExpressionNode.Invocation(
                    JFMethod(listOf(
                        JFVariableSymbol("param1", OperandType.SInt32, IdentifierCache),
                        JFVariableSymbol("param2", OperandType.SInt32, IdentifierCache)),
                        JFClass("IntKt", JFPackage("lang", JFPackage("jafun"))),
                        "+",
                        OperandType.SInt32, true, true, Associativity.INFIXL, 100), null, listOf(ExpressionNode.IntegerLiteral(21), ExpressionNode.IntegerLiteral(21)))
            )),
            5,
        )
    }

    private fun <R> testSingleParser(
        parser: Parser<ExpressionNode.Phase1Token, R>,
        input: String,
        expected: R,
        afterIndex: Int = 1,
    ) {
        val phase1 = Phase1Parser.parse(input.trimMargin()).first ?: fail("Phase1 not successful")
        val source = ListSource(phase1)
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
        assertEquals(afterIndex, source.index)
    }

    private fun <R> testSingleParserFailed(
        parser: Parser<ExpressionNode.Phase1Token, R>,
        input: String,
        expected: String,
        afterIndex: Int = 1,
    ) {
        val phase1 = Phase1Parser.parse(input.trimMargin()).first ?: fail("Phase1 not successful")
        val source = ListSource(phase1)

        val failureMessage = (parser.apply(source) as? Parser.Failure ?: fail("Parse not failed")).message
        ParserJafun.symbolMap.reset()
        assertEquals(
            expected,
            failureMessage,
        )
        assertEquals(source.index, afterIndex)
    }
}
