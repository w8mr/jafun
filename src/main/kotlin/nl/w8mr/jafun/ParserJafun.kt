package nl.w8mr.jafun

import nl.w8mr.jafun.compiler.Associativity.INFIXL
import nl.w8mr.jafun.compiler.Associativity.INFIXR
import nl.w8mr.jafun.compiler.Associativity.POSTFIX
import nl.w8mr.jafun.compiler.Associativity.PREFIX
import nl.w8mr.jafun.Type.JFClass
import nl.w8mr.jafun.Type.JFField
import nl.w8mr.jafun.Type.JFMethod
import nl.w8mr.jafun.Type.JFPackage
import nl.w8mr.jafun.Type.JFVariableSymbol
import nl.w8mr.jafun.Token.Identifier
import nl.w8mr.jafun.Type.JFFieldMethod
import nl.w8mr.jafun.Type.JFVariableMethod
import nl.w8mr.jafun.compiler.SymbolMapManager
import nl.w8mr.parsek.CombinatorDSL
import nl.w8mr.parsek.Parser
import nl.w8mr.parsek.Parser.Failure
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
import nl.w8mr.parsek.text.value
import nl.w8mr.parsek.text.zeroOrMore
import nl.w8mr.parsek.times
import nl.w8mr.parsek.zeroOrMore

object ParserJafun {
    val symbolMap = SymbolMapManager()

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
        ('\'' and char(" is not valid string Char") { it != '\'' && it != '\\' } and '\'').map { ASTNode.CharLiteral(it[0]) }

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

