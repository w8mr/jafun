package nl.w8mr.jafun.nl.w8mr.jafun

import nl.w8mr.jafun.compiler.ir2jvm.IRBuilder
import nl.w8mr.jafun.OperandType
import nl.w8mr.jafun.Type
import nl.w8mr.jafun.Type.MethodParent
import nl.w8mr.jafun.compiler.Associativity
import nl.w8mr.jafun.compiler.Compiler
import nl.w8mr.jafun.compiler.Compiler.PluginType.JVMIR
import nl.w8mr.jafun.compiler.Compiler.PluginType.Phase3
import nl.w8mr.jafun.compiler.Compiler.PluginType.Phase2
import nl.w8mr.jafun.compiler.ExpressionNode
import nl.w8mr.jafun.compiler.ExpressionNode.IntegerLiteral
import nl.w8mr.jafun.compiler.ExpressionNode.StringLiteral
import nl.w8mr.jafun.compiler.ExpressionNode.ValAssignment
import nl.w8mr.jafun.compiler.IdentifierCache
import nl.w8mr.jafun.debug.prettyPrint
import nl.w8mr.jafun.debug.print
import nl.w8mr.kasmine.ClassBuilder
import nl.w8mr.kasmine.ClassDef
import nl.w8mr.kasmine.classBuilder
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

expect fun compareDecompiled(
    expected: ByteArray,
    bytecode: (ClassBuilder.ClassDSL.DSL.() -> Unit)?,
    expectedResult: String,
    actualResult: String,
    actualBytes: ByteArray
)

expect fun writeFile(
    className: String,
    bytes: ByteArray,
)

expect fun runAndAssertOutput(
    actualBytes: Map<String, ByteArray>,
    className: String,
    methodName: String,
    params: Array<String>?,
    expectedOutput: String
): String

class TestContext() {
    var code: String? = null
    var bytecode: ByteArray? = null  // deprecated: use classBytecodes instead
    var classBytecodes: MutableMap<String, ByteArray> = mutableMapOf()
    var mainParams: Array<String>? = null
    var phase2Expected: List<ExpressionNode.Phase2Expression>? = null
    var expectedOutput: String? = null
}

fun test(block: TestDSL.() -> Unit) {
    IdentifierCache.reset()

    val files = mutableMapOf<String, TestContext>()
    val runner = TestDSLImpl(files)
    block.invoke(runner)

    val compiler = Compiler()

    //         val phase1Plugin = compiler.registerPlugin(Phase1, Phase1Plugin())
    val phase2Plugin = compiler.registerPlugin(Phase2, Phase2Plugin())
    val phase3Plugin = compiler.registerPlugin(Phase3, Phase3Plugin())
    val jvmirPlugin = compiler.registerPlugin(JVMIR, JVMIRPlugin())

    for ((path, file) in files) {
        val code = file.code?.trimIndent() ?: error("No code set for $path")

        //                val parsePhase1 = Phase1Parser(.parse(code)
        //                assertEquals(false, parsePhase1.first == null, "PHASE1 fail: ${parsePhase1.second}")

        val className = "Script"
        val methodName = "main"
        val allClasses = try {
            compiler.compile(code, className, methodName)
        } catch (e: Throwable) {
            e.printStackTrace()
            //                    println("Phase1: ${phase1Plugin.phase1}")
            println("Phase2:\n ${phase2Plugin.phase2?.joinToString("\n") { it.prettyPrint() }}")
            println("Phase3:\n ${phase3Plugin.phase3?.prettyPrint()}")
            println("IR:\n ${jvmirPlugin.jvmir?.print()}")
            throw e
        }

        val actualBytes = allClasses[className] ?: error("Script class not found")
        allClasses.forEach { (name, bytes) -> writeFile(name, bytes) }

        //       println("Phase1: ${parsePhase1.first?.joinToString("") { it.prettyPrint() } }}")

        if (file.phase2Expected != null) {
            if (file.phase2Expected != phase2Plugin.phase2) {
                assertEquals(
                    file.phase2Expected?.map { it.prettyPrint() },
                    phase2Plugin.phase2?.map { it.prettyPrint() })
            }
        } else {
            println("Phase2:\n ${phase2Plugin.phase2?.joinToString("\n") { it.prettyPrint() }}")
        }
        println("Phase3:\n ${phase3Plugin.phase3?.prettyPrint()}")
        println("IR:\n ${jvmirPlugin.jvmir?.print()}")

        val result = runAndAssertOutput(
            allClasses,
            className,
            methodName,
            file.mainParams,
            file.expectedOutput ?: TODO("Handle no expected case")
        )

        // Validate all expected class bytecodes
        for ((expectedClassName, expectedBytes) in file.classBytecodes) {
            val actualClassBytes = allClasses[expectedClassName]
                ?: error("Expected class $expectedClassName not found in compiled output")

            if ((expectedBytes.zip(actualClassBytes).any { it.first != it.second })) {
                compareDecompiled(expectedBytes, null /* change to boolean */, result, result, actualClassBytes)
            } else {
                assertContentEquals(expectedBytes, actualClassBytes)
            }
        }

        // Backward compatibility: validate old bytecode field if set
        val expectedBytes = file.bytecode
        if (expectedBytes != null && !file.classBytecodes.containsKey("Script")) {
            if ((expectedBytes.zip(actualBytes).any { it.first != it.second })) {
                compareDecompiled(expectedBytes, null /* change to boolean */, result, result, actualBytes)
            } else {
                assertContentEquals(expectedBytes, actualBytes)
            }
        }

    }
}

