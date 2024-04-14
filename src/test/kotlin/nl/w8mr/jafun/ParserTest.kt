package nl.w8mr.jafun.nl.w8mr.jafun

import jafun.compiler.Associativity
import jafun.compiler.HasPath
import jafun.compiler.IdentifierCache
import nl.w8mr.jafun.ASTNode
import nl.w8mr.jafun.ASTNode.Expression
import nl.w8mr.jafun.ASTNode.Function
import nl.w8mr.jafun.ASTNode.IntegerLiteral
import nl.w8mr.jafun.ASTNode.StringLiteral
import nl.w8mr.jafun.ASTNode.ValAssignment
import nl.w8mr.jafun.ASTNode.Variable
import nl.w8mr.jafun.IR
import nl.w8mr.jafun.Parser
import nl.w8mr.jafun.Token
import nl.w8mr.jafun.lexer
import kotlin.test.Test
import kotlin.test.assertContentEquals

class ParserTest {
    @Test
    fun helloWorldParser() {
        test(
            "println \"Hello World\"",
            invocation(println, null, s("Hello World")),
        )
    }

    @Test
    fun integerParser() {
        test(
            "println 7",
            invocation(println, null, i(7)),
        )
    }

    @Test
    fun helloWorldNormalParser() {
        test(
            "println(\"Hello World\")",
            invocation(println, null, s("Hello World")),
        )
    }

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
            """java.lang.System.out.println "Hello World"""",
            invocation(
                method(
                    "println",
                    IR.Unit,
                    IR.JFVariableSymbol("param1", IR.StringType, IdentifierCache),
                    parent = IR.JFField(IR.JFClass("java/lang/System"), "java/io/PrintStream", "out"),
                    static = false,
                ),
                IR.JFField(IR.JFClass("java/lang/System"), "java/io/PrintStream", "out"),
                s("Hello World"),
            ),
        )
    }

    @Test
    fun assignment() {
        test(
            """
            |val str = "Hello World"
            |println str""",
            ValAssignment(IR.JFVariableSymbol("str", IR.StringType, IdentifierCache), s("Hello World")),
            invocation(println, null, Variable(IR.JFVariableSymbol("str", IR.StringType, IdentifierCache))),
        )
    }

    @Test
    fun emptyFunction() {
        test(
            """fun test() { }""",
            function(
                method("test", IR.Unit, operator = false),
            ),
        )
    }

    @Test
    fun filledFunction() {
        test(
            "fun test() { println 1 + 2 }",
            function(
                method("test", IR.Unit, operator = false),
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
                method("test", IR.Unit, IR.JFVariableSymbol("a", IR.SInt32, IdentifierCache)),
                invocation(
                    println,
                    null,
                    invocation(plus, null, i(1), Variable(IR.JFVariableSymbol("a", IR.SInt32, IdentifierCache))),
                ),
            ),
        )
    }

    @Test
    fun expressionFunction() {
        test(
            "fun test() { 1 + 2 }",
            function(
                method("test", IR.SInt32, operator = false),
                invocation(plus, null, i(1), i(2)),
            ),
        )
    }

    @Test
    fun functionInFunction() {
        test(
            "fun test() { fun inner() { println 1 + 2 } }",
            function(
                method("test", IR.Unit, operator = false),
                function(
                    method("inner", IR.Unit, operator = false),
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
                method("test", IR.Unit, operator = false),
                invocation(
                    println,
                    null,
                    invocation(plus, null, i(1), i(2)),
                ),
            ),
            invocation(method("test", IR.Unit, operator = false), null),
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
            ASTNode.When(
                null,
                listOf(
                    ASTNode.Invocation(equals, null, listOf(i(1), i(2))) to s("False"),
                    ASTNode.Invocation(equals, null, listOf(i(1), i(1))) to s("True"),
                ),
            ),
        )
    }

    @Test
    fun whenWithInput() {
        test(
            """
            |when (val a = 2) { 
            |    a == 2 -> "False"
            |    a == 1 -> "True"
            |}
            """,
            ASTNode.When(
                ValAssignment(IR.JFVariableSymbol("a", IR.SInt32), i(2)),
                listOf(
                    ASTNode.Invocation(equals, null, listOf(Variable(IR.JFVariableSymbol("a", IR.SInt32)), i(2))) to s("False"),
                    ASTNode.Invocation(equals, null, listOf(Variable(IR.JFVariableSymbol("a", IR.SInt32)), i(1))) to s("True"),
                ),
            ),
        )
    }

    private fun test(
        code: String,
        vararg expressions: Expression,
    ) {
        val input = code.trimMargin()
        val lexed = lexer.parse(input).filter { it !is Token.WS }
        println(lexed)
        val parsed = Parser.parse(lexed)
        println(parsed)
        assertContentEquals(
            expressions.toList(),
            parsed,
        )
    }

    val objectType = IR.JFClass("java/lang/Object")

    private val join =
        method(
            "join",
            IR.StringType,
            IR.StringType,
            IR.StringType,
            parent = IR.JFClass("jafun/io/ConsoleKt"),
            operator = false,
        )

    private val println =
        method(
            "println",
            IR.Unit,
            objectType,
            parent = IR.JFClass("jafun/io/ConsoleKt"),
            operator = false,
        )

    private val plus =
        method(
            "+",
            IR.SInt32,
            IR.SInt32,
            IR.SInt32,
            associativity = Associativity.INFIXL,
            precedence = 100,
            parent = IR.JFClass("jafun/lang/IntKt"),
            operator = true,
        )

    private val equals =
        method(
            "==",
            IR.UInt1,
            IR.SInt32,
            IR.SInt32,
            associativity = Associativity.INFIXL,
            precedence = 40,
            parent = IR.JFClass("jafun/lang/IntKt"),
            operator = false, // TODO: check
        )

    private fun method(
        name: String,
        returnType: IR.OperandType<*>,
        vararg parameters: IR.JFVariableSymbol,
        static: Boolean = true,
        associativity: Associativity = if (parameters.isEmpty()) Associativity.SOLO else Associativity.PREFIX,
        precedence: Int = 10,
        parent: HasPath = IR.JFClass("Script"),
    ) = IR.JFMethod(
        parameters.toList(),
        parent,
        name,
        returnType,
        static,
        false,
        associativity,
        precedence,
    )

    private fun method(
        name: String,
        returnType: IR.OperandType<*>,
        vararg parameters: IR.OperandType<*>,
        static: Boolean = true,
        associativity: Associativity = if (parameters.isEmpty()) Associativity.SOLO else Associativity.PREFIX,
        precedence: Int = 10,
        parent: HasPath = IR.JFClass("Script"),
        operator: Boolean,
    ) = IR.JFMethod(
        parameters.toList().mapIndexed { i, type -> IR.JFVariableSymbol("param${i + 1}", type, IdentifierCache) },
        parent,
        name,
        returnType,
        static,
        operator,
        associativity,
        precedence,
    )

    private fun method(
        name: String,
        returnType: IR.OperandType<*>,
        static: Boolean = true,
        associativity: Associativity = Associativity.SOLO,
        precedence: Int = 10,
        parent: HasPath = IR.JFClass("Script"),
        operator: Boolean,
    ) = IR.JFMethod(
        emptyList(),
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
        method: IR.JFMethod,
        field: IR.JFField?,
        vararg parameters: Expression,
    ) = ASTNode.Invocation(
        method,
        field,
        parameters.toList(),
    )

    private fun function(
        method: IR.JFMethod,
        vararg expressions: Expression,
    ) = Function(method, expressions.toList())
}
