package nl.w8mr.jafun

import nl.w8mr.jafun.compiler.IdentifierCache
import nl.w8mr.jafun.compiler.LocalSymbolMap
import nl.w8mr.jafun.ParserJafun.currentSymbolMap
import nl.w8mr.jafun.compiler.ast2ir.compileExpressionNode
import nl.w8mr.jafun.debug.IRPrintTree
import nl.w8mr.jafun.debug.prettyPrint
import nl.w8mr.jafun.debug.print
import nl.w8mr.kasmine.DynamicClassLoader
import nl.w8mr.parsek.Parser
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.PrintStream
import kotlin.Array

fun compile(
    code: String,
    className: String = "Script",
    methodName: String = "main",
    returnType: Type.OperandType<*> = Type.Unit,
    parameterTypes: List<Type.OperandType<*>> = listOf(Type.Array(Type.Reference<String>("java.lang.String"))),
): ByteArray {
    val parseResult = ParserJafun.parse(code)
    when (parseResult.second) {
        is Parser.Failure<*> -> {
            println(parseResult.second)
            error("Parser failed")
        }
        is Parser.Success<*> -> {
            val parsed = parseResult.first
            println("PARSED: \n${parsed!!.joinToString("\n") { it.prettyPrint() }}")
            println()
            return compile(parsed, className, methodName, returnType, parameterTypes)
        }
    }
}

fun runMain(bytes: ByteArray) {
    writeFile("Script", bytes)
    runMethod(bytes, "Script", "main")
}

fun test(
    code: String,
    className: String = "Script",
    methodName: String = "main",
    returnType: Type.OperandType<*> = Type.Unit,
    parameterTypes: List<Type.OperandType<*>> = listOf(Type.Array(Type.Reference<String>("java.lang.String"))),
) = testBytes(code, className, methodName, returnType, parameterTypes).first

fun     testBytes(
    code: String,
    className: String = "Script",
    methodName: String = "main",
    returnType: Type.OperandType<*> = Type.Unit,
    parameterTypes: List<Type.OperandType<*>> = listOf(Type.Array(Type.Reference<String>("java.lang.String"))),
    params: Array<String>? = null,
): Pair<String, ByteArray> {

    val parseResult = ParserJafun.parse(code)
    when (parseResult.second) {
        is Parser.Failure<*> -> {
            println(parseResult.second)
            error("Parser failed")
        }

        is Parser.Success<*> -> {
            val parsed = parseResult.first
            println("PARSED: \n${parsed!!.joinToString("\n") { it.prettyPrint() }}")
            println()
            currentSymbolMap = LocalSymbolMap(IdentifierCache.reset()).apply {
                add(
                    "arguments",
                    Type.JFVariableSymbol("param1", Type.Array(Type.JFClass("java/lang/String")), this, false)
                )
            } // TODO: look into this.
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
    }
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
    returnType: Type.OperandType<*> = Type.Unit,
    parameterTypes: List<Type.OperandType<*>> = listOf(Type.Array(Type.Reference<String>("java.lang.String"))),
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
    returnType: Type.OperandType<*>,
    parameterTypes: List<Type.OperandType<*>>,
) {

    with(builder) {
        method(methodName, returnType, parameterTypes) {
            codeBlock {
                val lastIndex = statements.size - 1
                statements.forEachIndexed { index, statement ->
                    if ((lastIndex == index)) {
                        if (returnType is Type.Unit) {
                            compileAsStatement(statement, this)
                            `return`(Type.Unit)
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
    compileExpressionNode(expression, builder, false)
}

fun compileAsExpression(
    expression: ASTNode.Expression,
    builder: IRBuilder.CodeBlockDSL,
) {
    compileExpressionNode(expression, builder, true)
    if (expression.type() == Type.Unit) builder.getStatic("jafun/Unit", "INSTANCE", Type.Unit)
}
