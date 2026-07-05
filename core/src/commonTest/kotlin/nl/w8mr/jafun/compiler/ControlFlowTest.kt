package nl.w8mr.jafun.nl.w8mr.jafun

import nl.w8mr.jafun.OperandType
import nl.w8mr.jafun.compiler.ExpressionNode
import kotlin.test.Test

class ControlFlowTest {

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
                        val after = label()
                        loadConstant(1)
                        loadConstant(1)
                        invokeStatic("jafun/lang/IntKt", "==", "(II)Z")
                        val next = label()
                        ifequal(next)
                        loadConstant("One")
                        goto(after)
                        next {
                            loadConstant("Else")
                        }
                        after {
                            pop()
                            `return`()
                        }

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
                        val after = label()
                        loadConstant(2)
                        istore("a")
                        iload("a")
                        loadConstant(1)
                        invokeStatic("jafun/lang/IntKt", "==", "(II)Z")
                        val next1 = label()
                        val next2 = label()
                        val next3 = label()
                        ifequal(next1)
                        loadConstant("One")
                        goto(after)
                        next1 {
                            iload("a")
                            loadConstant(2)
                            invokeStatic("jafun/lang/IntKt", "==", "(II)Z")
                            ifequal(next2)
                            loadConstant("Two")
                            goto(after)
                        }
                        next2 {
                            iload("a")
                            loadConstant(3)
                            invokeStatic("jafun/lang/IntKt", "==", "(II)Z")
                            ifequal(next3)
                            loadConstant("Three")
                            goto(after)
                        }
                        next3 {
                            loadConstant("More")
                        }
                        after {
                            invokeStatic("jafun/io/ConsoleKt", "println", "(Ljava/lang/Object;)V")
                            `return`()
                        }
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
                        val after = label()
                        val next1 = label()
                        val next2 = label()
                        val next3 = label()
                        loadConstant(2)
                        istore("a")
                        iload("a")
                        loadConstant(1)
                        invokeStatic("jafun/lang/IntKt", "==", "(II)Z")
                        ifequal(next1)
                        loadConstant("One")
                        goto(after)
                        next1 {
                            iload("a")
                            loadConstant(2)
                            invokeStatic("jafun/lang/IntKt", "==", "(II)Z")
                            ifequal(next2)
                            loadConstant("Two")
                            goto(after)
                        }
                        next2 {
                            iload("a")
                            loadConstant(3)
                            invokeStatic("jafun/lang/IntKt", "==", "(II)Z")
                            ifequal(next3)
                            loadConstant("Three")
                            goto(after)
                        }
                        next3 {
                            loadConstant("More")
                        }
                        after {
                            invokeStatic("jafun/io/ConsoleKt", "println", "(Ljava/lang/Object;)V")
                            `return`()
                        }
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
                        val after = label()
                        val next1 = label()
                        val next2 = label()
                        val next3 = label()
                        loadConstant(1)
                        loadConstant(1)
                        invokeStatic("jafun/lang/IntKt", "+", "(II)I")
                        istore("tmp")
                        iload("tmp")
                        loadConstant(1)
                        invokeStatic("jafun/lang/IntKt", "==", "(II)Z")
                        ifequal(next1)
                        loadConstant("One")
                        goto(after)
                        next1 {
                            iload("tmp")
                            loadConstant(2)
                            invokeStatic("jafun/lang/IntKt", "==", "(II)Z")
                            ifequal(next2)
                            loadConstant("Two")
                            goto(after)
                        }
                        next2 {
                            iload("tmp")
                            loadConstant(3)
                            invokeStatic("jafun/lang/IntKt", "==", "(II)Z")
                            ifequal(next3)
                            loadConstant("Three")
                            goto(after)
                        }
                        next3 {
                            loadConstant("More")
                        }
                        after {
                            invokeStatic("jafun/io/ConsoleKt", "println", "(Ljava/lang/Object;)V")
                            `return`()
                        }
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
                        val after = label()
                        val next1 = label()
                        val next2 = label()
                        val next3 = label()
                        loadConstant(1)
                        loadConstant(1)
                        invokeStatic("jafun/lang/IntKt", "+", "(II)I")
                        istore("a")
                        iload("a")
                        loadConstant(1)
                        invokeStatic("jafun/lang/IntKt", "==", "(II)Z")
                        ifequal(next1)
                        loadConstant("One")
                        goto(after)
                        next1 {
                            iload("a")
                            loadConstant(2)
                            invokeStatic("jafun/lang/IntKt", "==", "(II)Z")
                            ifequal(next2)
                            loadConstant("Two")
                            goto(after)
                        }
                        next2 {
                            iload("a")
                            loadConstant(3)
                            invokeStatic("jafun/lang/IntKt", "==", "(II)Z")
                            ifequal(next3)
                            loadConstant("Three")
                            goto(after)
                        }
                        next3 {
                            loadConstant("More")
                        }
                        after {
                            invokeStatic("jafun/io/ConsoleKt", "println", "(Ljava/lang/Object;)V")
                            `return`()
                        }
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
                        val after = label()
                        val next1 = label()
                        val next2 = label()
                        val next3 = label()
                        loadConstant(1)
                        loadConstant(1)
                        invokeStatic("jafun/lang/IntKt", "+", "(II)I")
                        istore("a")
                        iload("a")
                        loadConstant(1)
                        invokeStatic("jafun/lang/IntKt", "==", "(II)Z")
                        ifequal(next1)
                        loadConstant("One")
                        goto(after)
                        next1 {
                            iload("a")
                            loadConstant(2)
                            invokeStatic("jafun/lang/IntKt", "==", "(II)Z")
                            ifequal(next2)
                            `new`("java/lang/StringBuilder")
                            dup()
                            invokeSpecial("java/lang/StringBuilder", "<init>", "()V")
                            loadConstant("Two ")
                            invokeVirtual("java/lang/StringBuilder", "append", "(Ljava/lang/String;)Ljava/lang/StringBuilder;")
                            iload("a")
                            invokeStatic("java/lang/String", "valueOf", "(I)Ljava/lang/String;")
                            invokeVirtual("java/lang/StringBuilder", "append", "(Ljava/lang/String;)Ljava/lang/StringBuilder;")
                            invokeVirtual("java/lang/StringBuilder", "toString", "()Ljava/lang/String;")
                            goto(after)
                        }
                        next2 {
                            iload("a")
                            loadConstant(3)
                            invokeStatic("jafun/lang/IntKt", "==", "(II)Z")
                            ifequal(next3)
                            loadConstant("Three")
                            goto(after)
                        }
                        next3 {
                            loadConstant("More")
                        }
                        after {
                            invokeStatic("jafun/io/ConsoleKt", "println", "(Ljava/lang/Object;)V")
                            `return`()
                        }
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
                        val next = label()
                        ifequal(next)
                        loadConstant(1)
                        val after = label()
                        goto(after)
                        next {
                            iload("param1")
                            iload("param1")
                            loadConstant(1)
                            invokeStatic("jafun/lang/IntKt", "-", "(II)I")
                            invokeStatic("Script", "factorial", "(I)I")
                            invokeStatic("jafun/lang/IntKt", "*", "(II)I")
                        }
                        after {
                            ireturn()
                        }
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
                        val after = label()
                        val body = label()
                        body {
                            iload("i")
                            loadConstant(3)
                            invokeStatic("jafun/lang/IntKt", "﹤", "(II)Z")
                            ifequal(after)
                            iload("i")
                            invokeStatic("java/lang/Integer", "valueOf", "(I)Ljava/lang/Integer;")
                            invokeStatic("jafun/io/ConsoleKt", "println", "(Ljava/lang/Object;)V")
                            iload("i")
                            loadConstant(1)
                            invokeStatic("jafun/lang/IntKt", "+", "(II)I")
                            istore("i")
                            goto(body)
                        }
                        after {
                            `return`()
                        }
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
                        val outerAfter = label()
                        val outerBody = label()
                        val innerAfter = label()
                        val innerBody = label()
                        loadConstant(1)
                        istore("i")
                        loadConstant("Start")
                        invokeStatic("jafun/io/ConsoleKt", "println", "(Ljava/lang/Object;)V")
                        outerBody {
                            iload("i")
                            loadConstant(4)
                            invokeStatic("jafun/lang/IntKt", "﹤", "(II)Z")
                            ifequal(outerAfter)
                            loadConstant(1)
                            istore("j")
                        }
                        innerBody {
                            iload("j")
                            loadConstant(4)
                            invokeStatic("jafun/lang/IntKt", "﹤", "(II)Z")
                            ifequal(innerAfter)
                            iload("i")
                            iload("j")
                            invokeStatic("jafun/lang/IntKt", "*", "(II)I")
                            invokeStatic("java/lang/Integer", "valueOf", "(I)Ljava/lang/Integer;")
                            invokeStatic("jafun/io/ConsoleKt", "println", "(Ljava/lang/Object;)V")
                            iload("j")
                            loadConstant(1)
                            invokeStatic("jafun/lang/IntKt", "+", "(II)I")
                            istore("j")
                            goto(innerBody)
                        }
                        innerAfter {
                            iload("i")
                            loadConstant(1)
                            invokeStatic("jafun/lang/IntKt", "+", "(II)I")
                            istore("i")
                            goto(outerBody)
                        }
                        outerAfter {
                            loadConstant("End")
                            invokeStatic("jafun/io/ConsoleKt", "println", "(Ljava/lang/Object;)V")
                            `return`()
                        }
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
}
