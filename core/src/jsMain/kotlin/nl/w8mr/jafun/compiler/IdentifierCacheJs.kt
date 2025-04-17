package nl.w8mr.jafun.compiler

import nl.w8mr.jafun.Type
import nl.w8mr.jafun.TypeSymbol

class JsClassInfo(val name: String) : ClassInfo

actual object IdentifierCache : SymbolMap {
    private val identifierMap = mutableMapOf<TypeSymbol?, MutableMap<String, List<TypeSymbol>>>()
    private var symbolMapCounter: Int = 0
    private val classCache = mutableMapOf<String, Type.JFClass>()

    actual override val symbolMapId: Int = 0

    actual override fun find(
        type: TypeSymbol?,
        path: String,
    ): List<TypeSymbol> {
        return identifierMap[type]?.get(path) ?: emptyList()
    }

    actual override fun contains(
        type: TypeSymbol?,
        path: String,
    ): Boolean {
        return identifierMap[type]?.containsKey(path) ?: false
    }

    actual override fun add(
        type: TypeSymbol?,
        path: String,
        typeSig: TypeSymbol,
    ) {
        val typeMap = identifierMap.getOrPut(type) { mutableMapOf() }
        typeMap[path] = listOf(typeSig)
    }

    actual override fun incSymbolMapCount(): Int {
        symbolMapCounter++
        return symbolMapCounter
    }

    actual fun reset(): IdentifierCache {
        symbolMapCounter = 1
        return this
    }

    actual fun findClass(path: String): Type.JFClass {
        return classCache.getOrPut(path) {
            val lastDot = path.lastIndexOf('.')
            val name = if (lastDot >= 0) path.substring(lastDot + 1) else path
            val packagePath = if (lastDot >= 0) path.substring(0, lastDot) else ""

            val packageParts = packagePath.split('.')
            var currentPackage: Type.JFPackage? = null

            for (part in packageParts) {
                if (part.isNotEmpty()) {
                    currentPackage = Type.JFPackage(part, currentPackage)
                }
            }

            Type.JFClass(name, currentPackage)
        }
    }

    actual override fun findOrAddClass(clazz: ClassInfo): Type.JFClass {
        if (clazz is JsClassInfo) {
            return findClass(clazz.name)
        }
        throw IllegalArgumentException("Unsupported class info type: ${clazz::class}")
    }
}
