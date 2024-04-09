package nl.w8mr.jafun

import jafun.compiler.IdentifierCache
import nl.w8mr.jafun.ASTNode.Expression

sealed interface ASTNode {
    fun compile(
        builder: IRBuilder.CodeBlockDSL,
        returnValue: Boolean = true,
    )

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
    }

    data class IntegerLiteral(val value: Int) : Expression() {
        override fun compile(
            builder: IRBuilder.CodeBlockDSL,
            returnValue: Boolean,
        ) {
            builder.loadConstant(value, IR.SInt32)
        }

        override fun type() = IR.SInt32
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

    data class ExpressionList(val expressions: List<Expression>, val singleValue: Boolean = false) : Expression() {
        override fun type() = expressions.lastOrNull()?.type() ?: IR.Unit

        override fun compile(
            builder: IRBuilder.CodeBlockDSL,
            returnValue: Boolean,
        ) {
            if (singleValue) {
                val lastIndex = expressions.size - 1
                expressions.forEachIndexed { index, statement ->
                    if (returnValue && (lastIndex == index)) {
                        compileAsExpression(statement, builder)
                    } else {
                        compileAsStatement(statement, builder)
                    }
                }
            } else {
                expressions.forEach { expression -> compileAsExpression(expression, builder) }
            }
        }
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
    }

    data class When(val input: Expression?, val matches: List<Pair<Expression, Expression>>) : Expression() {
        override fun type() = matches.last().second.type() // TODO: find common type

        override fun compile(
            builder: IRBuilder.CodeBlockDSL,
            returnValue: Boolean,
        ) {
            input?.let { compileAsExpression(it, builder) }
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
                        compileAsExpression(condition, builder)
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
    }

    data class Variable(val variableSymbol: IR.JFVariableSymbol) : Expression() {
        override fun compile(
            builder: IRBuilder.CodeBlockDSL,
            returnValue: Boolean,
        ) {
            builder.load("${variableSymbol.symbolMap.symbolMapId}.${variableSymbol.name}", this.variableSymbol.type)
        }

        override fun type() = variableSymbol.type
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
