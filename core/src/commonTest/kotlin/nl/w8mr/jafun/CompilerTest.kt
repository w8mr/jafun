package nl.w8mr.jafun.nl.w8mr.jafun

import nl.w8mr.jafun.compiler.ir2jvm.IRBuilder
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
import kotlin.test.Ignore
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
    actualBytes: Map<String, ByteArray>,
    className: String,
    methodName: String,
    params: Array<String>?,
    expectedOutput: String
): String


class CompilerTest {
    companion object {
        class TestContext() {
            var code: String? = null
            var bytecode: ByteArray? = null  // deprecated: use classBytecodes instead
            var classBytecodes: MutableMap<String, ByteArray> = mutableMapOf()
            var mainParams: Array<String>? = null
            var phase2Expected: List<ExpressionNode.Phase2Expression>? = null
            var expectedOutput: String? = null
        }

        fun test(block: TestDSL.() -> Unit) {
            IdentifierCache.reset()

            val files = mutableMapOf<String, TestContext>()
            val runner = TestDSLImpl(files)
            block.invoke(runner)

            val compiler = Compiler()

   //         val phase1Plugin = compiler.registerPlugin(Phase1, Phase1Plugin())
            val phase2Plugin = compiler.registerPlugin(Phase2, Phase2Plugin())
            val phase3Plugin = compiler.registerPlugin(Phase3, Phase3Plugin())
            val jvmirPlugin = compiler.registerPlugin(JVMIR, JVMIRPlugin())

            for ((path, file) in files) {
                val code = file.code?.trimIndent() ?: error("No code set for $path")

//                val parsePhase1 = Phase1Parser(.parse(code)
//                assertEquals(false, parsePhase1.first == null, "PHASE1 fail: ${parsePhase1.second}")

                val className = "Script"
                val methodName = "main"
                val allClasses = try {
                    compiler.compile(code, className, methodName)
                } catch (e: Throwable) {
                    e.printStackTrace()
//                    println("Phase1: ${phase1Plugin.phase1}")
                    println("Phase2:\n ${phase2Plugin.phase2?.joinToString("\n") { it.prettyPrint() }}")
                    println("Phase3:\n ${phase3Plugin.phase3?.prettyPrint()}")
                    println("IR:\n ${jvmirPlugin.jvmir?.print()}")
                    throw e
                }

                val actualBytes = allClasses[className] ?: error("Script class not found")
                allClasses.forEach { (name, bytes) -> writeFile(name, bytes) }

         //       println("Phase1: ${parsePhase1.first?.joinToString("") { it.prettyPrint() } }}")

                if (file.phase2Expected != null) {
                    if (file.phase2Expected != phase2Plugin.phase2) {
                        assertEquals(
                            file.phase2Expected?.map { it.prettyPrint() },
                            phase2Plugin.phase2?.map { it.prettyPrint() })
                    }
                } else {
                    println("Phase2:\n ${phase2Plugin.phase2?.joinToString("\n") { it.prettyPrint() }}")
                }
                println("Phase3:\n ${phase3Plugin.phase3?.prettyPrint()}")
                println("IR:\n ${jvmirPlugin.jvmir?.print()}")

                val result = runAndAssertOutput(
                    allClasses,
                    className,
                    methodName,
                    file.mainParams,
                    file.expectedOutput ?: TODO("Handle no expected case")
                )

                // Validate all expected class bytecodes
                for ((expectedClassName, expectedBytes) in file.classBytecodes) {
                    val actualClassBytes = allClasses[expectedClassName] 
                        ?: error("Expected class $expectedClassName not found in compiled output")
                    
                    if ((expectedBytes.zip(actualClassBytes).any { it.first != it.second })) {
                        compareDecompiled(expectedBytes, null /* change to boolean */, result, result, actualClassBytes)
                    } else {
                        assertContentEquals(expectedBytes, actualClassBytes)
                    }
                }

                // Backward compatibility: validate old bytecode field if set
                val expectedBytes = file.bytecode
                if (expectedBytes != null && !file.classBytecodes.containsKey("Script")) {
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

            /**
             * Validate JVM IR (bytecode) for a generated class.
             * 
             * Usage:
             *   - jvmIr { ... }                  // Validates "Script" class (default)
             *   - jvmIr("Address") { ... }       // Validates "Address" class
             *   - jvmIr("User") { ... }          // Validates "User" class
             * 
             * Can specify multiple jvmIr blocks to validate multiple generated classes.
             * Each block describes the expected methods and bytecode for that class.
             */
            fun jvmIr(className: String, block: ClassBuilder.ClassDSL.DSL.() -> Unit)
            fun phase2(block: Phase2Builder.() -> Unit)

        }

        class TestFileDSLImpl(val context: TestContext) : TestFileDSL {
            override var code by context::code
            override var expectedOutput by context::expectedOutput
            override fun params(vararg params: String) {
                context.mainParams = params.toList().toTypedArray()
            }

            override fun jvmIr(className: String, block: ClassBuilder.ClassDSL.DSL.() -> Unit) {
                val wrappedBlock: ClassBuilder.ClassDSL.DSL.() -> Unit = {
                    name = className  // Auto-set the class name
                    block()           // Run user's block
                }
                val bytecode = classBuilder(wrappedBlock).write()
                context.classBytecodes[className] = bytecode
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
            ) = ExpressionNode.MethodInvocation(
                methodName = method.name,
                parentPath = method.parentPath,
                parameters = method.parameters,
                rtnLookup = { method.rtn },
                field = null,
                arguments = parameters.toList(),
            )


            fun invocation(
                method: Type.JFMethod,
                field: Type.JFField,
                vararg parameters: ExpressionNode.Phase2_3Expression,
            ) = ExpressionNode.MethodInvocation(
                methodName = method.name,
                parentPath = method.parentPath,
                parameters = method.parameters,
                rtnLookup = { method.rtn },
                field = field,
                arguments = parameters.toList(),
            )

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
                jvmIr("Script") {
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
                jvmIr("Script") {
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
                jvmIr("Script") {
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
                jvmIr("Script") {
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
                jvmIr("Script") {
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
                jvmIr("Script") {
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
                jvmIr("Script") {
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
                jvmIr("Script") {
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
                jvmIr("Script") {
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
                jvmIr("Script") {
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
                jvmIr("Script") {
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
    fun stringInterpolationSimple() {
        test {
            file {
                code = "val num = 42\n" +
                    "println(\"The answer to the ultimate question of Life, the Universe, and Everything is \$num.\")"
                expectedOutput = "The answer to the ultimate question of Life, the Universe, and Everything is 42.\n"
                jvmIr("Script") {
                    method {
                        name = "main"
                        signature = "([Ljava/lang/String;)V"
                        loadConstant(42)
                        istore("num")
                        `new`("java/lang/StringBuilder")
                        dup()
                        invokeSpecial("java/lang/StringBuilder", "<init>", "()V")
                        loadConstant("The answer to the ultimate question of Life, the Universe, and Everything is ")
                        invokeVirtual("java/lang/StringBuilder", "append", "(Ljava/lang/String;)Ljava/lang/StringBuilder;")
                        iload("num")
                        invokeStatic("java/lang/String", "valueOf", "(I)Ljava/lang/String;")
                        invokeVirtual("java/lang/StringBuilder", "append", "(Ljava/lang/String;)Ljava/lang/StringBuilder;")
                        loadConstant(".")
                        invokeVirtual("java/lang/StringBuilder", "append", "(Ljava/lang/String;)Ljava/lang/StringBuilder;")
                        invokeVirtual("java/lang/StringBuilder", "toString", "()Ljava/lang/String;")
                        invokeStatic("jafun/io/ConsoleKt", "println", "(Ljava/lang/Object;)V")
                        `return`()
                    }
                }
            }
        }
    }

    @Test
    fun stringInterpolationExpression() {
        test {
            file {
                code = "val num = 21\n" +
                        "val num2 = 21\n" +
                        "println(\"The answer to the ultimate question of Life, the Universe, and Everything is \${num + num2}.\")"
                expectedOutput = "The answer to the ultimate question of Life, the Universe, and Everything is 42.\n"
                jvmIr("Script") {
                    method {
                        name = "main"
                        signature = "([Ljava/lang/String;)V"
                        loadConstant(21)
                        istore("num")
                        loadConstant(21)
                        istore("num2")
                        `new`("java/lang/StringBuilder")
                        dup()
                        invokeSpecial("java/lang/StringBuilder", "<init>", "()V")
                        loadConstant("The answer to the ultimate question of Life, the Universe, and Everything is ")
                        invokeVirtual("java/lang/StringBuilder", "append", "(Ljava/lang/String;)Ljava/lang/StringBuilder;")
                        iload("num")
                        iload("num2")
                        invokeStatic("jafun/lang/IntKt" , "+", "(II)I")
                        invokeStatic("java/lang/String", "valueOf", "(I)Ljava/lang/String;")
                        invokeVirtual("java/lang/StringBuilder", "append", "(Ljava/lang/String;)Ljava/lang/StringBuilder;")
                        loadConstant(".")
                        invokeVirtual("java/lang/StringBuilder", "append", "(Ljava/lang/String;)Ljava/lang/StringBuilder;")
                        invokeVirtual("java/lang/StringBuilder", "toString", "()Ljava/lang/String;")
                        invokeStatic("jafun/io/ConsoleKt", "println", "(Ljava/lang/Object;)V")
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
                    1 + 2"""
                expectedOutput = ""
                jvmIr("Script") {
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
                    1 + 2 * 3"""
                expectedOutput = ""
                jvmIr("Script") {
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
                jvmIr("Script") {
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
                jvmIr("Script") {
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
                jvmIr("Script") {
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
                jvmIr("Script") {
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
                jvmIr("Script") {
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
                jvmIr("Script") {
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
                jvmIr("Script") {
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
                jvmIr("Script") {
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
                jvmIr("Script") {
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
                jvmIr("Script") {
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
                jvmIr("Script") {
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
                jvmIr("Script") {
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
                jvmIr("Script") {
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
                jvmIr("Script") {
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
                jvmIr("Script") {
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
                jvmIr("Script") {
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
                jvmIr("Script") {
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
                jvmIr("Script") {
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
                jvmIr("Script") {
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
                jvmIr("Script") {
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
                jvmIr("Script") {
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
                jvmIr("Script") {
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
    fun funForwardsFunctionCallFromFunction() {
        test {
            file {
                code = """
                    fun test(prefix: String) { 
                        test2(prefix, 10)
                    }
                    fun test2(prefix: String, a: Int) {
                        print prefix
                        println a
                    }
                    test "test: "
                    test2("test: ", 5)"""
                expectedOutput = "test: 10\ntest: 5\n"
                jvmIr("Script") {
                    method {
                        name = "main"
                        signature = "([Ljava/lang/String;)V"
                        loadConstant("test: ")
                        invokeStatic("Script", "test", "(Ljava/lang/String;)V")
                        loadConstant("test: ")
                        loadConstant(5)
                        invokeStatic("Script", "test2", "(Ljava/lang/String;I)V")
                        `return`()
                    }
                    method {
                        name = "test"
                        signature = "(Ljava/lang/String;)V"
                        aload("prefix")
                        loadConstant(10)
                        invokeStatic("Script", "test2", "(Ljava/lang/String;I)V")
                        `return`()
                    }
                    method {
                        name = "test2"
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
    fun forwardRefExplicitIntReturn() {
        test {
            file {
                code = """
                    fun a() {
                        b()
                    }
                    fun b(): Int {
                        42
                    }
                    println a()"""
                expectedOutput = "42\n"
                jvmIr("Script") {
                    method {
                        name = "main"
                        signature = "([Ljava/lang/String;)V"
                        invokeStatic("Script", "a", "()I")
                        invokeStatic("java/lang/Integer", "valueOf", "(I)Ljava/lang/Integer;")
                        invokeStatic("jafun/io/ConsoleKt", "println", "(Ljava/lang/Object;)V")
                        `return`()
                    }
                    method {
                        name = "a"
                        signature = "()I"
                        invokeStatic("Script", "b", "()I")
                        ireturn()
                    }
                    method {
                        name = "b"
                        signature = "()I"
                        loadConstant(42)
                        ireturn()
                    }
                }
            }
        }
    }

    @Test
    fun forwardRefImplicitIntReturn() {
        test {
            file {
                code = """
                    fun a() {
                        b()
                    }
                    fun b() {
                        42
                    }
                    println a()"""
                expectedOutput = "42\n"
                jvmIr("Script") {
                    method {
                        name = "main"
                        signature = "([Ljava/lang/String;)V"
                        invokeStatic("Script", "a", "()I")
                        invokeStatic("java/lang/Integer", "valueOf", "(I)Ljava/lang/Integer;")
                        invokeStatic("jafun/io/ConsoleKt", "println", "(Ljava/lang/Object;)V")
                        `return`()
                    }
                    method {
                        name = "a"
                        signature = "()I"
                        invokeStatic("Script", "b", "()I")
                        ireturn()
                    }
                    method {
                        name = "b"
                        signature = "()I"
                        loadConstant(42)
                        ireturn()
                    }
                }
            }
        }
    }

    @Test
    fun forwardRefChain() {
        test {
            file {
                code = """
                    fun a() {
                        b() + 1
                    }
                    fun b() {
                        c()
                    }
                    fun c(): Int {
                        10
                    }
                    println a()"""
                expectedOutput = "11\n"
                jvmIr("Script") {
                    method {
                        name = "main"
                        signature = "([Ljava/lang/String;)V"
                        invokeStatic("Script", "a", "()I")
                        invokeStatic("java/lang/Integer", "valueOf", "(I)Ljava/lang/Integer;")
                        invokeStatic("jafun/io/ConsoleKt", "println", "(Ljava/lang/Object;)V")
                        `return`()
                    }
                    method {
                        name = "a"
                        signature = "()I"
                        invokeStatic("Script", "b", "()I")
                        loadConstant(1)
                        invokeStatic("jafun/lang/IntKt", "+", "(II)I")
                        ireturn()
                    }
                    method {
                        name = "b"
                        signature = "()I"
                        invokeStatic("Script", "c", "()I")
                        ireturn()
                    }
                    method {
                        name = "c"
                        signature = "()I"
                        loadConstant(10)
                        ireturn()
                    }
                }
            }
        }
    }

    @Test
    fun mutualRecursion() {
        test {
            file {
                code = """
                    fun a(x: Int): Int {
                        when {
                            x == 0 -> 0
                            else -> b(x - 1)
                        }
                    }
                    fun b(x: Int): Int {
                        a(x)
                    }
                    println a(3)"""
                expectedOutput = "0\n"
            }
        }
    }

    @Test
    fun forwardRefStringReturn() {
        test {
            file {
                code = """
                    fun a() {
                        b()
                    }
                    fun b(): String {
                        "hello"
                    }
                    println a()"""
                expectedOutput = "hello\n"
                jvmIr("Script") {
                    method {
                        name = "main"
                        signature = "([Ljava/lang/String;)V"
                        invokeStatic("Script", "a", "()Ljava/lang/String;")
                        invokeStatic("jafun/io/ConsoleKt", "println", "(Ljava/lang/Object;)V")
                        `return`()
                    }
                    method {
                        name = "a"
                        signature = "()Ljava/lang/String;"
                        invokeStatic("Script", "b", "()Ljava/lang/String;")
                        areturn()
                    }
                    method {
                        name = "b"
                        signature = "()Ljava/lang/String;"
                        loadConstant("hello")
                        areturn()
                    }
                }
            }
        }
    }

    @Test
    fun forwardRefFromNestedFunction() {
        test {
            file {
                code = """
                    fun a() {
                        fun nested() {
                            b()
                        }
                        nested()
                    }
                    fun b(): String {
                        "hello"
                    }
                    println a()"""
                expectedOutput = "hello\n"
                jvmIr("Script") {
                    method {
                        name = "main"
                        signature = "([Ljava/lang/String;)V"
                        invokeStatic("Script", "a", "()Ljava/lang/String;")
                        invokeStatic("jafun/io/ConsoleKt", "println", "(Ljava/lang/Object;)V")
                        `return`()
                    }
                    method {
                        name = "a"
                        signature = "()Ljava/lang/String;"
                        invokeStatic("Script", "nested", "()Ljava/lang/String;")
                        areturn()
                    }
                    method {
                        name = "nested"
                        signature = "()Ljava/lang/String;"
                        invokeStatic("Script", "b", "()Ljava/lang/String;")
                        areturn()
                    }
                    method {
                        name = "b"
                        signature = "()Ljava/lang/String;"
                        loadConstant("hello")
                        areturn()
                    }
                }
            }
        }
    }

    @Test
    fun forwardRefBooleanReturn() {
        test {
            file {
                code = """
                    fun a() {
                        b()
                    }
                    fun b(): Boolean {
                        true
                    }
                    println a()"""
                expectedOutput = "true\n"
                jvmIr("Script") {
                    method {
                        name = "main"
                        signature = "([Ljava/lang/String;)V"
                        invokeStatic("Script", "a", "()Z")
                        invokeStatic("java/lang/Boolean", "valueOf", "(Z)Ljava/lang/Boolean;")
                        invokeStatic("jafun/io/ConsoleKt", "println", "(Ljava/lang/Object;)V")
                        `return`()
                    }
                    method {
                        name = "a"
                        signature = "()Z"
                        invokeStatic("Script", "b", "()Z")
                        ireturn()
                    }
                    method {
                        name = "b"
                        signature = "()Z"
                        loadConstant(1)
                        ireturn()
                    }
                }
            }
        }
    }

    @Test
    fun forwardRefWithConditionalGuard() {
        test {
            file {
                code = """
                    fun a() {
                        when {
                            1 == 1 -> b()
                            else -> c()
                        }
                    }
                    fun b(): String {
                        "from b"
                    }
                    fun c(): String {
                        "from c"
                    }
                    println a()"""
                expectedOutput = "from b\n"
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
                jvmIr("Script") {

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
                    println test()"""
                expectedOutput = "3\n"
                jvmIr("Script") {

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
                jvmIr("Script") {
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
                jvmIr("Script") {
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
                jvmIr("Script") {
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
                            val a = a + 2
                            println a
                        }
                        println a
                    }
                    println a"""
                expectedOutput = "6\n4\n2\n"
                jvmIr("Script") {
                    method {
                        name = "main"
                        signature = "([Ljava/lang/String;)V"
                        loadConstant(2)
                        istore("0.a")
                        loadConstant(4)
                        istore("1.a")
                        iload("1.a")
                        loadConstant(2)
                        invokeStatic("jafun/lang/IntKt", "+", "(II)I")
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
                jvmIr("Script") {
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
                //TODO evaluate actual jvm bytecode
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
                jvmIr("Script") {
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
                jvmIr("Script") {
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
                code = """
                    1 == 1""".trimIndent()
                expectedOutput = ""
                jvmIr("Script") {
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
                code = """
                    'a' == 'a'""".trimIndent()
                expectedOutput = ""
                jvmIr("Script") {
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
                jvmIr("Script") {
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
                jvmIr("Script") {
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
                jvmIr("Script") {
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
                jvmIr("Script") {
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
    fun basicWhenWithSubjectValExpressionWithStringInterpolation() {
        test {
            file {
                code = """
                    println when (val a = 1 + 1) {
                        1 -> "One"
                        2 -> "Two ${'$'}a"
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
                            i(2) to ExpressionNode.StringTemplate(listOf(s("Two "), ExpressionNode.ExpressionList(listOf(variable(symbol("a",
                                OperandType.SInt32)))))),
                            i(3) to s("Three"),
                            b(true) to s("More")
                        )
                    )
                }
                expectedOutput = "Two 2\n"
                jvmIr("Script") {
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
                        goto(57)
                        iload("a")
                        loadConstant(2)
                        invokeStatic("jafun/lang/IntKt", "==", "(II)Z")
                        ifequal(30)
                        `new`("java/lang/StringBuilder")
                        dup()
                        invokeSpecial("java/lang/StringBuilder", "<init>", "()V")
                        loadConstant("Two ")
                        invokeVirtual("java/lang/StringBuilder", "append", "(Ljava/lang/String;)Ljava/lang/StringBuilder;")
                        iload("a")
                        invokeStatic("java/lang/String", "valueOf", "(I)Ljava/lang/String;")
                        invokeVirtual("java/lang/StringBuilder", "append", "(Ljava/lang/String;)Ljava/lang/StringBuilder;")
                        invokeVirtual("java/lang/StringBuilder", "toString", "()Ljava/lang/String;")
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
                jvmIr("Script") {
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
                jvmIr("Script") {
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
                jvmIr("Script") {
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
                    val l = input.length
                    while (i < l) {
                        val c = input.charAt(i)
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

    @Test
    fun simpleConstructor() {
        test {
            file {
                code = """
                    val p = jafun.test.POJO()
                    println p
                """.trimMargin()
                expectedOutput = "POJO(a=5)\n"
            }
        }
    }

    @Test
    fun constructorWithArguments() {
        test {
            file {
                code = """
                    val p = jafun.test.POJO(4)
                    println p 
                """.trimMargin()
                expectedOutput = "POJO(a=4)\n"
            }
        }
    }

    @Test
    fun valueClass() {
        test {
            file {
                code = """
                    value class Address(street: String, number: Int, city: String)
                    
                    val test = Address("Test", 1, "Test")
                    println test.number
                """.trimIndent()
                expectedOutput = "1\n"
                jvmIr("Script") {
                    method {
                        name = "main"
                        signature = "([Ljava/lang/String;)V"
                        loadConstant("Test")
                        astore("test_street")
                        loadConstant(1)
                        istore("test_number")
                        loadConstant("Test")
                        astore("test_city")
                        iload("test_number")
                        invokeStatic("java/lang/Integer", "valueOf", "(I)Ljava/lang/Integer;")
                        invokeStatic("jafun/io/ConsoleKt", "println", "(Ljava/lang/Object;)V")
                        `return`()
                    }
                }
            }
        }
    }

    @Test
    fun valueClassFunctionParam() {
        test {
            file {
                code = """
                    value class Address(number: Int, street: String)
                    fun printNumber(input: Address) {
                        println input.number
                    }
                    val test = Address(1, "x")
                    printNumber(test)
                """.trimIndent()
                expectedOutput = "1\n"
                jvmIr("Script") {
                    method {
                        name = "main"
                        signature = "([Ljava/lang/String;)V"
                        loadConstant(1)
                        istore("test_number")
                        loadConstant("x")
                        astore("test_street")
                        iload("test_number")
                        aload("test_street")
                        invokeStatic("Script", "printNumber", "(ILjava/lang/String;)V")
                        `return`()
                    }
                    method {
                        name = "printNumber"
                        signature = "(ILjava/lang/String;)V"
                        parameter("input_number")
                        parameter("input_street")
                        iload("input_number")
                        invokeStatic("java/lang/Integer", "valueOf", "(I)Ljava/lang/Integer;")
                        invokeStatic("jafun/io/ConsoleKt", "println", "(Ljava/lang/Object;)V")
                        `return`()
                    }
                }
            }
        }
    }

    @Test
    fun functionParamReverseAccessOrder() {
        test {
            file {
                code = """
                    fun test() {
                        fun subtract(a: Int, b: Int): Int {
                            b - a
                        }
                        println subtract(3, 5)
                    }
                    test()
                """.trimIndent()
                expectedOutput = "2\n"
            }
        }
    }

    @Test
    fun functionParamForwardAccessOrder() {
        test {
            file {
                code = """
                    fun test() {
                        fun subtract(a: Int, b: Int): Int {
                            a - b
                        }
                        println subtract(3, 5)
                    }
                    test()
                """.trimIndent()
                expectedOutput = "-2\n"
            }
        }
    }

    @Test
    fun valueClassTwoParamsReverseFieldOrder() {
        test {
            file {
                code = """
                    value class A(x: Int, y: String)
                    value class B(p: String, q: Int)
                    fun test(a: A, b: B) {
                        println b.q
                        println a.y
                        println a.x
                        println b.p
                    }
                    val a = A(10, "hello")
                    val b = B("world", 20)
                    test(a, b)
                """.trimIndent()
                expectedOutput = "20\nhello\n10\nworld\n"
                jvmIr("Script") {
                    method {
                        name = "main"
                        signature = "([Ljava/lang/String;)V"
                        loadConstant(10)
                        istore("a_x")
                        loadConstant("hello")
                        astore("a_y")
                        loadConstant("world")
                        astore("b_p")
                        loadConstant(20)
                        istore("b_q")
                        iload("a_x")
                        aload("a_y")
                        aload("b_p")
                        iload("b_q")
                        invokeStatic("Script", "test", "(ILjava/lang/String;Ljava/lang/String;I)V")
                        `return`()
                    }
                    method {
                        name = "test"
                        signature = "(ILjava/lang/String;Ljava/lang/String;I)V"
                        parameter("a_x")
                        parameter("a_y")
                        parameter("b_p")
                        parameter("b_q")
                        iload("b_q")
                        invokeStatic("java/lang/Integer", "valueOf", "(I)Ljava/lang/Integer;")
                        invokeStatic("jafun/io/ConsoleKt", "println", "(Ljava/lang/Object;)V")
                        aload("a_y")
                        invokeStatic("jafun/io/ConsoleKt", "println", "(Ljava/lang/Object;)V")
                        iload("a_x")
                        invokeStatic("java/lang/Integer", "valueOf", "(I)Ljava/lang/Integer;")
                        invokeStatic("jafun/io/ConsoleKt", "println", "(Ljava/lang/Object;)V")
                        aload("b_p")
                        invokeStatic("jafun/io/ConsoleKt", "println", "(Ljava/lang/Object;)V")
                        `return`()
                    }
                }
            }
        }
    }

    @Test
    fun vcReturnMultiField() {
        test {
            file {
                code = """
                    value class Address(number: Int, street: String)
                    fun makeAddress(): Address {
                        Address(1, "street")
                    }
                    println makeAddress().number
                """.trimIndent()
                expectedOutput = "1\n"
                jvmIr("Script") {
                    method {
                        name = "main"
                        signature = "([Ljava/lang/String;)V"
                        invokeStatic("Script", "makeAddress", "()LAddress;")
                        getField("Address", "number", "I")
                        invokeStatic("java/lang/Integer", "valueOf", "(I)Ljava/lang/Integer;")
                        invokeStatic("jafun/io/ConsoleKt", "println", "(Ljava/lang/Object;)V")
                        `return`()
                    }
                    method {
                        name = "makeAddress"
                        signature = "()LAddress;"
                        `new`("Address")
                        dup()
                        loadConstant(1)
                        loadConstant("street")
                        invokeSpecial("Address", "<init>", "(ILjava/lang/String;)V")
                        areturn()
                    }
                }
            }
        }
    }

    @Test
    fun vcReturnMultiFieldWithVal() {
        test {
            file {
                code = """
                    value class Address(number: Int, street: String)
                    fun makeAddress(): Address {
                        Address(1, "street")
                    }
                    val a = makeAddress()
                    println a.number
                """.trimIndent()
                expectedOutput = "1\n"
                jvmIr("Script") {
                    method {
                        name = "main"
                        signature = "([Ljava/lang/String;)V"
                        invokeStatic("Script", "makeAddress", "()LAddress;")
                        astore("a")
                        aload("a")
                        getField("Address", "number", "I")
                        invokeStatic("java/lang/Integer", "valueOf", "(I)Ljava/lang/Integer;")
                        invokeStatic("jafun/io/ConsoleKt", "println", "(Ljava/lang/Object;)V")
                        `return`()
                    }
                    method {
                        name = "makeAddress"
                        signature = "()LAddress;"
                        `new`("Address")
                        dup()
                        loadConstant(1)
                        loadConstant("street")
                        invokeSpecial("Address", "<init>", "(ILjava/lang/String;)V")
                        areturn()
                    }
                }
            }
        }
    }

    @Test
    fun vcReturnSingleField() {
        test {
            file {
                code = """
                    value class Id(value: Int)
                    fun makeId(): Id {
                        Id(42)
                    }
                    println makeId()
                """.trimIndent()
                expectedOutput = "42\n"
                jvmIr("Script") {
                    method {
                        name = "main"
                        signature = "([Ljava/lang/String;)V"
                        invokeStatic("Script", "makeId", "()I")
                        invokeStatic("java/lang/Integer", "valueOf", "(I)Ljava/lang/Integer;")
                        invokeStatic("jafun/io/ConsoleKt", "println", "(Ljava/lang/Object;)V")
                        `return`()
                    }
                    method {
                        name = "makeId"
                        signature = "()I"
                        loadConstant(42)
                        ireturn()
                    }
                }
            }
        }
    }

    @Test
    fun vcReturnSingleFieldWithVal() {
        test {
            file {
                code = """
                    value class Id(value: Int)
                    fun makeId(): Id {
                        Id(42)
                    }
                    val id = makeId()
                    println id
                """.trimIndent()
                expectedOutput = "42\n"
                jvmIr("Script") {
                    method {
                        name = "main"
                        signature = "([Ljava/lang/String;)V"
                        invokeStatic("Script", "makeId", "()I")
                        istore("id")
                        iload("id")
                        invokeStatic("java/lang/Integer", "valueOf", "(I)Ljava/lang/Integer;")
                        invokeStatic("jafun/io/ConsoleKt", "println", "(Ljava/lang/Object;)V")
                        `return`()
                    }
                    method {
                        name = "makeId"
                        signature = "()I"
                        loadConstant(42)
                        ireturn()
                    }
                }
            }
        }
    }

    @Test
    fun vcReturnSingleFieldFieldAccess() {
        test {
            file {
                code = """
                    value class Id(value: Int)
                    fun makeId(): Id {
                        Id(42)
                    }
                    println makeId().value
                """.trimIndent()
                expectedOutput = "42\n"
                jvmIr("Script") {
                    method {
                        name = "main"
                        signature = "([Ljava/lang/String;)V"
                        invokeStatic("Script", "makeId", "()I")
                        invokeStatic("java/lang/Integer", "valueOf", "(I)Ljava/lang/Integer;")
                        invokeStatic("jafun/io/ConsoleKt", "println", "(Ljava/lang/Object;)V")
                        `return`()
                    }
                    method {
                        name = "makeId"
                        signature = "()I"
                        loadConstant(42)
                        ireturn()
                    }
                }
            }
        }
    }

    @Test
    fun vcReturnSingleFieldFieldAccessWithVal() {
        test {
            file {
                code = """
                    value class Id(value: Int)
                    fun makeId(): Id {
                        Id(42)
                    }
                    val id = makeId()
                    println id.value
                """.trimIndent()
                expectedOutput = "42\n"
                jvmIr("Script") {
                    method {
                        name = "main"
                        signature = "([Ljava/lang/String;)V"
                        invokeStatic("Script", "makeId", "()I")
                        istore("id")
                        iload("id")
                        invokeStatic("java/lang/Integer", "valueOf", "(I)Ljava/lang/Integer;")
                        invokeStatic("jafun/io/ConsoleKt", "println", "(Ljava/lang/Object;)V")
                        `return`()
                    }
                    method {
                        name = "makeId"
                        signature = "()I"
                        loadConstant(42)
                        ireturn()
                    }
                }
            }
        }
    }

    @Test
    fun vcChangeAddress() {
        test {
            file {
                code = """
                    value class Address(number: Int, street: String)
                    fun increaseHouseNumber(address: Address, increment: Int): Address {
                        Address(address.number + increment, address.street)
                    }
                    fun changeAddress(address: Address): Address {
                        increaseHouseNumber(address, 5)
                    }
                    val a = Address(4, "Privet Drive")
                    val b = changeAddress(a)
                    println b.number
                """.trimIndent()
                expectedOutput = "9\n"
                jvmIr("Script") {
                    method {
                        name = "main"
                        signature = "([Ljava/lang/String;)V"
                        loadConstant(4)
                        istore("a_number")
                        loadConstant("Privet Drive")
                        astore("a_street")
                        iload("a_number")
                        aload("a_street")
                        invokeStatic("Script", "changeAddress", "(ILjava/lang/String;)LAddress;")
                        astore("b")
                        aload("b")
                        getField("Address", "number", "I")
                        invokeStatic("java/lang/Integer", "valueOf", "(I)Ljava/lang/Integer;")
                        invokeStatic("jafun/io/ConsoleKt", "println", "(Ljava/lang/Object;)V")
                        `return`()
                    }
                    method {
                        name = "increaseHouseNumber"
                        signature = "(ILjava/lang/String;I)LAddress;"
                        parameter("address_number")
                        parameter("address_street")
                        parameter("increment")
                        new("Address")
                        dup()
                        iload("address_number")
                        iload("increment")
                        invokeStatic("jafun/lang/IntKt", "+", "(II)I")
                        aload("address_street")
                        invokeSpecial("Address", "<init>", "(ILjava/lang/String;)V")
                        areturn()
                    }
                    method {
                        name = "changeAddress"
                        signature = "(ILjava/lang/String;)LAddress;"
                        parameter("address_number")
                        parameter("address_street")
                        iload("address_number")
                        aload("address_street")
                        loadConstant(5)
                        invokeStatic("Script", "increaseHouseNumber", "(ILjava/lang/String;I)LAddress;")
                        areturn()
                    }
                }
                jvmIr("Address") {
                    // Public fields for value class Address(number: Int, street: String)
                    field(access = 1u, "number", "I")
                    field(access = 1u, "street", "Ljava/lang/String;")
                    method {
                        name = "<init>"
                        signature = "(ILjava/lang/String;)V"
                        access = 1u  // ACC_PUBLIC (not static)
                        // Register local variables: this, number, street
                        // In kasmine, parameter() just creates local var slots
                        parameter("this")
                        parameter("number")
                        parameter("street")
                        // Emit constructor bytecode
                        aload("this")
                        invokeSpecial("java/lang/Object", "<init>", "()V")
                        aload("this")
                        iload("number")
                        putField("Address", "number", "I")
                        aload("this")
                        aload("street")
                        putField("Address", "street", "Ljava/lang/String;")
                        `return`()
                     }
                }
            }
        }
    }

    @Test
    fun vcNestedIdInUser() {
        test {
            file {
                code = """
                    value class Id(value: Int)
                    value class User(id: Id, name: String)
                    fun getUserName(user: User): String {
                        user.name
                    }
                    val u = User(Id(42), "Alice")
                    println getUserName(u)
                """.trimIndent()
                expectedOutput = "Alice\n"
                jvmIr("Script") {
                    method {
                        name = "main"
                        signature = "([Ljava/lang/String;)V"
                        // Both User and Id are fully inlined at call site
                        // User(Id(42), "Alice") expands to just (42, "Alice")
                        loadConstant(42)           // id value (from Id)
                        istore("u_id_value")
                        loadConstant("Alice")      // name
                        astore("u_name")
                        iload("u_id_value")
                        aload("u_name")
                        invokeStatic("Script", "getUserName", "(ILjava/lang/String;)Ljava/lang/String;")
                        invokeStatic("jafun/io/ConsoleKt", "println", "(Ljava/lang/Object;)V")
                        `return`()
                    }
                    method {
                        name = "getUserName"
                        signature = "(ILjava/lang/String;)Ljava/lang/String;"
                        // Parameters are fully expanded: id (int) and name (String)
                        parameter("user_id")
                        parameter("user_name")
                         // Return user.name (second parameter)
                         aload("user_name")
                         areturn()
                     }
                 }
             }
         }
     }

    @Test
    fun vcNestedIdAccess() {
        test {
            file {
                code = """
                    value class Id(value: Int)
                    value class User(id: Id, name: String)
                    fun getId(user: User): Int {
                        user.id.value
                    }
                    val u = User(Id(42), "Alice")
                    println getId(u)
                """.trimIndent()
                expectedOutput = "42\n"
            }
        }
    }

     @Test
    fun testBoxSimple2() {
        test {
            file {
                code = """
                    value class Box(x: Int)
                    val b = Box(99)
                    println b.x
                """.trimIndent()
                expectedOutput = "99\n"
            }
        }
    }

    @Test
    fun testSimpleBoxAccess() {
        test {
            file {
                code = """
                    value class Point(x: Int, y: Int)
                    value class Box(topLeft: Point)
                    val p = Point(99, 10)
                    val b = Box(p)
                    println b.topLeft.x
                """.trimIndent()
                expectedOutput = "99\n"
            }
        }
    }

    @Test
    fun testBoxSingleField() {
        test {
            file {
                code = """
                    value class Point(x: Int, y: Int)
                    value class Box(topLeft: Point)
                    fun getPoint(box: Box): Point {
                        box.topLeft
                    }
                    val b = Box(Point(99, 10))
                    println getPoint(b).x
                """.trimIndent()
                expectedOutput = "99\n"
            }
        }
    }

    @Test
    fun testBoxSingleFieldWorking() {
        test {
            file {
                code = """
                    value class Point(x: Int, y: Int)
                    value class Box(topLeft: Point)
                    fun getPoint(box: Box): Point {
                        box.topLeft
                    }
                    println getPoint(Box(Point(99, 10))).x
                """.trimIndent()
                expectedOutput = "99\n"
            }
        }
    }


    @Test
    fun debugChainedAccess() {
        test {
            file {
                code = """
                    value class Point(x: Int, y: Int)
                    value class Box(topLeft: Point)
                    fun getX(box: Box): Int {
                        box.topLeft.x
                    }
                    val p = Point(99, 10)
                    val b = Box(p)
                    println getX(b)
                """.trimIndent()
                expectedOutput = "99\n"
            }
        }
    }

    @Test
    fun testMultiFieldVCFieldAccess() {
        test {
            file {
                code = """
                    value class Point(x: Int, y: Int)
                    fun getX(p: Point): Int {
                        p.x
                    }
                    val p = Point(42, 10)
                    println getX(p)
                """.trimIndent()
                expectedOutput = "42\n"
            }
        }
    }

    @Test
    fun vcChainedNestedAccess() {
        test {
            file {
                code = """
                    value class Point(x: Int, y: Int)
                    value class Box(topLeft: Point, bottomRight: Point)
                    fun getX(box: Box): Int {
                        box.topLeft.x
                    }
                    val b = Box(Point(99,10), Point(100,11))
                    println getX(b)
                """.trimIndent()
                expectedOutput = "99\n"
            }
        }
    }

    @Test
    fun testMultiArgReconstruction() {
        test {
            file {
                code = """
                    value class Point(x: Int, y: Int)
                    value class Box(topLeft: Point)
                    fun selectFirst(b: Box, p: Point): Point {
                        b.topLeft
                    }
                    val b = Box(Point(99, 10))
                    val p = Point(999, 1)
                    println selectFirst(b, p).x
                """.trimIndent()
                expectedOutput = "99\n"
            }
        }
    }

    @Test
    fun testVarWithInlineVC() {
        test {
            file {
                code = """
                    value class Box(x: Int)
                    var b = Box(99)
                    println b.x
                    b = Box(100)
                    println b.x
                """.trimIndent()
                expectedOutput = "99\n100\n"
            }
        }
    }

    @Test
    fun testVarWithMultiFieldVC() {
        test {
            file {
                code = """
                    value class Point(x: Int, y: Int)
                    var p = Point(1, 2)
                    println p.x
                    p = Point(3, 4)
                    println p.x
                """.trimIndent()
                expectedOutput = "1\n3\n"
            }
        }
    }

    @Test
    fun vcDeepNesting() {
        test {
            file {
                code = """
                    value class A(value: Int)
                    value class B(a: A)
                    value class C(b: B)
                    fun getValue(c: C): Int {
                        c.b.a.value
                    }
                    val c = C(B(A(42)))
                    println getValue(c)
                """.trimIndent()
                expectedOutput = "42\n"
            }
        }
    }

    @Test
    fun vcFieldAccessOnCallResult() {
        test {
            file {
                code = """
                    value class Point(x: Int, y: Int)
                    value class Box(topLeft: Point)
                    fun getTopLeft(b: Box): Point {
                        b.topLeft
                    }
                    val b = Box(Point(99, 10))
                    println getTopLeft(b).x
                """.trimIndent()
                expectedOutput = "99\n"
            }
        }
    }

    // ---- Deep / nested value class field access tests ----

    @Test
    fun vcDeepValidAccess() {
        // Three-level deep access through nested inline VCs should succeed
        val code = """
            value class A(value: Int)
            value class B(a: A)
            value class C(b: B)
            fun getValid(c: C): Int {
                c.b.a.value
            }
        """.trimIndent()
        val result = Compiler().compile(code, "Script", "main")
        kotlin.test.assertTrue(result.containsKey("Script"), "Should compile nested field access")
    }

    @Test
    fun vcFieldAccessOnValAssignment() {
        // Accessing a non-existent field on an expanded val variable should fail
        val code = """
            value class Point(x: Int, y: Int)
            fun test(): Int {
                val p = Point(1, 2)
                p.z
            }
        """.trimIndent()
        kotlin.test.assertFailsWith<Throwable> {
            Compiler().compile(code, "Script", "main")
        }
    }

    // TODO: Known bug — parser silently drops unrecognised .identifier tokens instead of
    // producing an error node. Fix requires changing fieldRhs to always consume .identifier
    // and produce a FieldAccess with Unknown type, so AST2IR can report the missing field.
    @Test
    @kotlin.test.Ignore
    fun vcDeepInvalidAccess() {
        val code = """
            value class A(value: Int)
            value class B(a: A)
            value class C(b: B)
            fun getInvalid(c: C): Int {
                c.b.z
            }
        """.trimIndent()
        kotlin.test.assertFailsWith<Throwable> {
            Compiler().compile(code, "Script", "main")
        }
    }
}





