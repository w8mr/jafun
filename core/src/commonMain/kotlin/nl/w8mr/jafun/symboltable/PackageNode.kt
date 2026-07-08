package nl.w8mr.jafun.symboltable

data class PackageNode(
    val name: String,
    val parent: PackageNode? = null,
) {
    val fqdn: FQDN
        get() = if (parent == null) FQDN(name)
        else if (parent!!.fqdn.value.isEmpty()) FQDN(name)
        else FQDN("${parent!!.fqdn.value}.$name")

    val subPackages = mutableMapOf<String, PackageNode>()
    val classes = mutableMapOf<String, FQDN>()
    val functions = mutableMapOf<String, MutableSet<FQDN>>()

    fun findOrCreatePackage(vararg parts: String): PackageNode {
        var current = this
        for (part in parts) {
            current = current.subPackages.getOrPut(part) { PackageNode(part, current) }
        }
        return current
    }

    fun addClass(simpleName: String, typeFqdn: FQDN) {
        classes[simpleName] = typeFqdn
    }

    fun addFunction(simpleName: String, methodId: FQDN) {
        functions.getOrPut(simpleName) { mutableSetOf() }.add(methodId)
    }

    fun findClass(simpleName: String): FQDN? = classes[simpleName]

    fun findFunctionIds(simpleName: String): Set<FQDN> =
        functions[simpleName] ?: emptySet()

    fun allFunctions(): Map<String, Set<FQDN>> = functions
}
