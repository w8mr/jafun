package nl.w8mr.jafun

import nl.w8mr.jafun.Type.JFClass
import nl.w8mr.jafun.Type.JFField
import nl.w8mr.jafun.Type.JFFieldMethod
import nl.w8mr.jafun.Type.JFMethod
import nl.w8mr.jafun.Type.JFPackage
import nl.w8mr.jafun.Type.JFVariableMethod
import nl.w8mr.jafun.Type.JFVariableSymbol
import nl.w8mr.jafun.compiler.Associativity.INFIXL
import nl.w8mr.jafun.compiler.Associativity.INFIXR
import nl.w8mr.jafun.compiler.Associativity.POSTFIX
import nl.w8mr.jafun.compiler.Associativity.PREFIX
import nl.w8mr.jafun.compiler.ExpressionNode
import nl.w8mr.jafun.compiler.SymbolMapManager
import nl.w8mr.parsek.CombinatorDSL
import nl.w8mr.parsek.ListSource
import nl.w8mr.parsek.Parser
import nl.w8mr.parsek.Parser.Failure
import nl.w8mr.parsek.Parser.Success
import nl.w8mr.parsek.and
import nl.w8mr.parsek.asLiteral
import nl.w8mr.parsek.combi
import nl.w8mr.parsek.filter
import nl.w8mr.parsek.map
import nl.w8mr.parsek.oneOf
import nl.w8mr.parsek.oneOrMore
import nl.w8mr.parsek.optional
import nl.w8mr.parsek.or
import nl.w8mr.parsek.parse
import nl.w8mr.parsek.sepByAllowEmpty
import nl.w8mr.parsek.seq
import nl.w8mr.parsek.times
import nl.w8mr.parsek.zeroOrMore

data class ParserJafun(val symbolMap: SymbolMapManager = SymbolMapManager().apply { reset() }) {
    inline fun <reified R: Any> token() = nl.w8mr.parsek.token<ExpressionNode.Phase1Token, R>(R::class)

    val whitespace = token<ExpressionNode.Whitespace>()
    val ows = zeroOrMore(whitespace).asLiteral()

    val newline = token<ExpressionNode.Newline>()
    val wsnl = oneOrMore(whitespace or newline).asLiteral()
    val owsnl = zeroOrMore(whitespace or newline).asLiteral()

    val singleQoute = token<ExpressionNode.SingleQoute>()
    val doubleQoute = token<ExpressionNode.DoubleQoute>()
    val semiColon = token<ExpressionNode.SemiColon>().asLiteral()
    val colon = token<ExpressionNode.Colon>().asLiteral()
    val equals = token< ExpressionNode.Identifier>().filter { it.value == "=" }.asLiteral()
    val dot = token<ExpressionNode.Dot>().asLiteral()

    val integerLiteral_term = token<ExpressionNode.IntegerLiteral>()
    val booleanLiteral_term = token<ExpressionNode.BooleanLiteral>()
    val identifier = token<ExpressionNode.Identifier>()

    val commaTerm = (token<ExpressionNode.Comma>() and owsnl).asLiteral()
    val lParenTerm = (token<ExpressionNode.LeftParen>() and owsnl).asLiteral()
    val rParenTerm = (token<ExpressionNode.RightParen>() and ows).asLiteral()

    val charLiteral_term = seq(singleQoute, token<ExpressionNode.CharLiteral>(), singleQoute) { s, c, _ -> c}

    val complexIdentifier: Parser<ExpressionNode.Phase1Token, List<TypeSymbol>> =
        combi {
            fun CombinatorDSL<ExpressionNode.Phase1Token, List<TypeSymbol>>.handleSubIndentifiers(current: TypeSymbol): List<TypeSymbol> =
                when (current) {
                    is JFClass, is JFVariableSymbol, is JFPackage, is JFField -> {
                        val parentContext = when (current) {
                            is JFVariableSymbol -> current.type
                            is JFField -> current.type
                            else -> current
                        }
                        when (val result = (dot and identifier).bindAsResult()) {
                            is Success -> when (val children = symbolMap.find(parentContext, result.value.value)) {
                                else -> children.flatMap { child ->
                                    when (current) {
                                        is JFField -> when (child) {
                                            is JFMethod -> listOf(JFFieldMethod(current, child))
                                            else -> TODO("Should be method")
                                        }
                                        is JFPackage -> when (child) {
                                            is JFClass -> handleSubIndentifiers(child)
                                            is JFPackage -> handleSubIndentifiers(child)
                                            else -> TODO("Shoudl be class or package")
                                        }
                                        is JFClass -> when (child) {
                                            is JFField -> handleSubIndentifiers(child)
                                            else -> TODO("Should be field")
                                        }
                                        is JFVariableSymbol -> when (child) {
                                            is JFMethod -> listOf(JFVariableMethod(current, child))
                                            else -> TODO("Should be method")
                                        }
                                        else -> TODO("Handle else")
                                    }
                                }
                            }
                            is Failure ->
                                when (current) {
                                    is JFClass ->
                                        symbolMap.find(current).toList().filterIsInstance<Type.JFConstructor>()
                                    //TODO: Check for invoke methods
                                    else -> listOf(current)
                                }
                        }
                    }
                    is JFMethod -> listOf(current)
                    is OperandType<*> -> listOf(current)
                    else -> TODO("handle else")
                }

            val id = identifier.bind()
            val currents = symbolMap.find(null, id.value)
            currents.flatMap { handleSubIndentifiers(it) }
        }

