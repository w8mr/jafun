package jafun.compiler

import jafun.compiler.IdentifierCache.incSymbolMapCount

@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
annotation class FunctionAssociativity(val associativity: Associativity)

@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
annotation class FunctionPrecedence(val precedence: Int)

interface SymbolMap {
    fun find(path: String): TypeSig?

    fun add(
        path: String,
        typeSig: TypeSig,
    )

    fun incSymbolMapCount(): Int

    val symbolMapId: Int
}

data class LocalSymbolMap(val parent: SymbolMap, override val symbolMapId: Int = incSymbolMapCount()) : SymbolMap {
    private val identifierMap = mutableMapOf<String, TypeSig?>()

    override fun find(path: String): TypeSig? = identifierMap[path] ?: parent.find(path)

    override fun add(
        path: String,
        typeSig: TypeSig,
    ) {
        // TODO: Add shadow check
        identifierMap[path] = typeSig
    }

    override fun incSymbolMapCount(): Int = parent.incSymbolMapCount()
}

object IdentifierCache : SymbolMap {
    private val identifierMap = mutableMapOf<String, TypeSig?>()
    private var symbolMapCounter: Int = 0
    override val symbolMapId: Int = 0

    init {
        val systemOutPrintln =
            staticFieldMethod(
                "java.lang.System",
                "out",
                "java.io.PrintStream",
                "println",
            )
        val integerValueOf =
            staticMethod(
                "java.lang.Integer",
                "valueOf",
                jfClass("java/lang/Integer"),
                IntegerType,
            )
        val booleanValueOf =
            staticMethod(
                "java.lang.Boolean",
                "valueOf",
                jfClass("java/lang/Boolean"),
                BooleanType,
            )
        identifierMap["System.out.println"] = systemOutPrintln
        identifierMap["java.lang.System.out.println"] = systemOutPrintln
        identifierMap["java.lang.Integer.valueOf"] = integerValueOf
        identifierMap["java.lang.Boolean.valueOf"] = booleanValueOf

        identifierMap["Int"] = IntegerType
        identifierMap["String"] = StringType
    }

    private fun staticFieldMethod(
        containingClass: String,
        staticField: String,
        staticFieldType: String,
        methodName: String,
    ): TypeSig {
        val parent = jfClass(containingClass)
        val field = JFField(parent, staticFieldType.replace('.', '/'), staticField)
        return JFMethod(
            listOf(JFVariableSymbol("param1", jfClass("java.lang.String"), IdentifierCache)),
            field,
            methodName,
            VoidType,
            false,
        )
    }

    private fun staticMethod(
        containingClass: String,
        methodName: String,
        rtnType: TypeSig,
        vararg parameterTypes: TypeSig,
    ): TypeSig {
        val parent = jfClass(containingClass)
        return JFMethod(
            parameterTypes.mapIndexed { index, param -> JFVariableSymbol("param${index + 1}", param, IdentifierCache) },
            parent,
            methodName,
            rtnType,
            true,
        )
    }

    override fun find(path: String): TypeSig? {
        return identifierMap.computeIfAbsent(path) {
            val split = path.split(".")
            when {
                split.size == 1 -> {
                    val name = split[0]
                    val typeSigs =
                        listOf("jafun.lang.IntKt", "jafun.io.ConsoleKt").map {
                            val jClass = Class.forName(it)
                            findInClass(jClass, name)
                        }.firstOrNull { it != null }
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

    override fun add(
        path: String,
        typeSig: TypeSig,
    ) {
        identifierMap[path] = typeSig
    }

    override fun incSymbolMapCount(): Int {
        symbolMapCounter++
        return symbolMapCounter
    }

    private fun findInClass(
        jClass: Class<*>,
        name: String,
    ): TypeSig? {
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
            val method =
                JFMethod(
                    params.mapIndexed { i, t -> JFVariableSymbol("param${i + 1}", t, IdentifierCache) },
                    JFClass(jClass.name.replace('.', '/')),
                    name,
                    rtn,
                    true,
                    associativity,
                    precedence,
                )
            method
        }
    }

    private val jvmTypes: Map<String, TypeSig> =
        mapOf(
            "byte" to ByteType,
            "char" to CharType,
            "double" to DoubleType,
            "float" to FloatType,
            "int" to IntegerType,
            "long" to LongType,
            "short" to ShortType,
            "boolean" to BooleanType,
            "void" to VoidType,
        )

    private fun jvmType(returnName: String) = jvmTypes[returnName] ?: jfClass(returnName)

    private fun jfClass(name: String) = JFClass(name.replace('.', '/'))

    fun reset(): IdentifierCache {
        symbolMapCounter = 0
        return this
    }
}

interface HasPath {
    val path: String
}

data class JFClass(override val path: String) : TypeSig, HasPath {
    override val signature: String = "L$path;"
}

data class JFField(val parent: JFClass, override val path: String, val name: String) : TypeSig, HasPath {
    override val signature: String = "L$path;"
}

data class JFMethod(
    val parameters: List<JFVariableSymbol>,
    val parent: HasPath,
    val name: String,
    val rtn: TypeSig,
    val static: Boolean = false,
    val associativity: Associativity = Associativity.PREFIX,
    val precedence: Int = 10,
) : TypeSig {
    override val signature: String = rtn.signature
}

data class JFVariableSymbol(val name: String, val type: TypeSig, val symbolMap: SymbolMap = IdentifierCache) : TypeSig {
    override val signature: String
        get() = type.signature

    override fun equals(other: Any?): Boolean =
        when (other) {
            null -> false
            is JFVariableSymbol -> name == other.name && type == other.type
            else -> super.equals(other)
        }

    override fun hashCode(): Int {
        var result = name.hashCode()
        result = 31 * result + type.hashCode()
        return result
    }
}

open class Generic(override val signature: String) : TypeSig {
    override fun equals(other: Any?) = other is TypeSig && this.signature == other.signature

    override fun hashCode() = signature.hashCode()
}

val ThisType = JFField(JFClass(""), "this", "this")
val StringType = JFClass("java/lang/String")

object UnknownType : Generic("?")

object VoidType : Generic("V")

object ByteType : Generic("B")

object CharType : Generic("C")

object DoubleType : Generic("D")

object FloatType : Generic("F")

object IntegerType : Generic("I")

object LongType : Generic("J")

object ShortType : Generic("S")

object BooleanType : Generic("Z")

interface TypeSig {
    val signature: String
}
