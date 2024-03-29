package nl.w8mr.jafun.nl.w8mr.jafun

import nl.w8mr.jafun.test
import nl.w8mr.jafun.testBytes
import nl.w8mr.jafun.writeFile
import nl.w8mr.kasmine.ClassBuilder
import nl.w8mr.kasmine.classBuilder
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

class CompilerTest {
    fun String.runCommand(workingDir: File): String? {
        try {
            val parts = this.split("\\s".toRegex())
            val proc =
                ProcessBuilder(*parts.toTypedArray())
                    .directory(workingDir)
                    .redirectOutput(ProcessBuilder.Redirect.PIPE)
                    .redirectError(ProcessBuilder.Redirect.PIPE)
                    .start()

            proc.waitFor(60, TimeUnit.MINUTES)
            return proc.inputStream.bufferedReader().readText()
        } catch (e: IOException) {
            e.printStackTrace()
            return null
        }
    }

    fun test(
        code: String,
        result: String,
    ) = assertEquals(result, test(code))

    fun test(
        code: String,
        result: String,
        bytecode: ClassBuilder.ClassDSL.DSL.() -> Unit,
    ) {
        val tested = testBytes(code)
        val expected = classBuilder(bytecode).write()
        if ((result != tested.first) || (expected.zip(tested.second).any { it.first != it.second })) {
            val resultDecompiled = "javap -v Script.class".runCommand(File("./build/classes/jafun/test"))
            writeFile("Script", expected)
            val expectedDecompiled = "javap -v Script.class".runCommand(File("./build/classes/jafun/test"))
            assertEquals(expectedDecompiled, resultDecompiled)
            assertEquals(result, tested.first)
            assertContentEquals(expected, tested.second)
        } else {
            assertEquals(result, tested.first)
            assertContentEquals(expected, tested.second)
        }
    }

