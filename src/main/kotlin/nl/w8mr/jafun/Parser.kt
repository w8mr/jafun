package nl.w8mr.jafun

import jafun.compiler.Associativity.INFIXL
import jafun.compiler.Associativity.INFIXR
import jafun.compiler.Associativity.POSTFIX
import jafun.compiler.Associativity.PREFIX
import jafun.compiler.Associativity.SOLO
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
import nl.w8mr.parsek.*
import nl.w8mr.parsek.Parser.Success
import kotlin.reflect.KClass

object ParserJafun {
    private fun isVariableIdentifier(result: Parser.Result<List<Identifier>>): Parser.Result<ASTNode.Variable> {
        return when (result) {
            is Parser.Success<List<Identifier>> -> {
                val name = result.value.last().value
                when (val symbol = currentSymbolMap.find(name)) {
                    is IR.JFVariableSymbol -> Parser.Success(ASTNode.Variable(symbol), emptyList())
                    else -> Parser.Failure("no variable identifier")
                }
            }
            is Parser.Failure<*> -> Parser.Failure("no identifier")
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
                operator = identifier.operator,
                associativity = if (parameters.isEmpty()) SOLO else PREFIX,
            )
        currentSymbolMap.add(identifier.value, symbol)
        return FunctionDef(identifier, parameters, returnType)
    }

    fun <R> Parser<Token, R>.debug(f: (R) -> String) =
        this.map {
            println(f.invoke(it))
            it
        }

    fun <R: Any> token(kClass: KClass<R>) = object : Parser<Token, R> {
        override fun applyImpl(source: ParserSource<Token>): Parser.Result<R> {
            val mark = source.mark()
            val match = source.next()
            return when (kClass.isInstance(match)) {
                false -> {
                    source.reset(mark)
                    failure("token is not instance of ${kClass.simpleName}")
                }

                true -> {
                    source.release(mark)
                    success(match as R)
                }
            }
        }
    }

    fun <R: Any> literal(kClass: KClass<R>) = object : LiteralParser<Token> {
        override fun applyImpl(source: ParserSource<Token>): Parser.Result<Unit> {
            val mark = source.mark()
            return when (kClass.isInstance(source.next())) {
                false -> {
                    source.reset(mark)
                    failure("token is not instance of ${kClass.simpleName}")
                }

                true -> {
                    source.release(mark)
                    success(Unit)
                }
            }
        }
    }

    private val identifierTerm = token(Identifier::class)
    private val newlineTerm = literal(Newline::class)
    private val dotTerm = literal(Dot::class)
    private val colonTerm = literal(Colon::class)
    private val commaTerm = literal(Comma::class)
    private val semicolonTerm = literal(Semicolon::class)
    private val lParenTerm = literal(LParen::class)
    private val rParenTerm = literal(RParen::class)
    private val lCurlTerm = literal(LCurl::class)
    private val rCurlTerm = literal(RCurl::class)
    private val valTerm = literal(Val::class)
    private val funTerm = literal(Fun::class)
    private val whenTerm = literal(When::class)

    private val assignmentTerm = literal(Token.Assignment::class)

    private val stringLiteral_term = token(Token.StringLiteral::class) map { ASTNode.StringLiteral(it.value) }
    private val integerLiteral_term = (token(Token.IntegerLiteral::class) map { ASTNode.IntegerLiteral(it.value) })
    private val booleanLiteral_term =
        oneOf(
            token(Token.True::class).map { ASTNode.BooleanLiteral(true) },
            token(Token.False::class).map { ASTNode.BooleanLiteral(false) },
        )

    val complexIdentifier = identifierTerm sepBy dotTerm
    private val variableIdentifier: Parser<Token, ASTNode.Variable> = complexIdentifier.mapResult(func = ::isVariableIdentifier)

    private val initVal = (valTerm and identifierTerm and assignmentTerm)
    val blockOpen = (lCurlTerm) and any(newlineTerm)
    val blockClose = any(newlineTerm) and rCurlTerm

    private val curlBlock = blockOpen.effect(::pushSymbolMap) and
            ref(::block) and
            (blockClose.effect(::popSymbolMap)) map
            { ASTNode.ExpressionList(it, true) }

    private val parameter = identifierTerm and colonTerm and complexIdentifier map (::newParameterDef)

    private val functionDefinition =
        seq(
            funTerm.effect(::pushSymbolMap) and identifierTerm and lParenTerm,
            parameter sepByAllowEmpty commaTerm and rParenTerm,
            optional(colonTerm and identifierTerm),
            ::defineFunction)

    private val function: Parser<Token, ASTNode.Expression> =
        functionDefinition and curlBlock map (::newFunction)

    private val pratt = PrattParser()

    val expression =
        PrattParser(stopTerm = oneOf(newlineTerm, semicolonTerm))

    private val whenArrow = identifierTerm.filter { it.value == "->" }.asLiteral()
    private val whenMatch =
        seq(
            PrattParser(stopTerm = whenArrow) and whenArrow,
            PrattParser(stopTerm = oneOf(newlineTerm, semicolonTerm, rCurlTerm)),
        ) and newlineTerm

    private val whenSubject = optional(lParenTerm and PrattParser(stopTerm = rParenTerm) and rParenTerm)
    val whenMatches = blockOpen and zeroOrMore(whenMatch) and blockClose.effect(::popSymbolMap)
    private val whenExpression: Parser<Token, ASTNode.Expression> =
        whenTerm.effect(::pushSymbolMap) and
                whenSubject and
                whenMatches map
                (ASTNode::When)

