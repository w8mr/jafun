package nl.w8mr.jafun.nl.w8mr.jafun

import kotlin.test.Test

class IRBlockTest {

    @Test
    fun irMul() {
        test {
            file {
                code = """
                    val a = 3
                    val b = 4
                    val c = ir(a, b) { mul }
                    println c"""
                expectedOutput = "12\n"
            }
        }
    }

    @Test
    fun irAdd() {
        test {
            file {
                code = """
                    val a = 10
                    val b = 7
                    val c = ir(a, b) { add }
                    println c"""
                expectedOutput = "17\n"
            }
        }
    }

    @Test
    fun irSub() {
        test {
            file {
                code = """
                    val a = 20
                    val b = 8
                    val c = ir(a, b) { sub }
                    println c"""
                expectedOutput = "12\n"
            }
        }
    }

    @Test
    fun irDiv() {
        test {
            file {
                code = """
                    val a = 42
                    val b = 6
                    val c = ir(a, b) { div }
                    println c"""
                expectedOutput = "7\n"
            }
        }
    }

    @Test
    fun irCmpEq() {
        test {
            file {
                code = """
                    val a = 5
                    val b = 5
                    val c = ir(a, b) { cmpeq }
                    println c"""
                expectedOutput = "true\n"
            }
        }
    }

    @Test
    fun irCmpNe() {
        test {
            file {
                code = """
                    val a = 5
                    val b = 3
                    val c = ir(a, b) { cmpeq }
                    println c"""
                expectedOutput = "false\n"
            }
        }
    }

    @Test
    fun irCmpLt() {
        test {
            file {
                code = """
                    val a = 3
                    val b = 7
                    val c = ir(a, b) { cmplt }
                    println c"""
                expectedOutput = "true\n"
            }
        }
    }

    @Test
    fun irCmpLe() {
        test {
            file {
                code = """
                    val a = 5
                    val b = 5
                    val c = ir(a, b) { cmple }
                    println c"""
                expectedOutput = "true\n"
            }
        }
    }

    @Test
    fun irCmpGt() {
        test {
            file {
                code = """
                    val a = 10
                    val b = 3
                    val c = ir(a, b) { cmpgt }
                    println c"""
                expectedOutput = "true\n"
            }
        }
    }

    @Test
    fun irCmpGe() {
        test {
            file {
                code = """
                    val a = 5
                    val b = 5
                    val c = ir(a, b) { cmpge }
                    println c"""
                expectedOutput = "true\n"
            }
        }
    }
}
