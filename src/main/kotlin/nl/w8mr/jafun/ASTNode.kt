package nl.w8mr.jafun

import jafun.compiler.IdentifierCache
import jafun.compiler.LocalSymbolMap

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
            append(expressions.map {
                it.tree(indent+2)
            }.joinToString("\n"))
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
            append(arguments.map {
                it.tree(indent+2)

            }.joinToString(",\n"))
            append("\n${" ".repeat(indent)}): ${method.rtn}")
        }.toString()
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
                        is ASTNode.Variable -> it.variableSymbol
                        is ASTNode.ValAssignment -> {
                            compileAsStatement(it, builder)
                            it.variableSymbol
                        }
                        else -> {
                            val tmpVariable = IR.JFVariableSymbol("subject", it.type(), LocalSymbolMap(IdentifierCache))
                            compileAsStatement(ASTNode.ValAssignment(tmpVariable, it), builder)
                            tmpVariable
                        }
                    }
                }
            val after = builder.newCodeBlock()
            val lastIndex = matches.size - 1
            matches.forEachIndexed { index, (condition, expression) ->
                val nextBlock = builder.newCodeBlock()
                when {
                    (index == lastIndex) && (condition == BooleanLiteral(true)) -> {
                        builder.addCodeBlock(builder.newCodeBlock())
                        compileAsExpression(expression, builder)
                        builder.goto(after)
                    }
                    else -> {
                        condition(variable, condition, builder)
                        builder.iffalse(nextBlock)
                        builder.addCodeBlock(builder.newCodeBlock())
                        compileAsExpression(expression, builder)
                        builder.goto(after)
                    }
                }
                builder.addCodeBlock(nextBlock)
            }
            if ((matches.last().second == BooleanLiteral(true))) builder.pop() // throw Exception
            builder.addCodeBlock(after)
        }

        private fun condition(
            variable: IR.JFVariableSymbol?,
            condition: Expression,
            builder: IRBuilder.CodeBlockDSL,
        ) {
            variable?.let {
                compileAsExpression(
                    Invocation(
                        IdentifierCache.find("==") as IR.JFMethod,
                        null,
                        listOf(Variable(variable), condition),
                    ),
                    builder,
                )
            } ?: compileAsExpression(condition, builder)
        }

        override fun tree(indent: Int): String = StringBuilder().apply {
            append("${" ".repeat(indent)}when")
            if (subject != null) {
                append("(${subject.tree()})")
            }
            append(" {\n")
            append(matches.map {
                (condition, code) -> "${condition.tree(indent+2)} -> ${code.tree()}"
            }.joinToString("\n"))
            append("\n${" ".repeat(indent)}}")
        }.toString()

    }

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
            append(symbol.parameters.map {
                "${" ".repeat(indent+2)}${it.name}: ${it.type}"
            }.joinToString(",\n"))
            append("\n${" ".repeat(indent)}): ${symbol.rtn} {\n")
            append(block.map {
                it.tree(indent+2)
            }.joinToString("\n"))
            append("\n}")
        }.toString()

    }

    data class MethodIdentifier(val method: IR.JFMethod, val field: IR.JFField?) : Expression() {
        override fun type() = method.rtn
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
                } else if (argument.type() == IR.SInt32 && parameter is IR.JFClass) {
                    compileAsExpression(argument, builder)
                    builder.invoke(IdentifierCache.find("java.lang.Integer.valueOf") as IR.JFMethod, null)
                } else if (argument.type() == IR.UInt1 && parameter is IR.JFClass) {
                    compileAsExpression(argument, builder)
                    builder.invoke(IdentifierCache.find("java.lang.Boolean.valueOf") as IR.JFMethod, null)
                } else if (argument.type() == parameter) {
                    compileAsExpression(argument, builder)
                } else {
                    TODO()
                }
            }
        }
    }
}
