package nl.w8mr.jafun

import jafun.compiler.BooleanType
import jafun.compiler.IdentifierCache
import jafun.compiler.IntegerType
import jafun.compiler.JFClass
import jafun.compiler.JFField
import jafun.compiler.JFMethod
import jafun.compiler.JFVariableSymbol
import jafun.compiler.ThisType
import jafun.compiler.TypeSig
import jafun.compiler.UnknownType
import jafun.compiler.VoidType

sealed interface ASTNode {
    fun compile(
        builder: IRBuilder.CodeBlockDSL,
        returnValue: Boolean = true,
    )

    abstract class Expression : ASTNode {
        abstract fun type(): TypeSig

        override fun compile(
            builder: IRBuilder.CodeBlockDSL,
            returnValue: Boolean,
        ) { }
    }

    data class StringLiteral(val value: String) : Expression() {
        override fun type(): TypeSig {
            return IdentifierCache.find("java.lang.String") as TypeSig
        }

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

        override fun type(): TypeSig {
            return IntegerType
        }
    }

    data class BooleanLiteral(val value: Boolean) : Expression() {
        override fun compile(
            builder: IRBuilder.CodeBlockDSL,
            returnValue: Boolean,
        ) {
            builder.loadConstant(value, IR.UInt1)
        }

        override fun type(): TypeSig {
            return BooleanType
        }
    }

    data class ExpressionList(val expressions: List<Expression>, val singleValue: Boolean = false) : Expression() {
        override fun type(): TypeSig = expressions.lastOrNull()?.type() ?: VoidType

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

    data class Invocation(val method: JFMethod, val field: JFField?, val arguments: List<Expression>) : Expression() {
        override fun compile(
            builder: IRBuilder.CodeBlockDSL,
            returnValue: Boolean,
        ) {
            with(builder) {
                if (field != null) {
                    if (field.path == ThisType.path) {
                        // TODO: check implementation
                        load("this", IR.Reference<Any?>(field.signature))
                    } else {
                        val fieldClassName = field.parent.path
                        val fieldTypeSig = field.signature
                        getStatic(fieldClassName, field.name, fieldTypeSig)
                    }
                }

                loadArguments(
                    builder,
                    arguments,
                    method.parameters.map(JFVariableSymbol::type),
                )
                invoke(method, field)
                if (!returnValue && (method.rtn != VoidType)) {
                    pop()
                }
            }
        }

        override fun type(): TypeSig {
            return method.rtn
        }
    }

    data class When(val input: Expression?, val matches: List<Pair<Expression, Expression>>) : Expression() {
        override fun type(): TypeSig = matches.last().second.type() // TODO: find common type
    }

    data class ValAssignment(val variableSymbol: JFVariableSymbol, val expression: Expression) : Expression() {
        override fun type() = expression.type()

        override fun compile(
            builder: IRBuilder.CodeBlockDSL,
            returnValue: Boolean,
        ) {
            compileAsExpression(expression, builder)
            if (returnValue) builder.dup()

            builder.store(
                "${variableSymbol.symbolMap.symbolMapId}.${variableSymbol.name}",
                IR.operandType(this.variableSymbol),
            )
        }
    }

    data class Variable(val variableSymbol: JFVariableSymbol) : Expression() {
        override fun compile(
            builder: IRBuilder.CodeBlockDSL,
            returnValue: Boolean,
        ) {
            builder.load("${variableSymbol.symbolMap.symbolMapId}.${variableSymbol.name}", IR.operandType(this.variableSymbol))
        }

        override fun type(): TypeSig {
            return variableSymbol.type
        }
    }

    data class Function(val symbol: JFMethod, val block: List<Expression>) : Expression() {
        override fun type(): TypeSig {
            return VoidType
        }

        override fun compile(
            builder: IRBuilder.CodeBlockDSL,
            returnValue: Boolean,
        ) {
            compileMethod(
                builder.parent.parent,
                block,
                symbol.name,
                IR.operandType(symbol.rtn),
                symbol.parameters.map(IR::operandType),
            )
        }
    }

    data class MethodIdentifier(val method: JFMethod, val field: JFField?) : Expression() {
        override fun type(): TypeSig = UnknownType
    }

    fun loadArguments(
        builder: IRBuilder.CodeBlockDSL,
        arguments: List<Expression>,
        parameters: List<TypeSig>,
    ) {
        arguments.zip(parameters).forEach { (argument, parameter) ->
            if (argument.type() == parameter) {
                compileAsExpression(argument, builder)
            } else {
                if (argument.type() is JFClass && parameter is JFClass) {
                    compileAsExpression(argument, builder)
                } else if (argument.type() == IntegerType && parameter is JFClass) {
                    compileAsExpression(argument, builder)
                    builder.invoke(IdentifierCache.find("java.lang.Integer.valueOf") as JFMethod, null)
                } else if (argument.type() == BooleanType && parameter is JFClass) {
                    compileAsExpression(argument, builder)
                    builder.invoke(IdentifierCache.find("java.lang.Boolean.valueOf") as JFMethod, null)
                } else {
                    TODO()
                }
            }
        }
    }
}
