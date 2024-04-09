package nl.w8mr.jafun

import jafun.compiler.Associativity
import jafun.compiler.IdentifierCache
import jafun.compiler.LocalSymbolMap
import jafun.compiler.SymbolMap
import nl.w8mr.jafun.Token.Colon
import nl.w8mr.jafun.Token.Comma
import nl.w8mr.jafun.Token.Dot
import nl.w8mr.jafun.Token.Fun
import nl.w8mr.jafun.Token.Identifier
import nl.w8mr.jafun.Token.LCurl
import nl.w8mr.jafun.Token.LParen
import nl.w8mr.jafun.Token.Newline
import nl.w8mr.jafun.Token.RCurl
import nl.w8mr.jafun.Token.RParen
import nl.w8mr.jafun.Token.Semicolon
import nl.w8mr.jafun.Token.Val
import nl.w8mr.jafun.Token.When
import nl.w8mr.parsek.Context
import nl.w8mr.parsek.Parser
import nl.w8mr.parsek.eof
import nl.w8mr.parsek.filter
import nl.w8mr.parsek.iLiteral
import nl.w8mr.parsek.literal
import nl.w8mr.parsek.map
import nl.w8mr.parsek.oneOf
import nl.w8mr.parsek.optional
import nl.w8mr.parsek.postfixLiteral
import nl.w8mr.parsek.prefixLiteral
import nl.w8mr.parsek.ref
import nl.w8mr.parsek.sepBy
import nl.w8mr.parsek.sepByAllowEmpty
import nl.w8mr.parsek.seq
import nl.w8mr.parsek.zeroOrMore

