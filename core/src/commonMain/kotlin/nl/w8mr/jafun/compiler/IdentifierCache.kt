package nl.w8mr.jafun.compiler

import nl.w8mr.jafun.OperandType
import nl.w8mr.jafun.Type
import nl.w8mr.jafun.TypeSymbol

interface ClassInfo

    object IdentifierCache : SymbolMap {
        override val parent: SymbolMap? = null
        private val identifierMap = mutableMapOf<TypeSymbol?, MutableMap<String, MutableSet<TypeSymbol>>>()

        private var symbolMapCounter: Int = 0
        override val symbolMapId: Int = 0

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
            type: TypeSymbol?
        ): Set<TypeSymbol> =
            identifierMap.getOrPut(type) { mutableMapOf() }.values.flatten().toSet()


        override fun find(
            type: TypeSymbol?,
            path: String,
        ): Set<TypeSymbol> =
            identifierMap.getOrPut(type) { mutableMapOf() }.get(path)?: setOf()

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
            }.getOrPut(path) {
                mutableSetOf()
            }.add(typeSig)
        }

        override fun replaceType(
            type: TypeSymbol?,
            path: String,
            typeSig: Type,
        ) {
            val map = identifierMap.getOrPut(type) { mutableMapOf() }
            val list = map.get(path) ?: mutableListOf()
            val removed = list.filter { it !is Type.JFVariableSymbol || it.name != typeSig.name }.toMutableList()
            removed.add(typeSig)
            map.put(path, removed.toMutableSet())
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
                "long" to OperandType.SInt64,
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


            add(null, "Int", OperandType.SInt32)
            add(null, "Long", OperandType.SInt64)
            add(null, "String", OperandType.StringType)
            add(null, "Char", OperandType.CharType)

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
                    addClass("POJO").apply {
                        addClassToSymbolMap(this, this.path)
                    }

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
                    addClass("Long").apply {
                        addMethod(
                            "valueOf",
                            listOf(Type.JFVariableSymbol("param1", OperandType.SInt64)),
                            Type.JFClass("java.lang.Long"),
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
                addPackage("io").apply {
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
            }
            add(
                Type.JFClass("java.lang.System"),
                "out",
                Type.JFField("out", Type.JFClass("java.lang.System"), Type.JFClass("java.io.PrintStream")),
            )
            add(null, "System", findClass("java.lang.System")).apply {

            }
            add(
                Type.JFClass("System"),
                "out",
                Type.JFField("out", findClass("java.lang.System"), findClass("java.io.PrintStream")),
            )
            listOf("jafun.io.ConsoleKt", "jafun.test.TestKt").forEach { className ->
                addClassToSymbolMap(null, className) //import into direct scope
            }
            // added by StdlibLoader from String.jf

            return this
        }

    }

expect fun IdentifierCache.findMethodsInClass(
    jClassName: String,
): List<Type.JFMethod>

expect fun IdentifierCache.findConstructorsInClass(
    jClassName: String,
): List<Type.JFConstructor>


expect fun findClassInPackage(name: String, parent: Type.JFPackage): List<TypeSymbol>

