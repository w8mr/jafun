package nl.w8mr.jafun

import nl.w8mr.jafun.Type.JFClass
import nl.w8mr.jafun.Type.JFMethod
import nl.w8mr.jafun.compiler.Associativity.PREFIX
import nl.w8mr.jafun.compiler.ExpressionNode
import nl.w8mr.jafun.compiler.ExpressionNode.Identifier
import nl.w8mr.jafun.compiler.SymbolMapManager
import nl.w8mr.jafun.symboltable.SymbolTable
import nl.w8mr.jafun.symboltable.VariableDef
import nl.w8mr.parsek.Parser
import nl.w8mr.parsek.and
import nl.w8mr.parsek.asLiteral
import nl.w8mr.parsek.combi
import nl.w8mr.parsek.filter
import nl.w8mr.parsek.invoke
import nl.w8mr.parsek.map
import nl.w8mr.parsek.oneOf
import nl.w8mr.parsek.oneOrMore
import nl.w8mr.parsek.optional
import nl.w8mr.parsek.or
import nl.w8mr.parsek.parse
import nl.w8mr.parsek.ref
import nl.w8mr.parsek.sepByAllowEmpty
import nl.w8mr.parsek.seq
import nl.w8mr.parsek.simple
import nl.w8mr.parsek.simpleLiteral
import nl.w8mr.parsek.text.CharSequenceContext
import nl.w8mr.parsek.text.and
import nl.w8mr.parsek.text.any
import nl.w8mr.parsek.text.char
import nl.w8mr.parsek.text.digit
import nl.w8mr.parsek.text.letter
import nl.w8mr.parsek.text.oneOrMore
import nl.w8mr.parsek.text.repeat
import nl.w8mr.parsek.text.string
import nl.w8mr.parsek.text.value
import nl.w8mr.parsek.zeroOrMore

data class Phase1Parser(val symbolMapManager: SymbolMapManager = SymbolMapManager().apply { reset() }, val symbolTable: SymbolTable = SymbolTable()) {
    companion object {
        val operatorSymbols = listOf('!', '#', '$', '%', '*', '+', '<', '>', '?', '\\', '/', '^', '|', '-', '~', '=')
    }

    val whitespace = char(" is not whitespace") { it == '\u0020' || it == '\u0009' || it == '\u000c' }
    val ws = oneOrMore(whitespace) map { ExpressionNode.Whitespace(it) }

    private val newline = char('\n') or string("\r\n")
    val nl = oneOrMore(newline) map { ExpressionNode.Newline(it) }

    val owsnl = zeroOrMore(ws or nl) map { ExpressionNode.Phase1List(it) }
    val wsnl = oneOrMore(ws or nl) map { ExpressionNode.Phase1List(it) }

    val dot = char('.').map { ExpressionNode.Dot }
    val colon = char(':').map { ExpressionNode.Colon }
    val semiColon = char(';').map { ExpressionNode.SemiColon }
    val doubleQoute = char('"').map { ExpressionNode.DoubleQoute }
    val singleQoute = char('\'').map { ExpressionNode.SingleQoute }
    val leftParen = char('(').map { ExpressionNode.LeftParen }
    val rightParen = char(')').map { ExpressionNode.RightParen }
    val leftCurly = char('{').map { ExpressionNode.LeftCurly }
    val rightCurly = char('}').map { ExpressionNode.RightCurly }
    val comma = char(',').map { ExpressionNode.Comma }

    val charLiteral_term =
        seq(singleQoute, char(" is not valid string Char") { it != '\'' && it != '\\' } map { ExpressionNode.CharLiteral(it[0]) }, singleQoute) map ExpressionNode::Phase1List

    val decimalDigit = char { it in '0'..'9' }
    val decimalDigitNoZero = char { it in '1'..'9' }
    val decimalDigitOrSeparator = decimalDigit or char('_')
    val integerLiteral_term =
        ((repeat(char('-'), 1, 0) and decimalDigitNoZero and any(decimalDigitOrSeparator)) or decimalDigit).map {
            ExpressionNode.IntegerLiteral(it.replace("_", "").toInt())
        }

    val trueLiteral = "true" value ExpressionNode.BooleanLiteral(true)
    val falseLiteral = "false" value ExpressionNode.BooleanLiteral(false)
    val booleanLiteral_term = trueLiteral or falseLiteral

    val betweenParentheses1 = seq(leftParen, ref(::phase1Tokens) map ExpressionNode::Phase1List, rightParen) { l, b, r -> ExpressionNode.Phase1List(l,b,r) }
    val betweenCurly1 = seq(
        leftCurly.map { symbolMapManager.push(); symbolTable.pushScope(); it },
        ref(::phase1Tokens) map ExpressionNode::Phase1List,
        rightCurly) { l, b, r ->
        val current = symbolMapManager.pop()
        val scopeCapture = symbolTable.captureScope()
        symbolTable.popScope()
        ExpressionNode.CurlyBlock(current, scopeCapture, listOf(l) + b.flatten() + listOf(r))
    }

