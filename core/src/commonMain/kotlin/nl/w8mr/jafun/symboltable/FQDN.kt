package nl.w8mr.jafun.symboltable

@JvmInline
value class FQDN(val value: String) {
    val packageName: String
        get() = value.substringBeforeLast('.', "")
    val simpleName: String
        get() = value.substringAfterLast('.', value)

    operator fun plus(other: String) = FQDN("$value.$other")

    override fun toString(): String = value

    companion object {
        val ROOT = FQDN("")
        fun of(vararg parts: String) = FQDN(parts.joinToString("."))
    }
}
