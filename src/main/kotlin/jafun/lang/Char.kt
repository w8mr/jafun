package jafun.lang

import jafun.compiler.Associativity
import jafun.compiler.FunctionAssociativity
import jafun.compiler.FunctionPrecedence

@Suppress("ktlint:standard:function-naming")
@FunctionPrecedence(40)
@FunctionAssociativity(Associativity.INFIXL)
fun `==`(
    a: Char,
    b: Char,
) = a == b