object Parser {
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
        popSymbolMap(Unit)
        val symbol =
            IR.JFMethod(
                functionDef.parameters,
                IR.JFClass("Script"),
                functionDef.identifier.value,
                block.expressions.lastOrNull()?.type() ?: IR.Unit,
                static = true,
                associativity = if (functionDef.parameters.isEmpty()) Associativity.SOLO else Associativity.PREFIX,
            )
        currentSymbolMap.add(functionDef.identifier.value, symbol)
        return ASTNode.Function(symbol, block.expressions)
    }

    data class FunctionDef(val identifier: Identifier, val parameters: List<IR.JFVariableSymbol>, val returnType: Identifier?)

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
                associativity = if (parameters.isEmpty()) Associativity.SOLO else Associativity.PREFIX,
            )
        currentSymbolMap.add(identifier.value, symbol)
        return FunctionDef(identifier, parameters, returnType)
    }

    fun <R> Parser<R>.debug(f: (R) -> String) =
        this.map {
            println(f.invoke(it))
            it
        }

    private val identifierTerm = literal(Identifier::class)
    private val newlineTerm = iLiteral(Newline::class)
    private val dotTerm = iLiteral(Dot::class)
    private val colonTerm = iLiteral(Colon::class)
    private val commaTerm = iLiteral(Comma::class)
    private val semicolonTerm = iLiteral(Semicolon::class)
    private val lParenTerm = iLiteral(LParen::class)
    private val rParenTerm = iLiteral(RParen::class)
    private val lCurlTerm = iLiteral(LCurl::class)
    private val rCurlTerm = iLiteral(RCurl::class)
    private val valTerm = iLiteral(Val::class)
    private val funTerm = iLiteral(Fun::class)
    private val whenTerm = iLiteral(When::class)

    private val assignmentTerm = iLiteral(Token.Assignment::class)

    private val stringLiteral_term = literal(Token.StringLiteral::class) map { ASTNode.StringLiteral(it.value) }
    private val integerLiteral_term = (literal(Token.IntegerLiteral::class) map { ASTNode.IntegerLiteral(it.value) })
    private val booleanLiteral_term =
        oneOf(
            literal(Token.True::class).map { ASTNode.BooleanLiteral(true) },
            literal(Token.False::class).map { ASTNode.BooleanLiteral(false) },
        )

    private val complexIdentifier = identifierTerm sepBy dotTerm
    private val initVal = (valTerm prefixLiteral identifierTerm postfixLiteral assignmentTerm)

    private val curlBlock =
        seq(lCurlTerm.map(::pushSymbolMap), ref(::block), rCurlTerm.map(::popSymbolMap)) { _, b, _ -> ASTNode.ExpressionList(b, true) }

    private val parameter = seq(identifierTerm, colonTerm, complexIdentifier) { i, _, t -> newParameterDef(i, t) }

    private val functionDefinition =
        seq(
            funTerm.map(::pushSymbolMap) prefixLiteral
                identifierTerm postfixLiteral lParenTerm,
            parameter sepByAllowEmpty commaTerm postfixLiteral rParenTerm,
            optional(colonTerm prefixLiteral identifierTerm),
        ) { i, p, t -> defineFunction(i, p, t) }

    private val function: Parser<ASTNode.Expression> =
        seq(
            functionDefinition,
            curlBlock,
        ) { s, b -> newFunction(s, b) }

    private val pratt = PrattParser()

    val expression =
        seq(
            zeroOrMore(oneOf(newlineTerm, semicolonTerm)),
            pratt,
            zeroOrMore(oneOf(newlineTerm, eof(), semicolonTerm)),
        ) { _, i, _ -> i }

    private val whenArrow = identifierTerm.filter { it.value == "->" }.map {}
    private val whenMatch =
        seq(expression postfixLiteral whenArrow, expression)
    private val whenExpression: Parser<ASTNode.Expression> =
        seq(
            whenTerm.map(::pushSymbolMap),
            optional(lParenTerm prefixLiteral expression postfixLiteral rParenTerm),
            lCurlTerm prefixLiteral zeroOrMore(whenMatch) postfixLiteral rCurlTerm,
        ) { _, input, matches -> ASTNode.When(input, matches) }

    private val block =
        zeroOrMore(
            expression,
        )

    private val betweenParentheses = lParenTerm prefixLiteral expression postfixLiteral rParenTerm

    private val parser = block

    private var currentSymbolMap: SymbolMap = LocalSymbolMap(IdentifierCache.reset())

    private fun pushSymbolMap(unit: Unit) {
        currentSymbolMap = LocalSymbolMap(currentSymbolMap)
    }

    private fun popSymbolMap(unit: Unit) {
        val oldSymbolMap = currentSymbolMap
        currentSymbolMap =
            if (oldSymbolMap is LocalSymbolMap) {
                oldSymbolMap.parent
            } else {
                throw IllegalStateException("already at top of symbol map stack")
            }
    }

    fun parse(tokens: List<Token>): List<ASTNode.Expression> {
        return parser.parse(tokens)
    }

    fun Context.peekSkipNewLine(): Token? {
        var lhsToken = if (hasNext()) peek() as Token else null
        while (lhsToken == Newline) {
            index += 1
            lhsToken = if (hasNext()) peek() as Token else null
        }
        return lhsToken
    }

    class PrattParser(val minPrecedence: Int = 0) : Parser<ASTNode.Expression>() {
        interface PrefixHandler {
            fun handle(
                context: Context,
                lhsExpression: ASTNode.Expression?,
                minPrecedence: Int,
            ): Result<ASTNode.Expression>
        }

        open class ParserHandler(val parser: Parser<ASTNode.Expression>) : PrefixHandler {
            override fun handle(
                context: Context,
                lhsExpression: ASTNode.Expression?,
                minPrecedence: Int,
            ): Result<ASTNode.Expression> = parser.apply(context)
        }

        object LiteralHandler : ParserHandler(oneOf(integerLiteral_term, stringLiteral_term, booleanLiteral_term))

        object ParenthesesHandler : ParserHandler(betweenParentheses)

        object BlockHandler : ParserHandler(curlBlock.map { ASTNode.ExpressionList(it.expressions, true) })

        object ValAssignmentHandler : ParserHandler(seq(initVal, pratt, ::assignment))

        object FunctionDefHandler : ParserHandler(function)

        object WhenHandler : ParserHandler(whenExpression)

        object IdentifierHandler : PrefixHandler {
            override fun handle(
                context: Context,
                lhsExpression: ASTNode.Expression?,
                minPrecedence: Int,
            ): Result<ASTNode.Expression> =
                when (val identifier = complexIdentifier.apply(context)) {
                    is Success ->
                        when (
                            val methodVariable =
                                currentSymbolMap.find(identifier.value.joinToString(".") { it.value }.replace('/', '∕'))
                        ) {
                            is IR.JFMethod ->
                                method(
                                    methodVariable,
                                    lhsExpression,
                                    context,
                                    identifier.value,
                                    minPrecedence,
                                )

                            is IR.JFVariableSymbol ->
                                context.success(ASTNode.Variable(methodVariable), 0)
                            else -> context.error("Identifier expected")
                        }

                    else -> context.error("Identifier expected")
                }

            private fun method(
                methodVariable: IR.JFMethod,
                lhsExpression: ASTNode.Expression?,
                context: Context,
                identifier: List<Identifier>,
                minPrecedence: Int,
            ): Result<ASTNode.Expression> {
                val rightAssociate = if (methodVariable.associativity in listOf(Associativity.INFIXR, Associativity.PREFIX)) 1 else 0

                val arguments = lhsExpression?.let { (mutableListOf(it)) } ?: mutableListOf()
                return when (methodVariable.associativity) {
                    Associativity.POSTFIX -> return context.success(methodInvocation(methodVariable, arguments), 0)
                    Associativity.SOLO -> return context.success(methodInvocation(methodVariable, arguments), 0)
                    else -> {
                        val cur = context.index
                        if (!identifier.last().operator) {
                            val argumentsParser =
                                lParenTerm prefixLiteral (
                                    PrattParser(
                                        methodVariable.precedence - rightAssociate,
                                    ) sepByAllowEmpty commaTerm
                                ) postfixLiteral rParenTerm
                            val arguments = argumentsParser.apply(context)
                            if (arguments is Success) {
                                val list =
                                    lhsExpression?.let { listOf(it) }
                                        ?: emptyList<ASTNode.Expression>() + arguments.value
                                return context.success(methodInvocation(methodVariable, list), 0)
                            }
                        }
                        context.index = cur
                        if (methodVariable.precedence <= minPrecedence) {
                            return context.error("Lower precedence")
                        }

                        return when (
                            val rhs =
                                PrattParser(methodVariable.precedence - rightAssociate).apply(context)
                        ) {
                            is Success ->
                                context.success(
                                    methodInvocation(
                                        methodVariable,
                                        lhsExpression?.let { listOf(it, rhs.value) } ?: listOf(rhs.value),
                                    ),
                                    0,
                                )

                            is Error ->
                                lhsExpression?.let {
                                    context.index = cur
                                    context.success(it, 0)
                                } ?: context.error("LHS expected")
                        }
                    }
                }
            }

            private fun methodInvocation(
                method: IR.JFMethod,
                arguments: List<ASTNode.Expression>,
            ) = when {
                method.static -> ASTNode.Invocation(method, null, arguments)
                method.parent is IR.JFField -> ASTNode.Invocation(method, method.parent, arguments)
                else -> TODO()
            }
        }

        override fun apply(context: Context): Result<ASTNode.Expression> {
            // println("===> ${context.peek()} $minP")

            var lhsToken = context.peekSkipNewLine()
            if (lhsToken == null) return context.error("Expected token")
            val lhsHandler = findPrefixLiteralHandler(lhsToken)
            val lhs =
                lhsHandler?.let {
                    lhsHandler.handle(context, null, minPrecedence)
                } ?: context.error("No prefix / literal handler found")
            var current = lhs
            val stop = context.error<ASTNode.Expression>("break")

            while (current is Success) {
                val cur = context.index
                val rhs =
                    when (val rhsToken = context.tokenOrNull()) {
                        is RParen -> stop
                        is RCurl -> stop
                        is Comma -> stop
                        is Newline -> stop
                        null -> stop
                        else -> {
                            val rhsHandler = findInfixPostFixHandler(rhsToken)
                            rhsHandler?.let {
                                rhsHandler.handle(context, (current as Success).value, minPrecedence)
                            } ?: current
                        }
                    }
                if (rhs == current) {
                    context.index = cur
                    break
                }
                if (rhs is Error) {
                    context.index = cur
                    break
                }

                if (rhs == stop) {
                    context.index = cur
                    break
                }
                current = rhs
            }
            // println("<=== ${context.tokenOrNull()} $minP $current")

            return current
        }

        private fun Context.tokenOrNull() = if (hasNext()) peek() as Token else null

        private fun findPrefixLiteralHandler(token: Token): PrefixHandler? =
            when (token) {
                is Identifier -> IdentifierHandler
                is LParen -> ParenthesesHandler
                is LCurl -> BlockHandler
                is Token.IntegerLiteral -> LiteralHandler
                is Token.StringLiteral -> LiteralHandler
                is Token.True -> LiteralHandler
                is Token.False -> LiteralHandler
                is Val -> ValAssignmentHandler
                is Fun -> FunctionDefHandler
                is When -> WhenHandler
                else -> null
            }

        private fun findInfixPostFixHandler(token: Token): PrefixHandler? =
            when (token) {
                is Identifier -> IdentifierHandler
                is LParen -> ParenthesesHandler
                is LCurl -> BlockHandler
                is Token.IntegerLiteral -> LiteralHandler
                is Token.StringLiteral -> LiteralHandler
                is Token.True -> LiteralHandler
                is Token.False -> LiteralHandler
                is Val -> ValAssignmentHandler
                is When -> WhenHandler
                else -> null
            }
    }
}
