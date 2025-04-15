package nl.w8mr.jafun.compiler

import nl.w8mr.jafun.OperandType
import nl.w8mr.jafun.Type
import nl.w8mr.jafun.TypeSymbol

interface SymbolMap {
    fun findMethod(
        jfClass: Type.JFClass?,
        methodName: String,
        parameterTypes: List<OperandType<*>>,
    ): Type.JFMethod {
        val symbols = IdentifierCache.find(jfClass, methodName)
        val symbol = symbols.filterIsInstance<Type.JFMethod>().singleOrNull { methodSymbol ->
            methodSymbol.parameters.map { it.type } == parameterTypes
        }
            ?: error("No unambitious method found for $methodName with types ${parameterTypes.joinToString(",") { it.toString() }} ")
        return symbol
    }

    fun find(type: TypeSymbol?, path: String): List<TypeSymbol>
    fun findSingle(type: TypeSymbol?, path: String) = find(type, path).single()
    fun findSingleOrNull(type: TypeSymbol?, path: String) = find(type, path).singleOrNull()
    fun findFirst(type: TypeSymbol?, path: String) = find(type, path).first()
    fun findFirstOrNull(type: TypeSymbol?, path: String) = find(type, path).firstOrNull()


    fun contains(type: TypeSymbol?, path: String): Boolean

    fun add(
        type: TypeSymbol?, path: String,
        typeSig: TypeSymbol,
    )

    fun incSymbolMapCount(): Int

    val symbolMapId: Int

}

fun String.replaceIllegalCharacters() =
    this.replace('<', '﹤')
        .replace('>', '﹥')
        .replace('/', '∕')

