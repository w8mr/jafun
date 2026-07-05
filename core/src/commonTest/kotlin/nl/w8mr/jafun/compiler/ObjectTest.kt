package nl.w8mr.jafun.nl.w8mr.jafun

import kotlin.test.Test

class ObjectTest {

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
}
