package jafun.test

fun join(
    str1: String,
    str2: String,
) = listOf(str1, str2).joinToString(separator = " ")

fun reverse(str: String) = str.reversed()

fun first(strings: Array<String>) = strings.first()

data class SimpleObject(val a: Int) {
    fun fetchA(): Int = a
}

fun getSimpleObject5() = SimpleObject(5)

fun objectEquals(a: Any?, b: Any?): Boolean = a == b

fun objectHash(a: Any?): Int = a?.hashCode() ?: 0
