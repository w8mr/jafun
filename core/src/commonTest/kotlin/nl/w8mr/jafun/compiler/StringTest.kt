package nl.w8mr.jafun.nl.w8mr.jafun

import kotlin.test.Test

class StringTest {

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
}
