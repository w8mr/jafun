package nl.w8mr.jafun

import jafun.compiler.Associativity.INFIXL
import jafun.compiler.Associativity.INFIXR
import jafun.compiler.Associativity.POSTFIX
import jafun.compiler.Associativity.PREFIX
import jafun.compiler.Associativity.SOLO
import jafun.compiler.IdentifierCache
import jafun.compiler.LocalSymbolMap
import jafun.compiler.SymbolMap
import nl.w8mr.jafun.IR.JFClass
import nl.w8mr.jafun.IR.JFField
import nl.w8mr.jafun.IR.JFMethod
import nl.w8mr.jafun.IR.JFVariableSymbol
import nl.w8mr.jafun.IR.Unit
import nl.w8mr.jafun.Token.Identifier
import nl.w8mr.parsek.CombinatorDSL
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
import nl.w8mr.parsek.text.repeat
import nl.w8mr.parsek.text.sepBy
import nl.w8mr.parsek.text.value
import nl.w8mr.parsek.text.zeroOrMore
import nl.w8mr.parsek.zeroOrMore
import kotlin.collections.joinToString
import kotlin.collections.last

object ParserJafun {
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

    val charLiteral_term =
        ('\'' and char(" is not valid string Char") { it != '\'' && it != '\\' } and '\'').map(ASTNode::CharLiteral)

    val decimalDigit = char { it in '0'..'9' }
    val decimalDigitNoZero = char { it in '1'..'9' }
    val decimalDigitOrSeparator = decimalDigit or char('_')
    val integerLiteral_term =
        ((repeat(char('-'), 1, 0) and decimalDigitNoZero and any(decimalDigitOrSeparator)) or decimalDigit).map {
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

            if (identifier.value in currentSymbolMap) {
                throw IllegalStateException("Variable ${identifier.value} already defined")
            }
            val variableSymbol = JFVariableSymbol(identifier.value, expression.type(), currentSymbolMap, false)
            currentSymbolMap.add(identifier.value, variableSymbol)
            ASTNode.ValAssignment(variableSymbol, expression)
        }

    val initVarAssignment =
        combi {
            -("var" and wsnl)
            val identifier = identifier.bind()
            -owsnl
            -literal('=')
            -owsnl
            val expression = expressionUntilNewline.bind()

            if (identifier.value in currentSymbolMap) {
                throw IllegalStateException("Variable ${identifier.value} already defined")
            }
            val variableSymbol = JFVariableSymbol(identifier.value, expression.type(), currentSymbolMap, true)
            currentSymbolMap.add(identifier.value, variableSymbol)
            ASTNode.VarAssignment(variableSymbol, expression)
        }

    val varAssignment =
        combi {
            val identifier = identifier.bind()
            -owsnl
            -literal('=')
            -owsnl
            val expression = expressionUntilNewline.bind()

            val variableSymbol = currentSymbolMap.findSingle(identifier.value) as? JFVariableSymbol
                ?: throw IllegalStateException("Variable ${identifier.value} not defined")
            if (!variableSymbol.mutable) {
                throw IllegalStateException("Variable ${identifier.value} is not mutable")
            }
            ASTNode.VarAssignment(variableSymbol, expression)
        }

    val elseInWhen = "else".value(ASTNode.BooleanLiteral(true)) and owsnl
    val whenExpression =
        combi {
            -("when" and owsnl)
            pushSymbolMap()
            val subject = optional(lParenTerm and expressionUntilRightParen and rParenTerm).bind()
            -blockOpen
            val matches =
                zeroOrMore((elseInWhen or expressionUntilArrow) and whenArrow and owsnl and expressionUntilNewline and wsnl).bind()
            -blockClose
            popSymbolMap()
            ASTNode.When(subject, matches)
        }

    val whileExpression =
        combi {
            -("while" and owsnl)
            pushSymbolMap()
            -lParenTerm
            val condition = expressionUntilRightParen.bind()
            -rParenTerm
            -owsnl
            val expressions = curlBlock.bind()
            popSymbolMap()
            ASTNode.While(condition, expressions)
        }

    val function =
        combi {
            fun newParameterDef(
                identifier: Identifier,
                type: List<Identifier>,
            ): JFVariableSymbol {
                val variableSymbol =
                    JFVariableSymbol(
                        identifier.value,
                        type = currentSymbolMap.findSingle(type.last().value),
                        currentSymbolMap
                    ) // TODO: handle complex types
                currentSymbolMap.add(identifier.value, variableSymbol)

                return variableSymbol
            }

            -literal("fun")
            -wsnl
            val name = identifier.bind()
            -owsnl
            -lParenTerm
            val parameters =
                (identifier and owsnl and ':' and owsnl and complexIdentifier map (::newParameterDef) sepByAllowEmpty commaTerm).bind()
            -rParenTerm
            val returnType = optional(':' and owsnl and identifier).bind()
            -owsnl

            val symbol =
                JFMethod(
                    parameters,
                    JFClass("Script"),
                    name.value,
                    returnType?.value?.let { currentSymbolMap.findSingleOrNull(it) } ?: Unit,
                    static = true,
                    operator = name.operator,
                    associativity = if (parameters.isEmpty()) SOLO else PREFIX,
                )
            currentSymbolMap.add(name.value, symbol)

            val block = curlBlock.bind()

            val symbolWithReturnType = symbol.copy(rtn = block.expressions.lastOrNull()?.type() ?: Unit)
            currentSymbolMap.add(name.value, symbolWithReturnType)

            ASTNode.Function(symbolWithReturnType, block.expressions)

        }

