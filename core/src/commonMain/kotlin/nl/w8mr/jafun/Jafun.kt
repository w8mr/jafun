package nl.w8mr.jafun

import nl.w8mr.jafun.compiler.ExpressionNode
import nl.w8mr.jafun.compiler.ast2ir.compileExpressionNode
import nl.w8mr.jafun.compiler.ir2jvm.IRBuilder
import nl.w8mr.jafun.compiler.ir2jvm.compileJVM
import nl.w8mr.jafun.debug.prettyPrint
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
    statements: List<ExpressionNode.Phase2Expression>,
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

