package nl.w8mr.jafun.compiler

import nl.w8mr.jafun.IR
import nl.w8mr.jafun.ParserJafun
import java.lang.Boolean

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
                IR.Reference<Boolean>("java/lang/Boolean"),
                IR.UInt1,
            )
        val characterValueOf =
            staticMethod(
                "java.lang.Character",
                "valueOf",
                IR.Reference<Character>("java/lang/Character"),
                IR.CharType,
            )
    //    identifierMap["System.out.println"] = listOf(systemOutPrintln)
        identifierMap["java.lang.System.out.println"] = listOf(systemOutPrintln)
        identifierMap["java.lang.Integer.valueOf"] = listOf(integerValueOf)
        identifierMap["java.lang.Boolean.valueOf"] = listOf(booleanValueOf)
        identifierMap["java.lang.Character.valueOf"] = listOf(characterValueOf)

        identifierMap["Int"] = listOf(IR.SInt32)
        identifierMap["String"] = listOf(IR.StringType)

        identifierMap["System"] = listOf(IR.JFClass("java.lang.System"))
        identifierMap["java.lang.System.out"] = listOf(IR.JFField(IR.JFClass("java.lang.System"), "java.lang.System.out", "out", IR.JFClass("java.io.PrintStream")))
        identifierMap["java.io.PrintStream.println"] = listOf(systemOutPrintln)

        identifierMap["java"] = listOf(IR.JFPackage("java"))
        identifierMap["java.lang"] = listOf(IR.JFPackage("java.lang"))
        identifierMap["java.lang.System"] = listOf(IR.JFClass("java.lang.System"))
        identifierMap["java.io"] = listOf(IR.JFPackage("java.io"))
        identifierMap["java.io.PrintStream"] = listOf(IR.JFClass("java.io.PrintStream"))

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
                        listOf("jafun.lang.IntKt", "jafun.lang.CharKt", "jafun.lang.StringKt", "jafun.io.ConsoleKt", "jafun.io.test.TestKt").mapNotNull {
                            val jClass = Class.forName(it)
                            findInClass(jClass, name.replaceIllegalCharacters())
                        }
                    typeSigs
                }
                else -> {
                    try {
                        val jClass = Class.forName(path)
                        listOf(IR.JFClass(jClass.name.replace('.', '/')))
                    } catch (_: Exception) {
                        emptyList<IR.OperandType<*>>()
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
                    jMethod.name.all(ParserJafun.operatorSymbols::contains),
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