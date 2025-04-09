package nl.w8mr.jafun

import nl.w8mr.jafun.compiler.IdentifierCache
import nl.w8mr.jafun.compiler.LocalSymbolMap
import kotlin.collections.joinToString

sealed interface ASTNode {
    fun compile(
        builder: IRBuilder.CodeBlockDSL,
        returnValue: Boolean = true,
    )

    //TODO: extract to separate Printer class
    fun tree(indent: Int = 0): String =
        "${" ".repeat(indent)}${this}"

    abstract class Expression : ASTNode {
        abstract fun type(): IR.OperandType<*>

        override fun compile(
            builder: IRBuilder.CodeBlockDSL,
            returnValue: Boolean,
        ) { }
    }

    data class StringLiteral(val value: String) : Expression() {
        override fun type() = IR.StringType

        override fun compile(
            builder: IRBuilder.CodeBlockDSL,
            returnValue: Boolean,
        ) {
            builder.loadConstant(value, IR.StringType)
        }

        override fun tree(indent: Int): String = "${" ".repeat(indent)}StringLiteral(\"${value}\")"
    }

    data class CharLiteral(val value: String) : Expression() {
        override fun type() = IR.CharType

        override fun compile(
            builder: IRBuilder.CodeBlockDSL,
            returnValue: Boolean,
        ) {
            builder.loadConstant(value[0], IR.CharType)
        }

        override fun tree(indent: Int): String = "${" ".repeat(indent)}CharLiteral(\"${value[0]}\")"
    }

    data class IntegerLiteral(val value: Int) : Expression() {
        override fun compile(
            builder: IRBuilder.CodeBlockDSL,
            returnValue: Boolean,
        ) {
            builder.loadConstant(value, IR.SInt32)
        }

        override fun type() = IR.SInt32

        override fun tree(indent: Int): String = "${" ".repeat(indent)}Int32Literal(${value})"

    }

    data class BooleanLiteral(val value: Boolean) : Expression() {
        override fun compile(
            builder: IRBuilder.CodeBlockDSL,
            returnValue: Boolean,
        ) {
            builder.loadConstant(value, IR.UInt1)
        }

        override fun type() = IR.UInt1

        override fun tree(indent: Int): String = "${" ".repeat(indent)}BooleanLiteral(${value})"

    }

    data class ExpressionList(val expressions: List<Expression>) : Expression() {
        override fun type() = expressions.lastOrNull()?.type() ?: IR.Unit

        override fun compile(
            builder: IRBuilder.CodeBlockDSL,
            returnValue: Boolean,
        ) {
            val lastIndex = expressions.size - 1
            expressions.forEachIndexed { index, statement ->
                if (returnValue && (lastIndex == index)) {
                    compileAsExpression(statement, builder)
                } else {
                    compileAsStatement(statement, builder)
                }
            }
        }

        override fun tree(indent: Int): String = StringBuilder().apply {
            append(expressions.joinToString("\n") {
                it.tree(indent + 2)
            })
        }.toString()

    }

    data class Invocation(val method: IR.JFMethod, val field: IR.JFField?, val arguments: List<Expression>) : Expression() {
        override fun compile(
            builder: IRBuilder.CodeBlockDSL,
            returnValue: Boolean,
        ) {
            with(builder) {
                if (field != null) {
                    if (field.path == "this") {
                        // TODO: check implementation
                        load("this", IR.Reference<Any?>(field.path))
                    } else {
                        val fieldClassName = field.parent.path
                        val fieldTypeSig = IR.Reference<Any?>(field.path)
                        getStatic(fieldClassName, field.name, fieldTypeSig)
                    }
                }

                loadArguments(
                    builder,
                    arguments,
                    method.parameters.map(IR.JFVariableSymbol::type),
                )
                invoke(method, field)
                if (!returnValue && (method.rtn != IR.Unit)) {
                    pop()
                }
            }
        }

        override fun type() = method.rtn

        override fun tree(indent: Int): String = StringBuilder().apply {
            append("${" ".repeat(indent)}${field?.name?:""}${method.name}(\n")
            append(arguments.joinToString(",\n") {
                it.tree(indent + 2)

            })
            append("\n${" ".repeat(indent)}): ${method.rtn}")
        }.toString()
    }

    data class Class(val `class`: IR.JFClass): Expression() {
        override fun type() = `class`

        override fun compile(
            builder: IRBuilder.CodeBlockDSL,
            returnValue: Boolean,
        ) {
            error("Should not be in compiler output")
        }

