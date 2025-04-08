package nl.w8mr.jafun.compiler

import nl.w8mr.jafun.IR

interface SymbolMap {
    fun find(path: String): List<IR.OperandType<*>>
    fun findSingle(path: String) = find(path).single()
    fun findSingleOrNull(path: String) = find(path).singleOrNull()
    fun findFirst(path: String) = find(path).first()
    fun findFirstOrNull(path: String) = find(path).firstOrNull()

    operator fun contains(path: String): Boolean

    fun add(
        path: String,
        typeSig: IR.OperandType<*>,
    )

    fun incSymbolMapCount(): Int

    val symbolMapId: Int

    fun String.replaceIllegalCharacters() =
        this.replace('<', '﹤')
            .replace('>', '﹥')
            .replace('/', '∕')
}