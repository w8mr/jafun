package nl.w8mr.jafun.compiler

import nl.w8mr.jafun.compiler.ir2jvm.IRBuilder
import nl.w8mr.jafun.OperandType
import nl.w8mr.jafun.ParserJafun
import nl.w8mr.jafun.Phase1Parser
import nl.w8mr.jafun.Type
import nl.w8mr.jafun.compiler.ir2jvm.buildClass
import nl.w8mr.jafun.compiler.Compiler.PluginType.Phase3
import nl.w8mr.jafun.compiler.Compiler.PluginType.Phase2
import nl.w8mr.jafun.compiler.Compiler.PluginType.JVM
import nl.w8mr.jafun.compiler.Compiler.PluginType.JVMIR
import nl.w8mr.jafun.compiler.ast2ir.compileExpressionNode
import nl.w8mr.kasmine.ClassDef
import nl.w8mr.parsek.Parser

class Compiler(private val plugins: MutableMap<PluginType<*, *>, MutableList<Plugin<*>>> = mutableMapOf<PluginType<*,*>, MutableList<Plugin<*>>>()) {
    interface Plugin<A> {
        fun handle(input: A): A
    }

    interface Phase2Plugin : Plugin<List<ExpressionNode.Phase2Expression>>
    interface Phase3Plugin : Plugin<IRBuilder.ClassContext>
    interface JVMPlugin : Plugin<ByteArray>
    interface JVMIRPlugin : Plugin<ClassDef>

    sealed interface PluginType<T, Plugin> {
        object Phase2 : PluginType<List<ExpressionNode.Phase2Expression>, Phase2Plugin>
        object Phase3 : PluginType<IRBuilder.ClassContext, Phase3Plugin>
        object JVMIR : PluginType<ClassDef, JVMIRPlugin>
        object JVM : PluginType<ByteArray, JVMPlugin>
    }

    fun <T, P: Plugin<T>> registerPlugin(type: PluginType<T, in P>, plugin: P): P {
        plugins.getOrPut(type) { mutableListOf() }.add(plugin)
        return plugin
    }

    private fun <T, P: Plugin<T>> getPlugins(type: PluginType<T, P>) : List<P>? = plugins.get(type) as List<P>?

    private fun <T, P: Plugin<T>> PluginType<T, P>.run(input: T): T =
        getPlugins(this)?.fold(input) { context, plugin -> plugin.handle(context) } ?: input

    fun compile(code: String, className: String, methodName: String): ByteArray {
        val phase1 = Phase1Parser.parse(code).first ?: error("Phase 1 parsing failed")
        val parseResult = ParserJafun.parse(phase1)
        return when (parseResult.second) {
            is Parser.Failure<*> -> {
                println(parseResult.second)
                error("Parser failed")
            }

            is Parser.Success<*> -> {
                val parsed = parseResult.first
                val updatedParsed = Phase2.run(parsed ?: error("Parsed expression is null"))

                val classContext = ast2ir(className, updatedParsed, methodName)
                val updatedContext = Phase3.run(classContext)

                val clazz = buildClass(className, updatedContext)
                clazz.classDef = JVMIR.run(clazz.classDef)

                val actualBytes = clazz.write()
                JVM.run(actualBytes)
            }
        }
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

@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
annotation class FunctionName(val name: String)

fun compileMethod(
    builder: IRBuilder.ClassDSL,
    expression: List<ExpressionNode.Phase2_3Expression>,
    methodName: String,
    returnType: OperandType<*>,
    parameterTypes: List<OperandType<*>>,
) {
    with(builder) {
        method(methodName, returnType, parameterTypes) {
            codeBlock {
                expression.size - 1
                expression.forEachIndexed { index, statement ->
                    compileExpressionNode(statement, this)
                }
            }
        }
    }
}