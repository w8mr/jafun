package nl.w8mr.jafun.debug

import nl.w8mr.jafun.compiler.ir2jvm.IRBuilder
import nl.w8mr.jafun.OperandType
import nl.w8mr.jafun.Type
import nl.w8mr.jafun.compiler.ExpressionNode

/**
 * Generates a pretty-printed string representation of the ASTNode.
 * @param indentSize The number of spaces to use for each indentation level.
 * @return The formatted string.
 */
fun Printable.prettyPrint(indentSize: Int = 2): String {
    val indenter = Indenter(indentSize)
    indenter.print(this) // Start the recursive printing
    return indenter.toString()
}

// --- Core Recursive Printing Logic using Indenter ---

/**
 * Extension function for Indenter to recursively print ASTNode structures.
 * This function modifies the Indenter's internal buffer.
 */
private fun Indenter.print(element: Printable) {
    fun typeName(operandType: OperandType<*>): String =
        when (operandType) {
            is OperandType.Array -> "Array<${operandType.genericTypes[0]}>"
            is OperandType.Generic -> TODO()
            OperandType.SInt32 -> "Int32"
            OperandType.StringType -> "String"
            OperandType.UInt1 -> "Boolean"
            OperandType.CharType -> "Char"
            OperandType.Unit -> "Unit"
            OperandType.Unknown -> "Unknown"
            is Type.JFClass -> operandType.path
        }

    when (element) {
        is IRBuilder.ClassContext -> {
            -"class "
            -element.name
            +" {"
            indent {
                element.methods.forEach { method ->
                    print(method)
                }
            }
            +"}"
        }

        is IRBuilder.MethodContext -> {
            -"method "
            -element.name
            -"("
            -element.parameterTypes.joinToString(", ") { typeName(it) }
            -"): "
            -typeName(element.returnType)
            +" {"
            indent {
                element.instructions.forEach { instruction ->
                    print(instruction)
                }
            }
            +"}"
        }
        is Type.JFVariableSymbol -> {
            +element.name
            -": "
            -typeName(element.type)
        }

        // Expression Nodes
        is ExpressionNode.StringLiteral -> +"StringLiteral(\"${element.value}\")"
        is ExpressionNode.CharLiteral -> +"CharLiteral(\'${element.value}\')"
        is ExpressionNode.IntegerLiteral -> +"Int32Literal(${element.value})"
        is ExpressionNode.BooleanLiteral -> +"BooleanLiteral(${element.value})"

        is ExpressionNode.ExpressionList -> {
            element.expressions.forEach { print(it) }
        }

        is ExpressionNode.ConstructorInvocation -> {
            +"constructor ${element.cons.parent.path}("
            if (element.arguments.isNotEmpty()) {
                indent {
                    element.arguments.forEachIndexed { index, arg ->
                        print(arg) // Print the current arg
                    }
                }
            }
            +")" // Closing parenthesis
        }


        is ExpressionNode.MethodInvocation -> {
            -((element.field as? Type.JFField)?.name ?: "") // TODO Variable
            -(element.methodName)
            +"("
            if (element.arguments.isNotEmpty()) {
                indent {
                    element.arguments.forEachIndexed { index, arg ->
                        print(arg) // Print the current arg
                    }
                }
            }
            -")" // Closing parenthesis
            +": ${element.type()}" // Return type on the same line, then newline via '+'
        }

        is ExpressionNode.When -> {
            -"when"
            if (element.subject != null) {
                -" ("
                print(element.subject)
                -")"
            }
            +" {"
            indent {
                element.matches.forEach { (condition, code) ->
                    -""
                    print(condition)
                    +" -> {"
                    indent {
                        print(code)
                    }
                    +"}"
                }
            }
            +"}" // Closing brace
        }
        is ExpressionNode.WhenPhase3 -> {
            +"when {"
            indent {
                element.matches.forEach { (condition, code) ->
                    -""
                    print(condition)
                    +" -> {"
                    indent {
                        print(code)
                    }
                    +"}"
                }
            }
            +"}" // Closing brace
        }

        is ExpressionNode.While -> {
            -"while"
            -" ("
            print(element.condition)
            -")"
            +" {"
            indent {
                print(element.expressions)
            }
            +"}"
        }
        is ExpressionNode.WhilePhase3 -> {
            -"while"
            -" ("
            print(element.condition)
            -")"
            +" {"
            indent {
                print(element.expressions)
            }
            +"}"
        }

        is ExpressionNode.ValAssignment -> {
            -"val ${element.variableSymbol.name}: ${element.variableSymbol.type} ="
            +""
            indent {
                print(element.expression)
            }
        }

        is ExpressionNode.VarAssignment -> {
            -"var ${element.variableSymbol.name}: ${element.variableSymbol.type} ="
            +""
            indent {
                print(element.expression)
            }
        }

        is ExpressionNode.Variable -> {
            +"${element.variableSymbol.name}: ${element.variableSymbol.type}"
        }

        is ExpressionNode.Function -> {
            -"fun ${element.symbol.name}("
            -element.symbol.parameters.joinToString(", ") { "${it.name}: ${it.type}" }
            -")"
            -": ${element.symbol.rtn}"
            +" {"
            indent {
                element.block.forEach { print(it) }
            }
            +"}"
        }

        is ExpressionNode.Convert -> {
            -"("
            print(element.expression)
            -") as "
            +element.to
        }
        is ExpressionNode.StringTemplate -> {
            +"StringTemplate("
            element.expressions.forEachIndexed { index, expression ->
                if (index > 0) {
                    -"+ "
                }
                print(expression)
            }
            -")"
        }
        is ExpressionNode.Whitespace -> {
            +"Whitespace(\"${element.value}\")"
        }
        is ExpressionNode.Newline -> {
            +"Newline(\"${element.value.replace("\n", "\\n").replace("\r", "\\r")}\")"
        }
        is ExpressionNode.Keyword -> {
            +"Keyword(\"${element.value}\")"
        }
        is ExpressionNode.Identifier -> {
            +"Identifier(\"${element.value}\", ${element.operator})"
        }
        is ExpressionNode.CurlyBlock -> {
            +"{"
            indent {
                element.tokens.drop(1).dropLast(1).forEachIndexed { index, token ->
                    print(token)
                }
            }
            +"}"
        }
        is ExpressionNode.Phase1List -> {
            element.tokens.forEachIndexed { index, token ->
                print(token)
            }
        }


        is ExpressionNode.Dot -> -"."
        is ExpressionNode.Comma -> -","
        is ExpressionNode.Colon -> -":"
        is ExpressionNode.SemiColon -> -";"
        is ExpressionNode.DoubleQoute -> -"\""
        is ExpressionNode.SingleQoute -> -"'"
        is ExpressionNode.LeftParen -> -"("
        is ExpressionNode.RightParen -> -")"
        is ExpressionNode.LeftCurly -> +"{"
        is ExpressionNode.RightCurly -> +"}"

        // Handle non-Expression GenericNode types if necessary, using default toString
        else -> TODO("Create printable implementation for ${element::class.simpleName}") // +"${element::class.simpleName}(...)" // Generic fallback
    }
}
