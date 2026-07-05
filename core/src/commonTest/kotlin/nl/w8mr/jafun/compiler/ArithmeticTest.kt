package nl.w8mr.jafun.nl.w8mr.jafun

import kotlin.test.Test

class ArithmeticTest {

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
}
