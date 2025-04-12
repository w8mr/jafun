package nl.w8mr.jafun

import nl.w8mr.jafun.Type.JFClass
import nl.w8mr.jafun.Type.JFVariableSymbol
import nl.w8mr.jafun.compiler.IdentifierCache
import nl.w8mr.jafun.compiler.LocalSymbolMap
import nl.w8mr.jafun.compiler.SymbolMap

class SymbolMapManager {
    var currentSymbolMap: SymbolMap = LocalSymbolMap(IdentifierCache.reset()).apply {
        add( // For main method
            "arguments",
            JFVariableSymbol("arguments", Type.Array(JFClass("java/lang/String")), this, false)
        )
    }

    fun find(path: String) = currentSymbolMap.find(path)
    fun findSingle(path: String) = currentSymbolMap.findSingle(path)
    fun findSingleOrNull(path: String) = currentSymbolMap.findSingleOrNull(path)

    fun add(path: String, typeSig: Type.OperandType<*> ) = currentSymbolMap.add(path, typeSig)

    fun newVariableSymbol(
        name: String,
        type: Type.OperandType<*>,
        mutable: Boolean
    ): JFVariableSymbol {
        if (name in currentSymbolMap) {
            throw IllegalStateException("Variable $name already defined")
        }
        val variableSymbol = JFVariableSymbol(name, type, currentSymbolMap, mutable)
        currentSymbolMap.add(name, variableSymbol)
        return variableSymbol
    }

    fun push() {
        currentSymbolMap = LocalSymbolMap(currentSymbolMap)
    }

    fun pop() {
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