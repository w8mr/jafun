package nl.w8mr.jafun.debug

import nl.w8mr.jafun.IRBuilder
import nl.w8mr.jafun.OperandType
import nl.w8mr.jafun.Type
import nl.w8mr.jafun.compiler.ExpressionNode

object IRPrintTree {
    fun print(ir: Any) =
        with(Indenter()) {
            print(this, ir)
            toString()
        }

    fun print(
        code: Indenter,
        ir: Any,
    ): Unit =
        with(code) {
            when (ir) {
                is IRBuilder.ClassContext ->
                    with(ir) {
                        -"class "
                        -name
                        +" {"
                        indent {
                            methods.forEach { method ->
                                print(this, method)
                            }
                        }
                        +"}"
                    }

                is IRBuilder.MethodContext ->
                    with(ir) {
                        -"method "
                        -name
                        -"("
                        -parameterTypes.joinToString(", ") { typeName(it) }
                        -"): "
                        -typeName(returnType)
                        +" {"
                        indent {
                            instructions.forEach { instruction ->
                                print(this, instruction)
                            }
                        }
                        +"}"
                    }

                is ExpressionNode.Invocation ->
                    with(ir) {
                        -"Invoke "
                        -ir.method.parent.path
                        -"."
                        -ir.method.name
                        -" ("
                        var first = true
                        method.parameters.forEach {
                            if (first) {
                                first = false
                            } else {
                                -", "
                            }
                            -it.name
                            -": "
                            -typeName(it.type)
                        }
                        -"): "
                        +typeName(method.rtn)
                    }
                is Type.JFVariableSymbol ->
                    with(ir) {
                        +ir.name
                        -": "
                        -typeName(ir.type)
                    }
                is ExpressionNode.WhenPhase3 ->
                    with(ir) {
                        -"When {"
                        indent {
                            matches.forEach { match ->
                                match.first.let {
                                    print(this, it)
                                }
                                +" -> {"
                                indent {
                                    match.second.let {
                                        print(this, it)
                                    }
                                }
                                +"}"
                            }
                        }

                        +"}"
                    }
                is ExpressionNode.WhilePhase3 ->
                    with(ir) {
                        -"While {"
                        indent {
                            ir.expressions.let { expression ->
                                print(this, expression)
                            }
                        }
                        +"}"
                    }
                is ExpressionNode.ExpressionList -> {
                    ir.expressions.forEach { expression -> print(this, expression) }
                }
                else -> +ir.toString() // TODO While && When
            }
        }

    fun <J> typeName(operandType: OperandType<J>): String =
        when (operandType) {
            is OperandType.Array -> "Array<${operandType.genericTypes[0]}>"
            is OperandType.Reference<*> -> operandType.type
            is OperandType.Generic -> TODO()
            OperandType.SInt32 -> "Int32"
            OperandType.StringType -> "String"
            OperandType.UInt1 -> "Boolean"
            OperandType.CharType -> "Char"
            is Type.JFClass -> operandType.path
        }
}