    val unicode_digit = char(" is not Unicode digit") { it.category == CharCategory.DECIMAL_DIGIT_NUMBER }
    val normalIdentifier =
        (letter or char('_')) and
                any(letter or char('_') or unicode_digit) map (::Identifier)

    val operatorIdentifier = oneOrMore(char { it in operatorSymbols }).map { Identifier(it, true) }

    val identifier = normalIdentifier or operatorIdentifier

    val complexIdentifierPhase1 = ((identifier) and zeroOrMore(seq(
        dot, owsnl, identifier) { dot, ws1, identifier -> ExpressionNode.Phase1List(dot, ws1, identifier)} ) map { ExpressionNode.Phase1List(listOf(it.first) + it.second) })

    val equals = operatorIdentifier.filter { it.value == "=" }
    val dollar = operatorIdentifier.filter { it.value == "$" }

    val simpleStringExpression = seq(dollar, normalIdentifier) map { d, i -> ExpressionNode.Phase1List(d, i) }
    val complexStringExpression = seq(dollar, betweenCurly1) map { d, e -> ExpressionNode.Phase1List(d, e) }
    val stringLiteral = oneOrMore(char(" is not valid string Char") { it != '"' && it != '\\' && it != '$'}).map(ExpressionNode::StringLiteral)

    // TODO: Escape characters
    val lineStringContent = zeroOrMore(stringLiteral or simpleStringExpression or complexStringExpression) map { ExpressionNode.Phase1List(it) }
    val stringLiteral_term = seq(doubleQoute, lineStringContent, doubleQoute) map ExpressionNode::Phase1List
    // TODO: Multiline string

    val valDeclaration = combi {
        val rawVal = string("val").bind()
        // Check that 'val' is not part of a longer identifier (like 'value')
        val nextChar = (letter or digit or char { it == '_' }).bindAsResult()
        if (nextChar is Parser.Success) {
            fail("'val' is followed by identifier character")
        }
        val `val` = ExpressionNode.Keyword(rawVal)
        val whitespace1 = owsnl.bind()
        val identifier = identifier.bind()
        val whitespace2 = owsnl.bind()
        val optionalType = (optional(
            seq(colon, owsnl, complexIdentifierPhase1) { colon, ws1, identifier -> ExpressionNode.Phase1List(colon, ws1, identifier) }
        ).map { it ?: ExpressionNode.Phase1List() }).bind()
        symbolMapManager.newVariableSymbol(identifier.value, OperandType.Unknown, false, false)
        symbolTable.addVariable(identifier.value, VariableDef(identifier.value, OperandType.Unknown, mutable = false, initialized = false))
        ExpressionNode.Phase1List(`val`, whitespace1, identifier, whitespace2, optionalType/*, equals, whitespace3*/)
    }

    val varDeclaration = combi {
        val rawVar = string("var").bind()
        // Check that 'var' is not part of a longer identifier
        val nextChar = (letter or digit or char { it == '_' }).bindAsResult()
        if (nextChar is Parser.Success) {
            fail("'var' is followed by identifier character")
        }
        val `var` = ExpressionNode.Keyword(rawVar)
        val whitespace1 = owsnl.bind()
        val identifier = identifier.bind()
        val whitespace2 = owsnl.bind()
        val optionalType = (optional(
            seq(colon, owsnl, complexIdentifierPhase1) { colon, ws1, identifier -> ExpressionNode.Phase1List(colon, ws1, identifier) }
        ).map { it ?: ExpressionNode.Phase1List() }).bind()
        symbolMapManager.newVariableSymbol(identifier.value, OperandType.Unknown, true, false)
        symbolTable.addVariable(identifier.value, VariableDef(identifier.value, OperandType.Unknown, mutable = true, initialized = false))
        ExpressionNode.Phase1List(`var`, whitespace1, identifier, whitespace2, optionalType)
    }

    val inlineDeclaration = combi {
        val rawInline = string("inline").bind()
        val nextChar = (letter or digit or char { it == '_' }).bindAsResult()
        if (nextChar is Parser.Success) {
            fail("'inline' is followed by identifier character")
        }
        ExpressionNode.Keyword(rawInline)
    }

