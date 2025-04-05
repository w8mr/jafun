package nl.w8mr.jafun

import jafun.compiler.Associativity.INFIXL
import jafun.compiler.Associativity.INFIXR
import jafun.compiler.Associativity.POSTFIX
import jafun.compiler.Associativity.PREFIX
import jafun.compiler.Associativity.SOLO
import jafun.compiler.IdentifierCache
import jafun.compiler.LocalSymbolMap
import jafun.compiler.SymbolMap
import nl.w8mr.jafun.Token.Identifier
import nl.w8mr.parsek.Parser
import nl.w8mr.parsek.Parser.Success
import nl.w8mr.parsek.and
import nl.w8mr.parsek.asLiteral
import nl.w8mr.parsek.combi
import nl.w8mr.parsek.filter
import nl.w8mr.parsek.map
import nl.w8mr.parsek.oneOf
import nl.w8mr.parsek.optional
import nl.w8mr.parsek.or
import nl.w8mr.parsek.sepByAllowEmpty
import nl.w8mr.parsek.text.CharSequenceSource
import nl.w8mr.parsek.text.and
import nl.w8mr.parsek.text.any
import nl.w8mr.parsek.text.char
import nl.w8mr.parsek.text.letter
import nl.w8mr.parsek.text.literal
import nl.w8mr.parsek.text.oneOrMore
import nl.w8mr.parsek.text.or
import nl.w8mr.parsek.text.sepBy
import nl.w8mr.parsek.text.value
import nl.w8mr.parsek.text.zeroOrMore
import nl.w8mr.parsek.zeroOrMore
import kotlin.collections.last

object ParserJafun {
    private fun assignment(
        identifier: Identifier,
        expression: ASTNode.Expression,
    ): ASTNode.ValAssignment {
        val variableSymbol = IR.JFVariableSymbol(identifier.value, expression.type(), currentSymbolMap)
        currentSymbolMap.add(identifier.value, variableSymbol)
        return ASTNode.ValAssignment(variableSymbol, expression)
    }

    private fun newParameterDef(
        identifier: Identifier,
        type: List<Identifier>,
    ): IR.JFVariableSymbol {
        val variableSymbol =
            IR.JFVariableSymbol(
                identifier.value,
                type = currentSymbolMap.find(type.last().value) ?: throw IllegalStateException(),
                currentSymbolMap,
            ) // TODO: handle complex types
        currentSymbolMap.add(identifier.value, variableSymbol)

        return variableSymbol
    }

    private fun newFunction(
        functionDef: FunctionDef,
        block: ASTNode.ExpressionList,
    ): ASTNode.Function {
        popSymbolMap()
        val symbol =
            IR.JFMethod(
                functionDef.parameters,
                IR.JFClass("Script"),
                functionDef.identifier.value,
                block.expressions.lastOrNull()?.type() ?: IR.Unit,
                static = true,
                operator = functionDef.identifier.operator,
                associativity = if (functionDef.parameters.isEmpty()) SOLO else PREFIX,
            )
        currentSymbolMap.add(functionDef.identifier.value, symbol)
        return ASTNode.Function(symbol, block.expressions)
    }

    data class FunctionDef(
        val identifier: Identifier,
        val parameters: List<IR.JFVariableSymbol>,
        val returnType: Identifier?,
    )

    private fun defineFunction(
        identifier: Identifier,
        parameters: List<IR.JFVariableSymbol>,
        returnType: Identifier?,
    ): FunctionDef {
        val symbol =
            IR.JFMethod(
                parameters,
                IR.JFClass("Script"),
                identifier.value,
                returnType?.value?.let { currentSymbolMap.find(it) } ?: IR.Unit,
                static = true,
                operator = identifier.operator,
                associativity = if (parameters.isEmpty()) SOLO else PREFIX,
            )
        currentSymbolMap.add(identifier.value, symbol)
        return FunctionDef(identifier, parameters, returnType)
    }

    val whitespace = char(" is not whitespace") { it == '\u0020' || it == '\u0009' || it == '\u000c' }.asLiteral()
    val ows = zeroOrMore(whitespace)

    val newline = '\n' or ('\r' and '\n')
    val wsnl = oneOrMore(whitespace or newline)
    val owsnl = zeroOrMore(whitespace or newline)

    val commaTerm = ',' and owsnl
    val lParenTerm = '(' and owsnl
    val rParenTerm = ')' and ows

    // TODO: Escape characters
    val lineStringContent =
        zeroOrMore(char(" is not valid string Char") { it != '"' && it != '\\' })
    val stringLiteral_term = ('"' and lineStringContent and '"').map(ASTNode::StringLiteral)
    // TODO: Multiline string

