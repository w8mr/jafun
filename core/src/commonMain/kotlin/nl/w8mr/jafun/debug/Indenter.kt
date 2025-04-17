package nl.w8mr.jafun.debug

class Indenter(val size: Int = 2) {
    val buffer = StringBuilder()

    inline fun writeln(text: Any = "") {
        buffer.append(text.toString())
        buffer.append("\n")
    }

    inline fun write(text: Any = "") {
        buffer.append(text.toString())
    }

    inline operator fun Any.unaryPlus() {
        writeln(this)
    }

    inline operator fun Any.unaryMinus() {
        write(this)
    }

    fun indent(code: Indenter.() -> Unit) {
        val localIndenter = Indenter(size)
        code.invoke(localIndenter)
        val output = localIndenter.toString()
        output.lines().dropLast(1).forEach { line ->
            buffer.append(" ".repeat(size))
            buffer.append(line)
            buffer.append('\n')
        }
    }

    override fun toString(): String {
        return buffer.toString()
    }
}
