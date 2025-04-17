package nl.w8mr.jafun.compiler

import nl.w8mr.jafun.Type
import nl.w8mr.jafun.TypeSymbol

data class LocalSymbolMap(val parent: SymbolMap, override val symbolMapId: Int = IdentifierCache.incSymbolMapCount()) : SymbolMap {
    private val identifierMap = mutableMapOf<TypeSymbol?, MutableMap<String, List<TypeSymbol>>>()

    override fun find(
        type: TypeSymbol?,
        path: String,
    ): List<TypeSymbol> = identifierMap[type]?.get(path) ?: parent.find(type, path)

    override fun contains(
        type: TypeSymbol?,
        path: String,
    ): Boolean {
        return identifierMap[type]?.containsKey(path) ?: false
    }

    override fun add(
        type: TypeSymbol?,
        path: String,
        typeSig: TypeSymbol,
    ) {
        val typeMap = identifierMap.getOrPut(type) { mutableMapOf() }
        typeMap[path] = listOf(typeSig)
    }

    override fun findOrAddClass(clazz: ClassInfo): Type.JFClass {
        return parent.findOrAddClass(clazz)
    }

    override fun incSymbolMapCount(): Int = parent.incSymbolMapCount()
}
