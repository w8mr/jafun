package nl.w8mr.jafun

import nl.w8mr.jafun.Type.JFClass
import nl.w8mr.jafun.Type.JFField
import nl.w8mr.jafun.Type.JFFieldMethod
import nl.w8mr.jafun.Type.JFMethod
import nl.w8mr.jafun.Type.JFPackage
import nl.w8mr.jafun.Type.JFVariableMethod
import nl.w8mr.jafun.Type.JFVariableSymbol
import nl.w8mr.jafun.compiler.Associativity
import nl.w8mr.jafun.compiler.Associativity.INFIXL
import nl.w8mr.jafun.compiler.Associativity.INFIXR
import nl.w8mr.jafun.compiler.Associativity.POSTFIX
import nl.w8mr.jafun.compiler.Associativity.PREFIX
import nl.w8mr.jafun.compiler.ExpressionNode
import nl.w8mr.jafun.compiler.IdentifierCache
import nl.w8mr.jafun.compiler.LocalSymbolMap
import nl.w8mr.jafun.compiler.SymbolMap
import nl.w8mr.jafun.compiler.SymbolMapManager
import nl.w8mr.jafun.symboltable.FQDN
import nl.w8mr.jafun.symboltable.LocalScope
import nl.w8mr.jafun.symboltable.MethodDef
import nl.w8mr.jafun.symboltable.Parameter
import nl.w8mr.jafun.symboltable.SymbolTable
import nl.w8mr.jafun.symboltable.VariableDef
import nl.w8mr.parsek.CombinatorDSL
import nl.w8mr.parsek.ListContext
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
import nl.w8mr.parsek.text.anyChar
import nl.w8mr.parsek.times
import nl.w8mr.parsek.zeroOrMore
import nl.w8mr.parsek.invoke