    @Test
    fun helloWorldParens() {
        test(
            """
                println("Hello World")""",
            "Hello World\n",
        ) {
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

    @Test
    fun helloWorldParensCompile() {
        test(
            """
            println(join("Hello","World"))""",
            "Hello World\n",
        ) {
            name = "Script"
            method {
                name = "main"
                signature = "([Ljava/lang/String;)V"
                loadConstant("Hello")
                loadConstant("World")
                invokeStatic("jafun/io/ConsoleKt", "join", "(Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;")
                invokeStatic("jafun/io/ConsoleKt", "println", "(Ljava/lang/Object;)V")
                `return`()
            }
        }
    }

    @Test
    fun helloWorldDoubleNoParens() {
        test(
            """
            println reverse "Hello World"""",
            "dlroW olleH\n",
        ) {
            name = "Script"
            method {
                name = "main"
                signature = "([Ljava/lang/String;)V"
                loadConstant("Hello World")
                invokeStatic("jafun/io/ConsoleKt", "reverse", "(Ljava/lang/String;)Ljava/lang/String;")
                invokeStatic("jafun/io/ConsoleKt", "println", "(Ljava/lang/Object;)V")
                `return`()
            }
        }
    }

    @Test
    fun helloWorldJoinCompile() {
        test(
            """
            println join("Hello", "World")""",
            "Hello World\n",
        ) {
            name = "Script"
            method {
                name = "main"
                signature = "([Ljava/lang/String;)V"
                loadConstant("Hello")
                loadConstant("World")
                invokeStatic("jafun/io/ConsoleKt", "join", "(Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;")
                invokeStatic("jafun/io/ConsoleKt", "println", "(Ljava/lang/Object;)V")
                `return`()
            }
        }
    }

    @Test
    fun helloWorldJoinVariableCompile() {
        test(
            """
            val str1 = join("Hello", "World")
            println str1""",
            "Hello World\n",
        ) {
            name = "Script"
            method {
                name = "main"
                signature = "([Ljava/lang/String;)V"
                loadConstant("Hello")
                loadConstant("World")
                invokeStatic("jafun/io/ConsoleKt", "join", "(Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;")
                astore("str1")
                aload("str1")
                invokeStatic("jafun/io/ConsoleKt", "println", "(Ljava/lang/Object;)V")
                `return`()
            }
        }
    }

    @Test
    fun helloWorldTripleLevelCompile() {
        test(
            """
            println join(join("Hello World", "1"), join("2", "3"))""",
            "Hello World 1 2 3\n",
        ) {
            name = "Script"
            method {
                name = "main"
                signature = "([Ljava/lang/String;)V"
                loadConstant("Hello World")
                loadConstant("1")
                invokeStatic("jafun/io/ConsoleKt", "join", "(Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;")
                loadConstant("2")
                loadConstant("3")
                invokeStatic("jafun/io/ConsoleKt", "join", "(Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;")
                invokeStatic("jafun/io/ConsoleKt", "join", "(Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;")
                invokeStatic("jafun/io/ConsoleKt", "println", "(Ljava/lang/Object;)V")
                `return`()
            }
        }
    }

    @Test
    fun twoArgCompile() {
        test(
            """
            join("Hello", "World")""",
            "",
        ) {
            name = "Script"
            method {
                name = "main"
                signature = "([Ljava/lang/String;)V"
                loadConstant("Hello")
                loadConstant("World")
                invokeStatic("jafun/io/ConsoleKt", "join", "(Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;")
                pop()
                `return`()
            }
        }
    }

    @Test
    fun helloWorldCompile() {
        test(
            """
            println "Hello World"
            print "Hello "
            println "World"""",
            "Hello World\nHello World\n",
        ) {
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

    @Test
    fun complexHelloWorldCompile() {
        test(
            """
            System.out.println "Hello World"
            java.lang.System.out.println "Hello World"""",
            "Hello World\nHello World\n",
        ) {
            name = "Script"
            method {
                name = "main"
                signature = "([Ljava/lang/String;)V"
                getStatic("java/lang/System", "out", "Ljava/io/PrintStream;")
                loadConstant("Hello World")
                invokeVirtual("java/io/PrintStream", "println", "(Ljava/lang/String;)V")
                getStatic("java/lang/System", "out", "Ljava/io/PrintStream;")
                loadConstant("Hello World")
                invokeVirtual("java/io/PrintStream", "println", "(Ljava/lang/String;)V")
                `return`()
            }
        }
    }

    @Test
    fun assignmentSimple() {
        test(
            """
            val str1 = "Hello World"
            println str1""",
            "Hello World\n",
        ) {
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

    @Test
    fun assignmentTwoVariables() {
        test(
            """
            val str2 = "World"
            val str1 = "Hello"
            println join(str1, str2)""",
            "Hello World\n",
        ) {
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
                invokeStatic("jafun/io/ConsoleKt", "join", "(Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;")
                invokeStatic("jafun/io/ConsoleKt", "println", "(Ljava/lang/Object;)V")
                `return`()
            }
        }
    }

    @Test
    fun integer() {
        test(
            """
            val i = 128
            println i""",
            "128\n",
        ) {
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

    @Test
    fun inlineAssign() {
        test(
            """
            println(val i = 128)
            println i""",
            "128\n128\n",
        ) {
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

    @Test
    fun plus() {
        test(
            """
            val i = 5
            val j = 11
            println i + j""",
            "16\n",
        ) {
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

    @Test
    fun calc() {
        val result =
            test(
                """
            println 4 + 3 * 5 - 6 / 2""",
                "16\n",
            ) {
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

    @Test
    fun operatorIdentifier() {
        test(
            """
            val abc = 3
            val def = 4
            println abc+def""",
            "7\n",
        ) {
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

    @Test
    fun powerTimes() {
        test(
            """
            println 2**5*3""",
            "96\n",
        ) {
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

    @Test
    fun infixr() {
        test(
            """
            println 4**3**2""",
            "262144\n",
        ) {
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

    @Test
    fun postfixOperator() {
        test(
            """
            println 5++""",
            "6\n",
        ) {
            name = "Script"
            method {
                name = "main"
                signature = "([Ljava/lang/String;)V"
                loadConstant(5)
                invokeStatic("jafun/lang/IntKt", "++", "(I)I")
                invokeStatic("java/lang/Integer", "valueOf", "(I)Ljava/lang/Integer;")
                invokeStatic("jafun/io/ConsoleKt", "println", "(Ljava/lang/Object;)V")
                `return`()
            }
        }
    }

    @Test
    fun postfixFunction() {
        test(
            """
            println 5 euro + 20 cent""",
            "520\n",
        ) {
            name = "Script"
            method {
                name = "main"
                signature = "([Ljava/lang/String;)V"
                loadConstant(5)
                invokeStatic("jafun/io/ConsoleKt", "euro", "(I)I")
                loadConstant(20)
                invokeStatic("jafun/io/ConsoleKt", "cent", "(I)I")
                invokeStatic("jafun/lang/IntKt", "+", "(II)I")
                invokeStatic("java/lang/Integer", "valueOf", "(I)Ljava/lang/Integer;")
                invokeStatic("jafun/io/ConsoleKt", "println", "(Ljava/lang/Object;)V")
                `return`()
            }
        }
    }

    @Test
    fun funDeclaration() {
        test(
            """
            fun test() { println 2 }""",
            "",
        ) {
            name = "Script"
            method {
                name = "test"
                signature = "()V"
                loadConstant(2)
                invokeStatic("java/lang/Integer", "valueOf", "(I)Ljava/lang/Integer;")
                invokeStatic("jafun/io/ConsoleKt", "println", "(Ljava/lang/Object;)V")
                `return`()
            }
            method {
                name = "main"
                signature = "([Ljava/lang/String;)V"
                `return`()
            }
        }
    }

    @Test
    fun funDeclarationAndInvocation() {
        test(
            """
            fun test() { println 2 }
            test
            test()""",
            "2\n2\n",
        ) {
            name = "Script"
            method {
                name = "test"
                signature = "()V"
                loadConstant(2)
                invokeStatic("java/lang/Integer", "valueOf", "(I)Ljava/lang/Integer;")
                invokeStatic("jafun/io/ConsoleKt", "println", "(Ljava/lang/Object;)V")
                `return`()
            }
            method {
                name = "main"
                signature = "([Ljava/lang/String;)V"
                invokeStatic("Script", "test", "()V")
                invokeStatic("Script", "test", "()V")
                `return`()
            }
        }
    }

    @Test
    fun funDeclarationAndInvocationWithParam() {
        test(
            """
            fun test(a: Int) { 
                println 2 * a
            }
            test 2
            test(3)""",
            "4\n6\n",
        ) {
            name = "Script"
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
            method {
                name = "main"
                signature = "([Ljava/lang/String;)V"
                loadConstant(2)
                invokeStatic("Script", "test", "(I)V")
                loadConstant(3)
                invokeStatic("Script", "test", "(I)V")
                `return`()
            }
        }
    }

    @Test
    fun funDeclarationAndInvocationWithTwoParams() {
        test(
            """
            fun test(prefix: String, a: Int) { 
               print prefix
               println a
            }
            test("test: ",5)""",
            "test: 5\n",
        ) {
            name = "Script"
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
                name = "main"
                signature = "([Ljava/lang/String;)V"
                loadConstant("test: ")
                loadConstant(5)
                invokeStatic("Script", "test", "(Ljava/lang/String;I)V")
                `return`()
            }
        }
    }

    @Test
    fun funBackwardsFunctionCallFromFunction() {
        test(
            """
            fun test(prefix: String, a: Int) { 
               print prefix
               println a
            }
            fun test2(prefix: String) {
                test(prefix, 10)
            }
            test("test: ", 5)
            test2 "test: """",
            "test: 5\ntest: 10\n",
        ) {
            name = "Script"
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
        }
    }

    @Test
    fun stackNeutralTest() {
        test(
            """
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
                    1+2""",
            "",
        ) {
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

    @Test
    fun funReturnIntValue() {
        test(
            """
                    fun add2(a: Int) {
                      a + 2
                    }
                    println add2 4
                    println add2(6)""",
            "6\n8\n",
        ) {
            name = "Script"
            method {
                name = "add2"
                signature = "(I)I"
                iload("a")
                loadConstant(2)
                invokeStatic("jafun/lang/IntKt", "+", "(II)I")
                ireturn()
            }
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
        }
    }

    @Test
    fun funReturnStringValue() {
        test(
            """
                    fun prefixed(text: String) {
                      join("PREFIXED:", text)
                    }
                    println prefixed "Test"
                    println(prefixed("Test2"))""",
            "PREFIXED: Test\nPREFIXED: Test2\n",
        ) {
            name = "Script"
            method {
                name = "prefixed"
                signature = "(Ljava/lang/String;)Ljava/lang/String;"
                loadConstant("PREFIXED:")
                aload("text")
                invokeStatic("jafun/io/ConsoleKt", "join", "(Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;")
                areturn()
            }
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
        }
    }

    @Test
    fun scope() {
        test(
            """
                val a = 2
                {
                    val a = 4
                    {
                        val a = a
                        println a
                    }
                    println a
                }
                println a""",
            "4\n4\n2\n",
        ) {
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

    @Test
    fun integerLiterals() {
        test(
            """
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
                    println 32768""",
            "0\n1\n2\n3\n4\n5\n6\n127\n128\n32767\n32768\n",
        ) {
            name = "Script"
            method {
                name = "main"
                signature = "([Ljava/lang/String;)V"
                listOf(0, 1, 2, 3, 4, 5, 6, 127, 128, 32767, 32768).forEach {
                    loadConstant(it)
                    invokeStatic("java/lang/Integer", "valueOf", "(I)Ljava/lang/Integer;")
                    invokeStatic("jafun/io/ConsoleKt", "println", "(Ljava/lang/Object;)V")
                }
                `return`()
            }
        }
    }

    @Test
    fun basicBoolean() {
        test(
            """
                val b = true
                println b""",
            "true\n",
        ) {
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
