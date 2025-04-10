package jafun.io.test

import nl.w8mr.jafun.compiler.Associativity
import nl.w8mr.jafun.compiler.FunctionAssociativity
import nl.w8mr.jafun.compiler.FunctionPrecedence


fun join(
    str1: String,
    str2: String,
) = listOf(str1, str2).joinToString(separator = " ")

fun reverse(str: String) = str.reversed()

fun first(strings: Array<String>) = strings.first()
fun length(string: String) = string.length

@FunctionPrecedence(40)
@FunctionAssociativity(Associativity.POSTFIX)
fun euro(n: Int) = n * 100

@FunctionPrecedence(40)
@FunctionAssociativity(Associativity.POSTFIX)
fun cent(n: Int) = n

// @FunctionPrecedence(10)
// @FunctionAssociativity(Associativity.SOLO)
// fun test() = 5

fun `﹤=﹥`(n: Int) = 1
