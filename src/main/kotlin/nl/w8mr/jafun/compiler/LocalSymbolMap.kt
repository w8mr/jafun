package nl.w8mr.jafun.compiler

import nl.w8mr.jafun.Type

data class LocalSymbolMap(val parent: SymbolMap, override val symbolMapId: Int = IdentifierCache.incSymbolMapCount()) : SymbolMap {
    private val identifierMap2 = mutableMapOf<Type.OperandType<*>?, MutableMap<String, List<Type.OperandType<*>>>>()

    override fun find(type: Type.OperandType<*>?, path: String): List<Type.OperandType<*>> =
        identifierMap2[type]?.get(path) ?: parent.find(type, path)


    override fun contains(type: Type.OperandType<*>?, path: String): Boolean {
        return identifierMap2[type]?.containsKey(path) ?: false
    }

    override fun add(type: Type.OperandType<*>?, path: String, typeSig: Type.OperandType<*>) {
        identifierMap2.computeIfAbsent(type) { type ->
            mutableMapOf()
        }
        identifierMap2[type]?.computeIfAbsent(path) { path ->
            listOf(typeSig)
        }
    }


    override fun incSymbolMapCount(): Int = parent.incSymbolMapCount()
}