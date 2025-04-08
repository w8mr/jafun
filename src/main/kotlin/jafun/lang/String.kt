package jafun.lang

import jafun.compiler.Associativity
import jafun.compiler.FunctionAssociativity
import jafun.compiler.FunctionPrecedence

@FunctionPrecedence(40)
@FunctionAssociativity(Associativity.PREFIX)
fun charAt(
    str: String,
    index: Int,
): Char = str[index]
