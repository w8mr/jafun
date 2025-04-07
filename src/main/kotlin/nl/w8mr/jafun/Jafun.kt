package nl.w8mr.jafun

import jafun.compiler.IdentifierCache
import jafun.compiler.LocalSymbolMap
import nl.w8mr.jafun.ParserJafun.currentSymbolMap
import nl.w8mr.jafun.debug.IRPrintTree
import nl.w8mr.jafun.debug.print
import nl.w8mr.kasmine.DynamicClassLoader
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.PrintStream
import kotlin.Array

fun compile(
    code: String,
    className: String = "Script",
    methodName: String = "main",
    returnType: IR.OperandType<*> = IR.Unit,
    parameterTypes: List<IR.OperandType<*>> = listOf(IR.Array(IR.Reference<String>("java.lang.String"))),
): ByteArray {
    val parsed = ParserJafun.parse(code)
    println("PARSED: \n${parsed.joinToString("\n\n") { it.tree() }}")
    println()
    return compile(parsed, className, methodName, returnType, parameterTypes)
}

fun runMain(bytes: ByteArray) {
    writeFile("Script", bytes)
    runMethod(bytes, "Script", "main")
}

fun test(
    code: String,
    className: String = "Script",
    methodName: String = "main",
    returnType: IR.OperandType<*> = IR.Unit,
    parameterTypes: List<IR.OperandType<*>> = listOf(IR.Array(IR.Reference<String>("java.lang.String"))),
) = testBytes(code, className, methodName, returnType, parameterTypes).first

fun     testBytes(
    code: String,
    className: String = "Script",
    methodName: String = "main",
    returnType: IR.OperandType<*> = IR.Unit,
    parameterTypes: List<IR.OperandType<*>> = listOf(IR.Array(IR.Reference<String>("java.lang.String"))),
    params: Array<String>? = null,
): Pair<String, ByteArray> {

    val parsed = ParserJafun.parse(code)
    println("PARSED: \n${parsed.joinToString("\n\n") { it.tree() }}")
    println()
    currentSymbolMap = LocalSymbolMap(IdentifierCache.reset()).apply { add("arguments", IR.JFVariableSymbol("param1", IR.Array(IR.JFClass("java/lang/String")), this, false)) } // TODO: look into this.
    val builder =
        IRBuilder.define {
            `class`(className) {
                compileMethod(this, parsed, methodName, returnType, parameterTypes)
            }
        }

    println("IR: \n${IRPrintTree.print(builder.classes[className]!!)}")
    val clazz = buildClass(className, builder)
    println("Bytecode: \n${clazz.classDef.print()}")
    val bytes = clazz.write()

    writeFile(className, bytes)
    val oldOut = System.out
    val output = ByteArrayOutputStream()
    System.setOut(PrintStream(output))
    try {
        runMethod(bytes, className, methodName, params)
        System.setOut(oldOut)
    } catch (t: Throwable) {
        System.setOut(oldOut)
        println(t.message)
        t.printStackTrace()
    }
    val result = String(output.toByteArray())
    println("OUTPUT: $result")
    return result to bytes
}

private fun runMethod(
    bytes: ByteArray,
    className: String,
    methodName: String,
    params: Array<String>? = null,
) {
    val loader = DynamicClassLoader(Thread.currentThread().contextClassLoader)
    val scriptClass = loader.define(className, bytes)
    scriptClass.getMethod(methodName, Array<String>::class.java).invoke(null, params)
}

fun writeFile(
    className: String,
    bytes: ByteArray,
) {
    val dir = File("./build/classes/jafun/test")
    dir.mkdirs()
    val file = File(dir, "$className.class")
    file.writeBytes(bytes)
}

fun compile(
    statements: List<ASTNode.Expression>,
    className: String = "Script",
    methodName: String = "main",
    returnType: IR.OperandType<*> = IR.Unit,
    parameterTypes: List<IR.OperandType<*>> = listOf(IR.Array(IR.Reference<String>("java.lang.String"))),
): ByteArray {
    currentSymbolMap = LocalSymbolMap(IdentifierCache.reset())
    val builder =
        IRBuilder.define {
            `class`(className) {
                compileMethod(this, statements, methodName, returnType, parameterTypes)
            }
        }

    return compileJVM(className, builder)
}

fun compileMethod(
    builder: IRBuilder.ClassDSL,
    statements: List<ASTNode.Expression>,
    methodName: String,
    returnType: IR.OperandType<*>,
    parameterTypes: List<IR.OperandType<*>>,
) {

    with(builder) {
        method(methodName, returnType, parameterTypes) {
            codeBlock {
                val lastIndex = statements.size - 1
                statements.forEachIndexed { index, statement ->
                    if ((lastIndex == index)) {
                        if (returnType is IR.Unit) {
                            compileAsStatement(statement, this)
                            `return`(IR.Unit)
                        } else {
                            compileAsExpression(statement, this)
                            `return`(statement.type())
                        }
                    } else {
                        compileAsStatement(statement, this)
                    }
                }
            }
        }
    }
}

fun compileAsStatement(
    expression: ASTNode.Expression,
    builder: IRBuilder.CodeBlockDSL,
) {
    expression.compile(builder, false)
}

fun compileAsExpression(
    expression: ASTNode.Expression,
    builder: IRBuilder.CodeBlockDSL,
) {
    expression.compile(builder, true)
    if (expression.type() == IR.Unit) builder.getStatic("jafun/Unit", "INSTANCE", IR.Unit)
}
