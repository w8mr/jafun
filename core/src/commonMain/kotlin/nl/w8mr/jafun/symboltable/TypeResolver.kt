package nl.w8mr.jafun.symboltable

sealed class MemberResult {
    data class Method(val def: MethodDef) : MemberResult()
    data class Field(val def: VariableDef, val fieldTypeFqdn: String) : MemberResult()
}

interface TypeResolver {
    fun resolveClass(fqdn: String): TypeEntry?

    fun resolveMethods(classFqdn: String, methodName: String): List<MethodDef>

    fun resolveField(classFqdn: String, fieldName: String): VariableDef?

    fun resolveMember(classFqdn: String, memberName: String): MemberResult?

    fun resolveConstructors(classFqdn: String): List<MethodDef>
}
