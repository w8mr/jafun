package nl.w8mr.jafun.compiler

import nl.w8mr.jafun.Type
import nl.w8mr.jafun.ParserJafun
import java.lang.Boolean

object IdentifierCache : SymbolMap {
    private val identifierMap = mutableMapOf<String, List<Type.OperandType<*>>>()
    private var symbolMapCounter: Int = 0
    override val symbolMapId: Int = 0

    init {
        val systemOutPrintln =
            staticFieldMethod(
                "java.lang.System",
                "out",
                "java.io.PrintStream",
                "println",
                Type.StringType,
            )
        val integerValueOf =
            staticMethod(
                "java.lang.Integer",
                "valueOf",
                Type.Reference<Integer>("java/lang/Integer"),
                Type.SInt32,
            )
        val booleanValueOf =
            staticMethod(
                "java.lang.Boolean",
                "valueOf",
                Type.Reference<Boolean>("java/lang/Boolean"),
                Type.UInt1,
            )
        val characterValueOf =
            staticMethod(
                "java.lang.Character",
                "valueOf",
                Type.Reference<Character>("java/lang/Character"),
                Type.CharType,
            )
    //    identifierMap["System.out.println"] = listOf(systemOutPrintln)
        identifierMap["java.lang.System.out.println"] = listOf(systemOutPrintln)
        identifierMap["java.lang.Integer.valueOf"] = listOf(integerValueOf)
        identifierMap["java.lang.Boolean.valueOf"] = listOf(booleanValueOf)
        identifierMap["java.lang.Character.valueOf"] = listOf(characterValueOf)

        identifierMap["Int"] = listOf(Type.SInt32)
        identifierMap["String"] = listOf(Type.StringType)

        identifierMap["System"] = listOf(Type.JFClass("java.lang.System"))
        identifierMap["java.lang.System.out"] = listOf(Type.JFField(Type.JFClass("java.lang.System"), "java.lang.System.out", "out", Type.JFClass("java.io.PrintStream")))
        identifierMap["java.io.PrintStream.println"] = listOf(systemOutPrintln)

        identifierMap["java"] = listOf(Type.JFPackage("java"))
        identifierMap["java.lang"] = listOf(Type.JFPackage("java.lang"))
        identifierMap["java.lang.System"] = listOf(Type.JFClass("java.lang.System"))
        identifierMap["java.io"] = listOf(Type.JFPackage("java.io"))
        identifierMap["java.io.PrintStream"] = listOf(Type.JFClass("java.io.PrintStream"))

    }

    private fun staticFieldMethod(
        containingClass: String,
        staticField: String,
        staticFieldType: String,
        methodName: String,
        vararg parameterTypes: Type.OperandType<*>,
    ): Type.JFMethod {
        val parent = jfClass(containingClass)
        val field = Type.JFField(parent, staticFieldType.replace('.', '/'), staticField)
        return Type.JFMethod(
            arguments(parameterTypes),
            field,
            methodName,
            Type.Unit,
            false,
        )
    }

    private fun staticMethod(
        containingClass: String,
        methodName: String,
        rtnType: Type.OperandType<*>,
        vararg parameterTypes: Type.OperandType<*>,
    ): Type.JFMethod {
        val parent = jfClass(containingClass)
        return Type.JFMethod(
            arguments(parameterTypes),
            parent,
            methodName,
            rtnType,
            true,
        )
    }

    private fun arguments(parameterTypes: Array<out Type.OperandType<*>>): List<Type.JFVariableSymbol> =
        parameterTypes.mapIndexed { index, param ->
            Type.JFVariableSymbol(
                "param${index + 1}",
                param,
                IdentifierCache,
            )
        }

    override fun find(path: String): List<Type.OperandType<*>> {
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
                        listOf(Type.JFClass(jClass.name.replace('.', '/')))
                    } catch (_: Exception) {
                        emptyList<Type.OperandType<*>>()
                    }
                }
            }
        }
    }
    override fun contains(path: String) = identifierMap[path.replaceIllegalCharacters()] != null


    override fun add(
        path: String,
        typeSig: Type.OperandType<*>,
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
    ): Type.OperandType<*>? {
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
                Type.JFMethod(
                    params.mapIndexed { i, t -> Type.JFVariableSymbol("param${i + 1}", t, IdentifierCache) },
                    Type.JFClass(jClass.name.replace('.', '/')),
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

    private val jvmTypes: Map<String, Type.OperandType<*>> =
        mapOf(
            "int" to Type.SInt32,
            "boolean" to Type.UInt1,
            "void" to Type.Unit,
            "char" to Type.CharType,
            "java.lang.String" to Type.StringType,
        )

    private fun jvmType(returnName: String) = jvmTypes[returnName] ?: jfArray(returnName) ?: jfClass(returnName)

    private fun jfClass(name: String) = if (name.startsWith('L') && name.endsWith(';')) Type.JFClass(name.substring(1, name.length-1)) else Type.JFClass(name.replace('.', '/'))

    private fun jfArray(returnName: String): Type.Array? = if (returnName.startsWith('[')) Type.Array(jvmType(returnName.substring(1))) else null //TODO: check implementation

    fun reset(): IdentifierCache {
        symbolMapCounter = 1
        return this
    }
}