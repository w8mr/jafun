package jafun.lang

import nl.w8mr.jafun.compiler.Associativity
import nl.w8mr.jafun.compiler.FunctionAssociativity
import nl.w8mr.jafun.compiler.FunctionPrecedence

@Suppress("ktlint:standard:function-naming")
@FunctionPrecedence(40)
@FunctionAssociativity(Associativity.INFIXL)
fun `==`(
    a: Char,
    b: Char,
) = a == b
