package nl.w8mr.jafun.symboltable

import nl.w8mr.jafun.OperandType
import nl.w8mr.jafun.compiler.Associativity

data class Parameter(
    val name: String,
    val type: OperandType<*>,
)

data class MethodDef(
    val id: FQDN,
    val name: String,
    val parentFqdn: FQDN?,
    val parameters: List<Parameter>,
    val rtn: OperandType<*>,
    val static: Boolean = false,
    val operator: Boolean = false,
    val associativity: Associativity = Associativity.PREFIX,
    val precedence: Int = 10,
    val inline: Boolean = false,
)
