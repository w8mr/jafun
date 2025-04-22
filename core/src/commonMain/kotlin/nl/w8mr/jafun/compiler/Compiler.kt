package nl.w8mr.jafun.compiler

import nl.w8mr.jafun.IRBuilder
import nl.w8mr.jafun.OperandType
import nl.w8mr.jafun.ParserJafun
import nl.w8mr.jafun.Type
import nl.w8mr.jafun.buildClass
import nl.w8mr.jafun.compileMethod
import nl.w8mr.jafun.compiler.Compiler.PluginType.Phase3
import nl.w8mr.jafun.compiler.Compiler.PluginType.Phase2
import nl.w8mr.jafun.compiler.Compiler.PluginType.JVM
import nl.w8mr.jafun.debug.prettyPrint
import nl.w8mr.jafun.debug.print
import nl.w8mr.parsek.Parser

class Compiler(private val plugins: MutableMap<PluginType<*, *>, List<Plugin<*>>> = mutableMapOf<PluginType<*,*>, List<Plugin<*>>>()) {
    interface Plugin<A> {
        fun handle(input: A): A
    }

    interface Phase2Plugin : Plugin<List<ExpressionNode.Phase2Expression>>
    interface Phase3Plugin : Plugin<IRBuilder.ClassContext>
    interface JVMPlugin : Plugin<ByteArray>

    sealed interface PluginType<T, Plugin> {
        object Phase2 : PluginType<List<ExpressionNode.Phase2Expression>, Phase2Plugin>
        object Phase3 : PluginType<IRBuilder.ClassContext, Phase3Plugin>
        object JVM : PluginType<ByteArray, JVMPlugin>
    }

    fun <T, P: Plugin<T>> registerPlugin(type: PluginType<T, P>, plugin: P) = plugins.getOrPut(type) { listOf(plugin) }

    private fun <T, P: Plugin<T>> getPlugins(type: PluginType<T, P>) : List<P>? = plugins.get(type) as List<P>?

    private fun <T, P: Plugin<T>> PluginType<T, P>.run(input: T): T =
        getPlugins(this)?.fold(input) { context, plugin -> plugin.handle(context) } ?: input

    fun compile(code: String, className: String, methodName: String): ByteArray {
        val parseResult = ParserJafun.parse(code)
        return when (parseResult.second) {
            is Parser.Failure<*> -> {
                println(parseResult.second)
                error("Parser failed")
            }

            is Parser.Success<*> -> {
                val parsed = parseResult.first
                println("PARSED: \n${parsed!!.joinToString("\n") { it.prettyPrint() }}")
                println()
                val updatedParsed = Phase2.run(parsed)

                val classContext = ast2ir(className, updatedParsed, methodName)
                val updatedContext = Phase3.run(classContext)

                val actualBytes = ir2jvmByteCode(className, updatedContext)
                val updatedBytes = JVM.run(actualBytes)

                updatedBytes
            }
        }
    }

    private fun ir2jvmByteCode(className: String, classContext: IRBuilder.ClassContext): ByteArray {
        val clazz = buildClass(className, classContext)
        println("Bytecode: \n${clazz.classDef.print()}")
        val actualBytes = clazz.write()
        return actualBytes
    }

    private fun ast2ir(
        className: String,
        parsed: List<ExpressionNode.Phase2Expression>,
        methodName: String
    ): IRBuilder.ClassContext {
        ParserJafun.symbolMap.currentSymbolMap =
            LocalSymbolMap(IdentifierCache.reset()).apply {
                add(
                    null,
                    "arguments",
                    Type.JFVariableSymbol(
                        "param1",
                        OperandType.Array(
                            Type.JFClass(
                                "String",
                                Type.JFPackage("lang", Type.JFPackage("java"))
                            )
                        ),
                        this,
                        false,
                    ),
                )
            } // TODO: look into this.
        val returnType: OperandType<*> = OperandType.Unit
        val parameterTypes: List<OperandType<*>> =
            listOf(OperandType.Array(OperandType.StringType))
        val builder =
            IRBuilder.define {
                `class`(className) {
                    compileMethod(this, parsed, methodName, returnType, parameterTypes)
                }
            }

        println("IR: \n${builder.classes[className]?.prettyPrint()}")
        val classContext = builder.classes[className] ?: error("Class not found: $className")
        return classContext
    }
}


@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
annotation class FunctionAssociativity(val associativity: Associativity)

@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
annotation class FunctionPrecedence(val precedence: Int)