interface TestDSL {
    fun file(path: String = "Script", block: TestFileDSL.() -> Unit)
}

class TestDSLImpl(val files: MutableMap<String, TestContext>) : TestDSL {
    override fun file(path: String, block: TestFileDSL.() -> Unit) {
        val context = TestContext()
        val runner = TestFileDSLImpl(context)
        block.invoke(runner)
        files[path] = context
    }

}

interface TestFileDSL {
    var code: String?
    var expectedOutput: String?
    fun params(vararg params: String)

    /**
     * Validate JVM IR (bytecode) for a generated class.
     *
     * Usage:
     *   - jvmIr { ... }                  // Validates "Script" class (default)
     *   - jvmIr("Address") { ... }       // Validates "Address" class
     *   - jvmIr("User") { ... }          // Validates "User" class
     *
     * Can specify multiple jvmIr blocks to validate multiple generated classes.
     * Each block describes the expected methods and bytecode for that class.
     */
    fun jvmIr(className: String, block: ClassBuilder.ClassDSL.DSL.() -> Unit)
    fun phase2(block: Phase2Builder.() -> Unit)

}

class TestFileDSLImpl(val context: TestContext) : TestFileDSL {
    override var code by context::code
    override var expectedOutput by context::expectedOutput
    override fun params(vararg params: String) {
        context.mainParams = params.toList().toTypedArray()
    }

    override fun jvmIr(className: String, block: ClassBuilder.ClassDSL.DSL.() -> Unit) {
        val wrappedBlock: ClassBuilder.ClassDSL.DSL.() -> Unit = {
            name = className  // Auto-set the class name
            block()           // Run user's block
        }
        val bytecode = classBuilder(wrappedBlock).write()
        context.classBytecodes[className] = bytecode
    }

    override fun phase2(block: Phase2Builder.() -> Unit) {
        context.phase2Expected = Phase2Builder().apply(block).expressionsList
    }
}

class Phase2Builder {
    val expressionsList: MutableList<ExpressionNode.Phase2Expression> = mutableListOf()
    val objectType = Type.JFClass("java.lang.Object")

    val join =
        method(
            "join",
            OperandType.StringType,
            OperandType.StringType,
            OperandType.StringType,
            parent = IdentifierCache.findClass("jafun.test.TestKt"),
            operator = false,
        )

    val println =
        method(
            "println",
            OperandType.Unit,
            objectType,
            parent = IdentifierCache.findClass("jafun.io.ConsoleKt"),
            operator = false,
        )

    val printStreamPrintln = method(
        "println",
        OperandType.Unit,
        OperandType.StringType,
        parent = IdentifierCache.findClass("java.io.PrintStream"),
        static = false,
    )

    val systemOut = Type.JFField(
        "out",
        IdentifierCache.findClass("java.lang.System"),
        IdentifierCache.findClass("java.io.PrintStream")
    )

    val plus =
        method(
            "+",
            OperandType.SInt32,
            OperandType.SInt32,
            OperandType.SInt32,
            associativity = Associativity.INFIXL,
            precedence = 100,
            parent = IdentifierCache.findClass("jafun.lang.IntKt"),
            operator = true,
        )