    val whenArrow = identifier.filter { it.value == "->" }.asLiteral()

    val expressionUntilNewline = prattParser()
    val expressionUntilArrow = prattParser(stopTerm = whenArrow)
    val expressionUntilRightParen = prattParser(stopTerm = rParenTerm)
    val expressionUntilComma = prattParser(stopTerm = commaTerm)

    val stringLiteral = token<ExpressionNode.StringLiteral>()
    val simpleStringExpression = seq(token<ExpressionNode.Dollar>(), token<ExpressionNode.Identifier>()) { d, i ->  expressions.parse(listOf(i)).firstOrNull() as? ExpressionNode.Variable ?: error("Variable not found") }
    val complexStringExpression = seq(token<ExpressionNode.Dollar>(), token<ExpressionNode.CurlyBlock>()) { d, e -> ExpressionNode.ExpressionList( expressions.parse(e.tokens.drop(1).dropLast(1))).simplify() as ExpressionNode.Phase2Expression}

    // TODO: Escape characters
    val lineStringContent = zeroOrMore(stringLiteral or simpleStringExpression or complexStringExpression)
    val stringLiteral_term = seq(doubleQoute, lineStringContent, doubleQoute) { q1, c, q2 ->
        when (c.size) {
            0 -> ExpressionNode.StringLiteral("") as ExpressionNode.Phase2Expression
            1 -> c[0] as? ExpressionNode.StringLiteral ?: ExpressionNode.StringTemplate(c)
            else -> ExpressionNode.StringTemplate(c)
        }
    }
    // TODO: Multiline string


    val betweenParentheses = lParenTerm and expressionUntilNewline and rParenTerm

    private val expressions =
        zeroOrMore(owsnl and expressionUntilNewline)

    val curlyBlock = token<ExpressionNode.CurlyBlock>().map {
        symbolMap.override(it.symbolMap) {
            ExpressionNode.ExpressionList(expressions.parse(it.tokens.drop(1).dropLast(1)) )
        }
    }

    val valTerm = token<ExpressionNode.Keyword>().filter { it.value == "val" }.asLiteral() and wsnl
    val initValAssignment =
        combi {
            -valTerm
            val identifier = identifier.bind()
            -owsnl
            -equals
            -owsnl
            val expression = expressionUntilNewline.bind()
            ExpressionNode.ValAssignment(symbolMap.newVariableSymbol(identifier.value, expression.type(), false), expression)
        }

    val varTerm = token<ExpressionNode.Keyword>().filter { it.value == "var" }.asLiteral() and wsnl
    val initVarAssignment =
        combi {
            -varTerm
            val identifier = identifier.bind()
            -owsnl
            -equals
            -owsnl
            val expression = expressionUntilNewline.bind()
            ExpressionNode.VarAssignment(symbolMap.newVariableSymbol(identifier.value, expression.type(), true), expression)
        }

    val varAssignment =
        combi {
            val identifier = identifier.bind()
            -owsnl
            -equals
            -owsnl
            val expression = expressionUntilNewline.bind()

            val variableSymbol =
                symbolMap.findSingle(identifier.value) as? JFVariableSymbol
                    ?: throw IllegalStateException("Variable ${identifier.value} not defined")
            if (!variableSymbol.mutable) {
                throw IllegalStateException("Variable ${identifier.value} is not mutable")
            }
            ExpressionNode.VarAssignment(variableSymbol, expression)
        }

    val elseInWhen = token<ExpressionNode.Identifier>().filter { it.value == "else" }.map { ExpressionNode.BooleanLiteral(true) } and owsnl
    val whenTerm = token<ExpressionNode.Identifier>().filter { it.value == "when" }.asLiteral() and owsnl
    val matches = owsnl and
        zeroOrMore((elseInWhen or expressionUntilArrow) and whenArrow and owsnl and expressionUntilNewline and wsnl)

