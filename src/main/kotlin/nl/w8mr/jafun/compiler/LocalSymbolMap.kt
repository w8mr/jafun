package nl.w8mr.jafun.compiler

import nl.w8mr.jafun.IR

data class LocalSymbolMap(val parent: SymbolMap, override val symbolMapId: Int = IdentifierCache.incSymbolMapCount()) : SymbolMap {
    private val identifierMap = mutableMapOf<String, List<IR.OperandType<*>>>()

    override fun find(path: String): List<IR.OperandType<*>> =
        identifierMap[path.replaceIllegalCharacters()] ?: parent.find(path)

    override fun contains(path: String) = identifierMap[path.replaceIllegalCharacters()] != null

    override fun add(
        path: String,
        typeSig: IR.OperandType<*>,
    ) {
        // TODO: Add shadow check
        identifierMap[path.replaceIllegalCharacters()] = listOf(typeSig)
    }

    override fun incSymbolMapCount(): Int = parent.incSymbolMapCount()
}