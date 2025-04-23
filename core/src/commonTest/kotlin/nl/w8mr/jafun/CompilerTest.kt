package nl.w8mr.jafun.nl.w8mr.jafun

import nl.w8mr.jafun.IRBuilder
import nl.w8mr.jafun.OperandType
import nl.w8mr.jafun.Type
import nl.w8mr.jafun.Type.MethodParent
import nl.w8mr.jafun.compiler.Associativity
import nl.w8mr.jafun.compiler.Compiler
import nl.w8mr.jafun.compiler.Compiler.PluginType.JVMIR
import nl.w8mr.jafun.compiler.Compiler.PluginType.Phase3
import nl.w8mr.jafun.compiler.Compiler.PluginType.Phase2
import nl.w8mr.jafun.compiler.ExpressionNode
import nl.w8mr.jafun.compiler.ExpressionNode.IntegerLiteral
import nl.w8mr.jafun.compiler.ExpressionNode.StringLiteral
import nl.w8mr.jafun.compiler.ExpressionNode.ValAssignment
import nl.w8mr.jafun.compiler.IdentifierCache
import nl.w8mr.jafun.debug.prettyPrint
import nl.w8mr.jafun.debug.print
import nl.w8mr.kasmine.ClassBuilder
import nl.w8mr.kasmine.ClassDef
import nl.w8mr.kasmine.classBuilder
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

expect fun compareDecompiled(
    expected: ByteArray,
    bytecode: (ClassBuilder.ClassDSL.DSL.() -> Unit)?,
    expectedResult: String,
    actualResult: String,
    actualBytes: ByteArray
)

expect fun writeFile(
    className: String,
    bytes: ByteArray,
)

expect fun runAndAssertOutput(
    actualBytes: ByteArray,
    className: String,
    methodName: String,
    params: Array<String>?,
    expectedOutput: String
): String