    val times =
        method(
            "*",
            OperandType.SInt32,
            OperandType.SInt32,
            OperandType.SInt32,
            associativity = Associativity.INFIXL,
            precedence = 110,
            parent = IdentifierCache.findClass("jafun.lang.IntKt"),
            operator = true,
        )
    val equals =
        method(
            "==",
            OperandType.UInt1,
            OperandType.SInt32,
            OperandType.SInt32,
            associativity = Associativity.INFIXL,
            precedence = 40,
            parent = IdentifierCache.findClass("jafun.lang.IntKt"),
            operator = true,
        )

    fun method(
        name: String,
        returnType: OperandType<*>,
        vararg parameters: Type.JFVariableSymbol,
        static: Boolean = true,
        associativity: Associativity = Associativity.PREFIX,
        precedence: Int = 10,
        parent: MethodParent = Type.JFClass("Script"),
    ) = Type.JFMethod(
        parameters.toList(),
        parent,
        name,
        returnType,
        static,
        associativity = associativity,
        precedence = precedence,
    )

    fun method(
        name: String,
        returnType: OperandType<*>,
        vararg parameters: OperandType<*>,
        static: Boolean = true,
        associativity: Associativity = Associativity.PREFIX,
        precedence: Int = 10,
        parent: MethodParent = Type.JFClass("Script"),
        operator: Boolean = false,
    ) = Type.JFMethod(
        parameters.toList()
            .mapIndexed { i, type -> Type.JFVariableSymbol("param${i + 1}", type, IdentifierCache) },
        parent,
        name,
        returnType,
        static,
        operator,
        associativity,
        precedence,
    )

    fun i(integer: Int) = IntegerLiteral(integer)

    fun s(string: String) = StringLiteral(string)

    fun b(boolean: Boolean) = ExpressionNode.BooleanLiteral(boolean)

    operator fun ExpressionNode.Phase2Expression.unaryPlus() {
        expressionsList.add(this)
    }

    fun invocation(
        method: Type.JFMethod,
        vararg parameters: ExpressionNode.Phase2_3Expression,
    ) = ExpressionNode.MethodInvocation(
        methodName = method.name,
        parentPath = method.parentPath,
        parameters = method.parameters,
        rtnLookup = { method.rtn },
        field = null,
        arguments = parameters.toList(),
    )


    fun invocation(
        method: Type.JFMethod,
        field: Type.JFField,
        vararg parameters: ExpressionNode.Phase2_3Expression,
    ) = ExpressionNode.MethodInvocation(
        methodName = method.name,
        parentPath = method.parentPath,
        parameters = method.parameters,
        rtnLookup = { method.rtn },
        field = field,
        arguments = parameters.toList(),
    )

    fun function(
        method: Type.JFMethod,
        block: Phase2Builder.() -> Unit = {},
    ) = ExpressionNode.Function(method, Phase2Builder().apply(block).expressionsList)

    fun valAssignment(
        variable: Type.JFVariableSymbol,
        expression: ExpressionNode.Phase2_3Expression,
    ) = ValAssignment(variable, expression)

    fun symbol(name: String, type: OperandType<*>) = Type.JFVariableSymbol(name, type, IdentifierCache)
    fun variable(symbol: Type.JFVariableSymbol) = ExpressionNode.Variable(symbol)

    fun `when`(vararg matches: Pair<ExpressionNode.Phase2_3Expression, ExpressionNode.Phase2_3Expression>) =
        ExpressionNode.When(null, matches.toList())

    fun `when`(
        subject: ExpressionNode.Phase2_3Expression,
        vararg matches: Pair<ExpressionNode.Phase2_3Expression, ExpressionNode.Phase2_3Expression>
    ) =
        ExpressionNode.When(subject, matches.toList())
}

data class Phase2Plugin(var phase2: List<ExpressionNode.Phase2Expression>? = null) : Compiler.Phase2Plugin {
    override fun handle(input: List<ExpressionNode.Phase2Expression>): List<ExpressionNode.Phase2Expression> {
        phase2 = input
        return input
    }
}

data class Phase3Plugin(var phase3: IRBuilder.ClassContext? = null) : Compiler.Phase3Plugin {
    override fun handle(input: IRBuilder.ClassContext): IRBuilder.ClassContext {
        phase3 = input
        return input
    }
}

data class JVMIRPlugin(var jvmir: ClassDef? = null) : Compiler.JVMIRPlugin {
    override fun handle(input: ClassDef): ClassDef {
        jvmir = input
        return input
    }
}
