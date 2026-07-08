package nl.w8mr.jafun.compiler

import nl.w8mr.jafun.compiler.ir2jvm.IRBuilder
import nl.w8mr.jafun.compiler.ir2jvm.compileAll
import nl.w8mr.jafun.OperandType
import nl.w8mr.jafun.Phase1Parser
import nl.w8mr.jafun.ParserJafun
import nl.w8mr.jafun.Type
import nl.w8mr.jafun.TypeSymbol
import nl.w8mr.jafun.compiler.Compiler.PluginType.Phase2
import nl.w8mr.jafun.compiler.Compiler.PluginType.Phase3
import nl.w8mr.jafun.compiler.Compiler.PluginType.JVM
import nl.w8mr.jafun.compiler.Compiler.PluginType.JVMIR
import nl.w8mr.jafun.compiler.ast2ir.compileExpressionNode
import nl.w8mr.jafun.compiler.ir2jvm.VCBinder
import nl.w8mr.jafun.symboltable.SymbolTable
import nl.w8mr.jafun.symboltable.VariableDef
import nl.w8mr.jafun.symboltable.FQDN
import nl.w8mr.jafun.symboltable.MethodDef
import nl.w8mr.kasmine.ClassDef
import nl.w8mr.parsek.Parser

class Compiler(private val plugins: MutableMap<PluginType<*, *>, MutableList<Plugin<*>>> = mutableMapOf<PluginType<*,*>, MutableList<Plugin<*>>>()) {
    interface Plugin<A> {
        fun handle(input: A): A
    }

    interface Phase2Plugin : Plugin<List<ExpressionNode.Phase2Expression>>
    interface Phase3Plugin : Plugin<IRBuilder.ClassContext>
    interface JVMPlugin : Plugin<Map<String, ByteArray>>
    interface JVMIRPlugin : Plugin<ClassDef>

    sealed interface PluginType<T, Plugin> {
        object Phase2 : PluginType<List<ExpressionNode.Phase2Expression>, Phase2Plugin>
        object Phase3 : PluginType<IRBuilder.ClassContext, Phase3Plugin>
        object JVMIR : PluginType<ClassDef, JVMIRPlugin>
        object JVM : PluginType<Map<String, ByteArray>, JVMPlugin>
    }

    fun <T, P: Plugin<T>> registerPlugin(type: PluginType<T, in P>, plugin: P): P {
        plugins.getOrPut(type) { mutableListOf() }.add(plugin)
        return plugin
    }

    private fun <T, P: Plugin<T>> getPlugins(type: PluginType<T, P>) : List<P>? = plugins.get(type) as List<P>?

    private fun <T, P: Plugin<T>> PluginType<T, P>.run(input: T): T =
        getPlugins(this)?.fold(input) { context, plugin -> plugin.handle(context) } ?: input

    fun compile(code: String, className: String, methodName: String): Map<String, ByteArray> {
        val symbolTable = SymbolTable()
        val symbolMap = SymbolMapManager()
        symbolMap.reset()
        val stdlibInlineFunctions = StdlibLoader.load(symbolMap, symbolTable)
        for (jvmClass in listOf("jafun.io.ConsoleKt", "jafun.test.TestKt")) {
            for (jfm in IdentifierCache.findMethodsInClass(jvmClass)) {
                val parentFqdn = FQDN(jfm.parent.path)
                symbolTable.registerMethodByFqdn(
                    MethodDef(
                        id = parentFqdn + jfm.name,
                        name = jfm.name,
                        parentFqdn = parentFqdn,
                        parameters = jfm.parameters.map { nl.w8mr.jafun.symboltable.Parameter(it.name, it.type) },
                        rtn = jfm.rtn,
                        static = jfm.static,
                        operator = jfm.operator,
                        associativity = jfm.associativity,
                        precedence = jfm.precedence,
                        inline = jfm.inline,
                    )
                )
            }
        }
        val phase1 = Phase1Parser(symbolMap, symbolTable).parse(code).first ?: error("Phase 1 parsing failed")
        val parseResult = ParserJafun(symbolMap, symbolTable).parse(phase1)
        return when (parseResult.second) {
            is Parser.Failure -> {
                println(parseResult.second)
                error("Parser failed")
            }

            is Parser.Success<*> -> {
                val parsed = parseResult.first
                val userInlineFunctions = parsed?.filterIsInstance<ExpressionNode.Function>()
                    ?.filter { it.inline } ?: emptyList()
                val allInlineFunctions = userInlineFunctions + stdlibInlineFunctions
                if (allInlineFunctions.isNotEmpty()) {
                    registerPlugin(Phase3, Inliner(allInlineFunctions))
                }
                val updatedParsed = Phase2.run(parsed ?: error("Parsed expression is null"))

                val vcFieldMap = extractValueClassFields(symbolMap)
                val classContext = ast2ir(className, updatedParsed, methodName)
                val updatedContext = Phase3.run(classContext)
                val vcContext = runVCPhases(updatedContext)

                val allClasses = mutableMapOf(className to vcContext)
                for ((name, fields) in vcFieldMap) {
                    allClasses[name] = IRBuilder.ClassContext(name, fields = fields, parent = IRBuilder.BuilderContext())
                }
                val builder = IRBuilder.BuilderContext(classes = allClasses)
                val allBytecode = compileAll(builder)
                JVM.run(allBytecode)
            }
        }
    }

    private fun extractValueClassFields(symbolMap: SymbolMapManager): Map<String, List<Pair<String, OperandType<*>>>> {
        val result = mutableMapOf<String, List<Pair<String, OperandType<*>>>>()
        val topSymbols = symbolMap.find(null as TypeSymbol?)
        for (sym in topSymbols) {
            if (sym is Type.JFClass && sym.kind == Type.ClassKind.VALUE_CLASS) {
                val fields = sym.constructor?.parameters?.map { it.name to it.type } ?: emptyList()
                result[sym.name] = fields
            }
        }
        return result
    }

    private fun ast2ir(
        className: String,
        parsed: List<ExpressionNode.Phase2Expression>,
        methodName: String,
    ): IRBuilder.ClassContext {
        val returnType: OperandType<*> = OperandType.Unit
        val builder =
            IRBuilder.define {
                `class`(className) {
                    compileMethod(this, parsed, methodName, returnType, listOf(Parameter(OperandType.Array(OperandType.StringType))))
                }
            }

        val classContext = builder.classes[className] ?: error("Class not found: $className")
        return classContext
    }

    private fun runVCPhases(context: IRBuilder.ClassContext): IRBuilder.ClassContext {
        return VCBinder.handle(context)
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

data class Parameter(
    val type: OperandType<*>,
    val varName: String? = null,
)

fun compileMethod(
    builder: IRBuilder.ClassDSL,
    expression: List<ExpressionNode.Phase2_3Expression>,
    methodName: String,
    returnType: OperandType<*>,
    parameters: List<Parameter>,
) {
    with(builder) {
        method(methodName, returnType, parameters) {
            codeBlock {
                expression.size - 1
                expression.forEachIndexed { index, statement ->
                    compileExpressionNode(statement, this)
                }
            }
        }
    }
}