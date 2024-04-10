package jafun.lang

import jafun.compiler.Associativity
import jafun.compiler.FunctionAssociativity
import jafun.compiler.FunctionPrecedence

@Suppress("ktlint:standard:function-naming")
@FunctionPrecedence(40)
@FunctionAssociativity(Associativity.INFIXL)
fun `==`(
    a: Int,
    b: Int,
) = a == b

@Suppress("ktlint:standard:function-naming")
@FunctionPrecedence(50)
@FunctionAssociativity(Associativity.INFIXL)
fun `﹤=`(
    a: Int,
    b: Int,
) = a <= b

@Suppress("ktlint:standard:function-naming")
@FunctionPrecedence(50)
@FunctionAssociativity(Associativity.INFIXL)
fun `﹤`(
    a: Int,
    b: Int,
) = a < b

@Suppress("ktlint:standard:function-naming")
@FunctionPrecedence(50)
@FunctionAssociativity(Associativity.INFIXL)
fun `﹥`(
    a: Int,
    b: Int,
) = a > b

@Suppress("ktlint:standard:function-naming")
@FunctionPrecedence(50)
@FunctionAssociativity(Associativity.INFIXL)
fun `﹥=`(
    a: Int,
    b: Int,
) = a >= b

@Suppress("ktlint:standard:function-naming")
@FunctionPrecedence(100)
@FunctionAssociativity(Associativity.INFIXL)
fun `+`(
    a: Int,
    b: Int,
) = a + b

@Suppress("ktlint:standard:function-naming")
@FunctionPrecedence(100)
@FunctionAssociativity(Associativity.INFIXL)
fun `-`(
    a: Int,
    b: Int,
) = a - b

@Suppress("ktlint:standard:function-naming")
@FunctionPrecedence(110)
@FunctionAssociativity(Associativity.INFIXL)
fun `*`(
    a: Int,
    b: Int,
) = a * b

@Suppress("ktlint:standard:function-naming")
@FunctionPrecedence(110)
@FunctionAssociativity(Associativity.INFIXL)
fun `∕`(
    a: Int,
    b: Int,
) = a / b

@Suppress("ktlint:standard:function-naming")
@FunctionPrecedence(115)
@FunctionAssociativity(Associativity.INFIXR)
fun `**`(
    a: Int,
    b: Int,
): Int =
    when (b) {
        0 -> 1
        else -> a * `**`(a, b - 1)
    }

@Suppress("ktlint:standard:function-naming")
@FunctionPrecedence(140)
@FunctionAssociativity(Associativity.POSTFIX)
fun `++`(a: Int): Int = a + 1

@Suppress("ktlint:standard:function-naming")
@FunctionPrecedence(140)
@FunctionAssociativity(Associativity.POSTFIX)
fun `--`(a: Int): Int = a - 1