    val whenExpression =
        combi {
            -whenTerm
            val (subject, matches) =
                symbolMap.local {
                    val subject = optional(lParenTerm and expressionUntilRightParen and rParenTerm).bind()
                    val block = token<ExpressionNode.CurlyBlock>().bind()
                    val matches = matches.parse(block.tokens.drop(1).dropLast(1))

                    subject to matches
                }
            ExpressionNode.When(subject, matches)
        }

    val whileTerm = token<ExpressionNode.Identifier>().filter { it.value == "while" }.asLiteral() and owsnl
    val whileExpression =
        combi {
            -whileTerm
            val (condition, expressions) =
                symbolMap.local {
                    -lParenTerm
                    val condition = expressionUntilRightParen.bind()
                    -rParenTerm
                    -owsnl
                    val expressions = curlyBlock.bind()
                    condition to expressions
                }
            ExpressionNode.While(condition, expressions)
        }

    val funTerm = token<ExpressionNode.Keyword>().filter { it.value == "fun" }.asLiteral() and wsnl
    val function =
        combi {
            -funTerm
            val name = identifier.bind()
            -owsnl
            -lParenTerm
            -owsnl
            val parameters = (identifier and owsnl and colon and owsnl and complexIdentifier sepByAllowEmpty commaTerm).bind()
            -rParenTerm
            val returnType = optional(colon and owsnl and identifier).bind()
            -owsnl

            val (symbol, block) = token<ExpressionNode.CurlyBlock>().map {
                symbolMap.override(it.symbolMap) {
                    val arguments = parameters.map { (identifier, type) ->
                        symbolMap.newVariableSymbol(
                            identifier.value,
                            type.singleOrNull() as? OperandType<*> ?: TODO("Handle complex type"),
                            false,
                            )
                    }
                    val symbol =
                        JFMethod(
                            arguments,
                            JFClass("Script"),
                            name.value,
                            returnType?.value?.let { symbolMap.findSingleOrNull(it) as? OperandType<*> } ?: OperandType.Unit,
                            static = true,
                            operator = name.operator,
                            associativity = PREFIX,
                        )
                    symbolMap.add(name.value, symbol)


                    val block = ExpressionNode.ExpressionList(expressions.parse(it.tokens.drop(1).dropLast(1)) )

                    symbol to block
                }
            }.bind()

            val symbolWithReturnType = symbol.copy(rtn = block.expressions .lastOrNull()?.type() ?: OperandType.Unit)
            symbolMap.add(name.value, symbolWithReturnType)

            ExpressionNode.Function(symbolWithReturnType, block.expressions)
        }

