package nl.w8mr.jafun.debug

import nl.w8mr.jafun.IR
import nl.w8mr.jafun.IRBuilder
import nl.w8mr.jafun.Type

object IRPrintTree {
    fun print(ir: Any) =
        with(Indenter()) {
            print(this, ir)
            toString()
        }

    fun print(code: Indenter, ir: Any): Unit =
        with(code) {
            when (ir) {
                is IRBuilder.ClassContext -> with(ir) {
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

                is IRBuilder.MethodContext -> with(ir) {
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

                is IR.Invoke -> with(ir) {
                    -"Invoke "
                    -ir.method.parent.path
                    -"."
                    -ir.method.name
                    -" ("
                    var first = true
                    method.parameters.forEach {
                        if (first) first = false
                        else -", "
                        -it.name
                        -": "
                        -typeName(it.type)
                    }
                    -"): "
                    +typeName(method.rtn)
                }
                is Type.JFVariableSymbol -> with(ir) {
                    +ir.name
                    -": "
                    -typeName(ir.type)
                }
                is IR.When -> with(ir) {
                    -"When {"
                    indent {
                        cases.forEach { case ->
                            when (case) {
                                is IR.When.WhenConditionCase -> {
                                    case.condition.forEach {
                                        print(this, it)
                                    }
                                    +" -> {"
                                    indent {
                                        case.execution.forEach {
                                            print(this, it)
                                        }
                                    }
                                    +"}"
                                }

                                is IR.When.WhenElseCase -> {
                                    +"else -> {"
                                    case.execution.forEach {
                                        print(this, it)
                                    }
                                    +"}"
                                }
                            }
                        }
                    }

                    +"}"
                }
                is IR.While -> with(ir) {
                    -"While {"
                    indent {
                        ir.expressions.forEach { expression ->
                            print(this, expression)
                        }

                    }
                    +"}"
                }
                else -> +ir.toString() //TODO While && When
            }
        }


        fun <J> typeName(operandType: Type.OperandType<J>): String = when (operandType) {
            is Type.Array<*> -> "Array<${typeName(operandType.type)}>"
            is Type.JFClass -> operandType.path
            is Type.JFMethod -> "${operandType.parent.path}.${operandType.name}"
            is Type.JFVariableSymbol -> "${operandType.name}: ${typeName(operandType.type)}"
            is Type.Reference<*> -> operandType.type
            Type.SInt32 -> "Int32"
            Type.StringType -> "String"
            Type.UInt1 -> "Boolean"
            Type.CharType -> "Char"
            is Type.JFField -> "JFField"
            is Type.JFPackage -> "JFPackage"
        }
}