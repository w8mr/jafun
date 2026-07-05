package nl.w8mr.jafun.nl.w8mr.jafun

import nl.w8mr.jafun.OperandType
import kotlin.test.Test

class FunctionTest {

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
}
