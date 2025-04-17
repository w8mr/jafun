package nl.w8mr.jafun.compiler

import nl.w8mr.jafun.Type
import nl.w8mr.jafun.TypeSymbol

interface ClassInfo

expect object IdentifierCache : SymbolMap {
    override val symbolMapId: Int

    override fun find(
        type: TypeSymbol?,
        path: String,
    ): List<TypeSymbol>

    override fun contains(
        type: TypeSymbol?,
        path: String,
    ): Boolean

    override fun add(
        type: TypeSymbol?,
        path: String,
        typeSig: TypeSymbol,
    )

    override fun incSymbolMapCount(): Int

    fun reset(): IdentifierCache

    fun findClass(path: String): Type.JFClass

    override fun findOrAddClass(clazz: ClassInfo): Type.JFClass
}
