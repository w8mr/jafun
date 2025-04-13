package nl.w8mr.jafun.compiler

import nl.w8mr.jafun.Type

data class LocalSymbolMap(val parent: SymbolMap, override val symbolMapId: Int = IdentifierCache.incSymbolMapCount()) : SymbolMap {
    private val identifierMap = mutableMapOf<String, List<Type.OperandType<*>>>()
    private val identifierMap2 = mutableMapOf<Type.OperandType<*>?, Map<String, List<Type.OperandType<*>>>>()

    override fun find(path: String): List<Type.OperandType<*>> =
        identifierMap[path] ?: parent.find(path)

    override fun find(type: Type.OperandType<*>?, path: String): List<Type.OperandType<*>> =
        identifierMap2[type]?.get(path) ?: parent.find(type, path)

    override fun contains(path: String) = identifierMap[path] != null

    override fun add(
        path: String,
        typeSig: Type.OperandType<*>,
    ) {
        // TODO: Add shadow check
        identifierMap[path] = listOf(typeSig)
    }

    override fun contains(type: Type.OperandType<*>, path: String): Boolean {
        return identifierMap2[type]?.containsKey(path) ?: parent.contains(type, path)
    }

    override fun add(type: Type.OperandType<*>, path: String, typeSig: Type.OperandType<*>) {
        // TODO: Add shadow check
        identifierMap2[type] = mapOf(path to listOf(typeSig))
    }

    override fun incSymbolMapCount(): Int = parent.incSymbolMapCount()
}