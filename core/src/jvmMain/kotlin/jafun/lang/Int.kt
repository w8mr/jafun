package jafun.lang

import nl.w8mr.jafun.compiler.Associativity
import nl.w8mr.jafun.compiler.FunctionAssociativity
import nl.w8mr.jafun.compiler.FunctionName
import nl.w8mr.jafun.compiler.FunctionPrecedence

@Suppress("ktlint:standard:function-naming")
@FunctionPrecedence(140)
@FunctionAssociativity(Associativity.POSTFIX)
fun `++`(a: Int): Int = a + 1

@Suppress("ktlint:standard:function-naming")
@FunctionPrecedence(140)
@FunctionAssociativity(Associativity.POSTFIX)
fun `--`(a: Int): Int = a - 1
