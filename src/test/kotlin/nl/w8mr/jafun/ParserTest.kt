package nl.w8mr.jafun.nl.w8mr.jafun

import jafun.compiler.Associativity
import jafun.compiler.HasPath
import jafun.compiler.IntegerType
import jafun.compiler.JFClass
import jafun.compiler.JFField
import jafun.compiler.JFMethod
import jafun.compiler.JFVariableSymbol
import jafun.compiler.TypeSig
import jafun.compiler.VoidType
import nl.w8mr.jafun.ASTNode.*
import nl.w8mr.jafun.Parser
import nl.w8mr.jafun.Token
import nl.w8mr.jafun.lexer
import kotlin.test.Test
import kotlin.test.assertContentEquals

class ParserTest {
    @Test
    fun helloWorldParser() {
        test("println \"Hello World\"",
            staticInvocation(println, s("Hello World")))
    }

    @Test
    fun integerParser() {
        test("println 7",
            staticInvocation(println, i(7)))
    }

    @Test
    fun helloWorldNormalParser() {
        test("println(\"Hello World\")",
            staticInvocation(println, s("Hello World")))
    }

    @Test
    fun helloWorldTwoLevelParser() {
        test("""println(join("Hello","World"))""",
            staticInvocation(println, staticInvocation(join, s("Hello"), s("World"))))
    }

    @Test
    fun twoArgFunParser() {
        test("""join("Hello", "World")""",
            staticInvocation(join, s("Hello"), s("World")))
    }

    @Test
    fun complexHelloWorldParser() {
        test("""java.lang.System.out.println "Hello World"""",
            staticFieldInvocation(
                method("println", VoidType, JFVariableSymbol("param1", stringType),
                    parent = JFField(JFClass("java/lang/System"), "java/io/PrintStream", "out"),
                    static = false),
                JFField(JFClass("java/lang/System"),"java/io/PrintStream", "out"), s("Hello World")))
    }

    @Test
    fun assignment() {
        test("""
            |val str = "Hello World"
            |println str""",
            ValAssignment(JFVariableSymbol("str", stringType), s("Hello World")),
            staticInvocation(println, Variable(JFVariableSymbol("str", stringType)))
        )
    }

    @Test
    fun emptyFunction() {
        test("""fun test() { }""",
            function(method("test", VoidType)
            )
        )
    }

    @Test
    fun filledFunction() {
        test("fun test() { println 1 + 2 }",
            function(
                 method("test", VoidType),
                 staticInvocation(
                     println,
                     staticInvocation(plus, i(1), i(2))
                 )
            )
        )
    }

    @Test
    fun parameterFunction() {
        test("fun test(a: Int) { println 1 + a }",
            function(
                method("test", VoidType, JFVariableSymbol("a", IntegerType)),
                staticInvocation(
                    println,
                    staticInvocation(plus, i(1), Variable(JFVariableSymbol("a", IntegerType)))
                )
            )
        )
    }

    @Test
    fun expressionFunction() {
        test("fun test() { 1 + 2 }",
            function(method("test", VoidType),
                        staticInvocation(plus, i(1), i(2))
                                )
                            )
    }



    @Test
    fun functionInFunction() {
        test("fun test() { fun inner() { println 1 + 2 } }",
            function(
                method("test", VoidType),
                function(method("inner", VoidType),
                    staticInvocation(
                        println,
                        staticInvocation(plus, i(1), i(2))
                    )
                )
            )
        )
    }

    @Test
    fun functionAndInvocation() {
        test("""
            |fun test() { println 1 + 2 }
            |test""",
            function(
                method("test", VoidType),
                staticInvocation(
                    println,
                    staticInvocation(plus, i(1), i(2))
                )
            ),
            staticInvocation(method("test", VoidType))
        )
    }

    private fun test(code: String, vararg expressions: Expression) {
        val input = code.trimMargin()
        val lexed = lexer.parse(input).filter { it !is Token.WS }
        println(lexed)
        val parsed = Parser.parse(lexed)
        println(parsed)
        assertContentEquals(
            expressions.toList(),
            parsed
        )
    }

    val stringType = JFClass("java/lang/String")
    val objectType = JFClass("java/lang/Object")

    private val join = method("join", stringType, stringType, stringType,
        parent = JFClass("jafun/io/ConsoleKt"))

    private val println = method("println", VoidType, objectType,
        parent = JFClass("jafun/io/ConsoleKt"))

    private val plus = method("+", IntegerType, IntegerType, IntegerType,
        associativity = Associativity.INFIXL,
        precedence = 20,
        parent = JFClass("jafun/lang/IntKt"))

    private fun method(
        name: String,
        returnType: TypeSig,
        vararg parameters: JFVariableSymbol,
        static: Boolean = true,
        associativity: Associativity = if (parameters.isEmpty()) Associativity.SOLO else Associativity.PREFIX,
        precedence: Int = 10,
        parent: HasPath = JFClass("HelloWorld")
    ) = JFMethod(parameters.map(JFVariableSymbol::type), parameters.toList().map(::ParameterDef), parent, name, returnType,
        static, associativity, precedence)

    private fun method(
        name: String,
        returnType: TypeSig,
        vararg parameters: TypeSig,
        static: Boolean = true,
        associativity: Associativity = if (parameters.isEmpty()) Associativity.SOLO else Associativity.PREFIX,
        precedence: Int = 10,
        parent: HasPath = JFClass("HelloWorld")
    ) = JFMethod(parameters.toList(), parameters.toList().mapIndexed { i, type -> ParameterDef(JFVariableSymbol("param${i+1}", type))}, parent, name, returnType,
        static, associativity, precedence)

    private fun method(
        name: String,
        returnType: TypeSig,
        static: Boolean = true,
        associativity: Associativity = Associativity.SOLO,
        precedence: Int = 10,
        parent: HasPath = JFClass("HelloWorld")
    ) = JFMethod(emptyList(), emptyList(), parent, name, returnType,
        static, associativity, precedence)

    private fun i(integer: Int) = IntegerLiteral(integer)
    private fun s(string: String) = StringLiteral(string)

    private fun staticInvocation(method: JFMethod, vararg parameters: Expression) = StaticInvocation(
        method,
        parameters.toList()
    )

    private fun staticFieldInvocation(method: JFMethod, field: JFField, vararg parameters: Expression) = StaticFieldInvocation(
        method,
        field,
        parameters.toList()
    )

    private fun function(method: JFMethod, vararg expressions: Expression) =
        Function(method, expressions.toList())


}