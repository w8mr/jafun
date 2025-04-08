package jafun.compiler

import jafun.compiler.IdentifierCache.incSymbolMapCount
import nl.w8mr.jafun.IR
import nl.w8mr.jafun.ParserJafun.operatorSymbols

@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
annotation class FunctionAssociativity(val associativity: Associativity)

@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
annotation class FunctionPrecedence(val precedence: Int)

interface SymbolMap {
    fun find(path: String): List<IR.OperandType<*>>
    fun findSingle(path: String) = find(path).single()
    fun findSingleOrNull(path: String) = find(path).singleOrNull()
    fun findFirst(path: String) = find(path).first()
    fun findFirstOrNull(path: String) = find(path).firstOrNull()

    operator fun contains(path: String): Boolean

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
    private val identifierMap = mutableMapOf<String, List<IR.OperandType<*>>>()

    override fun find(path: String): List<IR.OperandType<*>> = identifierMap[path.replaceIllegalCharacters()] ?: parent.find(
        path
    )
    override fun contains(path: String) = identifierMap[path.replaceIllegalCharacters()] != null

    override fun add(
        path: String,
        typeSig: IR.OperandType<*>,
    ) {
        // TODO: Add shadow check
        identifierMap[path.replaceIllegalCharacters()] = listOf(typeSig)
    }

    override fun incSymbolMapCount(): Int = parent.incSymbolMapCount()
}

object IdentifierCache : SymbolMap {
    private val identifierMap = mutableMapOf<String, List<IR.OperandType<*>>>()
    private var symbolMapCounter: Int = 0
    override val symbolMapId: Int = 0

    init {
        val systemOutPrintln =
            staticFieldMethod(
                "java.lang.System",
                "out",
                "java.io.PrintStream",
                "println",
                IR.StringType,
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
        val characterValueOf =
            staticMethod(
                "java.lang.Character",
                "valueOf",
                IR.Reference<Character>("java/lang/Character"),
                IR.CharType,
            )
        identifierMap["System.out.println"] = listOf(systemOutPrintln)
        identifierMap["java.lang.System.out.println"] = listOf(systemOutPrintln)
        identifierMap["java.lang.Integer.valueOf"] = listOf(integerValueOf)
        identifierMap["java.lang.Boolean.valueOf"] = listOf(booleanValueOf)
        identifierMap["java.lang.Character.valueOf"] = listOf(characterValueOf)

        identifierMap["Int"] = listOf(IR.SInt32)
        identifierMap["String"] = listOf(IR.StringType)
    }

    private fun staticFieldMethod(
        containingClass: String,
        staticField: String,
        staticFieldType: String,
        methodName: String,
        vararg parameterTypes: IR.OperandType<*>,
    ): IR.JFMethod {
        val parent = jfClass(containingClass)
        val field = IR.JFField(parent, staticFieldType.replace('.', '/'), staticField)
        return IR.JFMethod(
            arguments(parameterTypes),
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
            arguments(parameterTypes),
            parent,
            methodName,
            rtnType,
            true,
        )
    }

    private fun arguments(parameterTypes: Array<out IR.OperandType<*>>): List<IR.JFVariableSymbol> =
        parameterTypes.mapIndexed { index, param ->
            IR.JFVariableSymbol(
                "param${index + 1}",
                param,
                IdentifierCache,
            )
        }

    override fun find(path: String): List<IR.OperandType<*>> {
        return identifierMap.computeIfAbsent(path) {
            val split = path.split(".")
            when {
                split.size == 1 -> {
                    val name = split[0]
                    val typeSigs =
                        listOf("jafun.lang.IntKt"/*, "jafun.lang.CharKt"*/, "jafun.io.ConsoleKt").mapNotNull {
                            val jClass = Class.forName(it)
                            findInClass(jClass, name.replaceIllegalCharacters())
                        }
                    typeSigs
                }
                else -> {
                    try {
                        val jClass = Class.forName(path)
                        listOf(IR.JFClass(jClass.name.replace('.', '/')))
                    } catch (e: Exception) {
                        TODO()
                    }
                }
            }
        }
    }
    override fun contains(path: String) = identifierMap[path.replaceIllegalCharacters()] != null


    override fun add(
        path: String,
        typeSig: IR.OperandType<*>,
    ) {
        identifierMap[path.replaceIllegalCharacters()] = listOf(typeSig)
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
            "char" to IR.CharType,
            "java.lang.String" to IR.StringType,
        )

    private fun jvmType(returnName: String) = jvmTypes[returnName] ?: jfArray(returnName) ?: jfClass(returnName)

    private fun jfClass(name: String) = if (name.startsWith('L') && name.endsWith(';')) IR.JFClass(name.substring(1, name.length-1)) else IR.JFClass(name.replace('.', '/'))

    private fun jfArray(returnName: String): IR.Array<*>? = if (returnName.startsWith('[')) IR.Array(jvmType(returnName.substring(1))) else null //TODO: check implementation

    fun reset(): IdentifierCache {
        symbolMapCounter = 1
        return this
    }
}

interface HasPath {
    val path: String
}
