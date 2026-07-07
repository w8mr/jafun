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
                        iadd()
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
                        imul()
                        iadd()
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
                        iadd()
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
                        imul()
                        iadd()
                        loadConstant(6)
                        loadConstant(2)
                        idiv()
                        isub()
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
                        imul()
                        iadd()
                        loadConstant(6)
                        loadConstant(2)
                        idiv()
                        isub()
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
                        iadd()
                        loadConstant(6)
                        loadConstant(4)
                        isub()
                        imul()
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
                        iadd()
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
                        loadConstant(1)
                        istore("result")
                        loadConstant(0)
                        istore("i")
                        val _6 = label()
                        _6 {}
                        iload("i")
                        loadConstant(5)
                        val _16 = label()
                        val _17 = label()
                        if_icmplt(_16)
                        loadConstant(0)
                        goto(_17)
                        _16 {}
                        loadConstant(1)
                        _17 {}
                        val _35 = label()
                        ifequal(_35)
                        iload("result")
                        loadConstant(2)
                        imul()
                        istore("result")
                        iload("i")
                        loadConstant(1)
                        iadd()
                        istore("i")
                        goto(_6)
                        _35 {}
                        iload("result")
                        loadConstant(3)
                        imul()
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
                        isub()
                        loadConstant(2)
                        isub()
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
                        loadConstant(100)
                        imul()
                        loadConstant(20)
                        iadd()
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
