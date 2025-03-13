package nl.w8mr.jafun

import nl.w8mr.jafun.Token.Assignment
import nl.w8mr.jafun.Token.Colon
import nl.w8mr.jafun.Token.Comma
import nl.w8mr.jafun.Token.Dot
import nl.w8mr.jafun.Token.False
import nl.w8mr.jafun.Token.Fun
import nl.w8mr.jafun.Token.Identifier
import nl.w8mr.jafun.Token.IntegerLiteral
import nl.w8mr.jafun.Token.LCurl
import nl.w8mr.jafun.Token.LParen
import nl.w8mr.jafun.Token.Newline
import nl.w8mr.jafun.Token.RCurl
import nl.w8mr.jafun.Token.RParen
import nl.w8mr.jafun.Token.Semicolon
import nl.w8mr.jafun.Token.StringLiteral
import nl.w8mr.jafun.Token.True
import nl.w8mr.jafun.Token.Val
import nl.w8mr.jafun.Token.WS
import nl.w8mr.jafun.Token.When
import nl.w8mr.parsek.text.char
import nl.w8mr.parsek.text.oneOrMore
import nl.w8mr.parsek.text.zeroOrMore
import nl.w8mr.parsek.text.and
import nl.w8mr.parsek.text.any
import nl.w8mr.parsek.text.literal
import nl.w8mr.parsek.*
import java.lang.Character.isLetter

val operatorSymbols = listOf('!', '#', '$', '%', '*', '+', '<', '>', '?', '\\', '/', '^', '|', '-', '~')

val underscore = char('_')
val unicode_digit = char(" is not Unicode digit") { it.category == CharCategory.DECIMAL_DIGIT_NUMBER }

val letter = char(" is not Unicode Letter", ::isLetter)
val normalIdentifier = ((letter or underscore) and any(oneOf(letter, underscore, unicode_digit))).map(::Identifier)
val operatorIdentifier = oneOrMore(char { it in operatorSymbols }).map { Identifier(it, true) }
val identifier = normalIdentifier or operatorIdentifier
val decimalDigit = char { it in '0'..'9' }
val decimalDigitNoZero = char { it in '1'..'9' }
val decimalDigitOrSeparator = decimalDigit or char('_')
val integerLiteral =
    ((decimalDigitNoZero and any(decimalDigitOrSeparator)) or decimalDigit).map {
        IntegerLiteral(it.replace("_", "").toInt())
    }

val dot = char('.').map { Dot }
val lParen = char('(').map { LParen }
val rParen = char(')').map { RParen }
val lCurl = char('{').map { LCurl }
val rCurl = char('}').map { RCurl }
val comma = char(',').map { Comma }
val colon = char(':').map { Colon }
val semicolon = char(';').map { Semicolon }
val equality = literal("==").map { Identifier("==", true) }
val lteq = literal("<=").map { Identifier("<=", true) }
val gteq = literal(">=").map { Identifier(">=", true) }
val assignment = char('=').map { Assignment }
val val_token = literal("val").map { Val }
val fun_token = literal("fun").map { Fun }
val when_token = literal("when").map { When }
val true_token = literal("true").map { True }
val false_token = literal("false").map { False }

val lineStringContent = zeroOrMore(char(" is not valid string Char") { it != '"' && it != '\\' })
val lineStringLiteral = ('"' and lineStringContent and '"').map(::StringLiteral)

val ws = oneOrMore(char(" is not whitespace") { it == '\u0020' || it == '\u0009' || it == '\u000c' }).map { WS }
val newline = oneOf(seq(char('\n')), seq(char('\r'), char(('\n')))).map { Newline }

val lexer =
    zeroOrMore(
        oneOf(
            fun_token,
            val_token,
            when_token,
            true_token,
            false_token,
            dot,
            lineStringLiteral,
            integerLiteral,
            ws,
            newline,
            lCurl,
            rCurl,
            lParen,
            rParen,
            comma,
            colon,
            semicolon,
            equality,
            lteq,
            gteq,
            assignment,
            operatorIdentifier,
            identifier,
        ),
    )
