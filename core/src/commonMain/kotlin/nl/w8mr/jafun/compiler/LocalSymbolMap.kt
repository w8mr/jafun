package nl.w8mr.jafun.compiler

import nl.w8mr.jafun.Type
import nl.w8mr.jafun.TypeSymbol

data class LocalSymbolMap(override val parent: SymbolMap, override val symbolMapId: Int = IdentifierCache.incSymbolMapCount()) : SymbolMap {
    private val identifierMap = mutableMapOf<TypeSymbol?, MutableMap<String, MutableSet<TypeSymbol>>>()

    override fun find(
        type: TypeSymbol?
    ): Set<TypeSymbol> =
        identifierMap.getOrPut(type) { mutableMapOf() }.values.flatten().toSet() + parent.find(type)

    override fun find(
        type: TypeSymbol?,
        path: String,
    ): Set<TypeSymbol> = identifierMap[type]?.get(path) ?: parent.find(type, path)

    override fun contains(
        type: TypeSymbol?,
        path: String,
    ): Boolean {
        return identifierMap[type]?.containsKey(path) == true
    }

    override fun add(
        type: TypeSymbol?,
        path: String,
        typeSig: TypeSymbol,
    ) {
        identifierMap.getOrPut(type) {
            mutableMapOf()
        }.getOrPut(path) {
            mutableSetOf()
        }.add(typeSig)
    }

    override fun replaceType(
        type: TypeSymbol?,
        path: String,
        typeSig: Type,
    ) {
        val list = identifierMap.get(type)?.get(path) ?: mutableListOf()
        if (list.isEmpty()) {
            parent?.replaceType(type, path, typeSig)
        } else {
            val removed = list.filter { it !is Type || it.name != typeSig.name }.toMutableList()
            removed.add(typeSig)
            identifierMap.get(type)?.put(path, removed.toMutableSet())
        }
    }


    override fun incSymbolMapCount(): Int = parent.incSymbolMapCount()
}
