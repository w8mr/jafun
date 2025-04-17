package nl.w8mr.jafun

import nl.w8mr.jafun.compiler.IdentifierCache
import nl.w8mr.jafun.compiler.LocalSymbolMap
import nl.w8mr.jafun.compiler.ast2ir.compileExpressionNode
import nl.w8mr.jafun.debug.IRPrintTree
import nl.w8mr.jafun.debug.prettyPrint
import nl.w8mr.jafun.debug.print
import nl.w8mr.parsek.Parser

fun compile(
    code: String,
    className: String,
    methodName: String,
    returnType: OperandType<*>,
    parameterTypes: List<OperandType<*>>,
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

fun compile(
    statements: List<ASTNode.Expression>,
    className: String,
    methodName: String,
    returnType: OperandType<*>,
    parameterTypes: List<OperandType<*>>,
): ByteArray {
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
    returnType: OperandType<*>,
    parameterTypes: List<OperandType<*>>,
) {
    with(builder) {
        method(methodName, returnType, parameterTypes) {
            codeBlock {
                val lastIndex = statements.size - 1
                statements.forEachIndexed { index, statement ->
                    if ((lastIndex == index)) {
                        if (returnType is OperandType.Unit) {
                            compileAsStatement(statement, this)
                            `return`(OperandType.Unit)
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
    if (expression.type() == OperandType.Unit) builder.getStatic("kotlin.Unit", "INSTANCE", OperandType.Unit)
}

fun runMain(bytes: ByteArray) {
    writeFile("Script", bytes)
    runMethod(bytes, "Script", "main")
}

fun test(
    code: String,
    className: String,
    methodName: String,
    returnType: OperandType<*>,
    parameterTypes: List<OperandType<*>>,
): String = testBytes(code, className, methodName, returnType, parameterTypes).first

fun testBytes(
    code: String,
    className: String = "Script",
    methodName: String = "main",
    returnType: OperandType<*> = OperandType.Unit,
    parameterTypes: List<OperandType<*>> = listOf(OperandType.Array(OperandType.Reference<String>("java.lang.String"))),
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
            ParserJafun.symbolMap.currentSymbolMap =
                LocalSymbolMap(IdentifierCache.reset()).apply {
                    add(
                        null,
                        "arguments",
                        Type.JFVariableSymbol(
                            "param1",
                            OperandType.Array(Type.JFClass("String", Type.JFPackage("lang", Type.JFPackage("java")))),
                            this,
                            false,
                        ),
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
            val result = runAndCatchOutput(bytes, className, methodName, params)
            println("OUTPUT: $result")
            return result to bytes
        }
    }
}

expect fun runAndCatchOutput(
    bytes: ByteArray,
    className: String,
    methodName: String,
    params: Array<String>?
): String

expect fun runMethod(
    bytes: ByteArray,
    className: String,
    methodName: String,
    params: Array<String>? = null,
)

expect fun writeFile(
    className: String,
    bytes: ByteArray,
)


