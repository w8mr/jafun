package nl.w8mr.jafun.symboltable

import nl.w8mr.jafun.OperandType

class SymbolTable {

    private var typeResolver: TypeResolver? = null

    fun setTypeResolver(resolver: TypeResolver) {
        typeResolver = resolver
    }

    // --- Type registry ---

    private val typeRegistry = mutableMapOf<FQDN, TypeEntry>()

    fun registerType(fqdn: FQDN, entry: TypeEntry) {
        typeRegistry[fqdn] = entry
    }

    fun lookupType(fqdn: FQDN): TypeEntry? {
        typeRegistry[fqdn]?.let { return it }
        val resolver = typeResolver ?: return null
        val resolved = resolver.resolveClass(fqdn.value) ?: return null
        registerType(fqdn, resolved)
        if (fqdn.packageName.isNotEmpty()) {
            val parts = fqdn.packageName.split(".")
            findOrCreatePackage(*parts.toTypedArray()).addClass(fqdn.simpleName, fqdn)
        }
        return resolved
    }

    fun lookupTypeByOperandType(operandType: OperandType<*>): TypeEntry? =
        typeRegistry.values.firstOrNull { it.operandType == operandType }

    // --- JVM bridge ---

    private val jvmBridge = mutableMapOf<String, FQDN>()

    fun registerJvmBridge(jvmName: String, fqdn: FQDN) {
        jvmBridge[jvmName] = fqdn
    }

    fun lookupJvmBridge(jvmName: String): FQDN? = jvmBridge[jvmName]

    // --- Method registry ---

    private val methodRegistry = mutableMapOf<String, MutableMap<FQDN, MethodDef>>()
    private val methodById = mutableMapOf<FQDN, MethodDef>()

    fun registerMethod(def: MethodDef) {
        val overloads = methodRegistry.getOrPut(def.name) { mutableMapOf() }
        overloads[def.id] = def
        methodById[def.id] = def
    }

    fun lookupMethods(name: String): Map<FQDN, MethodDef>? =
        methodRegistry[name]

    fun lookupMethodById(id: FQDN): MethodDef? = methodById[id]

    fun findMethod(name: String, parameterTypes: List<OperandType<*>>): MethodDef? {
        val overloads = methodRegistry[name] ?: return null
        return overloads.values.firstOrNull { def ->
            def.parameters.map { it.type } == parameterTypes
        }
    }

    fun findMethodByFirstParamType(name: String, firstParamType: OperandType<*>): MethodDef? {
        val overloads = methodRegistry[name] ?: return null
        return overloads.values.firstOrNull { def ->
            def.parameters.isNotEmpty() && def.parameters[0].type == firstParamType
        }
    }

    // --- Inferred return types ---

    private val inferredReturnTypes = mutableMapOf<FQDN, OperandType<*>>()

    fun setInferredReturnType(id: FQDN, type: OperandType<*>) {
        inferredReturnTypes[id] = type
    }

    fun getInferredReturnType(id: FQDN): OperandType<*>? = inferredReturnTypes[id]

    fun actualReturnType(methodDef: MethodDef): OperandType<*> =
        if (methodDef.rtn == OperandType.Unit || methodDef.rtn == OperandType.Unknown)
            inferredReturnTypes[methodDef.id] ?: methodDef.rtn
        else
            methodDef.rtn

    // --- Package tree ---

    private val rootPackage = PackageNode("")

    fun findOrCreatePackage(vararg parts: String): PackageNode =
        rootPackage.findOrCreatePackage(*parts)

    fun findPackage(vararg parts: String): PackageNode? {
        var current: PackageNode = rootPackage
        for (part in parts) {
            current = current.subPackages[part] ?: return null
        }
        return current
    }

    // --- Imports ---

    private val imports = mutableSetOf<FQDN>()

    fun addImport(packageFqdn: FQDN) {
        // Normalize: strip trailing .* if present
        val normalized = if (packageFqdn.value.endsWith(".*"))
            FQDN(packageFqdn.value.removeSuffix(".*"))
        else packageFqdn
        imports.add(normalized)
    }

    fun removeImport(packageFqdn: FQDN) {
        imports.remove(packageFqdn)
    }

    fun hasImport(packageFqdn: FQDN): Boolean = packageFqdn in imports

    // --- Name resolution via imports ---

    fun resolveTypeByShortName(shortName: String): TypeEntry? {
        for (import in imports) {
            val pkg = findPackage(*import.value.split('.').filter { it.isNotEmpty() }.toTypedArray()) ?: continue
            val typeFqdn = pkg.findClass(shortName) ?: continue
            val entry = lookupType(typeFqdn) ?: continue
            return entry
        }
        return null
    }

    fun resolveFunctionsByName(shortName: String): Map<FQDN, MethodDef> {
        val result = mutableMapOf<FQDN, MethodDef>()
        for (import in imports) {
            val pkg = findPackage(*import.value.split('.').filter { it.isNotEmpty() }.toTypedArray()) ?: continue
            val ids = pkg.findFunctionIds(shortName)
            for (id in ids) {
                val overloads = methodRegistry[shortName] ?: continue
                val def = overloads[id] ?: continue
                result[id] = def
            }
        }
        return result
    }

    // --- Local scope ---

    private var currentScope = LocalScope()

    fun pushScope() {
        currentScope = currentScope.push()
    }

    fun popScope() {
        currentScope = currentScope.pop()
    }

    fun addVariable(name: String, variable: VariableDef) {
        currentScope.add(name, variable)
    }

    fun replaceVariable(name: String, variable: VariableDef) {
        currentScope.replace(name, variable)
    }

    fun lookupVariable(name: String): VariableDef? =
        currentScope.find(name)

    val currentScopeId: Int get() = currentScope.id

    // --- Runtime fallback via TypeResolver ---

    sealed class ResolutionResult {
        data class Type(val entry: TypeEntry) : ResolutionResult()
        data class Method(val def: MethodDef, val parentType: TypeEntry) : ResolutionResult()
    }

    fun resolveDottedName(dottedName: String): ResolutionResult? {
        val parts = dottedName.split(".")
        for (i in parts.indices.reversed()) {
            val typeFqdn = parts.take(i + 1).joinToString(".")
            val type = lookupType(FQDN(typeFqdn))
            if (type != null) {
                val members = if (i + 1 < parts.size) parts.drop(i + 1) else emptyList()
                if (members.isEmpty()) return ResolutionResult.Type(type)
                return resolveMemberChain(type, members)
            }
        }
        return null
    }

    private fun resolveMemberChain(type: TypeEntry, members: List<String>): ResolutionResult? {
        val resolver = typeResolver ?: return null
        var currentFqdn = type.fqdn.value

        for (k in members.indices) {
            val member = members[k]
            val isLast = k == members.lastIndex
            val memberResult = resolver.resolveMember(currentFqdn, member) ?: return null

            when (memberResult) {
                is MemberResult.Method -> {
                    if (isLast) return ResolutionResult.Method(memberResult.def, type)
                    val rtnType = resolver.resolveClass(
                        memberResult.def.rtn.toString()
                    ) ?: return null
                    currentFqdn = rtnType.fqdn.value
                }
                is MemberResult.Field -> {
                    if (isLast) return null
                    currentFqdn = memberResult.fieldTypeFqdn
                }
            }
        }
        return null
    }
}
