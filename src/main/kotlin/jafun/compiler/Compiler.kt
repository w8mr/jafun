package jafun.compiler

import jafun.compiler.IdentifierCache.incSymbolMapCount
import nl.w8mr.jafun.IR
import nl.w8mr.jafun.operatorSymbols

@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
annotation class FunctionAssociativity(val associativity: Associativity)

@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
annotation class FunctionPrecedence(val precedence: Int)

interface SymbolMap {
    fun find(path: String): IR.OperandType<*>?

    fun add(
        path: String,
        typeSig: IR.OperandType<*>,
    )

    fun incSymbolMapCount(): Int

    val symbolMapId: Int

    fun String.replaceIllegalCharacters() =
        this.replace('<', '﹤')
            .replace('>', '﹥')
            .replace('/', '∕')
}

data class LocalSymbolMap(val parent: SymbolMap, override val symbolMapId: Int = incSymbolMapCount()) : SymbolMap {
    private val identifierMap = mutableMapOf<String, IR.OperandType<*>?>()

    override fun find(path: String): IR.OperandType<*>? = identifierMap[path.replaceIllegalCharacters()] ?: parent.find(path)

    override fun add(
        path: String,
        typeSig: IR.OperandType<*>,
    ) {
        // TODO: Add shadow check
        identifierMap[path.replaceIllegalCharacters()] = typeSig
    }

    override fun incSymbolMapCount(): Int = parent.incSymbolMapCount()
}

object IdentifierCache : SymbolMap {
    private val identifierMap = mutableMapOf<String, IR.OperandType<*>?>()
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
                IR.Reference<Integer>("java/lang/Integer"),
                IR.SInt32,
            )
        val booleanValueOf =
            staticMethod(
                "java.lang.Boolean",
                "valueOf",
                IR.Reference<java.lang.Boolean>("java/lang/Boolean"),
                IR.UInt1,
            )
        identifierMap["System.out.println"] = systemOutPrintln
        identifierMap["java.lang.System.out.println"] = systemOutPrintln
        identifierMap["java.lang.Integer.valueOf"] = integerValueOf
        identifierMap["java.lang.Boolean.valueOf"] = booleanValueOf

        identifierMap["Int"] = IR.SInt32
        identifierMap["String"] = IR.StringType
    }

    private fun staticFieldMethod(
        containingClass: String,
        staticField: String,
        staticFieldType: String,
        methodName: String,
    ): IR.JFMethod {
        val parent = jfClass(containingClass)
        val field = IR.JFField(parent, staticFieldType.replace('.', '/'), staticField)
        return IR.JFMethod(
            listOf(IR.JFVariableSymbol("param1", IR.StringType, IdentifierCache)),
            field,
            methodName,
            IR.Unit,
            false,
        )
    }

    private fun staticMethod(
        containingClass: String,
        methodName: String,
        rtnType: IR.OperandType<*>,
        vararg parameterTypes: IR.OperandType<*>,
    ): IR.JFMethod {
        val parent = jfClass(containingClass)
        return IR.JFMethod(
            parameterTypes.mapIndexed { index, param ->
                IR.JFVariableSymbol(
                    "param${index + 1}",
                    param,
                    IdentifierCache,
                )
            },
            parent,
            methodName,
            rtnType,
            true,
        )
    }

    override fun find(path: String): IR.OperandType<*>? {
        return identifierMap.computeIfAbsent(path) {
            val split = path.split(".")
            when {
                split.size == 1 -> {
                    val name = split[0]
                    val typeSigs =
                        listOf("jafun.lang.IntKt", "jafun.io.ConsoleKt").map {
                            val jClass = Class.forName(it)
                            findInClass(jClass, name.replaceIllegalCharacters())
                        }.firstOrNull { it != null }
                    typeSigs
                }
                else -> {
                    try {
                        val jClass = Class.forName(path)
                        IR.JFClass(jClass.name.replace('.', '/'))
                    } catch (e: Exception) {
                        TODO()
                    }
                }
            }
        }
    }

    override fun add(
        path: String,
        typeSig: IR.OperandType<*>,
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
    ): IR.OperandType<*>? {
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
                IR.JFMethod(
                    params.mapIndexed { i, t -> IR.JFVariableSymbol("param${i + 1}", t, IdentifierCache) },
                    IR.JFClass(jClass.name.replace('.', '/')),
                    name,
                    rtn,
                    true,
                    jMethod.name.all(operatorSymbols::contains),
                    associativity,
                    precedence,
                )
            method
        }
    }

    private val jvmTypes: Map<String, IR.OperandType<*>> =
        mapOf(
            "int" to IR.SInt32,
            "boolean" to IR.UInt1,
            "void" to IR.Unit,
            "java.lang.String" to IR.StringType,
        )

    private fun jvmType(returnName: String) = jvmTypes[returnName] ?: jfClass(returnName)

    private fun jfClass(name: String) = IR.JFClass(name.replace('.', '/'))

    fun reset(): IdentifierCache {
        symbolMapCounter = 0
        return this
    }
}

interface HasPath {
    val path: String
}
