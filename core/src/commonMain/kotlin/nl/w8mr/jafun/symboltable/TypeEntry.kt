package nl.w8mr.jafun.symboltable

import nl.w8mr.jafun.OperandType

data class TypeEntry(
    val fqdn: FQDN,
    val operandType: OperandType<*>,
    val jvmName: String? = null,
)