    fun prattParser(
        stopTerm: Parser<ExpressionNode.Phase1Token, *> = newline or semiColon,
        minPrecedence: Int = 0,
    ): Parser<ExpressionNode.Phase1Token, ExpressionNode.Phase2Expression> =
        // TODO: Cache parsers
        combi {
            var current =
                (
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
                        curlyBlock,
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
                        current =
                            when (val rhs = (owsnl and methodRhs(current.value, minPrecedence) and ows).bindAsResult()) {
                                is Failure -> break
                                is Success -> rhs
                            }
                    }
                }
            }
            current.bind()
        }

    fun methodLhs(minPrecedence: Int): Parser<ExpressionNode.Phase1Token, ExpressionNode.Phase2Expression> =
        // TODO: Cache parsers
        combi {
            val symbols = complexIdentifier.bind()
            symbols.mapNotNull { symbol ->
                (combi {
                    when (symbol) {
                        is JFVariableSymbol ->
                            ExpressionNode.Variable(symbol)

                        is Type.JFConstructor -> {
                            val arguments = methodArguments(symbol)
                            constructorInvocation(symbol, arguments)
                        }
                        is JFMethod -> {
                            val arguments =
                                when (symbol.associativity) {
                                    PREFIX -> methodArguments(symbol, minPrecedence)
                                    else -> fail("Method does not have the right associativity")
                                }
                            methodInvocation(symbol, arguments)
                        }

                        is JFFieldMethod -> {
                            val arguments =
                                when (symbol.method.associativity) {
                                    PREFIX -> methodArguments(symbol.method, minPrecedence)
                                    else -> fail("Method does not have the right associativity")
                                }
                            methodInvocation(symbol.method, symbol.field, arguments)
                        }

                        is JFVariableMethod -> {
                            val arguments =
                                when (symbol.method.associativity) {
                                    PREFIX -> methodArguments(symbol.method, minPrecedence)
                                    else -> fail("Method does not have the right associativity")
                                }
                            methodInvocation(symbol.method, symbol.variable, arguments)
                        } // TODO: remove duplication
                        else -> fail("Method or variable not found")
                    }
                }.bindAsResult() as? Success)?.value
            }.singleOrNull() ?: fail("No single method found")
        }

    fun methodRhs(
        lhsExpression: ExpressionNode.Phase2Expression,
        minPrecedence: Int,
    ): Parser<ExpressionNode.Phase1Token, ExpressionNode.Phase2Expression> =
        // TODO: Cache parsers
        combi {
            val identifier = (identifier and owsnl).bind()
            val symbols = symbolMap.find(lhsExpression.type(), identifier.value)
            var okMark = mark()
            val result =
                symbols.filterIsInstance<JFMethod>().mapNotNull { symbol ->
                    val mark = mark()
                    val arguments =
                        when (symbol.associativity) {
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
                }.singleOrNull() ?: fail("No single method ($identifier) not found")
            reset(okMark)
            result
        }

    private fun ExpressionNode.Phase2Expression?.asList() =
        when (this) {
            null -> emptyList()
            else -> listOf(this)
        }

    private fun CombinatorDSL<ExpressionNode.Phase1Token, ExpressionNode.Phase2Expression>.methodArguments(
        method: Type.JFConstructor,
        lhsExpression: ExpressionNode.Phase2Expression? = null,
    ): List<ExpressionNode.Phase2Expression> {
        val count = method.parameters.size - (if (lhsExpression == null) 0 else 1)
        return methodArguments(count, 10, lhsExpression)
    }
    private fun CombinatorDSL<ExpressionNode.Phase1Token, ExpressionNode.Phase2Expression>.methodArguments(
        method: JFMethod,
        minPrecedence: Int,
        lhsExpression: ExpressionNode.Phase2Expression? = null,
    ): List<ExpressionNode.Phase2Expression> {
        val count = method.parameters.size - (if (lhsExpression == null) 0 else 1)
        val newPrecedence =
            when {
                method.precedence > minPrecedence -> method.precedence - if (method.associativity == INFIXR) 1 else 0
                else -> 0
            }
        return methodArguments(count, newPrecedence, lhsExpression)
    }

    private fun CombinatorDSL<ExpressionNode.Phase1Token, ExpressionNode.Phase2Expression>.methodArguments(
        count: Int,
        newPrecedence: Int,
        lhsExpression: ExpressionNode.Phase2Expression?
    ): List<ExpressionNode.Phase2Expression> {
        val rhsArguments =
            (
                    ows and
                            oneOf(
                                lParenTerm and
                                        combi {
                                            when (count) {
                                                0 -> emptyList()
                                                1 -> listOf(expressionUntilComma.bind())
                                                else ->
                                                    listOf(
                                                        expressionUntilComma.bind(),
                                                    ) + (commaTerm and expressionUntilComma).times(count - 1).bind()
                                            }
                                        } and rParenTerm,
                                prattParser(minPrecedence = newPrecedence).times(count),
                            )
                    ).bind()
        return lhsExpression.asList() + rhsArguments
    }

    private fun constructorInvocation(
        constructor: Type.JFConstructor,
        arguments: List<ExpressionNode.Phase2Expression>,
    ): ExpressionNode.Phase2Expression {
            //(method.rtn as? JFClass)?.let { symbolMap.addClassToSymbolMap(it,it.path) }
            return ExpressionNode.Constructor(constructor, arguments)
        }

    private fun methodInvocation(
        method: JFMethod,
        arguments: List<ExpressionNode.Phase2Expression>,
    ): ExpressionNode.Phase2Expression =
        when {
            method.static -> {
                (method.rtn as? JFClass)?.let { symbolMap.addClassToSymbolMap(it,it.path) }
                ExpressionNode.Invocation(method, null, arguments)
            }
            else -> error("Method is not static")
        }

    private fun methodInvocation(
        method: JFMethod,
        field: Type.InvocationTarget,
        arguments: List<ExpressionNode.Phase2Expression>,
    ): ExpressionNode.Phase2Expression =
        when {
            !method.static -> ExpressionNode.Invocation(method, field, arguments)
            else -> error("Method is static")
        }

    fun parse(input: List<ExpressionNode.Phase1Token>): Pair<List<ExpressionNode.Phase2Expression>?, Parser.Result<List<ExpressionNode.Phase2Expression>>> {
        val source = ListSource(input)
        return expressions.parseTree(source)
    }
}
