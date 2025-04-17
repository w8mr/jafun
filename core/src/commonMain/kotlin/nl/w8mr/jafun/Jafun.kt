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

