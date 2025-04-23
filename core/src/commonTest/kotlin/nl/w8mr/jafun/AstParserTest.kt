package nl.w8mr.jafun.nl.w8mr.jafun

import nl.w8mr.jafun.compiler.ExpressionNode
import nl.w8mr.jafun.compiler.ExpressionNode.Function
import nl.w8mr.jafun.compiler.ExpressionNode.IntegerLiteral
import nl.w8mr.jafun.compiler.ExpressionNode.StringLiteral
import nl.w8mr.jafun.compiler.ExpressionNode.ValAssignment
import nl.w8mr.jafun.compiler.ExpressionNode.Variable
import nl.w8mr.jafun.OperandType
import nl.w8mr.jafun.ParserJafun
import nl.w8mr.jafun.Type
import nl.w8mr.jafun.Type.MethodParent
import nl.w8mr.jafun.compiler.Associativity
import nl.w8mr.jafun.compiler.IdentifierCache
import nl.w8mr.jafun.debug.prettyPrint
import nl.w8mr.parsek.Parser
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

class AstParserTest {
    @Test
    fun helloWorldTwoLevelParser() {
        test(
            """println(join("Hello","World"))""",
            invocation(println, null, invocation(join, null, s("Hello"), s("World"))),
        )
    }

    @Test
    fun twoArgFunParser() {
        test(
            """join("Hello", "World")""",
            invocation(join, null, s("Hello"), s("World")),
        )
    }

    @Test
    fun complexHelloWorldParser() {
        test(
            """System.out.println "Hello World"""",
            invocation(
                method(
                    "println",
                    OperandType.Unit,
                    OperandType.StringType,
                    parent = IdentifierCache.findClass("java.io.PrintStream"),
                    static = false,
                ),
                Type.JFField("out", IdentifierCache.findClass("java.lang.System"), IdentifierCache.findClass("java.io.PrintStream")),
                s("Hello World"),
            ),
        )
    }

    @Test
    fun complexHelloWorldParserWithParentheses() {
        test(
            """System.out.println("Hello World")""",
            invocation(
                method(
                    "println",
                    OperandType.Unit,
                    OperandType.StringType,
                    parent = IdentifierCache.findClass("java.io.PrintStream"),
                    static = false,
                ),
                Type.JFField("out", IdentifierCache.findClass("java.lang.System"), IdentifierCache.findClass("java.io.PrintStream")),
                s("Hello World"),
            ),
        )
    }

    @Test
    fun complexHelloWorldParserFullyQualified() {
        test(
            """java.lang.System.out.println "Hello World"""",
            invocation(
                method(
                    "println",
                    OperandType.Unit,
                    OperandType.StringType,
                    parent = IdentifierCache.findClass("java.io.PrintStream"),
                    static = false,
                ),
                Type.JFField("out", IdentifierCache.findClass("java.lang.System"), IdentifierCache.findClass("java.io.PrintStream")),
                s("Hello World"),
            ),
        )
    }

    @Test
    fun emptyFunction() {
        test(
            """fun test() { }""",
            function(
                method("test", OperandType.Unit, operator = false),
            ),
        )
    }

    @Test
    fun filledFunction() {
        test(
            "fun test() { println 1 + 2 }",
            function(
                method("test", OperandType.Unit, operator = false),
                invocation(
                    println,
                    null,
                    invocation(plus, null, i(1), i(2)),
                ),
            ),
        )
    }

    @Test
    fun parameterFunction() {
        test(
            "fun test(a: Int) { println 1 + a }",
            function(
                method("test", OperandType.Unit, Type.JFVariableSymbol("a", OperandType.SInt32, IdentifierCache)),
                invocation(
                    println,
                    null,
                    invocation(plus, null, i(1), Variable(Type.JFVariableSymbol("a", OperandType.SInt32, IdentifierCache))),
                ),
            ),
        )
    }

    @Test
    fun expressionFunction() {
        test(
            "fun test() { 1 + 2 }",
            function(
                method("test", OperandType.SInt32, operator = false),
                invocation(plus, null, i(1), i(2)),
            ),
        )
    }

    @Test
    fun functionInFunction() {
        test(
            "fun test() { fun inner() { println 1 + 2 } }",
            function(
                method("test", OperandType.Unit, operator = false),
                function(
                    method("inner", OperandType.Unit, operator = false),
                    invocation(
                        println,
                        null,
                        invocation(plus, null, i(1), i(2)),
                    ),
                ),
            ),
        )
    }