    private val block: Parser<Token, List<ASTNode.Expression>> =
        pratt sepByAllowEmpty zeroOrMore(newlineTerm)

    private val betweenParentheses = lParenTerm and expression and rParenTerm

    private val initValAssignment: Parser<Token, ASTNode.Expression> = initVal and expression map(::assignment)

    private val parser = block

    private var currentSymbolMap: SymbolMap = LocalSymbolMap(IdentifierCache.reset())

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

    fun parse(tokens: List<Token>): List<ASTNode.Expression> {
        return parser.parse(tokens)
    }

    class PrattParser(val stopTerm: Parser<Token, *> = newlineTerm, val minPrecedence: Int = 0) : Parser<Token, ASTNode.Expression> {
        override fun applyImpl(context: ParserSource<Token>): Parser.Result<ASTNode.Expression> {
             println("===> $minPrecedence ${context.index}")

            var current =
                oneOf(
                    variableIdentifier,
                    soloMethod,
                    methodWithParameterArguments(null),
                    methodWithPrecedence(null, minPrecedence),
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
                val mark = context.mark()
                when (stopTerm.apply(context)) {
                    is Success -> {
                        context.reset(mark)
                        break
                    }

                    is Parser.Failure -> {
                        context.reset(mark)
                        val rhs =
                            oneOf(
                                postfixMethod(current.value),
                                methodWithParameterArguments(current.value),
                                // methodWithPrecedence(current.value, minPrecedence),
                                MethodParser(current.value, minPrecedence),
                            ).apply(context)
                        if (rhs is Parser.Failure) break
                        current = rhs
                    }
                }
            }
            // println("<=== ${context.tokenOrNull()} $minP $current")
            return current
        }
    }

    fun postfixMethod(lhsExpression: ASTNode.Expression?) =
        complexIdentifier
            .map { identifier -> currentSymbolMap.find(identifier.joinToString(".") { it.value }) }
            .filter { it is IR.JFMethod }
            .map { it as IR.JFMethod }
            .filter { it.associativity == POSTFIX }
            .map { methodInvocation(it, lhsExpression?.let { (listOf(it)) } ?: emptyList()) }

    val soloMethod =
        complexIdentifier
            .map { identifier -> currentSymbolMap.find(identifier.joinToString(".") { it.value }) }
            .filter { it is IR.JFMethod }
            .map { it as IR.JFMethod }
            .filter { it.associativity == SOLO }
            .map { methodInvocation(it, emptyList()) }

    fun methodWithParameterArguments(lhsExpression: ASTNode.Expression?): Parser<Token, ASTNode.Expression> {
        val lhsArguments = lhsExpression?.let { (listOf(it)) } ?: emptyList()
        return seq(
            complexIdentifier
                .map { identifier -> currentSymbolMap.find(identifier.joinToString(".") { it.value }) }
                .filter { it is IR.JFMethod }
                .map { it as IR.JFMethod }
                .filter { it.associativity in listOf(PREFIX, INFIXL, INFIXR) }
                .filter { !it.operator },
            lParenTerm and (
                PrattParser(
                    stopTerm = oneOf(commaTerm, rParenTerm),
                ) sepByAllowEmpty commaTerm
            ) and rParenTerm,
        ) { m, a -> methodInvocation(m, lhsArguments + a) }
    }

    fun methodWithPrecedence(
        lhsExpression: ASTNode.Expression?,
        minPrecedence: Int,
    ): Parser<Token, ASTNode.Expression> {
        val lhsArguments = lhsExpression?.let { (listOf(it)) } ?: emptyList()
        var newPrecedence = 0
        return seq(
            complexIdentifier
                .map { identifier -> currentSymbolMap.find(identifier.joinToString(".") { it.value }) }
                .filter { it is IR.JFMethod }
                .map { it as IR.JFMethod }
                .filter { it.precedence > minPrecedence }
                .map {
                    newPrecedence = it.precedence - if (it.associativity == INFIXR) 1 else 0
                    it
                },
            PrattParser(minPrecedence = newPrecedence),
        ) { m, a -> methodInvocation(m, lhsArguments + listOf(a)) }
    }

    class MethodParser(val lhsExpression: ASTNode.Expression?, val minPrecedence: Int) : Parser<Token, ASTNode.Expression> {
        override fun applyImpl(context: ParserSource<Token>): Parser.Result<ASTNode.Expression> =
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
                                minPrecedence,
                            )
                        else -> Parser.Failure("Method expected")
                    }
                else -> Parser.Failure("Identifier expected")
            }

        private fun method(
            methodVariable: IR.JFMethod,
            lhsExpression: ASTNode.Expression?,
            context: ParserSource<Token>,
            minPrecedence: Int,
        ): Parser.Result<ASTNode.Expression> {
            val lhsArguments = lhsExpression?.let { (listOf(it)) } ?: emptyList()
            return when (methodVariable.associativity) {
                POSTFIX -> TODO()
                SOLO -> TODO()
                else -> {
                    val newPrecedence =
                        if (methodVariable.associativity == INFIXL) {
                            methodVariable.precedence
                        } else {
                            methodVariable.precedence - 1
                        }
                    if (methodVariable.precedence <= minPrecedence) {
                        return Parser.Failure("Lower precedence")
                    }

                    return (
                        PrattParser(minPrecedence = newPrecedence).map { rhs ->
                            methodInvocation(methodVariable, lhsArguments + listOf(rhs))
                        }
                    ).apply(context)
                }
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
