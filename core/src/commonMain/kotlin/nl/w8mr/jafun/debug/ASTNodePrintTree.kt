package nl.w8mr.jafun.debug

import nl.w8mr.jafun.Type
import nl.w8mr.jafun.compiler.ExpressionNode

/**
 * Generates a pretty-printed string representation of the ASTNode.
 * @param indentSize The number of spaces to use for each indentation level.
 * @return The formatted string.
 */
fun ExpressionNode.prettyPrint(indentSize: Int = 2): String {
    val indenter = Indenter(indentSize)
    indenter.print(this) // Start the recursive printing
    return indenter.toString()
}

// --- Core Recursive Printing Logic using Indenter ---

/**
 * Extension function for Indenter to recursively print ASTNode structures.
 * This function modifies the Indenter's internal buffer.
 */
private fun Indenter.print(node: ExpressionNode) {
    when (node) {
        // Expression Nodes
        is ExpressionNode.StringLiteral -> +"StringLiteral(\"${node.value}\")"
        is ExpressionNode.CharLiteral -> +"CharLiteral(\'${node.value}\')"
        is ExpressionNode.IntegerLiteral -> +"Int32Literal(${node.value})"
        is ExpressionNode.BooleanLiteral -> +"BooleanLiteral(${node.value})"

        is ExpressionNode.ExpressionList -> {
            node.expressions.forEach { print(it) }
        }

        is ExpressionNode.Invocation -> {
            -((node.field as? Type.JFField)?.name ?: "") // TODO Variable
            -(node.method.name)
            +"("
            if (node.arguments.isNotEmpty()) {
                indent {
                    node.arguments.forEachIndexed { index, arg ->
                        print(arg) // Print the current arg
                    }
                }
            }
            -")" // Closing parenthesis
            +": ${node.method.rtn}" // Return type on the same line, then newline via '+'
        }

        is ExpressionNode.When -> {
            -"when"
            if (node.subject != null) {
                -" ("
                print(node.subject)
                -")"
            }
            +" {"
            indent {
                node.matches.forEach { (condition, code) ->
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
                node.matches.forEach { (condition, code) ->
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
            print(node.condition)
            -")"
            +" {"
            indent {
                print(node.expressions)
            }
            +"}"
        }
        is ExpressionNode.WhilePhase3 -> {
            -"while"
            -" ("
            print(node.condition)
            -")"
            +" {"
            indent {
                print(node.expressions)
            }
            +"}"
        }

        is ExpressionNode.ValAssignment -> {
            -"val ${node.variableSymbol.name}: ${node.variableSymbol.type} ="
            +""
            indent {
                print(node.expression)
            }
        }

        is ExpressionNode.VarAssignment -> {
            -"var ${node.variableSymbol.name}: ${node.variableSymbol.type} ="
            +""
            indent {
                print(node.expression)
            }
        }

        is ExpressionNode.Variable -> {
            +"${node.variableSymbol.name}: ${node.variableSymbol.type}"
        }

        is ExpressionNode.Function -> {
            -"fun ${node.symbol.name}("
            -node.symbol.parameters.joinToString(", ") { "${it.name}: ${it.type}" }
            -")"
            -": ${node.symbol.rtn}"
            +" {"
            indent {
                node.block.forEach { print(it) }
            }
            +"}"
        }

        // Handle non-Expression GenericNode types if necessary, using default toString
        else -> +"${node::class.simpleName}(...)" // Generic fallback
    }
}
