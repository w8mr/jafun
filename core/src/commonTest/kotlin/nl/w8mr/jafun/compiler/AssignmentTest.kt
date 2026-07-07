package nl.w8mr.jafun.nl.w8mr.jafun

import nl.w8mr.jafun.OperandType
import kotlin.test.Test
import kotlin.test.assertEquals

class AssignmentTest {

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
                        iadd()
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
}
