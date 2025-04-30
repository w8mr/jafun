package jafun.lang

import nl.w8mr.jafun.compiler.Associativity
import nl.w8mr.jafun.compiler.FunctionAssociativity
import nl.w8mr.jafun.compiler.FunctionPrecedence

@FunctionPrecedence(40)
@FunctionAssociativity(Associativity.PREFIX)
fun charAt(
    str: String,
    index: Int,
): Char = str[index]

@FunctionPrecedence(40)
@FunctionAssociativity(Associativity.PREFIX)
fun length(
    str: String,
): Int = str.length
