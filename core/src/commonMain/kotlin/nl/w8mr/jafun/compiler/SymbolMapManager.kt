package nl.w8mr.jafun.compiler

import nl.w8mr.jafun.OperandType
import nl.w8mr.jafun.Type
import nl.w8mr.jafun.Type.JFClass
import nl.w8mr.jafun.Type.JFVariableSymbol
import nl.w8mr.jafun.TypeSymbol

class SymbolMapManager {
    var currentSymbolMap: SymbolMap =
        LocalSymbolMap(IdentifierCache.reset()).apply {
            add(
                null, // For main method
                "arguments",
                JFVariableSymbol("arguments", OperandType.Array(OperandType.StringType), this, false),
            )
        }

    fun reset() {
        currentSymbolMap =
            LocalSymbolMap(IdentifierCache.reset()).apply {
                add(
                    null, // For main method
                    "arguments",
                    JFVariableSymbol("arguments", OperandType.Array(OperandType.StringType), this, false),
                )
            }
    }

    fun findSingle(path: String) = currentSymbolMap.findSingle(null, path)

    fun findSingleOrNull(path: String) = currentSymbolMap.findSingleOrNull(null, path)

    fun find(
        type: TypeSymbol?,
    ) = currentSymbolMap.find(type)

    fun find(
        type: TypeSymbol?,
        path: String,
    ) = currentSymbolMap.find(type, path)

    fun findSingle(
        type: Type,
        path: String,
    ) = currentSymbolMap.findSingle(type, path)

    fun findSingleOrNull(
        type: Type,
        path: String,
    ) = currentSymbolMap.findSingleOrNull(type, path)

    fun add(
        path: String,
        typeSig: Type,
    ) = currentSymbolMap.add(null, path, typeSig)

    fun addClassToSymbolMap(parent: TypeSymbol? ,className: String) = currentSymbolMap.addClassToSymbolMap(parent, className)

    fun newVariableSymbol(
        name: String,
        type: OperandType<*>,
        mutable: Boolean,
    ): JFVariableSymbol {
        if (currentSymbolMap.contains(null, name)) {
            throw IllegalStateException("Variable $name already defined")
        }
        val variableSymbol = JFVariableSymbol(name, type, currentSymbolMap, mutable)
        currentSymbolMap.add(null, name, variableSymbol)
        return variableSymbol
    }

    private fun push() {
        currentSymbolMap = LocalSymbolMap(currentSymbolMap)
    }

    private fun pop() {
        val oldSymbolMap = currentSymbolMap
        currentSymbolMap =
            if (oldSymbolMap is LocalSymbolMap) {
                oldSymbolMap.parent
            } else {
                throw IllegalStateException("already at top of symbol map stack")
            }
    }

    fun <T> local(block: () -> T): T {
        push()
        return try {
            block()
        } catch (t: Throwable) {
            throw t
        } finally {
            pop()
        }
    }
}
