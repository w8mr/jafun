package nl.w8mr.jafun.compiler

import nl.w8mr.jafun.Type

interface SymbolMap {
    fun find(path: String): List<Type.OperandType<*>>
    fun findSingle(path: String) = find(path).single()
    fun findSingleOrNull(path: String) = find(path).singleOrNull()
    fun findFirst(path: String) = find(path).first()
    fun findFirstOrNull(path: String) = find(path).firstOrNull()
    fun findMethod(
        methodName: String,
        parameterTypes: List<Type.OperandType<out Any?>>
    ): Type.JFMethod {
        val symbols = IdentifierCache.find(methodName)
        val symbol = symbols.filterIsInstance<Type.JFMethod>().singleOrNull { methodSymbol ->
            methodSymbol.parameters.map { it.type } == parameterTypes
        }
            ?: error("No unambitious method found for $methodName with types ${parameterTypes.joinToString(",") { it.toString() }} ")
        return symbol
    }

    fun find(type: Type.OperandType<*>?, path: String): List<Type.OperandType<*>>
    fun findSingle(type: Type.OperandType<*>?, path: String) = find(type, path).single()
    fun findSingleOrNull(type: Type.OperandType<*>?, path: String) = find(type, path).singleOrNull()
    fun findFirst(type: Type.OperandType<*>?, path: String) = find(type, path).first()
    fun findFirstOrNull(type: Type.OperandType<*>?, path: String) = find(type, path).firstOrNull()


    fun contains(type: Type.OperandType<*>, path: String): Boolean

    fun add(
        type: Type.OperandType<*>, path: String,
        typeSig: Type.OperandType<*>,
    )

    operator fun contains(path: String): Boolean

    fun add(
        path: String,
        typeSig: Type.OperandType<*>,
    )


    fun incSymbolMapCount(): Int

    val symbolMapId: Int

}

fun String.replaceIllegalCharacters() =
    this.replace('<', '﹤')
        .replace('>', '﹥')
        .replace('/', '∕')

