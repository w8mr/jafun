package nl.w8mr.jafun.compiler

import nl.w8mr.jafun.Type

data class LocalSymbolMap(val parent: SymbolMap, override val symbolMapId: Int = IdentifierCache.incSymbolMapCount()) : SymbolMap {
    private val identifierMap = mutableMapOf<String, List<Type.OperandType<*>>>()

    override fun find(path: String): List<Type.OperandType<*>> =
        identifierMap[path] ?: parent.find(path)

    override fun contains(path: String) = identifierMap[path] != null

    override fun add(
        path: String,
        typeSig: Type.OperandType<*>,
    ) {
        // TODO: Add shadow check
        identifierMap[path] = listOf(typeSig)
    }

    override fun incSymbolMapCount(): Int = parent.incSymbolMapCount()
}