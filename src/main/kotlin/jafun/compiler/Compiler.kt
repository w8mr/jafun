package jafun.compiler

@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
annotation class FunctionAssociativity(val associativity: Associativity)

@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
annotation class FunctionPrecedence(val precedence: Int)

interface SymbolMap {
    fun find(path: String) : TypeSig?
    fun add(path: String, typeSig: TypeSig)

}

class LocalSymbolMap(val parent: SymbolMap): SymbolMap {
    private val identifierMap = mutableMapOf<String, TypeSig?>()
    override fun find(path: String): TypeSig? =
        identifierMap[path] ?: parent.find(path)

    override fun add(path: String, typeSig: TypeSig) {
        identifierMap[path] = typeSig
    }
}
object IdentifierCache: SymbolMap {
    val identifierMap = mutableMapOf<String, TypeSig?>()
    init {
        val systemOutPrintln = staticFieldMethod(
            "java.lang.System",
            "out",
            "java.io.PrintStream",
            "println"
        )
        identifierMap["System.out.println"]= systemOutPrintln
        identifierMap["java.lang.System.out.println"]= systemOutPrintln
    }

    private fun staticFieldMethod(
        containingClass: String,
        staticField: String,
        staticFieldType: String,
        methodName: String
    ): TypeSig {
        val parent = jfClass(containingClass)
        val field = JFField(parent, staticFieldType.replace('.','/'), staticField)
        return JFMethod(listOf(jfClass("java.lang.String")), field, methodName, VoidType, false)
    }

    override fun find(path: String) : TypeSig? {
        return identifierMap.computeIfAbsent(path) {
            val split = path.split(".")
            when {
                split.size == 1 -> {
                    val name = split[0]
                    val typeSigs = listOf("jafun.lang.IntKt", "jafun.io.ConsoleKt").map {
                        val jClass = Class.forName(it)
                        findInClass(jClass, name)
                    }.firstOrNull() { it != null }
                     typeSigs
                }
                else -> {
                    try {
                        val jClass = Class.forName(path)
                        JFClass(jClass.name.replace('.', '/'))
                    } catch (e: Exception) {
                        TODO()

                    }
                }
            }

        }
    }

    override fun add(path: String, typeSig: TypeSig) {
        identifierMap[path] = typeSig
    }

    private fun findInClass(jClass: Class<*>, name: String): TypeSig? {
        val jMethod = jClass.declaredMethods.find { it.name == name }
        return jMethod?.let {
            val params = jMethod.parameters.map { jvmType(it.type.name) }
            val returnName = jMethod.returnType.name
            val rtn = jvmType(returnName)
            val associativity =
                jMethod.annotations.filterIsInstance<FunctionAssociativity>().map(FunctionAssociativity::associativity)
                    .firstOrNull() ?: Associativity.PREFIX
            val precedence =
                jMethod.annotations.filterIsInstance<FunctionPrecedence>().map(FunctionPrecedence::precedence)
                    .firstOrNull() ?: 10
            val method = JFMethod(
                params, JFClass("${jClass.name.replace('.', '/')}"), name, rtn,
                true, associativity, precedence
            )
            method
        }
    }

    private val jvmTypes: Map<String, TypeSig> = mapOf(
        "byte" to ByteType,
        "char" to CharType,
        "double" to DoubleType,
        "float" to FloatType,
        "int" to IntegerType,
        "long" to LongType,
        "short" to ShortType,
        "boolean" to BooleanType,
        "void" to VoidType
    )

    private fun jvmType(returnName: String) = jvmTypes[returnName] ?: jfClass(returnName)

    private fun jfClass(name: String) = JFClass(name.replace('.', '/'))

}

interface HasPath {
    val path: String
}

data class JFClass(override val path: String): TypeSig, HasPath {
    override val signature: String = "L$path;"
}


data class JFField(val parent: JFClass, override val path: String, val name: String): TypeSig, HasPath {
    override val signature: String = "L${path};"
}

data class JFMethod(
    val parameters: List<TypeSig>,
    val parent: HasPath,
    val name: String,
    val rtn: TypeSig,
    val static: Boolean = false,
    val associativity: Associativity = Associativity.PREFIX,
    val precedence: Int = 10
): TypeSig {
    override val signature: String = rtn.signature


}

data class JFVariableSymbol(val name: String, val type: TypeSig): TypeSig {
    override val signature: String
        get() = type.signature
}


open class Generic(override val signature: String) : TypeSig {
    override fun equals(other: Any?) =
        other is TypeSig && this.signature == other.signature

    override fun hashCode() =
        signature.hashCode()
}

val ThisType = JFField(JFClass(""), "this", "this")

object ClassType: Generic("L")
object UnknownType: Generic("?")
object VoidType: Generic("V")
object ByteType: Generic("B")
object CharType: Generic("C")
object DoubleType: Generic("D")
object FloatType: Generic("F")
object IntegerType: Generic("I")
object LongType: Generic("J")
object ShortType: Generic("S")
object BooleanType: Generic("Z")

interface TypeSig {
    val signature: String
}
