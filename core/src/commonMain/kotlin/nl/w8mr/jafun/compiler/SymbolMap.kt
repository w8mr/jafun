package nl.w8mr.jafun.compiler

import nl.w8mr.jafun.OperandType
import nl.w8mr.jafun.Type
import nl.w8mr.jafun.TypeSymbol
import nl.w8mr.jafun.compiler.IdentifierCache.addClass
import nl.w8mr.jafun.compiler.IdentifierCache.addPackage

interface SymbolMap {
    val parent: SymbolMap?

    fun findMethod(
        typeSymbol: TypeSymbol?,
        methodName: String,
        parameterTypes: List<OperandType<*>>,
    ): Type.JFMethod {
        val symbols = IdentifierCache.find(typeSymbol, methodName)
        val symbol =
            symbols.filterIsInstance<Type.JFMethod>().singleOrNull { methodSymbol ->
                methodSymbol.parameters.map { it.type } == parameterTypes
            }
                ?: error("No unambitious method found for $methodName with types ${parameterTypes.joinToString(",") { it.toString() }} ")
        return symbol
    }

    fun find(
        type: TypeSymbol?
    ): Set<TypeSymbol>

    fun find(
        type: TypeSymbol?,
        path: String,
    ): Set<TypeSymbol>

    fun findSingle(
        type: TypeSymbol?,
        path: String,
    ) = find(type, path).single()

    fun findSingleOrNull(
        type: TypeSymbol?,
        path: String,
    ) = find(type, path).singleOrNull()

    fun findFirst(
        type: TypeSymbol?,
        path: String,
    ) = find(type, path).first()

    fun findFirstOrNull(
        type: TypeSymbol?,
        path: String,
    ) = find(type, path).firstOrNull()

    fun findFromPath(
        path: String,
        parent: Type? = null,
    ): Set<TypeSymbol> =
        if (path.contains('.')) {
            findFromPath(
                path.substringAfter('.'),
                IdentifierCache.find(
                    parent,
                    path.substringBefore('.'),
                ).singleOrNull() as? Type,
            )
        } else {
            IdentifierCache.find(parent, path)
        }

    fun findClass(
        path: String,
        parent: Type? = null,
    ) = findFromPath(path, parent).singleOrNull() as? Type.JFClass ?: error("$path is not a class")

    fun findOrAddClass(className: String, packageName: String, simpleName: String): Type.JFClass {
        val found = findFromPath(className, null) as? Type.JFClass
        if (found != null) {
            return found
        } else {
            val new: Type.JFClass =
                packageName.split('.').fold(null) { parent: Type.JFPackage?, name -> parent.addPackage(name) }
                    ?.addClass(simpleName) ?: error("")
            return new
        }
    }

    fun contains(
        type: TypeSymbol?,
        path: String,
    ): Boolean

    fun add(
        type: TypeSymbol?,
        path: String,
        typeSig: TypeSymbol,
    )

    fun replaceType(
        type: TypeSymbol?,
        path: String,
        typeSig: Type,
    )

    fun incSymbolMapCount(): Int

    val symbolMapId: Int

    fun addClassToSymbolMap(parent: TypeSymbol?, className: String) {
        IdentifierCache.findMethodsInClass(className).forEach {
            when (it.associativity) {
                Associativity.PREFIX -> add(parent, it.name, it)
                else -> add(it.parameters[0].type, it.name, it)
            }
        }
        IdentifierCache.findConstructorsInClass(className).forEach {
            add(parent, it.name, it)
        }
    }

}

fun String.replaceIllegalCharacters() =
    this.replace('<', '﹤')
        .replace('>', '﹥')
        .replace('/', '∕')
