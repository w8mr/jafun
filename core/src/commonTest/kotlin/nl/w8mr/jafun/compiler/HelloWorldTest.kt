package nl.w8mr.jafun.nl.w8mr.jafun

import kotlin.test.Test

class HelloWorldTest {

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
}
