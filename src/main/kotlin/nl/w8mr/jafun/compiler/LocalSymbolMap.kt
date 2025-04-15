package nl.w8mr.jafun.compiler

import nl.w8mr.jafun.TypeSymbol

data class LocalSymbolMap(val parent: SymbolMap, override val symbolMapId: Int = IdentifierCache.incSymbolMapCount()) : SymbolMap {
    private val identifierMap = mutableMapOf<TypeSymbol?, MutableMap<String, List<TypeSymbol>>>()

    override fun find(type: TypeSymbol?, path: String): List<TypeSymbol> =
        identifierMap[type]?.get(path) ?: parent.find(type, path)


    override fun contains(type: TypeSymbol?, path: String): Boolean {
        return identifierMap[type]?.containsKey(path) ?: false
    }

    override fun add(type: TypeSymbol?, path: String, typeSig: TypeSymbol) {
        identifierMap.computeIfAbsent(type) { type ->
            mutableMapOf()
        }
        identifierMap[type]?.computeIfAbsent(path) { path ->
            listOf(typeSig)
        }
    }


    override fun incSymbolMapCount(): Int = parent.incSymbolMapCount()
}