class CompilerTest {
    companion object {
        class TestContext() {
            var code: String? = null
            var bytecode: ByteArray? = null
            var mainParams: Array<String>? = null
            var phase2Expected: List<ExpressionNode.Phase2Expression>? = null
            var expectedOutput: String? = null
        }

        fun test(block: TestDSL.() -> Unit) {
            val files = mutableMapOf<String, TestContext>()
            val runner = TestDSLImpl(files)
            block.invoke(runner)

            val compiler = Compiler()

            val phase2Plugin = compiler.registerPlugin(Phase2, Phase2Plugin())
            val phase3Plugin = compiler.registerPlugin(Phase3, Phase3Plugin())
            val jvmirPlugin = compiler.registerPlugin(JVMIR, JVMIRPlugin())

            for ((path, file) in files) {
                val className = "Script"
                val methodName = "main"
                val actualBytes = compiler.compile(file.code ?: error("No code set for $path"), className, methodName)

                if (file.phase2Expected != null) {
                    if (file.phase2Expected != phase2Plugin.phase2) {
                        assertEquals(
                            file.phase2Expected?.map { it.prettyPrint() },
                            phase2Plugin.phase2?.map { it.prettyPrint() })
                    } else {
                        println("Phase2:\n ${phase2Plugin.phase2?.joinToString("\n") { it.prettyPrint() }}")

                    }
                }
                println("Phase3:\n ${phase3Plugin.phase3?.prettyPrint()}")
                println("IR:\n ${jvmirPlugin.jvmir?.print()}")

                writeFile(className, actualBytes)
                val result = runAndAssertOutput(
                    actualBytes,
                    className,
                    methodName,
                    file.mainParams,
                    file.expectedOutput ?: TODO("Handle no expected case")
                )

                val expectedBytes = file.bytecode
                if (expectedBytes != null) {
                    if ((expectedBytes.zip(actualBytes).any { it.first != it.second })) {
                        compareDecompiled(expectedBytes, null /* change to boolean */, result, result, actualBytes)
                    } else {
                        assertContentEquals(expectedBytes, actualBytes)
                    }
                }

            }
        }

        interface TestDSL {
            fun file(path: String = "Script", block: TestFileDSL.() -> Unit)
        }

        class TestDSLImpl(val files: MutableMap<String, TestContext>) : TestDSL {
            override fun file(path: String, block: TestFileDSL.() -> Unit) {
                val context = TestContext()
                val runner = TestFileDSLImpl(context)
                block.invoke(runner)
                files[path] = context
            }

        }

        interface TestFileDSL {
            var code: String?
            var expectedOutput: String?
            fun params(vararg params: String)

            fun jvmIr(block: ClassBuilder.ClassDSL.DSL.() -> Unit)
            fun phase2(block: Phase2Builder.() -> Unit)

        }

        class TestFileDSLImpl(val context: TestContext) : TestFileDSL {
            override var code by context::code
            override var expectedOutput by context::expectedOutput
            override fun params(vararg params: String) {
                context.mainParams = params.toList().toTypedArray()
            }

            override fun jvmIr(block: ClassBuilder.ClassDSL.DSL.() -> Unit) {
                context.bytecode = classBuilder(block).write()
            }

            override fun phase2(block: Phase2Builder.() -> Unit) {
                context.phase2Expected = Phase2Builder().apply(block).expressionsList
            }
        }

        class Phase2Builder {
            val expressionsList: MutableList<ExpressionNode.Phase2Expression> = mutableListOf()
            val objectType = Type.JFClass("java.lang.Object")

            val join =
                method(
                    "join",
                    OperandType.StringType,
                    OperandType.StringType,
                    OperandType.StringType,
                    parent = IdentifierCache.findClass("jafun.test.TestKt"),
                    operator = false,
                )

            val println =
                method(
                    "println",
                    OperandType.Unit,
                    objectType,
                    parent = IdentifierCache.findClass("jafun.io.ConsoleKt"),
                    operator = false,
                )

            val printStreamPrintln = method(
                "println",
                OperandType.Unit,
                OperandType.StringType,
                parent = IdentifierCache.findClass("java.io.PrintStream"),
                static = false,
            )

            val systemOut = Type.JFField(
                "out",
                IdentifierCache.findClass("java.lang.System"),
                IdentifierCache.findClass("java.io.PrintStream")
            )

            val plus =
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

            val times =
                method(
                    "*",
                    OperandType.SInt32,
                    OperandType.SInt32,
                    OperandType.SInt32,
                    associativity = Associativity.INFIXL,
                    precedence = 110,
                    parent = IdentifierCache.findClass("jafun.lang.IntKt"),
                    operator = true,
                )
            val equals =
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

            fun method(
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

            fun method(
                name: String,
                returnType: OperandType<*>,
                vararg parameters: OperandType<*>,
                static: Boolean = true,
                associativity: Associativity = Associativity.PREFIX,
                precedence: Int = 10,
                parent: MethodParent = Type.JFClass("Script"),
                operator: Boolean = false,
            ) = Type.JFMethod(
                parameters.toList()
                    .mapIndexed { i, type -> Type.JFVariableSymbol("param${i + 1}", type, IdentifierCache) },
                parent,
                name,
                returnType,
                static,
                operator,
                associativity,
                precedence,
            )

            fun i(integer: Int) = IntegerLiteral(integer)

            fun s(string: String) = StringLiteral(string)

            fun b(boolean: Boolean) = ExpressionNode.BooleanLiteral(boolean)

            operator fun ExpressionNode.Phase2Expression.unaryPlus() {
                expressionsList.add(this)
            }

            fun invocation(
                method: Type.JFMethod,
                vararg parameters: ExpressionNode.Phase2_3Expression,
            ) = ExpressionNode.Invocation(method, null, parameters.toList())


            fun invocation(
                method: Type.JFMethod,
                field: Type.JFField,
                vararg parameters: ExpressionNode.Phase2_3Expression,
            ) = ExpressionNode.Invocation(method, field, parameters.toList())

            fun function(
                method: Type.JFMethod,
                block: Phase2Builder.() -> Unit = {},
            ) = ExpressionNode.Function(method, Phase2Builder().apply(block).expressionsList)

            fun valAssignment(
                variable: Type.JFVariableSymbol,
                expression: ExpressionNode.Phase2_3Expression,
            ) = ValAssignment(variable, expression)

            fun symbol(name: String, type: OperandType<*>) = Type.JFVariableSymbol(name, type, IdentifierCache)
            fun variable(symbol: Type.JFVariableSymbol) = ExpressionNode.Variable(symbol)

            fun `when`(vararg matches: Pair<ExpressionNode.Phase2_3Expression, ExpressionNode.Phase2_3Expression>) =
                ExpressionNode.When(null, matches.toList())

            fun `when`(
                subject: ExpressionNode.Phase2_3Expression,
                vararg matches: Pair<ExpressionNode.Phase2_3Expression, ExpressionNode.Phase2_3Expression>
            ) =
                ExpressionNode.When(subject, matches.toList())
        }


        data class Phase2Plugin(var phase2: List<ExpressionNode.Phase2Expression>? = null) : Compiler.Phase2Plugin {
            override fun handle(input: List<ExpressionNode.Phase2Expression>): List<ExpressionNode.Phase2Expression> {
                phase2 = input
                return input
            }
        }

        data class Phase3Plugin(var phase3: IRBuilder.ClassContext? = null) : Compiler.Phase3Plugin {
            override fun handle(input: IRBuilder.ClassContext): IRBuilder.ClassContext {
                phase3 = input
                return input
            }
        }

        data class JVMIRPlugin(var jvmir: ClassDef? = null) : Compiler.JVMIRPlugin {
            override fun handle(input: ClassDef): ClassDef {
                jvmir = input
                return input
            }
        }
    }

    @Test
    fun helloWorldParens() {
        test {
            file {

                code = """
                    println("Hello World")"""
                phase2 {
                    +invocation(println, s("Hello World"))
                }
                expectedOutput = "Hello World\n"
                jvmIr {
                    name = "Script"
                    method {
                        name = "main"
                        signature = "([Ljava/lang/String;)V"
                        loadConstant("Hello World")
                        invokeStatic("jafun/io/ConsoleKt", "println", "(Ljava/lang/Object;)V")
                        `return`()
                    }
                }
            }
        }
    }

    @Test
    fun helloWorldNoParens() {
        test {
            file {

                code = """
                    println "Hello World""""
                phase2 {
                    +invocation(println, s("Hello World"))
                }
                expectedOutput = "Hello World\n"
                jvmIr {
                    name = "Script"
                    method {
                        name = "main"
                        signature = "([Ljava/lang/String;)V"
                        loadConstant("Hello World")
                        invokeStatic("jafun/io/ConsoleKt", "println", "(Ljava/lang/Object;)V")
                        `return`()
                    }
                }
            }
        }
    }

    @Test
    fun helloWorldParensCompile() {
        test {
            file {
                code = """
                    println(join("Hello", "World"))"""
                phase2 {
                    +invocation(println, invocation(join, s("Hello"), s("World")))
                }
                expectedOutput = "Hello World\n"
                jvmIr {
                    name = "Script"
                    method {
                        name = "main"
                        signature = "([Ljava/lang/String;)V"
                        loadConstant("Hello")
                        loadConstant("World")
                        invokeStatic(
                            "jafun/test/TestKt", "join", "(Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;"
                        )
                        invokeStatic("jafun/io/ConsoleKt", "println", "(Ljava/lang/Object;)V")
                        `return`()
                    }
                }
            }
        }
    }

    @Test
    fun helloWorldDoubleNoParens() {
        test {
            file {
                code = """
            println reverse "Hello World""""
                expectedOutput = "dlroW olleH\n"
                jvmIr {
                    name = "Script"
                    method {
                        name = "main"
                        signature = "([Ljava/lang/String;)V"
                        loadConstant("Hello World")
                        invokeStatic("jafun/test/TestKt", "reverse", "(Ljava/lang/String;)Ljava/lang/String;")
                        invokeStatic("jafun/io/ConsoleKt", "println", "(Ljava/lang/Object;)V")
                        `return`()
                    }
                }
            }
        }
    }

    @Test
    fun helloWorldJoinCompile() {
        test {
            file {
                code = """
            println join("Hello", "World")"""
                expectedOutput = "Hello World\n"
                jvmIr {
                    name = "Script"
                    method {
                        name = "main"
                        signature = "([Ljava/lang/String;)V"
                        loadConstant("Hello")
                        loadConstant("World")
                        invokeStatic(
                            "jafun/test/TestKt", "join", "(Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;"
                        )
                        invokeStatic("jafun/io/ConsoleKt", "println", "(Ljava/lang/Object;)V")
                        `return`()
                    }
                }
            }
        }
    }

    @Test
    fun helloWorldJoinVariableCompile() {
        test {
            file {
                code = """
            val str1 = join("Hello", "World")
            println str1"""
                expectedOutput = "Hello World\n"
                jvmIr {
                    name = "Script"
                    method {
                        name = "main"
                        signature = "([Ljava/lang/String;)V"
                        loadConstant("Hello")
                        loadConstant("World")
                        invokeStatic(
                            "jafun/test/TestKt", "join", "(Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;"
                        )
                        astore("str1")
                        aload("str1")
                        invokeStatic("jafun/io/ConsoleKt", "println", "(Ljava/lang/Object;)V")
                        `return`()
                    }
                }
            }
        }
    }

    @Test
    fun helloWorldTripleLevelCompile() {
        test {
            file {
                code = """
            println join(join("Hello World", "1"), join("2", "3"))"""
                expectedOutput = "Hello World 1 2 3\n"
                jvmIr {
                    name = "Script"
                    method {
                        name = "main"
                        signature = "([Ljava/lang/String;)V"
                        loadConstant("Hello World")
                        loadConstant("1")
                        invokeStatic(
                            "jafun/test/TestKt", "join", "(Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;"
                        )
                        loadConstant("2")
                        loadConstant("3")
                        invokeStatic(
                            "jafun/test/TestKt", "join", "(Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;"
                        )
                        invokeStatic(
                            "jafun/test/TestKt", "join", "(Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;"
                        )
                        invokeStatic("jafun/io/ConsoleKt", "println", "(Ljava/lang/Object;)V")
                        `return`()
                    }
                }
            }
        }
    }

    @Test
    fun twoArgCompile() {
        test {
            file {
                code = """
                    join("Hello", "World")"""
                phase2 {
                    +invocation(join, s("Hello"), s("World"))
                }
                expectedOutput = ""
                jvmIr {
                    name = "Script"
                    method {
                        name = "main"
                        signature = "([Ljava/lang/String;)V"
                        loadConstant("Hello")
                        loadConstant("World")
                        invokeStatic(
                            "jafun/test/TestKt", "join", "(Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;"
                        )
                        pop()
                        `return`()
                    }
                }
            }
        }
    }


    @Test
    fun helloWorldCompile() {
        test {
            file {
                code = """
                    println "Hello World"
                    print "Hello "
                    println "World""""
                expectedOutput = "Hello World\nHello World\n"
                jvmIr {
                    name = "Script"
                    method {
                        name = "main"
                        signature = "([Ljava/lang/String;)V"
                        loadConstant("Hello World")
                        invokeStatic("jafun/io/ConsoleKt", "println", "(Ljava/lang/Object;)V")
                        loadConstant("Hello ")
                        invokeStatic("jafun/io/ConsoleKt", "print", "(Ljava/lang/Object;)V")
                        loadConstant("World")
                        invokeStatic("jafun/io/ConsoleKt", "println", "(Ljava/lang/Object;)V")
                        `return`()
                    }
                }
            }
        }
    }

    @Test
    fun complexHelloWorldCompile() {
        test {
            file {
                code = """
                    System.out.println "Hello World""""
                phase2 {
                    +invocation(printStreamPrintln, systemOut, s("Hello World"))
                }
                expectedOutput = "Hello World\n"
                jvmIr {
                    name = "Script"
                    method {
                        name = "main"
                        signature = "([Ljava/lang/String;)V"
                        getStatic("java/lang/System", "out", "Ljava/io/PrintStream;")
                        loadConstant("Hello World")
                        invokeVirtual("java/io/PrintStream", "println", "(Ljava/lang/String;)V")
                        `return`()
                    }
                }
            }
        }
    }

    @Test
    fun complexHelloWorldCompileFullyQualified() {
        test {
            file {
                code = """
                    java.lang.System.out.println "Hello World""""
                phase2 {
                    +invocation(printStreamPrintln, systemOut, s("Hello World"))
                }
                expectedOutput = "Hello World\n"
                jvmIr {
                    name = "Script"
                    method {
                        name = "main"
                        signature = "([Ljava/lang/String;)V"
                        getStatic("java/lang/System", "out", "Ljava/io/PrintStream;")
                        loadConstant("Hello World")
                        invokeVirtual("java/io/PrintStream", "println", "(Ljava/lang/String;)V")
                        `return`()
                    }
                }
            }
        }
    }

    @Test
    fun simpleInfix() {
        test {
            file {
                code = """
                    1 + 2""""
                expectedOutput = ""
                jvmIr {
                    name = "Script"
                    method {
                        name = "main"
                        signature = "([Ljava/lang/String;)V"
                        loadConstant(1)
                        loadConstant(2)
                        invokeStatic("jafun/lang/IntKt", "+", "(II)I")
                        pop()
                        `return`()
                    }
                }
            }
        }
    }

    @Test
    fun simpleAssociativity() {
        test {
            file {
                code = """
                    1 + 2 * 3""""
                expectedOutput = ""
                jvmIr {
                    name = "Script"
                    method {
                        name = "main"
                        signature = "([Ljava/lang/String;)V"
                        loadConstant(1)
                        loadConstant(2)
                        loadConstant(3)
                        invokeStatic("jafun/lang/IntKt", "*", "(II)I")
                        invokeStatic("jafun/lang/IntKt", "+", "(II)I")
                        pop()
                        `return`()
                    }
                }
            }
        }
    }

    @Test
    fun assignmentSimple() {
        test {
            file {
                code = """
                    val str1 = "Hello World"
                    println str1"""
                phase2 {
                    +valAssignment(symbol("str1", OperandType.StringType), s("Hello World"))
                    +invocation(println, variable(symbol("str1", OperandType.StringType)))
                }
                expectedOutput = "Hello World\n"
                jvmIr {
                    name = "Script"
                    method {
                        name = "main"
                        signature = "([Ljava/lang/String;)V"
                        loadConstant("Hello World")
                        astore("str1")
                        aload("str1")
                        invokeStatic("jafun/io/ConsoleKt", "println", "(Ljava/lang/Object;)V")
                        `return`()
                    }
                }
            }
        }
    }

    @Test
    fun assignmentTwoVariables() {
        test {
            file {
                code = """
                    val str2 = "World"
                    val str1 = "Hello"
                    println join(str1, str2)"""
                expectedOutput = "Hello World\n"
                jvmIr {
                    name = "Script"
                    method {
                        name = "main"
                        signature = "([Ljava/lang/String;)V"
                        loadConstant("World")
                        astore("str2")
                        loadConstant("Hello")
                        astore("str1")
                        aload("str1")
                        aload("str2")
                        invokeStatic(
                            "jafun/test/TestKt", "join", "(Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;"
                        )
                        invokeStatic("jafun/io/ConsoleKt", "println", "(Ljava/lang/Object;)V")
                        `return`()
                    }
                }
            }
        }
    }

    @Test
    fun integer() {
        test {
            file {
                code = """
                    val i = 128
                    println i"""
                phase2 {
                    +valAssignment(symbol("i", OperandType.SInt32), i(128))
                    +invocation(println, variable(symbol("i", OperandType.SInt32)))
                }
                expectedOutput = "128\n"
                jvmIr {
                    name = "Script"
                    method {
                        name = "main"
                        signature = "([Ljava/lang/String;)V"
                        loadConstant(128)
                        istore("i")
                        iload("i")
                        invokeStatic("java/lang/Integer", "valueOf", "(I)Ljava/lang/Integer;")
                        invokeStatic("jafun/io/ConsoleKt", "println", "(Ljava/lang/Object;)V")
                        `return`()
                    }
                }
            }
        }
    }

    @Test
    fun valReassignment() {
        try {
            test {
                file {
                    code = """
                        val i = 1
                        i = 2
                        println i"""
                    expectedOutput = "2\n"
                }
            }
        } catch (e: IllegalStateException) {
            assertEquals("Variable i is not mutable", e.message)
        }
    }

    @Test
    fun reassignment() {
        test {
            file {
                code = """
                    var i = 1
                    i = 2
                    println i"""
                expectedOutput = "2\n"
                jvmIr {
                    name = "Script"
                    method {
                        name = "main"
                        signature = "([Ljava/lang/String;)V"
                        loadConstant(1)
                        istore("i")
                        loadConstant(2)
                        istore("i")
                        iload("i")
                        invokeStatic("java/lang/Integer", "valueOf", "(I)Ljava/lang/Integer;")
                        invokeStatic("jafun/io/ConsoleKt", "println", "(Ljava/lang/Object;)V")
                        `return`()
                    }
                }
            }
        }
    }

    @Test
    fun inlineAssign() {
        test {
            file {
                code = """
                    println(val i = 128)
                    println i"""
                expectedOutput = "128\n128\n"
                jvmIr {
                    name = "Script"
                    method {
                        name = "main"
                        signature = "([Ljava/lang/String;)V"
                        loadConstant(128)
                        dup()
                        istore("i")
                        invokeStatic("java/lang/Integer", "valueOf", "(I)Ljava/lang/Integer;")
                        invokeStatic("jafun/io/ConsoleKt", "println", "(Ljava/lang/Object;)V")
                        iload("i")
                        invokeStatic("java/lang/Integer", "valueOf", "(I)Ljava/lang/Integer;")
                        invokeStatic("jafun/io/ConsoleKt", "println", "(Ljava/lang/Object;)V")
                        `return`()
                    }
                }
            }
        }
    }

    @Test
    fun `assignment with plus`() {
        test {
            file {
                code = """
                    val i = 2 + 3
                    println i """
                expectedOutput = "5\n"
                jvmIr {
                    name = "Script"
                    method {
                        name = "main"
                        signature = "([Ljava/lang/String;)V"
                        loadConstant(2)
                        loadConstant(3)
                        invokeStatic("jafun/lang/IntKt", "+", "(II)I")
                        istore("i")
                        iload("i")
                        invokeStatic("java/lang/Integer", "valueOf", "(I)Ljava/lang/Integer;")
                        invokeStatic("jafun/io/ConsoleKt", "println", "(Ljava/lang/Object;)V")

                        `return`()
                    }
                }
            }
        }
    }

    @Test
    fun plus() {
        test {
            file {
                code = """
                    val i = 5
                    val j = 11
                    println i + j"""
                expectedOutput = "16\n"
                jvmIr {
                    name = "Script"
                    method {
                        name = "main"
                        signature = "([Ljava/lang/String;)V"
                        loadConstant(5)
                        istore("i")
                        loadConstant(11)
                        istore("j")
                        iload("i")
                        iload("j")
                        invokeStatic("jafun/lang/IntKt", "+", "(II)I")
                        invokeStatic("java/lang/Integer", "valueOf", "(I)Ljava/lang/Integer;")
                        invokeStatic("jafun/io/ConsoleKt", "println", "(Ljava/lang/Object;)V")
                        `return`()
                    }
                }
            }
        }
    }

    @Test
    fun calc() {
        test {
            file {
                code = """
                    println 4 + 3 * 5 - 6 / 2"""
                expectedOutput = "16\n"
                jvmIr {
                    name = "Script"
                    method {
                        name = "main"
                        signature = "([Ljava/lang/String;)V"
                        loadConstant(4)
                        loadConstant(3)
                        loadConstant(5)
                        invokeStatic("jafun/lang/IntKt", "*", "(II)I")
                        invokeStatic("jafun/lang/IntKt", "+", "(II)I")
                        loadConstant(6)
                        loadConstant(2)
                        invokeStatic("jafun/lang/IntKt", "∕", "(II)I")
                        invokeStatic("jafun/lang/IntKt", "-", "(II)I")
                        invokeStatic("java/lang/Integer", "valueOf", "(I)Ljava/lang/Integer;")
                        invokeStatic("jafun/io/ConsoleKt", "println", "(Ljava/lang/Object;)V")
                        `return`()
                    }
                }
            }
        }
    }

    @Test
    fun calcWithAssignment() {
        test {
            file {
                code = """
                    val a = 4 + 3 * 5 - 6 / 2
                    println a"""
                expectedOutput = "16\n"
                jvmIr {
                    name = "Script"
                    method {
                        name = "main"
                        signature = "([Ljava/lang/String;)V"
                        loadConstant(4)
                        loadConstant(3)
                        loadConstant(5)
                        invokeStatic("jafun/lang/IntKt", "*", "(II)I")
                        invokeStatic("jafun/lang/IntKt", "+", "(II)I")
                        loadConstant(6)
                        loadConstant(2)
                        invokeStatic("jafun/lang/IntKt", "∕", "(II)I")
                        invokeStatic("jafun/lang/IntKt", "-", "(II)I")
                        istore("a")
                        iload("a")
                        invokeStatic("java/lang/Integer", "valueOf", "(I)Ljava/lang/Integer;")
                        invokeStatic("jafun/io/ConsoleKt", "println", "(Ljava/lang/Object;)V")
                        `return`()
                    }
                }
            }
        }
    }

    @Test
    fun calcParentheses() {
        test {
            file {
                code = """
                    println((4 + 3) * (6 - 4))"""
                expectedOutput = "14\n"
                jvmIr {
                    name = "Script"
                    method {
                        name = "main"
                        signature = "([Ljava/lang/String;)V"
                        loadConstant(4)
                        loadConstant(3)
                        invokeStatic("jafun/lang/IntKt", "+", "(II)I")
                        loadConstant(6)
                        loadConstant(4)
                        invokeStatic("jafun/lang/IntKt", "-", "(II)I")
                        invokeStatic("jafun/lang/IntKt", "*", "(II)I")
                        invokeStatic("java/lang/Integer", "valueOf", "(I)Ljava/lang/Integer;")
                        invokeStatic("jafun/io/ConsoleKt", "println", "(Ljava/lang/Object;)V")
                        `return`()
                    }
                }
            }
        }
    }

    @Test
    fun operatorIdentifier() {
        test {
            file {
                code = """
                    val abc = 3
                    val def = 4
                    println abc+def"""
                expectedOutput = "7\n"
                jvmIr {
                    name = "Script"
                    method {
                        name = "main"
                        signature = "([Ljava/lang/String;)V"
                        loadConstant(3)
                        istore("abc")
                        loadConstant(4)
                        istore("def")
                        iload("abc")
                        iload("def")
                        invokeStatic("jafun/lang/IntKt", "+", "(II)I")
                        invokeStatic("java/lang/Integer", "valueOf", "(I)Ljava/lang/Integer;")
                        invokeStatic("jafun/io/ConsoleKt", "println", "(Ljava/lang/Object;)V")
                        `return`()
                    }
                }
            }
        }
    }

    @Test
    fun powerTimes() {
        test {
            file {
                code = """
                    println 2**5*3"""
                expectedOutput = "96\n"
                jvmIr {
                    name = "Script"
                    method {
                        name = "main"
                        signature = "([Ljava/lang/String;)V"
                        loadConstant(2)
                        loadConstant(5)
                        invokeStatic("jafun/lang/IntKt", "**", "(II)I")
                        loadConstant(3)
                        invokeStatic("jafun/lang/IntKt", "*", "(II)I")
                        invokeStatic("java/lang/Integer", "valueOf", "(I)Ljava/lang/Integer;")
                        invokeStatic("jafun/io/ConsoleKt", "println", "(Ljava/lang/Object;)V")
                        `return`()
                    }
                }
            }
        }
    }

    @Test
    fun infixl() {
        test {
            file {
                code = """
                    println 10 - 4 - 2"""
                expectedOutput = "4\n"
                jvmIr {
                    name = "Script"
                    method {
                        name = "main"
                        signature = "([Ljava/lang/String;)V"
                        loadConstant(10)
                        loadConstant(4)
                        invokeStatic(
                            "jafun/lang/IntKt", "-", "(II)I"
                        )
                        loadConstant(2)
                        invokeStatic(
                            "jafun/lang/IntKt", "-", "(II)I"
                        )
                        invokeStatic(
                            "java/lang/Integer", "valueOf", "(I)Ljava/lang/Integer;"
                        )
                        invokeStatic(
                            "jafun/io/ConsoleKt", "println", "(Ljava/lang/Object;)V"
                        )
                        `return`()
                    }
                }
            }
        }
    }

    @Test
    fun infixr() {
        test {
            file {
                code = """
                    println 4 ** 3 ** 2"""
                expectedOutput = "262144\n"
                jvmIr {
                    name = "Script"
                    method {
                        name = "main"
                        signature = "([Ljava/lang/String;)V"
                        loadConstant(4)
                        loadConstant(3)
                        loadConstant(2)
                        invokeStatic("jafun/lang/IntKt", "**", "(II)I")
                        invokeStatic("jafun/lang/IntKt", "**", "(II)I")
                        invokeStatic("java/lang/Integer", "valueOf", "(I)Ljava/lang/Integer;")
                        invokeStatic("jafun/io/ConsoleKt", "println", "(Ljava/lang/Object;)V")
                        `return`()
                    }
                }
            }
        }
    }

    @Test
    fun postfixOperator() {
        test {
            file {
                code = """
                    println 5++"""
                expectedOutput = "6\n"
                jvmIr {
                    name = "Script"
                    method {
                        name = "main"
                        signature = "([Ljava/lang/String;)V"
                        loadConstant(5)
                        invokeStatic(
                            "jafun/lang/IntKt", "++", "(I)I"
                        )
                        invokeStatic(
                            "java/lang/Integer", "valueOf", "(I)Ljava/lang/Integer;"
                        )
                        invokeStatic(
                            "jafun/io/ConsoleKt", "println", "(Ljava/lang/Object;)V"
                        )
                        `return`()
                    }
                }
            }
        }
    }

    @Test
    fun postfixFunction() {
        test {
            file {
                code = """
println 5 euro + 20 cent"""
                expectedOutput = "520\n"
                jvmIr {
                    name = "Script"
                    method {
                        name = "main"
                        signature = "([Ljava/lang/String;)V"
                        loadConstant(5)
                        invokeStatic(
                            "jafun/test/TestKt", "euro", "(I)I"
                        )
                        loadConstant(20)
                        invokeStatic(
                            "jafun/test/TestKt", "cent", "(I)I"
                        )
                        invokeStatic(
                            "jafun/lang/IntKt", "+", "(II)I"
                        )
                        invokeStatic(
                            "java/lang/Integer", "valueOf", "(I)Ljava/lang/Integer;"
                        )
                        invokeStatic(
                            "jafun/io/ConsoleKt", "println", "(Ljava/lang/Object;)V"
                        )
                        `return`()
                    }
                }
            }
        }
    }

    @Test
    fun funDeclarationEmptyFunction() {
        test {
            file {
                code = """
fun test() { }"""
                phase2 {
                    +function(method("test", OperandType.Unit))
                }
                expectedOutput = ""
                jvmIr {
                    name = "Script"
                    method {
                        name = "main"
                        signature = "([Ljava/lang/String;)V"
                        `return`()
                    }
                    method {
                        name = "test"
                        signature = "()V"
                        `return`()
                    }
                }
            }
        }
    }


    @Test
    fun funDeclaration() {
        test {
            file {
                code = """
fun test() { println 2 }"""
                phase2 {
                    +function(method("test", OperandType.Unit)) {
                        +invocation(println, i(2))
                    }
                }
                expectedOutput = ""
                jvmIr {
                    name = "Script"
                    method {
                        name = "main"
                        signature = "([Ljava/lang/String;)V"
                        `return`()
                    }
                    method {
                        name = "test"
                        signature = "()V"
                        loadConstant(2)
                        invokeStatic("java/lang/Integer", "valueOf", "(I)Ljava/lang/Integer;")
                        invokeStatic("jafun/io/ConsoleKt", "println", "(Ljava/lang/Object;)V")
                        `return`()
                    }
                }
            }
        }
    }

    @Test
    fun funDeclarationAndInvocation() {
        test {
            file {
                code = """
fun test() { println 2 }
test
test()"""
                expectedOutput = "2\n2\n"
                jvmIr {
                    name = "Script"
                    method {
                        name = "main"
                        signature = "([Ljava/lang/String;)V"
                        invokeStatic("Script", "test", "()V")
                        invokeStatic("Script", "test", "()V")
                        `return`()
                    }
                    method {
                        name = "test"
                        signature = "()V"
                        loadConstant(2)
                        invokeStatic("java/lang/Integer", "valueOf", "(I)Ljava/lang/Integer;")
                        invokeStatic("jafun/io/ConsoleKt", "println", "(Ljava/lang/Object;)V")
                        `return`()
                    }
                }
            }
        }
    }

    @Test
    fun funDeclarationAndInvocationWithParam() {
        test {
            file {
                code = """
fun test(a: Int) { 
println 2 * a
}
test 2
test(3)"""
                phase2 {
                    +function(method("test", OperandType.Unit, symbol("a", OperandType.SInt32))) {
                        +invocation(
                            println,
                            invocation(times, i(2), variable(symbol("a", OperandType.SInt32)))
                        )
                    }
                    +invocation(method("test", OperandType.Unit, symbol("a", OperandType.SInt32)), i(2))
                    +invocation(method("test", OperandType.Unit, symbol("a", OperandType.SInt32)), i(3))
                }
                expectedOutput = "4\n6\n"
                jvmIr {
                    name = "Script"
                    method {
                        name = "main"
                        signature = "([Ljava/lang/String;)V"
                        loadConstant(2)
                        invokeStatic("Script", "test", "(I)V")
                        loadConstant(3)
                        invokeStatic("Script", "test", "(I)V")
                        `return`()
                    }
                    method {
                        name = "test"
                        signature = "(I)V"
                        loadConstant(2)
                        iload("a")
                        invokeStatic("jafun/lang/IntKt", "*", "(II)I")
                        invokeStatic("java/lang/Integer", "valueOf", "(I)Ljava/lang/Integer;")
                        invokeStatic("jafun/io/ConsoleKt", "println", "(Ljava/lang/Object;)V")
                        `return`()
                    }

                }
            }
        }
    }

    @Test
    fun funDeclarationAndInvocationWithTwoParams() {
        test {
            file {
                code = """
fun test(prefix: String, a: Int) { 
print prefix
println a
}
test("test: ",5)"""
                expectedOutput = "test: 5\n"
                jvmIr {
                    name = "Script"
                    method {
                        name = "main"
                        signature = "([Ljava/lang/String;)V"
                        loadConstant("test: ")
                        loadConstant(5)
                        invokeStatic("Script", "test", "(Ljava/lang/String;I)V")
                        `return`()
                    }
                    method {
                        name = "test"
                        signature = "(Ljava/lang/String;I)V"
                        aload("prefix")
                        invokeStatic("jafun/io/ConsoleKt", "print", "(Ljava/lang/Object;)V")
                        iload("a")
                        invokeStatic("java/lang/Integer", "valueOf", "(I)Ljava/lang/Integer;")
                        invokeStatic("jafun/io/ConsoleKt", "println", "(Ljava/lang/Object;)V")
                        `return`()
                    }
                }
            }

        }
    }

    @Test
    fun funBackwardsFunctionCallFromFunction() {
        test {
            file {
                code = """
fun test(prefix: String, a: Int) { 
print prefix
println a
}
fun test2(prefix: String) {
test(prefix, 10)
}
test("test: ", 5)
test2 "test: """"
                expectedOutput = "test: 5\ntest: 10\n"
                jvmIr {
                    name = "Script"
                    method {
                        name = "main"
                        signature = "([Ljava/lang/String;)V"
                        loadConstant("test: ")
                        loadConstant(5)
                        invokeStatic("Script", "test", "(Ljava/lang/String;I)V")
                        loadConstant("test: ")
                        invokeStatic("Script", "test2", "(Ljava/lang/String;)V")
                        `return`()
                    }
                    method {
                        name = "test"
                        signature = "(Ljava/lang/String;I)V"
                        aload("prefix")
                        invokeStatic("jafun/io/ConsoleKt", "print", "(Ljava/lang/Object;)V")
                        iload("a")
                        invokeStatic("java/lang/Integer", "valueOf", "(I)Ljava/lang/Integer;")
                        invokeStatic("jafun/io/ConsoleKt", "println", "(Ljava/lang/Object;)V")
                        `return`()
                    }
                    method {
                        name = "test2"
                        signature = "(Ljava/lang/String;)V"
                        aload("prefix")
                        loadConstant(10)
                        invokeStatic("Script", "test", "(Ljava/lang/String;I)V")
                        `return`()
                    }
                }
            }
        }
    }


    @Test
    fun stackNeutralTest() {
        test {
            file {
                code = """
1+2
1+2
1+2
1+2
1+2
1+2
1+2
1+2
1+2
1+2
1+2
1+2"""
                expectedOutput = ""
                jvmIr {
                    name = "Script"

                    method {
                        name = "main"
                        signature = "([Ljava/lang/String;)V"
                        (1..12).forEach {
                            loadConstant(1)
                            loadConstant(2)
                            invokeStatic("jafun/lang/IntKt", "+", "(II)I")
                            pop()
                        }
                        `return`()
                    }
                }
            }
        }
    }

    @Test
    fun stackNeutralInFunctionTest() {
        test {
            file {
                code = """
fun test() {
1+2
1+2
1+2
1+2
1+2
1+2
1+2
1+2
1+2
1+2
}
println test()
"""
                expectedOutput = "3\n"
                jvmIr {
                    name = "Script"

                    method {
                        name = "main"
                        signature = "([Ljava/lang/String;)V"
                        invokeStatic("Script", "test", "()I")
                        invokeStatic(
                            "java/lang/Integer", "valueOf", "(I)Ljava/lang/Integer;"
                        )
                        invokeStatic(
                            "jafun/io/ConsoleKt", "println", "(Ljava/lang/Object;)V"
                        )
                        `return`()
                    }

                    method {
                        name = "test"
                        signature = "()I"
                        (1..9).forEach {
                            loadConstant(1)
                            loadConstant(2)
                            invokeStatic("jafun/lang/IntKt", "+", "(II)I")
                            pop()
                        }
                        loadConstant(1)
                        loadConstant(2)
                        invokeStatic("jafun/lang/IntKt", "+", "(II)I")
                        `ireturn`()
                    }

                }
            }
        }
    }


    @Test
    fun funReturnIntValue() {
        test {
            file {
                code = """
fun add2(a: Int) {
a + 2
}
println add2 4
println add2(6)"""
                phase2 {
                    +function(
                        method(
                            "add2",
                            OperandType.SInt32,
                            symbol("a", OperandType.SInt32)
                        )
                    ) {
                        +invocation(
                            plus,
                            variable(symbol("a", OperandType.SInt32)),
                            i(2)
                        )
                    }
                    +invocation(
                        println,
                        invocation(
                            method(
                                "add2",
                                OperandType.SInt32,
                                symbol("a", OperandType.SInt32)
                            ), i(4)
                        )
                    )
                    +invocation(
                        println,
                        invocation(
                            method(
                                "add2",
                                OperandType.SInt32,
                                symbol("a", OperandType.SInt32)
                            ), i(6)
                        )
                    )
                }
                expectedOutput = "6\n8\n"
                jvmIr {
                    name = "Script"
                    method {
                        name = "main"
                        signature = "([Ljava/lang/String;)V"
                        loadConstant(4)
                        invokeStatic("Script", "add2", "(I)I")
                        invokeStatic("java/lang/Integer", "valueOf", "(I)Ljava/lang/Integer;")
                        invokeStatic("jafun/io/ConsoleKt", "println", "(Ljava/lang/Object;)V")
                        loadConstant(6)
                        invokeStatic("Script", "add2", "(I)I")
                        invokeStatic("java/lang/Integer", "valueOf", "(I)Ljava/lang/Integer;")
                        invokeStatic("jafun/io/ConsoleKt", "println", "(Ljava/lang/Object;)V")
                        `return`()
                    }
                    method {
                        name = "add2"
                        signature = "(I)I"
                        iload("a")
                        loadConstant(2)
                        invokeStatic("jafun/lang/IntKt", "+", "(II)I")
                        ireturn()
                    }
                }
            }
        }
    }

    @Test
    fun functionInFunction() {
        test {
            file {
                code = """
fun test() { fun inner() { println 1 + 2 } }"""
                phase2 {
                    +function(method("test", OperandType.Unit)) {
                        +function(method("inner", OperandType.Unit)) {
                            +invocation(
                                println,
                                invocation(plus, i(1), i(2))
                            )
                        }
                    }
                }
                expectedOutput = ""
                jvmIr {
                    name = "Script"
                    method {
                        name = "main"
                        signature = "([Ljava/lang/String;)V"
                        `return`()
                    }
                    method {
                        name = "test"
                        signature = "()V"
                        `return`()
                    }
                    method {
                        name = "inner"
                        signature = "()V"
                        loadConstant(1)
                        loadConstant(2)
                        invokeStatic("jafun/lang/IntKt", "+", "(II)I")
                        invokeStatic("java/lang/Integer", "valueOf", "(I)Ljava/lang/Integer;")
                        invokeStatic("jafun/io/ConsoleKt", "println", "(Ljava/lang/Object;)V")
                        `return`()
                    }
                }
            }
        }
    }

    @Test
    fun funReturnStringValue() {
        test {
            file {
                code = """
fun prefixed(text: String) {
join("PREFIXED:", text)
}
println prefixed "Test"
println(prefixed("Test2"))"""
                expectedOutput = "PREFIXED: Test\nPREFIXED: Test2\n"
                jvmIr {
                    name = "Script"
                    method {
                        name = "main"
                        signature = "([Ljava/lang/String;)V"
                        loadConstant("Test")
                        invokeStatic("Script", "prefixed", "(Ljava/lang/String;)Ljava/lang/String;")
                        invokeStatic("jafun/io/ConsoleKt", "println", "(Ljava/lang/Object;)V")
                        loadConstant("Test2")
                        invokeStatic("Script", "prefixed", "(Ljava/lang/String;)Ljava/lang/String;")
                        invokeStatic("jafun/io/ConsoleKt", "println", "(Ljava/lang/Object;)V")
                        `return`()
                    }
                    method {
                        name = "prefixed"
                        signature = "(Ljava/lang/String;)Ljava/lang/String;"
                        loadConstant("PREFIXED:")
                        aload("text")
                        invokeStatic("jafun/test/TestKt", "join", "(Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;")
                        areturn()
                    }
                }
            }
        }
    }

    @Test
    fun scope() {
        test {
            file {
                code = """
val a = 2
{
val a = 4
{
val a = a
println a
}
println a
}
println a"""
                expectedOutput = "4\n4\n2\n"
                jvmIr {
                    name = "Script"
                    method {
                        name = "main"
                        signature = "([Ljava/lang/String;)V"
                        loadConstant(2)
                        istore("0.a")
                        loadConstant(4)
                        istore("1.a")
                        iload("1.a")
                        istore("2.a")
                        iload("2.a")
                        invokeStatic("java/lang/Integer", "valueOf", "(I)Ljava/lang/Integer;")
                        invokeStatic("jafun/io/ConsoleKt", "println", "(Ljava/lang/Object;)V")
                        iload("1.a")
                        invokeStatic("java/lang/Integer", "valueOf", "(I)Ljava/lang/Integer;")
                        invokeStatic("jafun/io/ConsoleKt", "println", "(Ljava/lang/Object;)V")
                        iload("0.a")
                        invokeStatic("java/lang/Integer", "valueOf", "(I)Ljava/lang/Integer;")
                        invokeStatic("jafun/io/ConsoleKt", "println", "(Ljava/lang/Object;)V")
                        `return`()
                    }
                }
            }
        }
    }

    @Test
    fun integerLiterals() {
        test {
            file {
                code = """
println 0
println 1
println 2
println 3
println 4
println 5
println 6
println 127
println 128
println 32767
println 32768"""
                expectedOutput = "0\n1\n2\n3\n4\n5\n6\n127\n128\n32767\n32768\n"
                jvmIr {
                    name = "Script"
                    method {
                        name = "main"
                        signature = "([Ljava/lang/String;)V"
                        listOf(
                            0,
                            1,
                            2,
                            3,
                            4,
                            5,
                            6,
                            127,
                            128,
                            32767,
                            32768
                        ).forEach {
                            loadConstant(it)
                            invokeStatic("java/lang/Integer", "valueOf", "(I)Ljava/lang/Integer;")
                            invokeStatic("jafun/io/ConsoleKt", "println", "(Ljava/lang/Object;)V")
                        }
                        `return`()
                    }
                }
            }
        }
    }

    @Test
    fun basicBoolean() {
        test {
            file {
                code = """
val b = true
println b"""
                expectedOutput = "true\n"
                jvmIr {
                    name = "Script"
                    method {
                        name = "main"
                        signature = "([Ljava/lang/String;)V"
                        loadConstant(1)
                        istore("b")
                        iload("b")
                        invokeStatic("java/lang/Boolean", "valueOf", "(Z)Ljava/lang/Boolean;")
                        invokeStatic("jafun/io/ConsoleKt", "println", "(Ljava/lang/Object;)V")
                        `return`()
                    }
                }
            }
        }
    }

    @Test
    fun veryBasicwhen() {
        test {
            file {
                code = """
when {
1 == 1 -> "One"
else -> "Else"
}"""
                phase2 {
                    +`when`(
                        invocation(equals, i(1), i(1)) to s("One"),
                        b(true) to s("Else")
                    )
                }
                expectedOutput = ""
                jvmIr {
                    name = "Script"
                    method {
                        name = "main"
                        signature = "([Ljava/lang/String;)V"
                        loadConstant(1)
                        loadConstant(1)
                        invokeStatic("jafun/lang/IntKt", "==", "(II)Z")
                        ifequal(9)
                        loadConstant("One")
                        goto(6)
                        loadConstant("Else")
                        pop()
                        `return`()
                    }
                }

            }
        }
    }

    @Test
    fun basicEquality() {
        test {
            file {
                code = """1 == 1"""
                expectedOutput = ""
                jvmIr {
                    name = "Script"
                    method {
                        name = "main"
                        signature = "([Ljava/lang/String;)V"
                        loadConstant(1)
                        loadConstant(1)
                        invokeStatic("jafun/lang/IntKt", "==", "(II)Z")
                        pop()
                        `return`()
                    }
                }
            }
        }
    }

    @Test
    fun basicEqualityChars() {
        test {
            file {
                code = """'a' == 'a'"""
                expectedOutput = ""
                jvmIr {
                    name = "Script"
                    method {
                        name = "main"
                        signature = "([Ljava/lang/String;)V"
                        loadConstant('a')
                        loadConstant('a')
                        invokeStatic("jafun/lang/CharKt", "==", "(CC)Z")
                        pop()
                        `return`()
                    }
                }
            }
        }
    }

    @Test
    fun basicWhen() {
        test {
            file {
                code = """
val a = 2
println when {
a == 1 -> "One"
a == 2 -> "Two"
a == 3 -> "Three"
else -> "More"
}"""
                expectedOutput = "Two\n"
                jvmIr {
                    name = "Script"
                    method {
                        name = "main"
                        signature = "([Ljava/lang/String;)V"
                        loadConstant(2)
                        istore("a")
                        iload("a")
                        loadConstant(1)
                        invokeStatic("jafun/lang/IntKt", "==", "(II)Z")
                        ifequal(9)
                        loadConstant("One")
                        goto(36)
                        iload("a")
                        loadConstant(2)
                        invokeStatic("jafun/lang/IntKt", "==", "(II)Z")
                        ifequal(9)
                        loadConstant("Two")
                        goto(21)
                        iload("a")
                        loadConstant(3)
                        invokeStatic("jafun/lang/IntKt", "==", "(II)Z")
                        ifequal(9)
                        loadConstant("Three")
                        goto(6)
                        loadConstant("More")
                        invokeStatic("jafun/io/ConsoleKt", "println", "(Ljava/lang/Object;)V")
                        `return`()
                    }
                }
            }
        }
    }

    @Test
    fun basicWhenWithVariableSubject() {
        test {
            file {
                code = """
val a = 2
println when (a) {
1 -> "One"
2 -> "Two"
3 -> "Three"
else -> "More"
}"""
                expectedOutput = "Two\n"
                jvmIr {
                    name = "Script"
                    method {
                        name = "main"
                        signature = "([Ljava/lang/String;)V"
                        loadConstant(2)
                        istore("a")
                        iload("a")
                        loadConstant(1)
                        invokeStatic("jafun/lang/IntKt", "==", "(II)Z")
                        ifequal(9)
                        loadConstant("One")
                        goto(36)
                        iload("a")
                        loadConstant(2)
                        invokeStatic("jafun/lang/IntKt", "==", "(II)Z")
                        ifequal(9)
                        loadConstant("Two")
                        goto(21)
                        iload("a")
                        loadConstant(3)
                        invokeStatic("jafun/lang/IntKt", "==", "(II)Z")
                        ifequal(9)
                        loadConstant("Three")
                        goto(6)
                        loadConstant("More")
                        invokeStatic("jafun/io/ConsoleKt", "println", "(Ljava/lang/Object;)V")
                        `return`()
                    }
                }
            }
        }
    }

    @Test
    fun basicWhenWithSubjectExpression() {
        test {
            file {
                code = """
println when (1+1) {
1 -> "One"
2 -> "Two"
3 -> "Three"
else -> "More"
}"""
                expectedOutput = "Two\n"
                jvmIr {
                    name = "Script"
                    method {
                        name = "main"
                        signature = "([Ljava/lang/String;)V"
                        loadConstant(1)
                        loadConstant(1)
                        invokeStatic("jafun/lang/IntKt", "+", "(II)I")
                        istore("tmp")
                        iload("tmp")
                        loadConstant(1)
                        invokeStatic("jafun/lang/IntKt", "==", "(II)Z")
                        ifequal(9)
                        loadConstant("One")
                        goto(36)
                        iload("tmp")
                        loadConstant(2)
                        invokeStatic("jafun/lang/IntKt", "==", "(II)Z")
                        ifequal(9)
                        loadConstant("Two")
                        goto(21)
                        iload("tmp")
                        loadConstant(3)
                        invokeStatic("jafun/lang/IntKt", "==", "(II)Z")
                        ifequal(9)
                        loadConstant("Three")
                        goto(6)
                        loadConstant("More")
                        invokeStatic("jafun/io/ConsoleKt", "println", "(Ljava/lang/Object;)V")
                        `return`()
                    }
                }
            }
        }
    }

    @Test
    fun basicWhenWithSubjectValExpression() {
        test {
            file {
                code = """
println when (val a = 1 + 1) {
1 -> "One"
2 -> "Two"
3 -> "Three"
else -> "More"
}"""
                phase2 {
                    +invocation(
                        println,
                        `when`(
                            valAssignment(
                                symbol("a", OperandType.SInt32),
                                invocation(plus, i(1), i(1))
                            ),
                            i(1) to s("One"),
                            i(2) to s("Two"),
                            i(3) to s("Three"),
                            b(true) to s("More")
                        )
                    )
                }
                expectedOutput = "Two\n"
                jvmIr {
                    name = "Script"
                    method {
                        name = "main"
                        signature = "([Ljava/lang/String;)V"
                        loadConstant(1)
                        loadConstant(1)
                        invokeStatic("jafun/lang/IntKt", "+", "(II)I")
                        istore("a")
                        iload("a")
                        loadConstant(1)
                        invokeStatic("jafun/lang/IntKt", "==", "(II)Z")
                        ifequal(9)
                        loadConstant("One")
                        goto(36)
                        iload("a")
                        loadConstant(2)
                        invokeStatic("jafun/lang/IntKt", "==", "(II)Z")
                        ifequal(9)
                        loadConstant("Two")
                        goto(21)
                        iload("a")
                        loadConstant(3)
                        invokeStatic("jafun/lang/IntKt", "==", "(II)Z")
                        ifequal(9)
                        loadConstant("Three")
                        goto(6)
                        loadConstant("More")
                        invokeStatic("jafun/io/ConsoleKt", "println", "(Ljava/lang/Object;)V")
                        `return`()
                    }
                }
            }
        }
    }

    @Test
    fun factorial() {
        test {
            file {
                code = """
fun factorial(n: Int): Int {
when (n) {
0 -> 1
else -> n * (factorial n - 1)
}
}
println factorial 6"""
                expectedOutput = "720\n"
                jvmIr {
                    name = "Script"
                    method {
                        name = "main"
                        signature = "([Ljava/lang/String;)V"
                        loadConstant(6)
                        invokeStatic("Script", "factorial", "(I)I")
                        invokeStatic("java/lang/Integer", "valueOf", "(I)Ljava/lang/Integer;")
                        invokeStatic("jafun/io/ConsoleKt", "println", "(Ljava/lang/Object;)V")
                        `return`()
                    }
                    method {
                        name = "factorial"
                        signature = "(I)I"
                        iload("param1")
                        loadConstant(0)
                        invokeStatic("jafun/lang/IntKt", "==", "(II)Z")
                        ifequal(7)
                        loadConstant(1)
                        goto(17)
                        iload("param1")
                        iload("param1")
                        loadConstant(1)
                        invokeStatic("jafun/lang/IntKt", "-", "(II)I")
                        invokeStatic("Script", "factorial", "(I)I")
                        invokeStatic("jafun/lang/IntKt", "*", "(II)I")
                        ireturn()
                    }
                }
            }
        }
    }

    @Test
    fun simpleFibonacci() {
        test {
            file {
                code = """
fun fibonacci(n: Int): Int {
when (n) {
0 -> 0
1 -> 1
else -> (fibonacci n - 1) + (fibonacci n - 2)
}
}
println fibonacci 13"""
                expectedOutput = "233\n"

            }
        }
    }

    @Test
    fun simpleFibonacci2() {
        test {
            file {

                code = """
fun fibonacci(n: Int): Int {
when {
n <= 1 -> n
else -> fibonacci(n - 1) + fibonacci(n - 2)
}
}
println fibonacci 13"""
                expectedOutput = "233\n"

            }
        }
    }

    @Test
    fun basicWhile() {
        test {
            file {
                code = """
var i = 0
while (i < 3) {
println i
i = i + 1
}"""

                expectedOutput = "0\n1\n2\n"
                jvmIr {
                    name = "Script"
                    method {
                        name = "main"
                        signature = "([Ljava/lang/String;)V"
                        loadConstant(0)
                        istore("i")
                        iload("i")
                        loadConstant(3)
                        invokeStatic("jafun/lang/IntKt", "﹤", "(II)Z")
                        ifequal(22)
                        iload("i")
                        invokeStatic("java/lang/Integer", "valueOf", "(I)Ljava/lang/Integer;")
                        invokeStatic("jafun/io/ConsoleKt", "println", "(Ljava/lang/Object;)V")
                        iload("i")
                        loadConstant(1)
                        invokeStatic("jafun/lang/IntKt", "+", "(II)I")
                        istore("i")
                        goto(-25)
                        `return`()
                    }
                }
            }
        }
    }


    @Test
    fun nestedWhile() {
        test {
            file {
                code = """
var i = 1
println "Start"
while (i < 4) {
var j = 1
while (j < 4) {
println i * j
j = j + 1
}
i = i + 1
}
println "End"
"""
                expectedOutput = "Start\n1\n2\n3\n2\n4\n6\n3\n6\n9\nEnd\n"
                jvmIr {
                    name = "Script"
                    method {
                        name = "main"
                        signature = "([Ljava/lang/String;)V"
                        loadConstant(1)
                        istore("i")
                        loadConstant("Start")
                        invokeStatic("jafun/io/ConsoleKt", "println", "(Ljava/lang/Object;)V")
                        iload("i")
                        loadConstant(4)
                        invokeStatic("jafun/lang/IntKt", "﹤", "(II)Z")
                        ifequal(50)
                        loadConstant(1)
                        istore("j")
                        iload("j")
                        loadConstant(4)
                        invokeStatic("jafun/lang/IntKt", "﹤", "(II)Z")
                        ifequal(27)
                        iload("i")
                        iload("j")
                        invokeStatic("jafun/lang/IntKt", "*", "(II)I")
                        invokeStatic("java/lang/Integer", "valueOf", "(I)Ljava/lang/Integer;")
                        invokeStatic("jafun/io/ConsoleKt", "println", "(Ljava/lang/Object;)V")
                        iload("j")
                        loadConstant(1)
                        invokeStatic("jafun/lang/IntKt", "+", "(II)I")
                        istore("j")
                        goto(-30)
                        iload("i")
                        loadConstant(1)
                        invokeStatic("jafun/lang/IntKt", "+", "(II)I")
                        istore("i")
                        goto(-53)
                        loadConstant("End")
                        invokeStatic("jafun/io/ConsoleKt", "println", "(Ljava/lang/Object;)V")
                        `return`()
                    }
                }
            }
        }
    }

    @Test
    fun whileWithInput() {
        test {
            file {
                code = """
val input = first(arguments)
var i = 0
val l = length(input)
while (i < l) {
val c = charAt(input, i)
println c
i = i + 1
}"""
                expectedOutput = "T\ne\ns\nt\n"
                params("Test")
            }
        }
    }

    @Test
    fun simpleObject() {
        test {
            file {


                code = """
val so = getSimpleObject5()
println so
val a = so.fetchA()
println a
"""
                expectedOutput = "SimpleObject(a=5)\n5\n"
            }
        }
    }
}
