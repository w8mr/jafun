package nl.w8mr.jafun

import jafun.compiler.Associativity
import jafun.compiler.*
import nl.w8mr.jafun.Token.*
import nl.w8mr.parsek.*
import nl.w8mr.parsek.Parser
import java.lang.IllegalArgumentException

object Parser {
    private fun invocation(methodIdentifier: ASTNode.MethodIdentifier, arguments: List<ASTNode.Expression> ): ASTNode.Expression {
        return when {
            methodIdentifier.field?.signature == "this" -> ASTNode.FieldInvocation(methodIdentifier.method, methodIdentifier.field, arguments)
            methodIdentifier.field != null -> ASTNode.StaticFieldInvocation(methodIdentifier.method, methodIdentifier.field, arguments)
            else -> ASTNode.StaticInvocation(methodIdentifier.method, arguments)
        }
    }

    private fun invocation(methodIdentifier: ASTNode.MethodIdentifier, expression: ASTNode.Expression ) =
        invocation(methodIdentifier,
            when (expression) {
                is ASTNode.ExpressionList -> expression.arguments
                else -> listOf(expression)
            })


    private fun isMethodIdentifier(result: Parser.Result<List<Identifier>>, associativity: Associativity, precedence: Int): Parser.Result<ASTNode.MethodIdentifier> {
        return when (result) {
            is Parser.Success<List<Identifier>> -> {
                val method = currentSymbolMap.find(result.value.map(Identifier::value).joinToString(".").replace('/','∕'))
                method?.let {
                    if (it is JFMethod) {
                        if (it.associativity == associativity && it.precedence == precedence) {
                            if (it.static) {
                                return Parser.Success(ASTNode.MethodIdentifier(it, null))
                            } else if (it.parent is JFField) {
                                return Parser.Success(ASTNode.MethodIdentifier(it, it.parent))
                            } else {
                                return Parser.Success(ASTNode.MethodIdentifier(it, ThisType))

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

    private fun assignment(identifier: ASTNode.Expression, expression: ASTNode.Expression): ASTNode.ValAssignment {
        if (identifier !is ASTNode.Variable) throw IllegalArgumentException()
        val variableSymbol = JFVariableSymbol(identifier.variableSymbol.name, expression.type())
        currentSymbolMap.add(identifier.variableSymbol.name, variableSymbol)
        return ASTNode.ValAssignment(variableSymbol, expression)
    }

    private fun newVariable(identifier: Identifier): ASTNode.Variable {
        val variableSymbol = JFVariableSymbol(identifier.value, type = UnknownType)
        currentSymbolMap.add(identifier.value, variableSymbol)
        return ASTNode.Variable(variableSymbol)
    }

    private fun newParameterDef(identifier: Identifier, type: List<Identifier>): JFVariableSymbol{
        val variableSymbol = JFVariableSymbol(identifier.value, type = currentSymbolMap.find(type.last().value)?:UnknownType) //TODO: handle complex types
        currentSymbolMap.add(identifier.value, variableSymbol)

        return variableSymbol
    }

    private fun newFunction(identifier: Identifier, parameters: List<JFVariableSymbol>, block: List<ASTNode.Expression> ): ASTNode.Function {
        println("PARAMETERS: $parameters")
        popSymbolMap(Unit)
        val symbol = JFMethod(
            parameters,
            JFClass("HelloWorld"),
            identifier.value,
            VoidType,
            static = true,
            associativity = if (parameters.isEmpty()) Associativity.SOLO else Associativity.PREFIX
        )
        currentSymbolMap.add(identifier.value, symbol)
        return ASTNode.Function(symbol, block)
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

    private val assignmentTerm = iLiteral(Assignment::class)

    private val stringLiteral_term = literal(StringLiteral::class) map { ASTNode.StringLiteral(it.value) }
    private val integerLiteral_term = literal(IntegerLiteral::class) map { ASTNode.IntegerLiteral(it.value) }

    private val complexIdentifier = identifierTerm sepBy dotTerm
    private val variableIdentifier : Parser<ASTNode.Variable> = complexIdentifier.mapResult(::isVariableIdentifier)
    private val arguments = (lParenTerm prefixLiteral (ref(::expression) sepByAllowEmpty literal(Comma::class)) postfixLiteral rParenTerm).map(ASTNode::ExpressionList)
    private val nullaryMethod = complexIdentifier.mapResult { isMethodIdentifier(it, Associativity.SOLO, 10) }.map { invocation(it, emptyList()) }
    //TODO: Check if expression and complexExpression can be combined.  Maybe use longest match? Or at least if it is used correctly now.
    private val expression : Parser<ASTNode.Expression> = oneOf(stringLiteral_term, integerLiteral_term, variableIdentifier, arguments, ref(::function), ref(::operations), nullaryMethod)
    private val complexExpression : Parser<ASTNode.Expression> = oneOf(arguments, ref(::function), ref(::operations), nullaryMethod, stringLiteral_term, integerLiteral_term, variableIdentifier)

    private val initVal = (valTerm prefixLiteral identifierTerm postfixLiteral assignmentTerm).map(::newVariable)

    private fun methodIdentifier(associativity: Associativity = Associativity.PREFIX, precedence: Int = 10): Parser<ASTNode.MethodIdentifier> = complexIdentifier.mapResult { isMethodIdentifier(it, associativity, precedence) }

    private val operations = OparatorTable.create(expression) {
        //TODO: add based on methods

        postfix(40, methodIdentifier(Associativity.POSTFIX, 40).unary { ident -> { expr -> invocation(ident, expr) } })
        infixr(40, methodIdentifier(Associativity.INFIXR, 40).binary { ident -> { expr1, expr2 -> invocation(ident, ASTNode.ExpressionList(listOf(expr1, expr2))) } })
        infixl(30, methodIdentifier(Associativity.INFIXL, 30).binary { ident -> { expr1, expr2 -> invocation(ident, ASTNode.ExpressionList(listOf(expr1, expr2))) } })
        infixl(20, methodIdentifier(Associativity.INFIXL, 20).binary { ident -> { expr1, expr2 -> invocation(ident, ASTNode.ExpressionList(listOf(expr1, expr2))) } })
        prefix(10, methodIdentifier().unary { ident -> { expr -> invocation(ident, expr) } })

        prefix(0, initVal.unary { ident -> { expr -> assignment(ident, expr) } } )
    }
    private val curlBlock = seq(lCurlTerm.map(::pushSymbolMap), ref(::block), rCurlTerm.map(::popSymbolMap)) { _, b, _ -> b }

    private val parameter = seq(identifierTerm, colonTerm, complexIdentifier) { i, _, t -> newParameterDef(i, t) }
    private val function : Parser<ASTNode.Expression> = seq(funTerm.map(::pushSymbolMap), identifierTerm, lParenTerm, parameter sepByAllowEmpty commaTerm, rParenTerm, curlBlock) { _, i, _, p, _, b -> newFunction(i, p, b) }
    private val block = seq(zeroOrMore(oneOf(newlineTerm, semicolonTerm)), complexExpression sepByAllowEmpty oneOf(newlineTerm, eof(), semicolonTerm), zeroOrMore(oneOf(newlineTerm, eof(), semicolonTerm))) { _, i, _ -> i}
    private val parser = block

    var currentSymbolMap: SymbolMap = LocalSymbolMap(IdentifierCache)

    fun pushSymbolMap(unit: Unit) {
        currentSymbolMap = LocalSymbolMap(currentSymbolMap)
    }

    private fun popSymbolMap(unit: Unit) {
        val oldSymbolMap = currentSymbolMap
        currentSymbolMap = if (oldSymbolMap is LocalSymbolMap) oldSymbolMap.parent else throw IllegalStateException("already at top of symbol map stack")
    }

    fun parse(tokens: List<Token>): List<ASTNode.Expression> {
            return parser.parse(tokens)
    }

}