    val decimalDigit = char { it in '0'..'9' }
    val decimalDigitNoZero = char { it in '1'..'9' }
    val decimalDigitOrSeparator = decimalDigit or char('_')
    val integerLiteral_term =
        ((decimalDigitNoZero and any(decimalDigitOrSeparator)) or decimalDigit).map {
            ASTNode.IntegerLiteral(it.replace("_", "").toInt())
        }

    val trueLiteral = "true" value ASTNode.BooleanLiteral(true)
    val falseLiteral = "false" value ASTNode.BooleanLiteral(false)
    val booleanLiteral_term = trueLiteral or falseLiteral

    val unicode_digit = char(" is not Unicode digit") { it.category == CharCategory.DECIMAL_DIGIT_NUMBER }
    val normalIdentifier =
        (letter or char('_')) and
            any(letter or char('_') or unicode_digit) map (::Identifier)

    val operatorSymbols = listOf('!', '#', '$', '%', '*', '+', '<', '>', '?', '\\', '/', '^', '|', '-', '~', '=')
    val operatorIdentifier = oneOrMore(char { it in operatorSymbols }).map { Identifier(it, true) }

    val identifier = normalIdentifier or operatorIdentifier
    val complexIdentifier = identifier sepBy '.'

    val variableIdentifier =
        combi {
            val identifier = complexIdentifier.bind()
            val name = identifier.last().value
            when (val symbol = currentSymbolMap.find(name)) {
                is IR.JFVariableSymbol -> ASTNode.Variable(symbol)
                else -> fail("no variable identifier")
            }
        }
    val whenArrow = identifier.filter { it.value == "->" }.asLiteral()

    val expressionUntilNewline = prattParser()
    val expressionUntilArrow = prattParser(stopTerm = whenArrow)
    val expressionUntilRightParen = prattParser(stopTerm = rParenTerm)
    val expressionUntilComma = prattParser(stopTerm = literal(','))

    val betweenParentheses = lParenTerm and expressionUntilNewline and rParenTerm

    private val expressions =
        zeroOrMore(owsnl and expressionUntilNewline)

    val blockOpen = '{' and owsnl
    val blockClose = owsnl and '}'

    val curlBlock =
        combi {
            -blockOpen
            pushSymbolMap()
            val expressions = expressions.bind()
            -blockClose
            popSymbolMap()
            ASTNode.ExpressionList(expressions)
        }

    val initValAssignment =
        combi {
            -("val" and wsnl)
            val identifier = identifier.bind()
            -owsnl
            -literal('=')
            -owsnl
            val expression = expressionUntilNewline.bind()

            assignment(identifier, expression)
        }

    val whenExpression =
        combi {
            -("when" and owsnl)
            pushSymbolMap()
            val subject = optional(lParenTerm and expressionUntilRightParen and rParenTerm).bind()
            -blockOpen
            val matches = zeroOrMore(expressionUntilArrow and whenArrow and owsnl and expressionUntilNewline and wsnl).bind()
            -blockClose
            popSymbolMap()
            ASTNode.When(subject, matches)
        }

    val parameter = identifier and owsnl and ':' and owsnl and complexIdentifier map (::newParameterDef)

    val functionDefinition =
        combi {
            -literal("fun")
            -wsnl
            pushSymbolMap()
            val name = identifier.bind()
            -owsnl
            -lParenTerm
            val parameters = (parameter sepByAllowEmpty commaTerm).bind()
            -rParenTerm
            val returnType = optional(':' and owsnl and identifier).bind()
            -owsnl
            defineFunction(name, parameters, returnType)
        }

    val function: Parser<Char, ASTNode.Expression> =
        functionDefinition and curlBlock map (::newFunction)

    var currentSymbolMap: SymbolMap = LocalSymbolMap(IdentifierCache.reset())

    private fun pushSymbolMap() {
        currentSymbolMap = LocalSymbolMap(currentSymbolMap)
    }

    private fun popSymbolMap() {
        val oldSymbolMap = currentSymbolMap
        currentSymbolMap =
            if (oldSymbolMap is LocalSymbolMap) {
                oldSymbolMap.parent
            } else {
                throw IllegalStateException("already at top of symbol map stack")
            }
    }

    fun parse(input: String): List<ASTNode.Expression> {
        val source = CharSequenceSource(input)
        return expressions.parse(source)
    }