    @Test
    fun functionAndInvocation() {
        test(
            """
            |fun test() { println 1 + 2 }
            |test""",
            function(
                method("test", OperandType.Unit, operator = false),
                invocation(
                    println,
                    null,
                    invocation(plus, null, i(1), i(2)),
                ),
            ),
            invocation(method("test", OperandType.Unit, operator = false), null),
        )
    }

    @Test
    fun simpleWhen() {
        test(
            """
            |when { 
            |    1 == 2 -> "False"
            |    1 == 1 -> "True"
            |}
            """,
            ExpressionNode.When(
                null,
                listOf(
                    ExpressionNode.Invocation(equals, null, listOf(i(1), i(2))) to s("False"),
                    ExpressionNode.Invocation(equals, null, listOf(i(1), i(1))) to s("True"),
                ),
            ),
        )
    }

    @Test
    fun whenWithInput() {
        test(
            """
            |when (val a = 2) { 
            |    2 -> "False"
            |    1 -> "True"
            |}
            """,
            ExpressionNode.When(
                ValAssignment(Type.JFVariableSymbol("a", OperandType.SInt32), i(2)),
                listOf(
                    IntegerLiteral(2) to s("False"),
                    IntegerLiteral(1) to s("True"),
                ),
            ),
        )
    }

    private fun test(
        code: String,
        vararg expressions: ExpressionNode.Phase2_3Expression,
    ) {
        val parseResult = ParserJafun.parse(code.trimMargin())
        when (parseResult.second) {
            is Parser.Failure<*> -> {
                println(parseResult.second)
                error("Parser failed")
            }

            is Parser.Success<*> -> {
                val parsed = parseResult.first
                println("PARSED: \n${parsed!!.joinToString("\n") { it.prettyPrint() }}")
                assertEquals(
                    ExpressionNode.ExpressionList(expressions.toList()).prettyPrint(),
                    parsed.let { ExpressionNode.ExpressionList(it).prettyPrint() },
                )
                assertContentEquals(
                    expressions.toList(),
                    parsed,
                )
            }
        }
    }

    val objectType = Type.JFClass("java.lang.Object")

    private val join =
        method(
            "join",
            OperandType.StringType,
            OperandType.StringType,
            OperandType.StringType,
            parent = IdentifierCache.findClass("jafun.test.TestKt"),
            operator = false,
        )

    private val println =
        method(
            "println",
            OperandType.Unit,
            objectType,
            parent = IdentifierCache.findClass("jafun.io.ConsoleKt"),
            operator = false,
        )

    private val plus =
        method(
            "+",
            OperandType.SInt32,
            OperandType.SInt32,
            OperandType.SInt32,
            associativity = Associativity.INFIXL,
            precedence = 100,
            parent = IdentifierCache.findClass("jafun.lang.IntKt"),
            operator = true,
        )

    private val equals =
        method(
            "==",
            OperandType.UInt1,
            OperandType.SInt32,
            OperandType.SInt32,
            associativity = Associativity.INFIXL,
            precedence = 40,
            parent = IdentifierCache.findClass("jafun.lang.IntKt"),
            operator = true,
        )

    private fun method(
        name: String,
        returnType: OperandType<*>,
        vararg parameters: Type.JFVariableSymbol,
        static: Boolean = true,
        associativity: Associativity = Associativity.PREFIX,
        precedence: Int = 10,
        parent: MethodParent = Type.JFClass("Script"),
    ) = Type.JFMethod(
        parameters.toList(),
        parent,
        name,
        returnType,
        static,
        associativity = associativity,
        precedence = precedence,
    )

    private fun method(
        name: String,
        returnType: OperandType<*>,
        vararg parameters: OperandType<*>,
        static: Boolean = true,
        associativity: Associativity = Associativity.PREFIX,
        precedence: Int = 10,
        parent: MethodParent = Type.JFClass("Script"),
        operator: Boolean = false,
    ) = Type.JFMethod(
        parameters.toList().mapIndexed { i, type -> Type.JFVariableSymbol("param${i + 1}", type, IdentifierCache) },
        parent,
        name,
        returnType,
        static,
        operator,
        associativity,
        precedence,
    )

    private fun i(integer: Int) = IntegerLiteral(integer)

    private fun s(string: String) = StringLiteral(string)

    private fun invocation(
        method: Type.JFMethod,
        field: Type.JFField?,
        vararg parameters: ExpressionNode.Phase2_3Expression,
    ) = ExpressionNode.Invocation(
        method,
        field,
        parameters.toList(),
    )

    private fun function(
        method: Type.JFMethod,
        vararg expressions: ExpressionNode.Phase2_3Expression,
    ) = Function(method, expressions.toList())
}