    val funDeclaration = combi {
        val rawFun = string("fun").bind()
        // Check that 'fun' is not part of a longer identifier
        val nextChar = (letter or digit or char { it == '_' }).bindAsResult()
        if (nextChar is Parser.Success) {
            fail("'fun' is followed by identifier character")
        }
        val `fun` = ExpressionNode.Keyword(rawFun)
        val whitespace1 = owsnl.bind()
        val name = identifier.bind()
        val whitespace2 = owsnl.bind()
        symbolMapManager.push()
        symbolTable.pushScope()
        val lp = leftParen.bind()
        val whitespace3 = owsnl.bind()
        val arguments = mutableListOf<Type.JFVariableSymbol>()
        val parameters = ((seq(identifier, owsnl, colon, owsnl, complexIdentifierPhase1, owsnl ) map { it ->
            val identifier = it.getOrNull(0) as? ExpressionNode.Identifier ?: error("Identifier not found")
            val type = ((it.getOrNull(4) as? ExpressionNode.Phase1List ?: error("Type not found"))
                .tokens.singleOrNull() as? Identifier ?: error("Type not simple") ).value
                .let { resolveTypeName(it) }
            arguments += symbolMapManager.newVariableSymbol(identifier.value, type, false)
            symbolTable.addVariable(identifier.value, VariableDef(identifier.value, type, mutable = false))
            it
        } map ExpressionNode::Phase1List ) sepByAllowEmpty (comma and owsnl) map { ExpressionNode.Phase1List(it.flatMap { it.flatten() + listOf(
            ExpressionNode.Comma) }.dropLast(1)) }).bind()
        val rp = rightParen.bind()
        val optionalType = (optional(
            seq(owsnl, colon, owsnl, complexIdentifierPhase1, owsnl) map ExpressionNode::Phase1List
        ).map { it ?: ExpressionNode.Phase1List() }).bind()
        val whitespace4 = owsnl.bind()
        val body = betweenCurly1.bind()
        val symbol =
            JFMethod(
                arguments,
                JFClass("Script"),
                name.value,
                (optionalType.tokens.firstOrNull() as? Identifier)?.value?.let { resolveTypeName(it) } ?: OperandType.Unknown,
                static = true,
                operator = name.operator,
                associativity = PREFIX,
            )
        symbolMapManager.pop()
        symbolTable.popScope()
        symbolMapManager.add(name.value, symbol)
        ExpressionNode.Phase1List(`fun`, whitespace1, name, whitespace2, lp, whitespace3, parameters, rp, optionalType, whitespace4, body)
    }

    val valueClassDeclaration = combi {
        val rawValue = string("value").bind()
        // Check that 'value' is not part of a longer identifier
        val nextChar = (letter or digit or char { it == '_' }).bindAsResult()
        if (nextChar is Parser.Success) {
            fail("'value' is followed by identifier character")
        }
        val `value` = ExpressionNode.Keyword(rawValue)
        val white1 = owsnl.bind()
        val `class` = string("class").map { ExpressionNode.Keyword(it) }.bind()
        val white2 = owsnl.bind()
        val name = identifier.bind()
        val white3 = owsnl.bind()
        val lp = leftParen.bind()
        val white4 = owsnl.bind()

        val parameters = mutableListOf<Type.JFVariableSymbol>()
        val paramList = ((seq(identifier, owsnl, colon, owsnl, complexIdentifierPhase1, owsnl) map { it ->
            val id = it.getOrNull(0) as? ExpressionNode.Identifier ?: error("Identifier not found")
            val type = ((it.getOrNull(4) as? ExpressionNode.Phase1List ?: error("Type not found"))
                .tokens.singleOrNull() as? Identifier ?: error("Type not simple")).value
                .let { resolveTypeName(it) }
            parameters += Type.JFVariableSymbol(id.value, type)
            it
        } map ExpressionNode::Phase1List) sepByAllowEmpty (comma and owsnl) map { ExpressionNode.Phase1List(it.flatMap { it.flatten() + listOf(ExpressionNode.Comma) }.dropLast(1)) }).bind()

        val rp = rightParen.bind()

        val jfClass = Type.JFClass(name.value, kind = Type.ClassKind.VALUE_CLASS)
        val cons = Type.JFConstructor(parameters, jfClass)
        val finalClass = jfClass.copy(constructor = cons)
        val finalCons = cons.copy(parent = finalClass)
        symbolMapManager.add(name.value, finalClass)
        symbolMapManager.add(finalClass, finalCons.name, finalCons)

        for ((index, param) in parameters.withIndex()) {
            val getter = Type.JFMethod(
                emptyList(), finalClass, param.name, param.type,
                static = false, operator = false, associativity = PREFIX, precedence = 10,
            )
            symbolMapManager.add(finalClass, param.name, getter)
        }

        ExpressionNode.Phase1List(`value`, white1, `class`, white2, name, white3, lp, white4, paramList, rp)
    }

    val phase1Tokens: Parser<Char, List<ExpressionNode.Phase1Token>> = zeroOrMore(oneOf(
        integerLiteral_term,
        stringLiteral_term,
        charLiteral_term,
        booleanLiteral_term,
        valueClassDeclaration,
        valDeclaration,
        varDeclaration,
        inlineDeclaration,
        funDeclaration,
        equals,
        comma,
        dot,
        semiColon,
        identifier,
        betweenParentheses1,
        betweenCurly1,
        wsnl,
    )).map { ExpressionNode.Phase1List(it).flatten() }

    val phase1 = phase1Tokens and simpleLiteral { if (hasToken()) fail("not eof") }

    fun parse(input: String): Pair<List<ExpressionNode.Phase1Token>?, Parser.Result<List<ExpressionNode.Phase1Token>>> {
        val source = CharSequenceContext(input)
        return phase1.parse(source)
    }

    private fun resolveTypeName(typeName: String): OperandType<*> =
        symbolTable.resolveTypeByShortName(typeName)?.operandType
            ?: symbolMapManager.findSingleOrNull(typeName) as? OperandType<*>
            ?: OperandType.Unknown

}