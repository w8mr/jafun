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
import nl.w8mr.parsek.filter
import nl.w8mr.parsek.iLiteral
import nl.w8mr.parsek.literal
import nl.w8mr.parsek.map
import nl.w8mr.parsek.mapResult
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
    private fun isVariableIdentifier(result: Parser.Result<List<Identifier>>): Parser.Result<ASTNode.Variable> {
        return when (result) {
            is Parser.Success<List<Identifier>> -> {
                val name = result.value.last().value
                when (val symbol = currentSymbolMap.find(name)) {
                    is IR.JFVariableSymbol -> Parser.Success(ASTNode.Variable(symbol), emptyList())
                    else -> Parser.Error("no variable identifier")
                }
            }
            is Parser.Error<*> -> Parser.Error("no identifier")
        }
    }

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
    private val variableIdentifier: Parser<ASTNode.Variable> = complexIdentifier.mapResult(::isVariableIdentifier)

    private val initVal = (valTerm prefixLiteral identifierTerm postfixLiteral assignmentTerm)
    private val curlBlock =
        (lCurlTerm.map(::pushSymbolMap) prefixLiteral ref(::block) postfixLiteral rCurlTerm.map(::popSymbolMap))
            .map { ASTNode.ExpressionList(it, true) }

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
        PrattParser(stopTerm = oneOf(newlineTerm, semicolonTerm))

    private val whenArrow = identifierTerm.filter { it.value == "->" }.map {}
    private val whenMatch =
        seq(
            PrattParser(stopTerm = whenArrow) postfixLiteral whenArrow,
            PrattParser(stopTerm = oneOf(newlineTerm, semicolonTerm, rCurlTerm)),
        )
    private val whenExpression: Parser<ASTNode.Expression> =
        seq(
            whenTerm.map(::pushSymbolMap),
            optional(lParenTerm prefixLiteral PrattParser(stopTerm = rParenTerm) postfixLiteral rParenTerm),
            lCurlTerm prefixLiteral zeroOrMore(whenMatch) postfixLiteral
                seq(
                    zeroOrMore(newlineTerm),
                    rCurlTerm,
                ).map { Unit },
        ) { _, subject, matches -> ASTNode.When(subject, matches) }

    private val block =
        zeroOrMore(
            pratt postfixLiteral zeroOrMore(newlineTerm).map { Unit },
        )

    private val betweenParentheses = lParenTerm prefixLiteral expression postfixLiteral rParenTerm

    private val initValAssignment: Parser<ASTNode.Expression> = seq(initVal, expression, ::assignment)

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

    class PrattParser(val stopTerm: Parser<*> = newlineTerm, val minPrecedence: Int = 0) : Parser<ASTNode.Expression>() {
        override fun apply(context: Context): Result<ASTNode.Expression> {
            // println("===> ${context.peek()} $minP")

            context.peekSkipNewLine() // TODO: check which parser isn't able to eat newlines
            var current =
                oneOf(
                    variableIdentifier,
                    MethodParser(null, minPrecedence),
                    betweenParentheses,
                    curlBlock,
                    integerLiteral_term,
                    stringLiteral_term,
                    booleanLiteral_term,
                    initValAssignment,
                    function,
                    whenExpression,
                ).apply(context)

            while (current is Success) {
                val cur = context.index
                when (stopTerm.apply(context)) {
                    is Success -> {
                        context.index = cur
                        break
                    }

                    is Error -> {
                        context.index = cur
                        val rhs = MethodParser(current.value, minPrecedence).apply(context)
                        if (rhs is Error) {
                            context.index = cur // TODO: check which parser is not resetting cursor
                            break
                        }
                        current = rhs
                    }
                }
            }
            // println("<=== ${context.tokenOrNull()} $minP $current")
            return current
        }
    }

    class MethodParser(val lhsExpression: ASTNode.Expression?, val minPrecedence: Int) : Parser<ASTNode.Expression>() {
        override fun apply(context: Context): Result<ASTNode.Expression> =
            when (val identifier = complexIdentifier.apply(context)) {
                is Success ->
                    when (
                        val methodVariable =
                            currentSymbolMap.find(identifier.value.joinToString(".") { it.value })
                    ) {
                        is IR.JFMethod ->
                            method(
                                methodVariable,
                                lhsExpression,
                                context,
                                identifier.value,
                                minPrecedence,
                            )
                        else -> context.error("Method expected")
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
            val lhsArguments = lhsExpression?.let { (mutableListOf(it)) } ?: mutableListOf()
            return when (methodVariable.associativity) {
                Associativity.POSTFIX -> return context.success(methodInvocation(methodVariable, lhsArguments), 0)
                Associativity.SOLO -> return context.success(methodInvocation(methodVariable, emptyList()), 0)
                else -> {
                    val newPrecedence =
                        if (methodVariable.associativity == Associativity.INFIXL) {
                            methodVariable.precedence
                        } else {
                            methodVariable.precedence - 1
                        }
                    if (!identifier.last().operator) {
                        val argumentsParser =
                            lParenTerm prefixLiteral (
                                PrattParser(
                                    stopTerm = oneOf(commaTerm, rParenTerm),
                                    minPrecedence = newPrecedence,
                                ) sepByAllowEmpty commaTerm
                            ) postfixLiteral rParenTerm
                        val invocation = (argumentsParser.map { methodInvocation(methodVariable, lhsArguments + it) }).apply(context)
                        if (invocation is Success) {
                            return invocation
                        }
                    }
                    if (methodVariable.precedence <= minPrecedence) {
                        return context.error("Lower precedence")
                    }

                    return (
                        PrattParser(minPrecedence = newPrecedence).map { rhs ->
                            methodInvocation(methodVariable, lhsArguments + listOf(rhs))
                        }
                    ).apply(context)
                }
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
}
