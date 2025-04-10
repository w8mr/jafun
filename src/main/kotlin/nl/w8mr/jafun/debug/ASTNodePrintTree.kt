package nl.w8mr.jafun.debug

import nl.w8mr.jafun.ASTNode

/**
 * Generates a pretty-printed string representation of the ASTNode.
 * @param indentSize The number of spaces to use for each indentation level.
 * @return The formatted string.
 */
fun ASTNode.prettyPrint(indentSize: Int = 2): String {
    val indenter = Indenter(indentSize)
    indenter.print(this) // Start the recursive printing
    return indenter.toString()
}

// --- Core Recursive Printing Logic using Indenter ---
/**
 * Extension function for Indenter to recursively print ASTNode structures.
 * This function modifies the Indenter's internal buffer.
 */
private fun Indenter.print(node: ASTNode) {
    when (node) {
        // Expression Nodes
        is ASTNode.StringLiteral -> +"StringLiteral(\"${node.value}\")"
        is ASTNode.CharLiteral -> +"CharLiteral(\'${node.value}\')"
        is ASTNode.IntegerLiteral -> +"Int32Literal(${node.value})"
        is ASTNode.BooleanLiteral -> +"BooleanLiteral(${node.value})"

        is ASTNode.ExpressionList -> {
            node.expressions.forEach { print(it) }
        }

        is ASTNode.Invocation -> {
            -(node.field?.name ?: "")
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

        is ASTNode.When -> {
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

        is ASTNode.While -> {
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

        is ASTNode.ValAssignment -> {
            -"val ${node.variableSymbol.name}: ${node.variableSymbol.type} ="
            +""
            indent {
                print(node.expression)
            }
        }

        is ASTNode.VarAssignment -> {
            -"var ${node.variableSymbol.name}: ${node.variableSymbol.type} ="
            +""
            indent {
                print(node.expression)
            }
        }

        is ASTNode.Variable -> {
            +"${node.variableSymbol.name}: ${node.variableSymbol.type}"
        }

        is ASTNode.Function -> {
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

        // Handle non-Expression ASTNode types if necessary, using default toString
        else -> +"${node::class.simpleName}(...)" // Generic fallback
    }
}
