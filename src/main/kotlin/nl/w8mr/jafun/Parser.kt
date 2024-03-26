package nl.w8mr.jafun

import jafun.compiler.Associativity
import jafun.compiler.IdentifierCache
import jafun.compiler.JFClass
import jafun.compiler.JFField
import jafun.compiler.JFMethod
import jafun.compiler.JFVariableSymbol
import jafun.compiler.LocalSymbolMap
import jafun.compiler.SymbolMap
import jafun.compiler.ThisType
import jafun.compiler.UnknownType
import jafun.compiler.VoidType
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
import nl.w8mr.parsek.OparatorTable
import nl.w8mr.parsek.Parser
import nl.w8mr.parsek.binary
import nl.w8mr.parsek.eof
import nl.w8mr.parsek.iLiteral
import nl.w8mr.parsek.literal
import nl.w8mr.parsek.map
import nl.w8mr.parsek.mapResult
import nl.w8mr.parsek.oneOf
import nl.w8mr.parsek.postfixLiteral
import nl.w8mr.parsek.prefixLiteral
import nl.w8mr.parsek.ref
import nl.w8mr.parsek.sepBy
import nl.w8mr.parsek.sepByAllowEmpty
import nl.w8mr.parsek.seq
import nl.w8mr.parsek.unary
import nl.w8mr.parsek.zeroOrMore

object Parser {
    private fun invocation(
        methodIdentifier: ASTNode.MethodIdentifier,
        arguments: List<ASTNode.Expression>,
    ): ASTNode.Expression {
        return when {
            methodIdentifier.field?.signature == "this" ->
                ASTNode.FieldInvocation(
                    methodIdentifier.method,
                    methodIdentifier.field,
                    arguments,
                )
            methodIdentifier.field != null -> ASTNode.StaticFieldInvocation(methodIdentifier.method, methodIdentifier.field, arguments)
            else -> ASTNode.StaticInvocation(methodIdentifier.method, arguments)
        }
    }

    private fun invocation(
        methodIdentifier: ASTNode.MethodIdentifier,
        expression: ASTNode.Expression,
    ) = invocation(
        methodIdentifier,
        when (expression) {
            is ASTNode.ExpressionList -> expression.expressions
            else -> listOf(expression)
        },
    )

    private fun isMethodIdentifier(
        result: Parser.Result<List<Identifier>>,
        associativity: Associativity,
        precedence: Int,
    ): Parser.Result<ASTNode.MethodIdentifier> {
        return when (result) {
            is Parser.Success<List<Identifier>> -> {
                val method = currentSymbolMap.find(result.value.map(Identifier::value).joinToString(".").replace('/', '∕'))
                method?.let {
                    if (it is JFMethod) {
                        if (it.associativity == associativity && it.precedence == precedence) {
                            return if (it.static) {
                                Parser.Success(ASTNode.MethodIdentifier(it, null))
                            } else if (it.parent is JFField) {
                                Parser.Success(ASTNode.MethodIdentifier(it, it.parent))
                            } else {
                                Parser.Success(ASTNode.MethodIdentifier(it, ThisType))
                            }
                        }
                    }
                }
                Parser.Error("no method identifier")
            }

            is Parser.Error<*> -> Parser.Error("no identifier")
        }
    }