        override fun tree(indent: Int): String = "${" ".repeat(indent)}Class(${`class`.path})"
    }

    data class Package(val `package`: IR.JFPackage): Expression() {
        override fun type() = `package`

        override fun compile(
            builder: IRBuilder.CodeBlockDSL,
            returnValue: Boolean,
        ) {
            error("Should not be in compiler output")
        }

        override fun tree(indent: Int): String = "${" ".repeat(indent)}Package(${`package`.path})"
    }


    data class Field(val `class`: IR.JFClass, val field: IR.JFField) : Expression() {
        override fun type() = field.type ?: TODO("remove null type")

        override fun compile(
            builder: IRBuilder.CodeBlockDSL,
            returnValue: Boolean,
        ) {
            TODO("identify compiler code")
        }


        override fun tree(indent: Int): String = "${" ".repeat(indent)}Field(${`class`.path}.${field.name})"

    }


    data class When(val subject: Expression?, val matches: List<Pair<Expression, Expression>>) : Expression() {
        override fun type() = matches.last().second.type() // TODO: find common type

        override fun compile(
            builder: IRBuilder.CodeBlockDSL,
            returnValue: Boolean,
        ) {
            val variable =
                subject?.let {
                    when (it) {
                        is Variable -> it.variableSymbol
                        is ValAssignment -> {
                            compileAsStatement(it, builder)
                            it.variableSymbol
                        }
                        else -> {
                            val tmpVariable = IR.JFVariableSymbol("subject", it.type(), LocalSymbolMap(IdentifierCache))
                            compileAsStatement(ValAssignment(tmpVariable, it), builder)
                            tmpVariable
                        }
                    }
                }
            val lastIndex = matches.size - 1
            var elseExpression: List<IR.Instruction>? = null
            val condfitionMatches = mutableListOf<Pair<List<IR.Instruction>, List<IR.Instruction>>>()

            matches.forEachIndexed { index, (condition, expression) ->


                when {
                    (index == lastIndex) && (condition == BooleanLiteral(true)) -> {
                        elseExpression = compileAsCodeBlock(builder, expression)
                    }
                    else -> {
                        condfitionMatches.add(compileAsCodeBlock(builder, condition(variable, condition)) to compileAsCodeBlock(builder,expression))
                    }
                }
            }
            builder.`when`(condfitionMatches, elseExpression)
        }

        private fun condition(
            variable: IR.JFVariableSymbol?,
            condition: Expression,
        ) = variable?.let {
                val symbols = IdentifierCache.find("==")
                val symbol = symbols.filterIsInstance<IR.JFMethod>().singleOrNull { symbol ->
                    symbol.parameters.map { it.type } == listOf(variable.type, condition.type())
                } ?: error("No single method found for == in when condition check")
                Invocation(
                    symbol,
                    null,
                    listOf(Variable(variable), condition),
                )
            } ?: condition

        override fun tree(indent: Int): String = StringBuilder().apply {
            append("${" ".repeat(indent)}when")
            if (subject != null) {
                append("(${subject.tree()})")
            }
            append(" {\n")
            append(matches.joinToString("\n") { (condition, code) ->
                "${condition.tree(indent + 2)} -> ${code.tree()}"
            })
            append("\n${" ".repeat(indent)}}")
        }.toString()

    }

    data class While(val condition: Expression, val expressions: ExpressionList) : Expression() {
        override fun type() = expressions.type()

        override fun compile(
            builder: IRBuilder.CodeBlockDSL,
            returnValue: Boolean,
        ) {
            val conditionInstructions = compileAsCodeBlock(builder, condition)
            val expressionInstructions = compileAsCodeBlock(builder, expressions, false)
            builder.`while`(conditionInstructions, expressionInstructions)
        }

        override fun tree(indent: Int): String = StringBuilder().apply {
            append("${" ".repeat(indent)}while")
            append("(${condition.tree()})")
            append("{\n")
            append(expressions.tree(indent + 2))
            append("}\n")
        }.toString()
    }


    fun compileAsCodeBlock(
        builder: IRBuilder.CodeBlockDSL,
        expression: Expression,
        returnValue: Boolean = true
    ): List<IR.Instruction> {
        val subBuilder = getSubBuilder(builder)
        expression.compile(subBuilder, returnValue)

        return subBuilder.instructions
    }

    fun getSubBuilder(builder: IRBuilder.CodeBlockDSL): IRBuilder.CodeBlockDSL =
        IRBuilder.CodeBlockDSL(mutableListOf(), builder.parent)

