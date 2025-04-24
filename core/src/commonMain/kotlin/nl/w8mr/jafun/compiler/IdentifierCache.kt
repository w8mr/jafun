package nl.w8mr.jafun.compiler

import nl.w8mr.jafun.OperandType
import nl.w8mr.jafun.Type
import nl.w8mr.jafun.TypeSymbol

interface ClassInfo

object IdentifierCache : SymbolMap {
    private val identifierMap = mutableMapOf<TypeSymbol?, MutableMap<String, List<TypeSymbol>>>()

    private var symbolMapCounter: Int = 0
    override val symbolMapId: Int = 0

    init {
        add(null, "Int", OperandType.SInt32)
        add(null, "String", OperandType.StringType)

        null.addPackage("jafun").apply {
            addPackage("lang").apply {
                addClass("IntKt")
                addClass("CharKt")
                addClass("StringKt")
            }
            addPackage("io").apply {
                addClass("ConsoleKt")
            }
            addPackage("test").apply {
                addClass("TestKt")
            }
        }
        null.addPackage("java").apply {
            addPackage("io").apply {
                addClass("PrintStream")
            }
            addPackage("lang").apply {
                addClass("System").apply {
                    addField("out", findClass("java.io.PrintStream"))
                }
                addClass("Integer").apply {
                    addMethod(
                        "valueOf",
                        listOf(Type.JFVariableSymbol("param1", OperandType.SInt32)),
                        Type.JFClass("java.lang.Integer"),
                        null,
                        true,
                    )
                }
                addClass("Boolean").apply {
                    addMethod(
                        "valueOf",
                        listOf(Type.JFVariableSymbol("param1", OperandType.UInt1)),
                        Type.JFClass("java.lang.Boolean"),
                        null,
                        true,
                    )
                }
                addClass("Character").apply {
                    addMethod(
                        "valueOf",
                        listOf(Type.JFVariableSymbol("param1", OperandType.CharType)),
                        Type.JFClass("java.lang.Character"),
                        null,
                        true,
                    )
                }
            }
            addClass("PrintStream").apply {
                addMethod(
                    "println",
                    listOf(Type.JFVariableSymbol("param1", OperandType.StringType)),
                    OperandType.Unit,
                    Type.JFField("out", findClass("java.lang.System"), findClass("java.io.PrintStream")),
                    // TODO: Should be provided from somewhere else
                    false,
                )
            }
        }
        add(
            Type.JFClass("java.lang.System"),
            "out",
            Type.JFField("out", Type.JFClass("java.lang.System"), Type.JFClass("java.io.PrintStream")),
        )
        add(null, "System", findClass("java.lang.System"))
        add(
            Type.JFClass("System"),
            "out",
            Type.JFField("out", findClass("java.lang.System"), findClass("java.io.PrintStream")),
        )
    }

    fun Type.JFPackage?.addPackage(name: String): Type.JFPackage {
        val `package` = Type.JFPackage(name, this)
        add(this, name, `package`)
        return `package`
    }

    fun Type.JFPackage.addClass(name: String): Type.JFClass {
        val `class` = Type.JFClass(name, this)
        add(this, name, `class`)
        return `class`
    }

    fun Type.JFClass.addField(
        name: String,
        type: OperandType<*>,
    ): Type.JFField {
        val field = Type.JFField(name, this, type)
        add(this, name, field)
        return field
    }

    fun Type.JFClass.addMethod(
        name: String,
        arguments: List<Type.JFVariableSymbol>,
        returnType: OperandType<*>,
        field: Type.JFField? = null,
        static: Boolean = false,
        operator: Boolean = false,
        associativity: Associativity = Associativity.PREFIX,
        precedence: Int = 10,
    ): Type.JFMethod {
        val method =
            Type.JFMethod(
                arguments,
                this,
                name,
                returnType,
                static,
                operator,
                associativity,
                precedence,
            )
        add(this, name, method)
        return method
    }

    private fun arguments(parameterTypes: Array<out OperandType<*>>): List<Type.JFVariableSymbol> =
        parameterTypes.mapIndexed { index, param ->
            Type.JFVariableSymbol(
                "param${index + 1}",
                param,
                IdentifierCache,
            )
        }

    override fun find(
        type: TypeSymbol?,
        path: String,
    ): List<TypeSymbol> {
        identifierMap.getOrPut(type) { mutableMapOf() }
        return identifierMap[type]?.getOrPut(path) {
            when (type) {
                null ->
                    listOf(
                        "jafun.lang.IntKt",
                        "jafun.lang.CharKt",
                        "jafun.lang.StringKt",
                        "jafun.io.ConsoleKt",
                        "jafun.test.TestKt",
                    ).flatMap {
                        findInClass(it, path)?.let { listOf(it) } ?: emptyList()
                    }
                is OperandType.SInt32 ->
                    listOf("jafun.lang.IntKt", "jafun.io.ConsoleKt", "jafun.test.TestKt").flatMap {
                        findInClass(it, path)?.let { listOf(it) } ?: emptyList()
                    }
                is OperandType.CharType -> findInClass("jafun.lang.CharKt", path)?.let { listOf(it) } ?: emptyList()
                is OperandType.StringType -> findInClass("jafun.lang.StringKt", path)?.let { listOf(it) } ?: emptyList()
                is Type.JFClass -> findInClass(type.path, path)?.let { listOf(it) } ?: emptyList()
                else -> emptyList()
            }
        } ?: emptyList()
    }

    override fun contains(
        type: TypeSymbol?,
        path: String,
    ): Boolean {
        return identifierMap[type]?.containsKey(path) == true
    }

    override fun add(
        type: TypeSymbol?,
        path: String,
        typeSig: TypeSymbol,
    ) {
        identifierMap.getOrPut(type) {
            mutableMapOf()
        }
        identifierMap[type]?.getOrPut(path) {
            listOf(typeSig)
        }
    }

    override fun incSymbolMapCount(): Int {
        symbolMapCounter++
        return symbolMapCounter
    }


    private val jvmTypes: Map<String, OperandType<*>> =
        mapOf(
            "int" to OperandType.SInt32,
            "boolean" to OperandType.UInt1,
            "void" to OperandType.Unit,
            "char" to OperandType.CharType,
            "java.lang.String" to OperandType.StringType,
            "Ljava.lang.String;" to OperandType.StringType,
        )

    fun jvmType(returnName: String) = jvmTypes[returnName] ?: jfArray(returnName) ?: jfClass(returnName)

    private fun jfClass(name: String) =
        if (name.startsWith('L') && name.endsWith(';')) Type.JFClass(name.substring(1, name.length - 1)) else Type.JFClass(name) // TODO: build correct class naem

    private fun jfArray(returnName: String): OperandType.Array? =
        if (returnName.startsWith('[')) OperandType.Array(jvmType(returnName.substring(1))) else null // TODO: check implementation

    fun reset(): IdentifierCache {
        symbolMapCounter = 1
        return this
    }
}

expect fun IdentifierCache.findInClass(
    jClassName: String,
    name: String,
): Type.JFMethod?

expect fun findClassInPackage(name: String, parent: Type.JFPackage): List<TypeSymbol>