data class ParserJafun(val symbolMapManager: SymbolMapManager = SymbolMapManager().apply { reset() }, val symbolTable: SymbolTable = SymbolTable()) {
    inline fun <reified R : ExpressionNode.Phase1Token> token() = nl.w8mr.parsek.token<ExpressionNode.Phase1Token, R>(R::class)

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
            fun CombinatorDSL<ExpressionNode.Phase1Token>.handleSubIndentifiers(current: TypeSymbol): List<TypeSymbol> =
                when (current) {
                    is JFClass, is JFVariableSymbol, is JFPackage, is JFField -> {
                        val parentContext = when (current) {
                            is JFVariableSymbol -> current.type
                            is JFField -> current.type
                            else -> current
                        }
                        when (val result = (dot and identifier).bindAsResult()) {
                            is Success -> when (val children = symbolMapManager.find(parentContext, result.value.value)) {
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
                                        symbolMapManager.find(current).toList().filterIsInstance<Type.JFConstructor>()
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
            val symbolTableMethods = symbolTable.lookupMethods(id.value)?.values?.map { it.toJFMethod() } ?: emptyList()
            val symbolTableImportMethods = if (symbolTableMethods.isEmpty()) {
                symbolTable.findMethodsByShortNameViaImports(id.value).map { it.toJFMethod() }
            } else {
                emptyList()
            }
            val allStMethods = symbolTableMethods + symbolTableImportMethods
            val stMethodNames = allStMethods.map { it.name }.toSet()
            val oldSymbols = symbolMapManager.find(null, id.value)
            val currents = allStMethods +
                oldSymbols.filterNot {
                    (it as? JFMethod)?.name in stMethodNames
                }
            currents.flatMap { handleSubIndentifiers(it) }
        }

    val whenArrow = identifier.filter { it.value == "->" }.asLiteral()

    val expressionUntilNewline = prattParser()
    val expressionUntilArrow = prattParser(stopTerm = whenArrow)
    val expressionUntilRightParen = prattParser(stopTerm = rParenTerm)
    val expressionUntilComma = prattParser(stopTerm = commaTerm)

    val stringLiteral = token<ExpressionNode.StringLiteral>()
    val dollar = token<ExpressionNode.Identifier>().filter { it.value == "\$" }
    val simpleStringExpression = seq(dollar, token<ExpressionNode.Identifier>()) { d, i ->  expressions(listOf(i)).firstOrNull() as? ExpressionNode.Variable ?: error("Variable not found") }
    val complexStringExpression = seq(dollar, token<ExpressionNode.CurlyBlock>()) { d, e -> ExpressionNode.ExpressionList( expressions(e.tokens.drop(1).dropLast(1))).simplify() as ExpressionNode.Phase2Expression}

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

    val curlyBlock = token<ExpressionNode.CurlyBlock>().map { block ->
        val saved = symbolTable.captureScope()
        (block.scopeCapture as? LocalScope)?.let { symbolTable.restoreScope(it) }
        try {
            symbolMapManager.override(block.symbolMap) {
                ExpressionNode.ExpressionList(expressions(block.tokens.drop(1).dropLast(1)))
            }
        } finally {
            symbolTable.restoreScope(saved)
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
            val uninitialisedSymbol =  symbolMapManager.find(null, identifier.value).singleOrNull() as? JFVariableSymbol ?: error("Variable not found")
            val updatedSymbol = uninitialisedSymbol.copy(type = expression.type(), initialized = true)
            symbolMapManager.replaceType(identifier.value, updatedSymbol)
            symbolTable.replaceVariable(identifier.value, VariableDef(identifier.value, expression.type(), mutable = false, initialized = true))
            ExpressionNode.ValAssignment(updatedSymbol, expression)
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
            val uninitialisedSymbol =  symbolMapManager.find(null, identifier.value).singleOrNull() as? JFVariableSymbol ?: error("Variable not found")
            val updatedSymbol = uninitialisedSymbol.copy(type = expression.type(), initialized = true).apply { symbolMapManager.replaceType(identifier.value, this) }
            ExpressionNode.VarAssignment(updatedSymbol, expression)
        }

    val varAssignment =
        combi {
            val identifier = identifier.bind()
            -owsnl
            -equals
            -owsnl
            val expression = expressionUntilNewline.bind()

            val variableSymbol =
                symbolMapManager.findSingle(identifier.value) as? JFVariableSymbol
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
            val (subject, subjectSymbolMap) = symbolMapManager.local {
                optional(lParenTerm and expressionUntilRightParen and rParenTerm).bind() to symbolMapManager.currentSymbolMap
            }
            val block = token<ExpressionNode.CurlyBlock>().bind()
            val symbolMap = block.symbolMap as? LocalSymbolMap ?: error("Invalid symbol map")
            val newSymbolMap = symbolMap.copy(parent = subjectSymbolMap)
            val matches = symbolMapManager.override(newSymbolMap) {
                matches(block.tokens.drop(1).dropLast(1))

            }

            ExpressionNode.When(subject, matches)
        }

    val irExpression =
        combi {
            val irToken = identifier.filter { it.value == "ir" }.bind()
            -owsnl
            val params = optional(
                seq(lParenTerm, owsnl, identifier sepByAllowEmpty commaTerm, owsnl, rParenTerm) { _, _, ids, _, _ -> ids }
            ).bind() ?: emptyList()
            -owsnl
            val block = token<ExpressionNode.CurlyBlock>().bind()
            val opTokens = block.tokens.drop(1).dropLast(1)
            val opName = opTokens.filterIsInstance<ExpressionNode.Identifier>().firstOrNull()?.value
                ?: error("Expected IR operation in ir block, got: ${opTokens.map { it::class.simpleName }}")
            val paramExprs = params.map { id ->
                val sym = symbolMapManager.findSingleOrNull(id.value) as? JFVariableSymbol
                    ?: error("Variable '${id.value}' not found for ir parameter")
                ExpressionNode.Variable(sym)
            }
            val operation = when (opName) {
                "mul" -> ExpressionNode.Mul(paramExprs[0], paramExprs[1])
                "add" -> ExpressionNode.Add(paramExprs[0], paramExprs[1])
                "sub" -> ExpressionNode.Sub(paramExprs[0], paramExprs[1])
                "div" -> ExpressionNode.Div(paramExprs[0], paramExprs[1])
                "cmpeq" -> ExpressionNode.CmpEq(paramExprs[0], paramExprs[1])
                "cmplt" -> ExpressionNode.CmpLt(paramExprs[0], paramExprs[1])
                "cmple" -> ExpressionNode.CmpLe(paramExprs[0], paramExprs[1])
                "cmpgt" -> ExpressionNode.CmpGt(paramExprs[0], paramExprs[1])
                "cmpge" -> ExpressionNode.CmpGe(paramExprs[0], paramExprs[1])
                else -> error("Unknown IR operation: $opName")
            }
            ExpressionNode.IRBlock(paramExprs, operation)
        }

    val invokeExpression =
        combi {
            val keyword = identifier.filter { it.value == "invokevirtual" || it.value == "invokestatic" }.bind()
            -owsnl
            -lParenTerm
            -owsnl
            val args = (expressionUntilComma sepByAllowEmpty commaTerm).bind()
            -owsnl
            -rParenTerm
            if (args.size < 3) error("${keyword.value} requires at least 3 arguments (className, methodName, signature)")
            val className = (args[0] as? ExpressionNode.StringLiteral)?.value
                ?: error("First argument to ${keyword.value} must be a string literal (className)")
            val methodName = (args[1] as? ExpressionNode.StringLiteral)?.value
                ?: error("Second argument to ${keyword.value} must be a string literal (methodName)")
            val signature = (args[2] as? ExpressionNode.StringLiteral)?.value
                ?: error("Third argument to ${keyword.value} must be a string literal (signature)")
            val callArgs = args.drop(3)
            if (keyword.value == "invokestatic")
                ExpressionNode.InvokeStatic(className, methodName, signature, callArgs)
            else
                ExpressionNode.InvokeVirtual(className, methodName, signature, callArgs)
        }

    val whileTerm = token<ExpressionNode.Identifier>().filter { it.value == "while" }.asLiteral() and owsnl
    val whileExpression =
        combi {
            -whileTerm
            val (condition, expressions) =
                symbolMapManager.local {
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
    val inlineTerm = token<ExpressionNode.Keyword>().filter { it.value == "inline" }

    val function =
        combi {
            val isInline = optional(inlineTerm and wsnl).bind() != null
            -funTerm
            val name = identifier.bind()
            -owsnl
            -lParenTerm
            -owsnl
            val parameters = (identifier and owsnl and colon and owsnl and complexIdentifier sepByAllowEmpty commaTerm).bind()
            -rParenTerm
            val returnType = optional(colon and owsnl and identifier).bind()
            -owsnl

            val (symbol, block) = token<ExpressionNode.CurlyBlock>().map { cb ->
                val savedScope = symbolTable.captureScope()
                (cb.scopeCapture as? LocalScope)?.let { symbolTable.restoreScope(it) }
                try {
                    symbolMapManager.override(cb.symbolMap) {
                        val arguments = parameters.map { (identifier, type) ->
                        val variableSymbol = symbolMapManager.replaceVariableSymbol(
                            identifier.value,
                            type.singleOrNull() as? OperandType<*> ?: TODO("Handle complex type"),
                            false,
                            scopeId = symbolTable.currentScopeId,
                            )
                        symbolTable.replaceVariable(
                            identifier.value,
                            VariableDef(identifier.value, type.singleOrNull() as? OperandType<*> ?: TODO("Handle complex type")),
                        )
                        variableSymbol
                    }
                            val symbol =
                        JFMethod(
                            arguments,
                            JFClass("Script"),
                            name.value,
                            returnType?.value?.let { resolveTypeName(it) } ?: OperandType.Unit,
                            static = true,
                            operator = name.operator,
                            associativity = when {
                                name.operator && arguments.size == 2 -> Associativity.INFIXL
                                name.operator && arguments.size == 1 -> Associativity.PREFIX
                                else -> PREFIX
                            },
                            inline = isInline,
                        )
                    symbolMapManager.replaceType(name.value, symbol)
                    if (name.operator && symbol.parameters.isNotEmpty()) {
                        IdentifierCache.replaceType(symbol.parameters[0].type, name.value, symbol)
                    }
                    registerFunctionInSymbolTable(symbol)

                    val block = ExpressionNode.ExpressionList(expressions(cb.tokens.drop(1).dropLast(1)))

                    symbol to block
                }
                } finally {
                    symbolTable.restoreScope(savedScope)
                }
            }.bind()

            val symbolWithReturnType = symbol.copy(rtn = block.expressions .lastOrNull()?.type() ?: OperandType.Unit)
            symbolMapManager.replaceType(name.value, symbolWithReturnType)
            symbolTable.setInferredReturnType(FQDN("Script.${name.value}"), symbolWithReturnType.rtn)

            ExpressionNode.Function(symbolWithReturnType, block.expressions, inline = isInline)
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
                        irExpression,
                        invokeExpression,
                        betweenParentheses,
                        methodLhs(minPrecedence),
                        curlyBlock,
                    ) and ows
                ).bindAsResult()

            while (current is Success) {
                val stop = ((combi {
                    when (stopTerm.bindAsResult()) {
                        is Success -> fail("stop term found")
                        is Failure -> fail("stop term not found")
                    }
                }).bindAsResult() as? Failure)?.error == "stop term found"

                when (stop) {
                    true -> break
                    false -> {
                        current = when (val rhs = (owsnl and oneOf(
                            methodRhs(current.value, minPrecedence),
                            fieldRhs(current.value),
                        ) and ows).bindAsResult()) {
                            is Success -> rhs
                            is Failure -> break
                        }
                    }
                }
            }
            current.bind()
        }

    fun methodLhs(minPrecedence: Int): Parser<ExpressionNode.Phase1Token, ExpressionNode.Phase2Expression> =
        // TODO: Cache parsers
        combi  {
            val symbols = complexIdentifier.bind()
            symbols.mapNotNull { symbol ->
                val combi: Parser<ExpressionNode.Phase1Token, ExpressionNode.Phase2Expression> = combi {
                    when (symbol) {
                        is JFVariableSymbol ->
                            if (symbol.initialized)
                                ExpressionNode.Variable(symbol)
                            else {
                                ExpressionNode.Variable(
                                    symbolMapManager.currentSymbolMap.parent
                                        ?.find(null, symbol.path)
                                        ?.firstOrNull() as? JFVariableSymbol
                                        ?: error("Variable not found")
                                )
                            }


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
                            methodInvocation(symbol, arguments) ?: fail("Method not found")
                        }

                        is JFFieldMethod -> {
                            val arguments =
                                when (symbol.method.associativity) {
                                    PREFIX -> methodArguments(symbol.method, minPrecedence)
                                    else -> fail("Method does not have the right associativity")
                                }
                            methodInvocation(symbol.method, symbol.field, arguments)
                        }

                        is JFVariableMethod -> oneOf(
                            combi { vcFieldAccess(symbol) ?: fail("Not a value class getter") },
                            combi<ExpressionNode.Phase1Token, ExpressionNode.Phase2Expression> {
                                val arguments =
                                    when (symbol.method.associativity) {
                                        PREFIX -> methodArguments(symbol.method, minPrecedence)
                                        else -> fail("Method does not have the right associativity")
                                    }
                                methodInvocation(symbol.method, symbol.variable, arguments)
                            },
                            combi {
                                val arguments =
                                    when (symbol.method.associativity) {
                                        PREFIX -> methodArguments(
                                            symbol.method,
                                            minPrecedence,
                                            ExpressionNode.Variable(symbol.variable)
                                        )
                                        else -> fail("Method does not have the right associativity")
                                    }
                                methodInvocation(symbol.method, arguments) ?: fail("Method not found")
                            }
                        ).bind() // TODO: remove duplication
                        else -> fail("Method or variable not found")
                    }
                }
                (combi.bindAsResult<ExpressionNode.Phase2Expression>() as? Success)?.value
            }.groupBy { (it as? ExpressionNode.Invocation)?.arguments?.size ?: 0 }.maxByOrNull { it.key }?.value?.singleOrNull() ?: fail("No single method found")
        }

    fun methodRhs(
        lhsExpression: ExpressionNode.Phase2Expression,
        minPrecedence: Int,
    ): Parser<ExpressionNode.Phase1Token, ExpressionNode.Phase2Expression> =
        // TODO: Cache parsers
        combi {
            val identifier = (identifier and owsnl).bind()
            val lhsType = lhsExpression.type()
            val oldSymbols = symbolMapManager.find(lhsType, identifier.value)
                .ifEmpty { symbolMapManager.find(null, identifier.value) }
            val symbolTableMethods = if (oldSymbols.isEmpty()) {
                symbolTable.lookupMethods(identifier.value)?.values
                    ?.filter { it.parameters.isNotEmpty() && it.parameters[0].type == lhsType }
                    ?.map { it.toJFMethod() } ?: emptyList()
            } else {
                emptyList()
            }
            val symbols = oldSymbols + symbolTableMethods
            val result =
                symbols.filterIsInstance<JFMethod>().mapNotNull { symbol ->
                    (combi<ExpressionNode.Phase1Token, ExpressionNode.MethodInvocation?> {
                        val arguments =
                            when (symbol.associativity) {
                                POSTFIX -> lhsExpression.asList()
                                INFIXL, INFIXR -> {
                                    if (symbol.precedence <= minPrecedence) fail("Lower precedence")
                                    methodArguments(symbol, minPrecedence, lhsExpression)
                                }

                                PREFIX -> if (symbol.parameters.size == 2) {
                                    if (symbol.precedence <= minPrecedence) fail("Lower precedence")
                                    listOf(lhsExpression, prattParser(minPrecedence = symbol.precedence).bind())
                                } else {
                                    fail("Method (${identifier.value}) does not have the right associativity")
                                }

                                else -> fail("Method (${identifier.value}) does not have the right associativity")
                            }
                        if (arguments.map { it.type() } == symbol.parameters.map { it.type }) {
                            methodInvocation(symbol, arguments)
                        } else {
                            fail("no method match")
                        }
                    }.bindAsResult() as? Success)?.value
                }.singleOrNull() ?: fail("No single method ($identifier) not found")
            result
        }

    private fun fieldRhs(
        lhsExpression: ExpressionNode.Phase2Expression,
    ): Parser<ExpressionNode.Phase1Token, ExpressionNode.FieldAccess> = combi {
        val fieldName = (dot and owsnl and identifier).bind().value
        val lhsType = lhsExpression.type()
        val methods = symbolMapManager.find(lhsType, fieldName).filterIsInstance<JFMethod>()
        val getter = methods.singleOrNull { it.parameters.isEmpty() } ?: fail("No getter for $fieldName")
        val fieldIndex = symbolMapManager.find(lhsType).filterIsInstance<Type.JFConstructor>()
            .flatMap { it.parameters }.indexOfFirst { it.name == fieldName }
        ExpressionNode.FieldAccess(
            instance = lhsExpression,
            fieldName = fieldName,
            fieldIndex = if (fieldIndex >= 0) fieldIndex else 0,
            fieldType = getter.rtn,
            arguments = emptyList(),
        )
    }

    private fun vcFieldAccess(symbol: JFVariableMethod): ExpressionNode.FieldAccess? {
        val lhsType = symbol.variable.type
        if (lhsType is Type.JFClass && lhsType.kind == Type.ClassKind.VALUE_CLASS && symbol.method.parameters.isEmpty()) {
            val fields = symbolMapManager.find(lhsType).filterIsInstance<Type.JFConstructor>().flatMap { it.parameters }
            val fieldIndex = fields.indexOfFirst { it.name == symbol.method.name }
            return ExpressionNode.FieldAccess(
                instance = ExpressionNode.Variable(symbol.variable),
                fieldName = symbol.method.name,
                fieldIndex = if (fieldIndex >= 0) fieldIndex else 0,
                fieldType = symbol.method.rtn,
                arguments = emptyList(),
            )
        }
        return null
    }

    private fun ExpressionNode.Phase2Expression?.asList() =
        when (this) {
            null -> emptyList()
            else -> listOf(this)
        }

    private fun CombinatorDSL<ExpressionNode.Phase1Token>.methodArguments(
        method: Type.JFConstructor,
        lhsExpression: ExpressionNode.Phase2Expression? = null,
    ): List<ExpressionNode.Phase2Expression> {
        val count = method.parameters.size - (if (lhsExpression == null) 0 else 1)
        return methodArguments(count, 10, lhsExpression)
    }
    private fun CombinatorDSL<ExpressionNode.Phase1Token>.methodArguments(
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

    private fun CombinatorDSL<ExpressionNode.Phase1Token>.methodArguments(
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
                                                1 -> listOf(expressionUntilRightParen.bind())
                                                else ->
                                                    listOf(expressionUntilComma.bind()) + 
                                                            (commaTerm and expressionUntilComma).times((count - 2).coerceAtLeast(0)).bind() +
                                                            listOf((commaTerm and expressionUntilRightParen).bind())
                                                
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
    ): ExpressionNode.ConstructorInvocation {
            //(method.rtn as? JFClass)?.let { symbolMap.addClassToSymbolMap(it,it.path) }
            return ExpressionNode.ConstructorInvocation(constructor, constructor.parent as Type.JFClass, arguments)
        }

    private fun methodInvocation(
        method: JFMethod,
        arguments: List<ExpressionNode.Phase2Expression>,
    ): ExpressionNode.MethodInvocation? =
        when {
            method.static -> {
                (method.rtn as? JFClass)?.let { jfClass ->
                    if (jfClass.kind != Type.ClassKind.VALUE_CLASS) {
                        symbolMapManager.addClassToSymbolMap(jfClass, jfClass.path)
                    }
                }
                ExpressionNode.MethodInvocation(
                    methodName = method.name,
                    parentPath = method.parentPath,
                    parameters = method.parameters,
                    rtnLookup = {
                        val methodFqdn = if (method.parentPath.isNotEmpty())
                            FQDN("${method.parentPath}.${method.name}")
                        else
                            FQDN("Script.${method.name}")
                        val methodDef = symbolTable.lookupMethodById(methodFqdn)
                        methodDef?.let { symbolTable.actualReturnType(it) }
                            ?: symbolMapManager.findSingleOrNull(method.name)?.let { (it as? JFMethod)?.rtn }
                            ?: method.rtn
                    },
                    field = null,
                    arguments = arguments,
                )
            }
            else -> null
        }

    private fun methodInvocation(
        method: JFMethod,
        field: Type.InvocationTarget,
        arguments: List<ExpressionNode.Phase2Expression>,
    ): ExpressionNode.Phase2Expression =
        when {
            !method.static -> ExpressionNode.MethodInvocation(
                methodName = method.name,
                parentPath = method.parentPath,
                parameters = method.parameters,
                rtnLookup = {
                    val methodFqdn = if (method.parentPath.isNotEmpty())
                        FQDN("${method.parentPath}.${method.name}")
                    else
                        FQDN("Script.${method.name}")
                    val methodDef = symbolTable.lookupMethodById(methodFqdn)
                    methodDef?.let { symbolTable.actualReturnType(it) }
                        ?: symbolMapManager.findSingleOrNull(method.name)?.let { (it as? JFMethod)?.rtn }
                        ?: method.rtn
                },
                field = field,
                arguments = arguments,
            )
            else -> error("Method is static")
        }

    fun parse(input: List<ExpressionNode.Phase1Token>): Pair<List<ExpressionNode.Phase2Expression>?, Parser.Result<List<ExpressionNode.Phase2Expression>>> {
        val (mainTokens, functions) = structurePass(input)
        val mainParseResult = expressions.parse(ListContext(mainTokens))
        val mainExprs = mainParseResult.first ?: emptyList()
        val allExpressions = parseBodies(functions, mainExprs)
        return allExpressions to Parser.Success(emptyList())
    }

    // --- Two-phase parser support (Step 1) ---

    data class FunDescriptor(
        val name: String,
        val bodyTokens: List<ExpressionNode.Phase1Token>,
        val symbolMap: SymbolMap,
        val returnType: OperandType<*>,
        val inline: Boolean = false,
        val scopeCapture: Any? = null,
    )

    data class StructureResult(
        val mainTokens: List<ExpressionNode.Phase1Token>,
        val functions: List<FunDescriptor>,
    )

    fun structurePass(tokens: List<ExpressionNode.Phase1Token>): StructureResult {
        val mainTokens = mutableListOf<ExpressionNode.Phase1Token>()
        val functions = mutableListOf<FunDescriptor>()
        var i = 0

        while (i < tokens.size) {
            val token = tokens[i]
            if (token is ExpressionNode.Keyword && token.value == "value") {
                i++
                while (i < tokens.size && (tokens[i] !is ExpressionNode.Keyword || (tokens[i] as ExpressionNode.Keyword).value != "class")) i++
                if (i < tokens.size) i++ // skip "class"
                while (i < tokens.size && tokens[i] !is ExpressionNode.Identifier) i++
                if (i < tokens.size) i++ // skip class name
                while (i < tokens.size && tokens[i] !is ExpressionNode.LeftParen) i++
                if (i < tokens.size) i++ // skip "("
                var depth = 1
                while (i < tokens.size && depth > 0) {
                    when (tokens[i]) {
                        is ExpressionNode.LeftParen -> depth++
                        is ExpressionNode.RightParen -> depth--
                    }
                    i++
                }
            } else if (token is ExpressionNode.Keyword && token.value == "inline") {
                i++
                // skip whitespace before "fun"
                while (i < tokens.size && tokens[i] is ExpressionNode.Whitespace) i++
                if (i >= tokens.size || tokens[i] !is ExpressionNode.Keyword || (tokens[i] as ExpressionNode.Keyword).value != "fun") {
                    error("Expected 'fun' after 'inline'")
                }
                i++ // skip "fun"
                while (i < tokens.size && tokens[i] !is ExpressionNode.Identifier) i++
                if (i >= tokens.size) error("Expected function name")
                val name = (tokens[i] as ExpressionNode.Identifier).value
                i++

                while (i < tokens.size && tokens[i] !is ExpressionNode.LeftParen) i++
                if (i >= tokens.size) error("Expected '(' for function '$name'")
                val leftParenIdx = i
                i++

                while (i < tokens.size && tokens[i] !is ExpressionNode.RightParen) i++
                if (i >= tokens.size) error("Expected ')' for function '$name'")
                val rightParenIdx = i
                val parameters = parseParameters(tokens.subList(leftParenIdx + 1, rightParenIdx))

                i++
                val returnType = extractReturnType(tokens, i)

                while (i < tokens.size && tokens[i] !is ExpressionNode.CurlyBlock) i++
                if (i >= tokens.size) error("Expected body for function '$name'")
                val curlyBlock = tokens[i] as ExpressionNode.CurlyBlock

                val jfm = JFMethod(
                    parameters,
                    JFClass("Script"),
                    name,
                    returnType,
                    static = true,
                    operator = false,
                    associativity = PREFIX,
                    inline = true,
                )
                symbolMapManager.replaceType(name, jfm)
                registerFunctionInSymbolTable(jfm)

                val savedScope = symbolTable.captureScope()
                (curlyBlock.scopeCapture as? LocalScope)?.let { symbolTable.restoreScope(it) }
                try {
                    symbolMapManager.override(curlyBlock.symbolMap) {
                        structurePass(curlyBlock.tokens)
                    }
                } finally {
                    symbolTable.restoreScope(savedScope)
                }

                functions.add(FunDescriptor(name, curlyBlock.tokens.drop(1).dropLast(1), curlyBlock.symbolMap, returnType, inline = true, scopeCapture = curlyBlock.scopeCapture))
            } else if (token is ExpressionNode.Keyword && token.value == "fun") {
                i++
                while (i < tokens.size && tokens[i] !is ExpressionNode.Identifier) i++
                if (i >= tokens.size) error("Expected function name")
                val name = (tokens[i] as ExpressionNode.Identifier).value
                i++

                while (i < tokens.size && tokens[i] !is ExpressionNode.LeftParen) i++
                if (i >= tokens.size) error("Expected '(' for function '$name'")
                val leftParenIdx = i
                i++

                while (i < tokens.size && tokens[i] !is ExpressionNode.RightParen) i++
                if (i >= tokens.size) error("Expected ')' for function '$name'")
                val rightParenIdx = i
                val parameters = parseParameters(tokens.subList(leftParenIdx + 1, rightParenIdx))

                i++
                val returnType = extractReturnType(tokens, i)

                while (i < tokens.size && tokens[i] !is ExpressionNode.CurlyBlock) i++
                if (i >= tokens.size) error("Expected body for function '$name'")
                val curlyBlock = tokens[i] as ExpressionNode.CurlyBlock

                val jfm = JFMethod(
                    parameters,
                    JFClass("Script"),
                    name,
                    returnType,
                    static = true,
                    operator = false,
                    associativity = PREFIX,
                )
                symbolMapManager.replaceType(name, jfm)
                registerFunctionInSymbolTable(jfm)

                val savedScope = symbolTable.captureScope()
                (curlyBlock.scopeCapture as? LocalScope)?.let { symbolTable.restoreScope(it) }
                try {
                    symbolMapManager.override(curlyBlock.symbolMap) {
                        structurePass(curlyBlock.tokens)
                    }
                } finally {
                    symbolTable.restoreScope(savedScope)
                }

                functions.add(FunDescriptor(name, curlyBlock.tokens.drop(1).dropLast(1), curlyBlock.symbolMap, returnType, scopeCapture = curlyBlock.scopeCapture))
            } else {
                mainTokens.add(token)
            }
            i++
        }

        return StructureResult(mainTokens, functions)
    }

    private fun parseParameters(tokens: List<ExpressionNode.Phase1Token>): List<JFVariableSymbol> {
        if (tokens.isEmpty()) return emptyList()
        val params = mutableListOf<JFVariableSymbol>()
        val segments = mutableListOf(mutableListOf<ExpressionNode.Phase1Token>())
        for (token in tokens) {
            if (token is ExpressionNode.Comma) segments.add(mutableListOf()) else segments.last().add(token)
        }
        for (segment in segments) {
            val nonWhitespace = segment.filter { it !is ExpressionNode.Whitespace && it !is ExpressionNode.Newline }
            if (nonWhitespace.isEmpty()) continue
            val colonIdx = nonWhitespace.indexOfFirst { it is ExpressionNode.Colon }
            if (colonIdx < 0) error("Expected colon in parameter")
            val name = (nonWhitespace.getOrNull(colonIdx - 1) as? ExpressionNode.Identifier)?.value
                ?: error("Expected identifier before colon")
            val typeName = (nonWhitespace.getOrNull(colonIdx + 1) as? ExpressionNode.Identifier)?.value
                ?: error("Expected type after colon")
            val type = resolveTypeName(typeName)
            params.add(JFVariableSymbol(name, type))
        }
        return params
    }

    private fun extractReturnType(
        tokens: List<ExpressionNode.Phase1Token>,
        startIdx: Int,
    ): OperandType<*> {
        var j = startIdx
        while (j < tokens.size && (tokens[j] is ExpressionNode.Whitespace || tokens[j] is ExpressionNode.Newline)) j++
        if (j < tokens.size && tokens[j] is ExpressionNode.Colon) {
            j++
            while (j < tokens.size && (tokens[j] is ExpressionNode.Whitespace || tokens[j] is ExpressionNode.Newline)) j++
            val typeName = (tokens.getOrNull(j) as? ExpressionNode.Identifier)?.value
            if (typeName != null) {
                return resolveTypeName(typeName)
            }
        }
        return OperandType.Unknown
    }

    private data class PendingFunction(
        val descriptor: FunDescriptor,
        var parsedBody: List<ExpressionNode.Phase2Expression>? = null,
    )

    private fun resolveTypeName(typeName: String): OperandType<*> =
        symbolTable.resolveTypeByShortName(typeName)?.operandType
            ?: symbolMapManager.findSingleOrNull(typeName) as? OperandType<*>
            ?: OperandType.Unknown

    private fun MethodDef.toJFMethod(): JFMethod = JFMethod(
        parameters = parameters.map { JFVariableSymbol(it.name, it.type) },
        parent = parentFqdn?.let { JFClass(it.value) } ?: JFClass("Script"),
        name = name,
        rtn = rtn,
        static = static,
        operator = operator,
        associativity = associativity,
        precedence = precedence,
        inline = inline,
    )

    private fun registerFunctionInSymbolTable(method: JFMethod) {
        val parentFqdn = FQDN("Script")
        val methodId = parentFqdn + method.name
        symbolTable.registerMethod(
            MethodDef(
                id = methodId,
                name = method.name,
                parentFqdn = parentFqdn,
                parameters = method.parameters.map { Parameter(it.name, it.type) },
                rtn = method.rtn,
                static = method.static,
                operator = method.operator,
                associativity = method.associativity,
                precedence = method.precedence,
                inline = method.inline,
            )
        )
        val pkgParts = parentFqdn.packageName.split(".").filter { it.isNotEmpty() }
        symbolTable.findOrCreatePackage(*pkgParts.toTypedArray()).addFunction(method.name, methodId)
    }

    private fun <T> withRestoredScope(fn: FunDescriptor, block: () -> T): T {
        val saved = symbolTable.captureScope()
        (fn.scopeCapture as? LocalScope)?.let { symbolTable.restoreScope(it) }
        try {
            return block()
        } finally {
            symbolTable.restoreScope(saved)
        }
    }

    private fun parseBodies(
        functions: List<FunDescriptor>,
        mainResult: List<ExpressionNode.Phase2Expression>,
    ): List<ExpressionNode.Phase2Expression> {
        if (functions.isEmpty()) return mainResult
        val pending = functions.map { PendingFunction(it) }
        val rtnCache = functions.associate { it.name to it.returnType }.toMutableMap()

        var changed = true
        while (changed) {
            changed = false
            for (fn in pending.filter { it.parsedBody == null }) {
                val body = withRestoredScope(fn.descriptor) {
                    symbolMapManager.override(fn.descriptor.symbolMap) {
                        expressions(fn.descriptor.bodyTokens)
                    }
                }
                val inferredType = body.lastOrNull()?.type() ?: OperandType.Unit
                val currentType = rtnCache[fn.descriptor.name] ?: OperandType.Unknown
                if (inferredType != currentType) {
                    rtnCache[fn.descriptor.name] = inferredType
                    changed = true
                    if (inferredType != OperandType.Unknown) {
                        symbolTable.setInferredReturnType(FQDN("Script.${fn.descriptor.name}"), inferredType)
                        val currentSymbol = withRestoredScope(fn.descriptor) {
                            symbolMapManager.override(fn.descriptor.symbolMap) {
                                symbolMapManager.findSingleOrNull(fn.descriptor.name) as? JFMethod
                            }
                        }
                        if (currentSymbol != null) {
                            withRestoredScope(fn.descriptor) {
                                symbolMapManager.override(fn.descriptor.symbolMap) {
                                    symbolMapManager.replaceType(fn.descriptor.name, currentSymbol.copy(rtn = inferredType))
                                }
                            }
                        }
                    }
                }
                if (inferredType != OperandType.Unknown) {
                    fn.parsedBody = body
                }
            }
            if (!changed && pending.any { it.parsedBody == null }) {
                for (fn in pending.filter { it.parsedBody == null }) {
                    fn.parsedBody = withRestoredScope(fn.descriptor) {
                        symbolMapManager.override(fn.descriptor.symbolMap) {
                            expressions(fn.descriptor.bodyTokens)
                        }
                    }
                }
            }
        }

        val functionNodes = pending.map { fn ->
            val symbol = withRestoredScope(fn.descriptor) {
                symbolMapManager.override(fn.descriptor.symbolMap) {
                    symbolMapManager.findSingleOrNull(fn.descriptor.name) as? JFMethod
                }
            } ?: symbolTable.lookupMethodById(FQDN("Script.${fn.descriptor.name}"))?.toJFMethod()
                ?: error("Symbol for ${fn.descriptor.name} not found")
            ExpressionNode.Function(symbol, fn.parsedBody ?: emptyList(), inline = fn.descriptor.inline)
        }
        return functionNodes + mainResult
    }
}