    val complexIdentifier: Parser<Char, List<TypeSymbol>> = combi {
        fun CombinatorDSL<Char, List<TypeSymbol>>.nextIdentifierPart(current: TypeSymbol): List<TypeSymbol> {
            fun CombinatorDSL<Char, List<TypeSymbol>>.handleNexts(
                nextIdResult: Success<Identifier>,
                current: TypeSymbol
            ): List<TypeSymbol> {
                val nexts =
                    symbolMap.find(current, nextIdResult.value.value)
                return nexts.flatMap { next ->
                    when (next) {
                        is JFField -> {
                            nextIdentifierPart((next.type as JFClass))
                                .filterIsInstance<JFMethod>()
                                .filter { !it.static }
                                .map { JFFieldMethod(next, it) }
                        }

                        is JFClass, is JFPackage -> nextIdentifierPart(next)
                        is JFMethod -> listOf(next)
                        else -> error("Should be field or method")
                    }
                }
            }

            return when (val nextIdResult = combi {
                -char('.')
                (identifier and owsnl).bind()
            }.bindAsResult()) {
                is Success<Identifier> -> {
                    when (current) {
                        is JFClass, is JFPackage -> handleNexts(nextIdResult, current)
                        is JFField -> handleNexts(nextIdResult, (current.type as JFClass))
                        is JFVariableSymbol -> {
                            handleNexts(nextIdResult, (current.type as JFClass))
                                .filterIsInstance<JFMethod>()
                                .filter { !it.static }
                                .map { JFVariableMethod(current, it) }
                        }

                        else -> error("Should be field or method")
                    }
                }

                is Failure<*> -> listOf(current)
            }
        }

        val id = identifier.bind()
        val currents = symbolMap.find(null, id.value)
        currents.flatMap { current ->
            when (current) {
                is JFMethod -> listOf(current)
                else -> nextIdentifierPart(current)
            }
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
            ASTNode.ExpressionList(
                symbolMap.local {
                    val expressions = expressions.bind()
                    -blockClose
                    expressions
                }
            )
        }


    val initValAssignment =
        combi {
            -("val" and wsnl)
            val identifier = identifier.bind()
            -owsnl
            -literal('=')
            -owsnl
            val expression = expressionUntilNewline.bind()
            ASTNode.ValAssignment(symbolMap.newVariableSymbol(identifier.value, expression.type(), false), expression)
        }

    val initVarAssignment =
        combi {
            -("var" and wsnl)
            val identifier = identifier.bind()
            -owsnl
            -literal('=')
            -owsnl
            val expression = expressionUntilNewline.bind()
            ASTNode.VarAssignment(symbolMap.newVariableSymbol(identifier.value, expression.type(), true), expression)
        }

    val varAssignment =
        combi {
            val identifier = identifier.bind()
            -owsnl
            -literal('=')
            -owsnl
            val expression = expressionUntilNewline.bind()

            val variableSymbol = symbolMap.findSingle(identifier.value) as? JFVariableSymbol
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
            val (subject, matches) = symbolMap.local() {
                val subject = optional(lParenTerm and expressionUntilRightParen and rParenTerm).bind()
                -blockOpen
                val matches =
                    zeroOrMore((elseInWhen or expressionUntilArrow) and whenArrow and owsnl and expressionUntilNewline and wsnl).bind()
                -blockClose
                subject to matches
            }
            ASTNode.When(subject, matches)
        }

    val whileExpression =
        combi {
            -("while" and owsnl)
            val (condition, expressions) = symbolMap.local {
                -lParenTerm
                val condition = expressionUntilRightParen.bind()
                -rParenTerm
                -owsnl
                val expressions = curlBlock.bind()
                condition to expressions
            }
            ASTNode.While(condition, expressions)
        }

    val function =
        combi {
            -literal("fun")
            -wsnl
            val name = identifier.bind()
            -owsnl
            val (symbol, block) = symbolMap.local {
                -lParenTerm
                val parameters =
                    (identifier and owsnl and ':' and owsnl and complexIdentifier map { identifier, type ->
                        symbolMap.newVariableSymbol(
                        identifier.value, type.singleOrNull() as? OperandType<*> ?: TODO("Handle complex type"), false)
                    } sepByAllowEmpty commaTerm).bind()
                -rParenTerm
                val returnType = optional(':' and owsnl and identifier).bind()
                -owsnl

                val symbol =
                    JFMethod(
                        parameters,
                        JFClass("Script"),
                        name.value,
                        returnType?.value?.let { symbolMap.findSingleOrNull(it) as? OperandType<*> } ?: OperandType.Unit,
                        static = true,
                        operator = name.operator,
                        associativity = PREFIX,
                    )
                symbolMap.add(name.value, symbol)

                val block = curlBlock.bind()
                symbol to block
            }

            val symbolWithReturnType = symbol.copy(rtn = block.expressions.lastOrNull()?.type() ?: OperandType.Unit)
            symbolMap.add(name.value, symbolWithReturnType)

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
                    is Failure -> {
                        reset(mark)
                        current = when (val rhs = (owsnl and methodRhs(current.value, minPrecedence) and ows).bindAsResult()) {
                            is Failure -> break
                            is Success -> rhs
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
            val symbols = complexIdentifier.bind()
            symbols.map { symbol ->
                when (symbol) {
                    is JFVariableSymbol ->
                        ASTNode.Variable(symbol)
                    is JFMethod -> {
                        val arguments = when (symbol.associativity) {
                            PREFIX -> methodArguments(symbol, minPrecedence)
                            else -> fail("Method does not have the right associativity")
                        }
                        methodInvocation(symbol, arguments)
                    }
                    is JFFieldMethod -> {
                        val arguments = when (symbol.method.associativity) {
                            PREFIX -> methodArguments(symbol.method, minPrecedence)
                            else -> fail("Method does not have the right associativity")
                        }
                        methodInvocation(symbol.method, symbol.field, arguments)
                    }
                    is JFVariableMethod -> {
                        val arguments = when (symbol.method.associativity) {
                            PREFIX -> methodArguments(symbol.method, minPrecedence)
                            else -> fail("Method does not have the right associativity")
                        }
                        methodInvocation(symbol.method, symbol.variable, arguments)
                    } // TODO: remove duplication
                    else -> fail("Method or variable not found")
                }
            }.singleOrNull() ?: fail("No single method found")
        }

    fun methodRhs(
        lhsExpression: ASTNode.Expression,
        minPrecedence: Int,
    ): Parser<Char, ASTNode.Expression> =
        //TODO: Cache parsers
        combi {
            val identifier = (identifier and owsnl).bind()
            val symbols = symbolMap.find(lhsExpression.type(), identifier.value)
            var okMark = mark()
            val result = symbols.filterIsInstance<JFMethod>().mapNotNull { symbol ->
                val mark = mark()
                val arguments = when (symbol.associativity) {
                    POSTFIX -> lhsExpression.asList()
                    INFIXL, INFIXR -> {
                        if (symbol.precedence <= minPrecedence) fail("Lower precedence")
                        methodArguments(symbol, minPrecedence, lhsExpression)
                    }
                    else -> fail("Method (${identifier.value }) does not have the right associativity")
                }
                if (arguments.map { it.type() } == symbol.parameters.map { it.type }) {
                    okMark = mark()
                    reset(mark)
                    methodInvocation(symbol, arguments)
                } else {
                    reset(mark)
                    null
                }
            }.singleOrNull() ?: fail("No single method (${identifier}) not found")
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
        val count = method.parameters.size - (if (lhsExpression == null) 0 else 1)
        val newPrecedence =
            when {
                method.precedence > minPrecedence -> method.precedence - if (method.associativity == INFIXR) 1 else 0
                else -> 0
            }
        val rhsArguments = (ows and
            oneOf(
                lParenTerm and combi {
                    when (count) {
                        0 -> emptyList()
                        1 -> listOf(expressionUntilComma.bind())
                        else -> listOf(expressionUntilComma.bind()) + (commaTerm and expressionUntilComma).times(count - 1).bind()
                    }
                } and rParenTerm,
                prattParser(minPrecedence = newPrecedence).times(count),
            )).bind()
        return lhsExpression.asList() + rhsArguments
    }

    private fun methodInvocation(
        method: JFMethod,
        arguments: List<ASTNode.Expression>,
    ): ASTNode.Expression =
        when {
            method.static -> ASTNode.Invocation(method, null, arguments)
            else -> error("Method is not static")
        }

    private fun methodInvocation(
        method: JFMethod,
        field: Type.InvocationTarget,
        arguments: List<ASTNode.Expression>,
    ): ASTNode.Expression =
        when {
            !method.static -> ASTNode.Invocation(method, field, arguments)
            else -> error("Method is static")
        }


    fun parse(input: String): Pair<List<ASTNode.Expression>?, Parser.Result<List<ASTNode.Expression>>> {
        val source = CharSequenceSource(input)
        return expressions.parseTree(source)
    }
}

