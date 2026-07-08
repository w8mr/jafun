package nl.w8mr.jafun.symboltable

import nl.w8mr.jafun.OperandType
import nl.w8mr.jafun.Type
import nl.w8mr.jafun.compiler.Associativity
import java.lang.reflect.Method
import java.lang.reflect.Modifier

class JvmTypeResolver(
    private val symbolTable: SymbolTable,
) : TypeResolver {

    override fun resolveClass(fqdn: String): TypeEntry? {
        val jvmClass = try {
            Class.forName(fqdn)
        } catch (_: ClassNotFoundException) {
            return null
        } catch (_: NoClassDefFoundError) {
            return null
        }
        val jfFqdn = FQDN(fqdn)
        val operandType = jvmClassToOperandType(jvmClass)
        return TypeEntry(jfFqdn, operandType, jvmName = fqdn)
    }

    override fun resolveMethods(classFqdn: String, methodName: String): List<MethodDef> {
        val jvmClass = try {
            Class.forName(classFqdn)
        } catch (_: ClassNotFoundException) {
            return emptyList()
        } catch (_: NoClassDefFoundError) {
            return emptyList()
        }
        return jvmClass.methods
            .filter { it.name == methodName }
            .map { toMethodDef(classFqdn, it) }
    }

    override fun resolveField(classFqdn: String, fieldName: String): VariableDef? {
        val jvmClass = try {
            Class.forName(classFqdn)
        } catch (_: ClassNotFoundException) {
            return null
        } catch (_: NoClassDefFoundError) {
            return null
        }
        val jvmField = try {
            jvmClass.getField(fieldName)
        } catch (_: NoSuchFieldException) {
            return null
        }
        return VariableDef(fieldName, jvmClassToOperandType(jvmField.type))
    }

    override fun resolveMember(classFqdn: String, memberName: String): MemberResult? {
        val jvmClass = try {
            Class.forName(classFqdn)
        } catch (_: ClassNotFoundException) {
            return null
        } catch (_: NoClassDefFoundError) {
            return null
        }
        val methods = jvmClass.methods.filter { it.name == memberName }
        if (methods.isNotEmpty()) {
            return MemberResult.Method(toMethodDef(classFqdn, methods.first()))
        }
        val field = try {
            jvmClass.getField(memberName)
        } catch (_: NoSuchFieldException) {
            return null
        }
        val fieldTypeName = field.type.canonicalName ?: field.type.name
        return MemberResult.Field(
            VariableDef(memberName, jvmClassToOperandType(field.type)),
            fieldTypeName,
        )
    }

    override fun resolveConstructors(classFqdn: String): List<MethodDef> {
        val jvmClass = try {
            Class.forName(classFqdn)
        } catch (_: ClassNotFoundException) {
            return emptyList()
        } catch (_: NoClassDefFoundError) {
            return emptyList()
        }
        return jvmClass.constructors.map { constructor ->
            val params = constructor.parameters.mapIndexed { i, param ->
                Parameter("p$i", jvmClassToOperandType(param.type))
            }
            val paramSig = constructor.parameterTypes.joinToString(",") { it.name }
            MethodDef(
                id = FQDN("$classFqdn.<init>($paramSig)"),
                name = "<init>",
                parentFqdn = FQDN(classFqdn),
                parameters = params,
                rtn = jvmClassToOperandType(jvmClass),
            )
        }
    }

    private fun toMethodDef(classFqdn: String, method: Method): MethodDef {
        val id = methodFqdn(classFqdn, method)
        val params = method.parameters.mapIndexed { i, param ->
            Parameter("p$i", jvmClassToOperandType(param.type))
        }
        val rtn = jvmClassToOperandType(method.returnType)
        val isStatic = Modifier.isStatic(method.modifiers)
        return MethodDef(
            id = id,
            name = method.name,
            parentFqdn = FQDN(classFqdn),
            parameters = params,
            rtn = rtn,
            static = isStatic,
            operator = false,
            associativity = Associativity.PREFIX,
            precedence = 10,
        )
    }

    private fun methodFqdn(classFqdn: String, method: Method): FQDN {
        val paramSig = method.parameterTypes.joinToString(",") { jvmTypeName(it) }
        return FQDN("$classFqdn.${method.name}($paramSig)")
    }

    private fun jvmTypeName(clazz: Class<*>): String = when {
        clazz.isPrimitive -> clazz.name
        clazz.isArray -> clazz.canonicalName ?: clazz.name
        else -> clazz.name
    }

    fun jvmClassToOperandType(jvmClass: Class<*>): OperandType<*> {
        val name = jvmClass.name
        return when (name) {
            "int" -> OperandType.SInt32
            "long" -> OperandType.SInt64
            "boolean" -> OperandType.UInt1
            "char" -> OperandType.CharType
            "void" -> OperandType.Unit
            "java.lang.String" -> OperandType.StringType
            "java.lang.Integer" -> OperandType.SInt32
            "java.lang.Long" -> OperandType.SInt64
            "java.lang.Boolean" -> OperandType.UInt1
            "java.lang.Character" -> OperandType.CharType
            "java.lang.Void" -> OperandType.Unit
            else -> {
                val simpleName = jvmClass.simpleName
                Type.JFClass(simpleName, null)
            }
        }
    }
}
