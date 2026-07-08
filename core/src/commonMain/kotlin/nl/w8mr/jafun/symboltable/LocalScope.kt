package nl.w8mr.jafun.symboltable

import nl.w8mr.jafun.OperandType

data class VariableDef(
    val name: String,
    val type: OperandType<*>,
    val mutable: Boolean = false,
    val initialized: Boolean = true,
)

class LocalScope(
    val parent: LocalScope? = null,
    val id: Int = nextId,
) {
    private val variables = mutableMapOf<String, VariableDef>()

    fun add(name: String, variable: VariableDef) {
        if (variables.containsKey(name)) {
            throw IllegalStateException("Variable '$name' already defined in this scope")
        }
        variables[name] = variable
    }

    fun replace(name: String, variable: VariableDef) {
        variables[name] = variable
    }

    fun find(name: String): VariableDef? =
        variables[name] ?: parent?.find(name)

    fun containsLocally(name: String): Boolean =
        variables.containsKey(name)

    fun push(): LocalScope = LocalScope(this)

    fun pop(): LocalScope =
        parent ?: throw IllegalStateException("Already at top of scope stack")

    companion object {
        private var counter = 0
        private val nextId: Int get() = counter++
    }
}