    private fun isVariableIdentifier(result: Parser.Result<List<Identifier>>): Parser.Result<ASTNode.Variable> {
        return when (result) {
            is Parser.Success<List<Identifier>> -> {
                val name = result.value.last().value
                when (val symbol = currentSymbolMap.find(name)) {
                    is JFVariableSymbol -> Parser.Success(ASTNode.Variable(symbol), emptyList())
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
        // if (identifier !is ASTNode.Variable) throw IllegalArgumentException()
        val variableSymbol = JFVariableSymbol(identifier.value, expression.type(), currentSymbolMap)
        currentSymbolMap.add(identifier.value, variableSymbol)
        return ASTNode.ValAssignment(variableSymbol, expression)
    }

    private fun newParameterDef(
        identifier: Identifier,
        type: List<Identifier>,
    ): JFVariableSymbol {
        val variableSymbol =
            JFVariableSymbol(
                identifier.value,
                type = currentSymbolMap.find(type.last().value) ?: UnknownType,
                currentSymbolMap,
            ) // TODO: handle complex types
        currentSymbolMap.add(identifier.value, variableSymbol)

        return variableSymbol
    }

    private fun newFunction(
        identifier: Identifier,
        parameters: List<JFVariableSymbol>,
        block: ASTNode.ExpressionList,
    ): ASTNode.Function {
        popSymbolMap(Unit)
        val symbol =
            JFMethod(
                parameters,
                JFClass("HelloWorld"),
                identifier.value,
                block.expressions.lastOrNull()?.type() ?: VoidType,
                static = true,
                associativity = if (parameters.isEmpty()) Associativity.SOLO else Associativity.PREFIX,
            )
        currentSymbolMap.add(identifier.value, symbol)
        return ASTNode.Function(symbol, block.expressions)
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

    private val assignmentTerm = iLiteral(Token.Assignment::class)

    private val stringLiteral_term = literal(Token.StringLiteral::class) map { ASTNode.StringLiteral(it.value) }
    private val integerLiteral_term = literal(Token.IntegerLiteral::class) map { ASTNode.IntegerLiteral(it.value) }

    private val complexIdentifier = identifierTerm sepBy dotTerm
    private val variableIdentifier: Parser<ASTNode.Variable> = complexIdentifier.mapResult(::isVariableIdentifier)
    private val arguments =
        (lParenTerm prefixLiteral (ref(::expression) sepByAllowEmpty literal(Comma::class)) postfixLiteral rParenTerm).map(
            ASTNode::ExpressionList,
        )
    private val nullaryMethod =
        complexIdentifier.mapResult {
            isMethodIdentifier(it, Associativity.SOLO, 10)
        }.map { invocation(it, emptyList()) }

    // TODO: Check if expression and complexExpression can be combined.  Maybe use longest match? Or at least if it is used correctly now.
    private val expression: Parser<ASTNode.Expression> =
        oneOf(
            stringLiteral_term,
            integerLiteral_term,
            variableIdentifier,
            arguments,
            ref(::curlBlock),
            ref(::function),
            ref(::operations),
            nullaryMethod,
        )
    private val complexExpression: Parser<ASTNode.Expression> =
        oneOf(
            arguments,
            ref(::curlBlock),
            ref(::function),
            ref(::operations),
            nullaryMethod,
            stringLiteral_term,
            integerLiteral_term,
            variableIdentifier,
        )

    private val initVal = (valTerm prefixLiteral identifierTerm postfixLiteral assignmentTerm)

    private fun methodIdentifier(
        associativity: Associativity = Associativity.PREFIX,
        precedence: Int = 10,
    ): Parser<ASTNode.MethodIdentifier> =
        complexIdentifier.mapResult {
            isMethodIdentifier(it, associativity, precedence)
        }

    private val operations =
        OparatorTable.create(expression) {
            // TODO: add based on methods

            postfix(
                40,
                methodIdentifier(Associativity.POSTFIX, 40)
                    .unary { ident -> { expr -> invocation(ident, expr) } },
            )
            infixr(
                40,
                methodIdentifier(Associativity.INFIXR, 40)
                    .binary { ident -> { expr1, expr2 -> invocation(ident, ASTNode.ExpressionList(listOf(expr1, expr2))) } },
            )
            infixl(
                30,
                methodIdentifier(Associativity.INFIXL, 30)
                    .binary { ident -> { expr1, expr2 -> invocation(ident, ASTNode.ExpressionList(listOf(expr1, expr2))) } },
            )
            infixl(
                20,
                methodIdentifier(Associativity.INFIXL, 20)
                    .binary { ident -> { expr1, expr2 -> invocation(ident, ASTNode.ExpressionList(listOf(expr1, expr2))) } },
            )
            prefix(
                10,
                methodIdentifier()
                    .unary { ident -> { expr -> invocation(ident, expr) } },
            )
            prefix(
                0,
                initVal
                    .unary { ident -> { expr -> assignment(ident, expr) } },
            )
        }
    private val curlBlock =
        seq(lCurlTerm.map(::pushSymbolMap), ref(::block), rCurlTerm.map(::popSymbolMap)) { _, b, _ -> ASTNode.ExpressionList(b) }

    private val parameter = seq(identifierTerm, colonTerm, complexIdentifier) { i, _, t -> newParameterDef(i, t) }
    private val function: Parser<ASTNode.Expression> =
        seq(
            funTerm.map(::pushSymbolMap),
            identifierTerm,
            lParenTerm,
            parameter sepByAllowEmpty commaTerm,
            rParenTerm,
            curlBlock,
        ) { _, i, _, p, _, b -> newFunction(i, p, b) }
    private val block =
        seq(
            zeroOrMore(oneOf(newlineTerm, semicolonTerm)),
            complexExpression sepByAllowEmpty oneOf(newlineTerm, eof(), semicolonTerm),
            zeroOrMore(oneOf(newlineTerm, eof(), semicolonTerm)),
        ) { _, i, _ -> i }
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
}
