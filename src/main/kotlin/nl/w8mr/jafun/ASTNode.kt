package nl.w8mr.jafun

import jafun.compiler.BooleanType
import jafun.compiler.IdentifierCache
import jafun.compiler.IntegerType
import jafun.compiler.JFClass
import jafun.compiler.JFField
import jafun.compiler.JFMethod
import jafun.compiler.JFVariableSymbol
import jafun.compiler.TypeSig
import jafun.compiler.UnknownType
import jafun.compiler.VoidType
import nl.w8mr.kasmine.ClassBuilder

sealed interface ASTNode {
    fun compile(
        builder: ClassBuilder.MethodDSL.DSL,
        returnValue: Boolean = true,
    )

    abstract class Expression : ASTNode {
        abstract fun type(): TypeSig

        override fun compile(
            builder: ClassBuilder.MethodDSL.DSL,
            returnValue: Boolean,
        ) { }
    }

    data class StringLiteral(val value: String) : Expression() {
        override fun type(): TypeSig {
            return IdentifierCache.find("java.lang.String") as TypeSig
        }

        override fun compile(
            builder: ClassBuilder.MethodDSL.DSL,
            returnValue: Boolean,
        ) {
            JVMBackend.Context(builder).compile(IR.LoadConstant(value, IR.StringType))
        }
    }

    data class IntegerLiteral(val value: Int) : Expression() {
        override fun compile(
            builder: ClassBuilder.MethodDSL.DSL,
            returnValue: Boolean,
        ) {
            JVMBackend.Context(builder).compile(IR.LoadConstant(value, IR.SInt32))
        }

        override fun type(): TypeSig {
            return IntegerType
        }
    }

    data class BooleanLiteral(val value: Boolean) : Expression() {
        override fun compile(
            builder: ClassBuilder.MethodDSL.DSL,
            returnValue: Boolean,
        ) {
            JVMBackend.Context(builder).compile(IR.LoadConstant(value, IR.UInt1))
        }

        override fun type(): TypeSig {
            return BooleanType
        }
    }

    data class ExpressionList(val expressions: List<Expression>, val singleValue: Boolean = false) : Expression() {
        override fun type(): TypeSig = expressions.lastOrNull()?.type() ?: VoidType

        override fun compile(
            builder: ClassBuilder.MethodDSL.DSL,
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
            builder: ClassBuilder.MethodDSL.DSL,
            returnValue: Boolean,
        ) {
            JVMBackend.Context(builder).compile(IR.Invoke(method, field, arguments))
            if (!returnValue && (method.rtn != VoidType)) {
                JVMBackend.Context(builder).compile(IR.Pop)
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
            builder: ClassBuilder.MethodDSL.DSL,
            returnValue: Boolean,
        ) {
            compileAsExpression(expression, builder)
            if (returnValue) JVMBackend.Context(builder).compile(IR.Dup)

            JVMBackend.Context(builder).compile(
                IR.Store(
                    "${variableSymbol.symbolMap.symbolMapId}.${variableSymbol.name}",
                    operandType(this.variableSymbol),
                ),
            )
        }
    }

    fun operandType(variableSymbol: JFVariableSymbol): IR.OperandType<out Any?> {
        val type =
            when (variableSymbol.type) {
                is JFClass -> IR.Reference<Any?>()
                is IntegerType -> IR.SInt32
                is BooleanType -> IR.UInt1
                else -> TODO("Need to implement types")
            }
        return type
    }

    data class Variable(val variableSymbol: JFVariableSymbol) : Expression() {
        override fun compile(
            builder: ClassBuilder.MethodDSL.DSL,
            returnValue: Boolean,
        ) {
            JVMBackend.Context(builder).compile(
                IR.Load(
                    "${variableSymbol.symbolMap.symbolMapId}.${variableSymbol.name}",
                    operandType(this.variableSymbol),
                ),
            )
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
            builder: ClassBuilder.MethodDSL.DSL,
            returnValue: Boolean,
        ) {
            symbol.parameters.forEach { builder.parameter("${it.symbolMap.symbolMapId}.${it.name}") }
            compileMethod(
                builder.parent,
                block,
                symbol.name,
                "(${symbol.parameters.map(JFVariableSymbol::signature).joinToString(separator = "")})${symbol.signature}",
            )
        }
    }

    data class MethodIdentifier(val method: JFMethod, val field: JFField?) : Expression() {
        override fun type(): TypeSig = UnknownType
    }
}