    fun prattParser(
        stopTerm: Parser<Char, *> = newline or ';',
        minPrecedence: Int = 0,
    ): Parser<Char, ASTNode.Expression> =
        //TODO: Cache parsers
        combi {
            var current = (
                    oneOf(
                        integerLiteral_term,
                        stringLiteral_term,
                        charLiteral_term,
                        booleanLiteral_term,
                        function,
                        initValAssignment,
                        initVarAssignment,
                        varAssignment,
                        whenExpression,
                        whileExpression,
                        betweenParentheses,
                        methodLhs(minPrecedence),
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
                        when (val rhs = (owsnl and methodRhs(current.value, minPrecedence) and ows).bindAsResult()) {
                            is Parser.Failure -> break
                            else -> current = rhs
                        }
                    }
                }
            }
            current.bind()
        }

    fun methodLhs(
        minPrecedence: Int,
    ): Parser<Char, ASTNode.Expression> =
        //TODO: Cache parsers
        combi {
            val complexIdentifier = (complexIdentifier and ows).bind()
            val symbol = currentSymbolMap.findFirstOrNull(complexIdentifier.joinToString(".") { it.value })
            when (symbol) {
                is JFVariableSymbol ->
                    ASTNode.Variable(symbol)
                is JFMethod -> {
                    val arguments = when (symbol.associativity) {
                        SOLO -> {
                            -optional(lParenTerm and rParenTerm)
                            emptyList()
                        }
                        PREFIX -> {
                            methodArguments(symbol, minPrecedence)
                        }
                        else -> fail("Method (${complexIdentifier.joinToString(".") { it.value}}) does not have the right associativity")
                    }
                    methodInvocation(symbol, arguments)
                }
                else -> fail("Method or variable (${complexIdentifier.joinToString(".") { it.value}}) not found")
            }
        }

    fun methodRhs(
        lhsExpression: ASTNode.Expression?,
        minPrecedence: Int,
    ): Parser<Char, ASTNode.Expression> =
        //TODO: Cache parsers
        combi {
            val complexIdentifier = (complexIdentifier and owsnl).bind()
            val symbols = currentSymbolMap.find(complexIdentifier.joinToString(".") { it.value })
            var okMark = mark()
            val result = symbols.filterIsInstance<JFMethod>().mapNotNull { symbol ->
                val mark = mark()
                val arguments = when (symbol.associativity) {
                    POSTFIX -> {
                        lhsExpression.asList()
                    }

                    INFIXL, INFIXR -> {
                        if (symbol.precedence <= minPrecedence) fail("Lower precedence")
                        methodArguments(symbol, minPrecedence, lhsExpression)
                    }

                    else -> fail("Method (${complexIdentifier.joinToString(".") { it.value }}) does not have the right associativity")
                }
                if (arguments.map { it.type() } == symbol.parameters.map { it.type }) {
                    okMark = mark()
                    reset(mark)
                    methodInvocation(symbol, arguments)
                } else {
                    reset(mark)
                    null
                }

            }.singleOrNull() ?: fail("No single method (${complexIdentifier}) not found")
            reset(okMark)
            result
        }

    private fun ASTNode.Expression?.asList() =
        when (this) {
            null -> emptyList()
            else -> listOf(this)
        }

    private fun CombinatorDSL<Char, ASTNode.Expression>.methodArguments(
        method: JFMethod,
        minPrecedence: Int,
        lhsExpression: ASTNode.Expression? = null
    ): List<ASTNode.Expression> {
        val newPrecedence =
            when {
                method.precedence > minPrecedence -> method.precedence - if (method.associativity == INFIXR) 1 else 0
                else -> 0
            }
        val rhsArguments =
            oneOf(
                lParenTerm and (expressionUntilComma sepByAllowEmpty commaTerm) and rParenTerm,
                prattParser(minPrecedence = newPrecedence).map { listOf(it) }
            ).bind()
        return lhsExpression.asList() + rhsArguments
    }

    private fun methodInvocation(
        method: JFMethod,
        arguments: List<ASTNode.Expression>,
    ): ASTNode.Expression =
        when {
            method.static -> ASTNode.Invocation(method, null, arguments)
            method.parent is JFField -> ASTNode.Invocation(method, method.parent, arguments)
            else -> TODO()
        }

    var currentSymbolMap: SymbolMap = LocalSymbolMap(IdentifierCache.reset()).apply {
        add(
            "arguments",
            JFVariableSymbol("arguments", IR.Array(JFClass("java/lang/String")), this, false)
        )
    }

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
}
