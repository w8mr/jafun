package jafun.io

import nl.w8mr.jafun.compiler.Associativity
import nl.w8mr.jafun.compiler.FunctionAssociativity
import nl.w8mr.jafun.compiler.FunctionPrecedence

fun print(text: Any?) = System.out.print(text)

fun println(text: Any?) = System.out.println(text)

fun join(
    str1: String,
    str2: String,
) = listOf(str1, str2).joinToString(separator = " ")

fun reverse(str: String) = str.reversed()

fun first(strings: Array<String>) = strings.first()
fun length(string: String) = string.length
//fun charAt(string: String, index: Int) = string[index]

@FunctionPrecedence(40)
@FunctionAssociativity(Associativity.POSTFIX)
fun euro(n: Int) = n * 100

@FunctionPrecedence(40)
@FunctionAssociativity(Associativity.POSTFIX)
fun cent(n: Int) = n

// @FunctionPrecedence(10)
// @FunctionAssociativity(Associativity.SOLO)
// fun test() = 5