    data class ValAssignment(val variableSymbol: IR.JFVariableSymbol, val expression: Expression) : Expression() {
        override fun type() = expression.type()

        override fun compile(
            builder: IRBuilder.CodeBlockDSL,
            returnValue: Boolean,
        ) {
            compileAsExpression(expression, builder)
            if (returnValue) builder.dup()

            builder.store(
                "${variableSymbol.symbolMap.symbolMapId}.${variableSymbol.name}",
                this.variableSymbol.type,
            )
        }

        override fun tree(indent: Int): String =
            """|${" ".repeat(indent)}val ${variableSymbol.name}: ${variableSymbol.type} = 
               |${expression.tree(indent+2)}""".trimMargin()
    }

    data class VarAssignment(val variableSymbol: IR.JFVariableSymbol, val expression: Expression) : Expression() {
        override fun type() = expression.type()

        override fun compile(
            builder: IRBuilder.CodeBlockDSL,
            returnValue: Boolean,
        ) {
            compileAsExpression(expression, builder)
            if (returnValue) builder.dup()

            builder.store(
                "${variableSymbol.symbolMap.symbolMapId}.${variableSymbol.name}",
                this.variableSymbol.type,
            )
        }

        override fun tree(indent: Int): String =
            """|${" ".repeat(indent)}var ${variableSymbol.name}: ${variableSymbol.type} = 
               |${expression.tree(indent+2)}""".trimMargin()
    }


    data class Variable(val variableSymbol: IR.JFVariableSymbol) : Expression() {
        override fun compile(
            builder: IRBuilder.CodeBlockDSL,
            returnValue: Boolean,
        ) {
            builder.load("${variableSymbol.symbolMap.symbolMapId}.${variableSymbol.name}", this.variableSymbol.type)
        }

        override fun type() = variableSymbol.type

        override fun tree(indent: Int): String =
            """|${" ".repeat(indent)}${variableSymbol.name}: ${variableSymbol.type}""".trimMargin()
    }

    data class Function(val symbol: IR.JFMethod, val block: List<Expression>) : Expression() {
        override fun type() = IR.Unit

        override fun compile(
            builder: IRBuilder.CodeBlockDSL,
            returnValue: Boolean,
        ) {
            compileMethod(
                builder.parent.parent,
                block,
                symbol.name,
                symbol.rtn,
                symbol.parameters.map(IR.JFVariableSymbol::type),
            )
        }

        override fun tree(indent: Int): String = StringBuilder().apply {
            append("${" ".repeat(indent)}fun ${symbol}(\n")
            append(symbol.parameters.joinToString(",\n") {
                "${" ".repeat(indent + 2)}${it.name}: ${it.type}"
            })
            append("\n${" ".repeat(indent)}): ${symbol.rtn} {\n")
            append(block.joinToString("\n") {
                it.tree(indent + 2)
            })
            append("\n}")
        }.toString()

    }

    fun loadArguments(
        builder: IRBuilder.CodeBlockDSL,
        arguments: List<Expression>,
        parameters: List<IR.OperandType<*>>,
    ) {
        arguments.zip(parameters).forEach { (argument, parameter) ->
            if (argument.type() == parameter) {
                compileAsExpression(argument, builder)
            } else {
                if (argument.type() is IR.StringType && parameter is IR.JFClass) {
                    compileAsExpression(argument, builder)
                } else if (argument.type() is IR.JFClass && parameter is IR.JFClass) {
                    compileAsExpression(argument, builder)
                } else if (argument.type() is IR.Array && parameter is IR.Array) {
                    compileAsExpression(argument, builder)
                } else if (argument.type() == IR.SInt32 && parameter is IR.JFClass) {
                    compileAsExpression(argument, builder)
                    builder.invoke(IdentifierCache.findSingle("java.lang.Integer.valueOf") as IR.JFMethod, null)
                } else if (argument.type() == IR.CharType && parameter is IR.JFClass) {
                    compileAsExpression(argument, builder)
                    builder.invoke(IdentifierCache.findSingle("java.lang.Character.valueOf") as IR.JFMethod, null)
                } else if (argument.type() == IR.UInt1 && parameter is IR.JFClass) {
                    compileAsExpression(argument, builder)
                    builder.invoke(IdentifierCache.findSingle("java.lang.Boolean.valueOf") as IR.JFMethod, null)
                } else if (argument.type() == parameter) {
                    compileAsExpression(argument, builder)
                } else {
                    TODO()
                }
            }
        }
    }
}