    fun prattParser(
        stopTerm: Parser<Char, *> = newline or ';',
        minPrecedence: Int = 0,
    ): Parser<Char, ASTNode.Expression> =
        combi {
            var current =
                (
                    oneOf(
                        initValAssignment,
                        whenExpression,
                        integerLiteral_term,
                        stringLiteral_term,
                        booleanLiteral_term,
                        variableIdentifier,
                        soloMethod,
                        methodWithParameterArguments(null),
                        methodWithPrecedence(null, minPrecedence),
                        function,
                        betweenParentheses,
                        curlBlock,
                    ) and ows
                ).bindAsResult()

            while (current is Success) {
                val mark = mark()
                when (stopTerm.bindAsResult()) {
                    is Success -> {
                        reset(mark)
                        break
                    }

                    is Parser.Failure -> {
                        reset(mark)
                        val rhs =
                            (
                                owsnl and
                                    oneOf(
                                        postfixMethod(current.value),
                                        methodWithParameterArguments(current.value),
                                        // methodWithPrecedence(current.value, minPrecedence),
                                        methodParser(current.value, minPrecedence),
                                    ) and ows
                            ).bindAsResult()
                        if (rhs is Parser.Failure) break
                        current = rhs
                    }
                }
            }
            // println("<=== ${context.tokenOrNull()} $minP $current")
            current.bind()
        }

    fun ASTNode.Expression?.asList() =
        when (this) {
            null -> emptyList()
            else -> listOf(this)
        }

    fun postfixMethod(lhsExpression: ASTNode.Expression?) =
        combi {
            val complexIdentifier = (complexIdentifier).bind()
            val method = currentSymbolMap.find(complexIdentifier.joinToString(".") { it.value })
            if ((method is IR.JFMethod) && (method.associativity == POSTFIX)) {
                methodInvocation(method, lhsExpression.asList())
            } else {
                fail("Method ($complexIdentifier) not found or not of the right type")
            }
        }

    val soloMethod =
        combi {
            val complexIdentifier = (complexIdentifier).bind()
            val method = currentSymbolMap.find(complexIdentifier.joinToString(".") { it.value })
            if ((method is IR.JFMethod) && (method.associativity == SOLO)) {
                methodInvocation(method, emptyList())
            } else {
                fail("Method ($complexIdentifier) not found or not of the right type")
            }
        }

    fun methodWithParameterArguments(lhsExpression: ASTNode.Expression?) =
        combi {
            val complexIdentifier = (complexIdentifier).bind()
            val method = currentSymbolMap.find(complexIdentifier.joinToString(".") { it.value })
            if ((method is IR.JFMethod) && (method.associativity in listOf(PREFIX, INFIXL, INFIXR)) && (!method.operator)) {
                val arguments =
                    (
                        lParenTerm and
                            (expressionUntilComma sepByAllowEmpty commaTerm) and
                            rParenTerm
                    ).bind()
                methodInvocation(method, lhsExpression.asList() + arguments)
            } else {
                fail("Method ($complexIdentifier) not found or not of the right type")
            }
        }

    fun methodWithPrecedence(
        lhsExpression: ASTNode.Expression?,
        minPrecedence: Int,
    ): Parser<Char, ASTNode.Expression> =
        combi {
            val complexIdentifier = (complexIdentifier and owsnl).bind()
            val method = currentSymbolMap.find(complexIdentifier.joinToString(".") { it.value })
            if (method is IR.JFMethod) {
                val newPrecedence =
                    when {
                        method.precedence > minPrecedence -> method.precedence - if (method.associativity == INFIXR) 1 else 0
                        else -> 0
                    }
                val argument = prattParser(minPrecedence = newPrecedence).bind()
                methodInvocation(method, lhsExpression.asList() + listOf(argument))
            } else {
                fail("Method ($complexIdentifier) not found")
            }
        }

    fun methodParser(
        lhsExpression: ASTNode.Expression?,
        minPrecedence: Int,
    ): Parser<Char, ASTNode.Expression> =
        combi {
            val complexIdentifier = (complexIdentifier and owsnl).bind()
            val method = currentSymbolMap.find(complexIdentifier.joinToString(".") { it.value })
            if (method is IR.JFMethod) {
                val lhsArguments = lhsExpression?.let { (listOf(it)) } ?: emptyList()
                when (method.associativity) {
                    POSTFIX -> TODO()
                    SOLO -> TODO()
                    else -> {
                        val newPrecedence =
                            when {
                                method.precedence > minPrecedence -> method.precedence - if (method.associativity == INFIXR) 1 else 0
                                else -> 0
                            }
                        if (method.precedence <= minPrecedence) fail("Lower precedence")

                        val argument = (prattParser(minPrecedence = newPrecedence)).bind()
                        methodInvocation(method, lhsArguments + listOf(argument))
                    }
                }
            } else {
                fail("Method ($complexIdentifier) not found")
            }
        }

    private fun methodInvocation(
        method: IR.JFMethod,
        arguments: List<ASTNode.Expression>,
    ): ASTNode.Expression =
        when {
            method.static -> ASTNode.Invocation(method, null, arguments)
            method.parent is IR.JFField -> ASTNode.Invocation(method, method.parent, arguments)
            else -> TODO()
        }
}
