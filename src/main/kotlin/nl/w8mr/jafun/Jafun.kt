package nl.w8mr.jafun

import nl.w8mr.kasmine.DynamicClassLoader
import nl.w8mr.kasmine.classBuilder
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
    val lexed = lexer.parse(code).filter { it !is Token.WS }
    println("LEXED: $lexed")
    val parsed = Parser.parse(lexed)
    println("PARSED: $parsed")
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

fun testBytes(
    code: String,
    className: String = "Script",
    methodName: String = "main",
    returnType: IR.OperandType<*> = IR.Unit,
    parameterTypes: List<IR.OperandType<*>> = listOf(IR.Array(IR.Reference<String>("java.lang.String"))),
): Pair<String, ByteArray> {
    val bytes =
        compile(
            code.trimIndent(),
            className,
            methodName,
            returnType,
            parameterTypes,
        )
    writeFile(className, bytes)
    val oldOut = System.out
    val output = ByteArrayOutputStream()
    System.setOut(PrintStream(output))
    try {
        runMethod(bytes, className, methodName)
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
) {
    val loader = DynamicClassLoader(Thread.currentThread().contextClassLoader)
    val scriptClass = loader.define(className, bytes)
    scriptClass.getMethod(methodName, Array<String>::class.java).invoke(null, null)
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
    val builder =
        IRBuilder.define {
            `class`(className) {
                compileMethod(this, statements, methodName, returnType, parameterTypes)
            }
        }

    val clazz =
        classBuilder {
            name = className
            builder.classes[className]?.let {
                it.methods.forEach { method ->
                    method.codeBlocks.forEach { codeBlock ->
                        method {
                            name = method.name
                            signature = "(${method.parameterTypes.map(IR::signature).joinToString(separator = "")})" +
                                "${IR.signature(method.returnType)}"
                            val context = JVMBackend.Context(this)
                            codeBlock.instructions.forEach(context::compile)
                        }
                    }
                }
            }
        }

    return clazz.write()
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
