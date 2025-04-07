package nl.w8mr.jafun.debug

import nl.w8mr.jafun.IR
import nl.w8mr.jafun.IRBuilder

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
                is IR.JFVariableSymbol -> with(ir) {
                    +ir.name
                    -": "
                    -typeName(ir.type)
                }
                else -> +ir.toString()
            }
        }


        fun <J> typeName(operandType: IR.OperandType<J>): String = when (operandType) {
            is IR.Array<*> -> "Array<${typeName(operandType.type)}>"
            is IR.JFClass -> operandType.path
            is IR.JFMethod -> "${operandType.parent.path}.${operandType.name}"
            is IR.JFVariableSymbol -> "${operandType.name}: ${typeName(operandType.type)}"
            is IR.Reference<*> -> operandType.type
            IR.SInt32 -> "Int32"
            IR.StringType -> "String"
            IR.UInt1 -> "Boolean"
            IR.CharType -> "Char"
        }
}