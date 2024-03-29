package nl.w8mr.jafun

import jafun.compiler.BooleanType
import jafun.compiler.ClassType
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
            with(builder) {
                loadConstant(value)
            }
        }
    }

    data class IntegerLiteral(val value: Int) : Expression() {
        override fun compile(
            builder: ClassBuilder.MethodDSL.DSL,
            returnValue: Boolean,
        ) {
            with(builder) {
                loadConstant(value)
            }
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
            with(builder) {
                when (value) {
                    false -> loadConstant(0)
                    true -> loadConstant(1)
                }
            }
        }

        override fun type(): TypeSig {
            return BooleanType
        }
    }

    data class ExpressionList(val expressions: List<Expression>) : Expression() {
        override fun type(): TypeSig = expressions.lastOrNull()?.type() ?: VoidType

        override fun compile(
            builder: ClassBuilder.MethodDSL.DSL,
            returnValue: Boolean,
        ) {
            expressions.forEach { expression -> expression.compile(builder, returnValue) }
        }
    }

    data class FieldInvocation(val method: JFMethod, val field: JFField, val arguments: List<Expression>) : Expression() {
        override fun compile(
            builder: ClassBuilder.MethodDSL.DSL,
            returnValue: Boolean,
        ) {
            if (field.path == ThisType.path) { // TODO: check implementation
                val methodClassName = method.parent.path
                val methodSignature = "(${method.parameters.map(JFVariableSymbol::signature).joinToString("")})${method.rtn.signature}"
                with(builder) {
                    aload("this")
                    loadArguments(builder, arguments, method.parameters.map(JFVariableSymbol::type))
                    invokeVirtual(methodClassName, method.name, methodSignature)
                }
            } else {
                TODO()
            }
        }

        override fun type(): TypeSig {
            return method.rtn
        }
    }

    data class StaticFieldInvocation(val method: JFMethod, val field: JFField, val arguments: List<Expression>) : Expression() {
        override fun compile(
            builder: ClassBuilder.MethodDSL.DSL,
            returnValue: Boolean,
        ) {
            val fieldClassName = field.parent.path
            val fieldTypeSig = field.signature
            val methodClassName = method.parent.path
            val methodSignature = "(${method.parameters.map(JFVariableSymbol::signature).joinToString("")})${method.rtn.signature}"
            with(builder) {
                getStatic(fieldClassName, field.name, fieldTypeSig)
                loadArguments(builder, arguments, method.parameters.map(JFVariableSymbol::type))
                invokeVirtual(methodClassName, method.name, methodSignature)
            }
        }

        override fun type(): TypeSig {
            return method.rtn
        }
    }

    data class StaticInvocation(val method: JFMethod, val arguments: List<Expression>) : Expression() {
        override fun compile(
            builder: ClassBuilder.MethodDSL.DSL,
            returnValue: Boolean,
        ) {
            val methodClassName = method.parent.path
            val methodSignature = "(${method.parameters.map(JFVariableSymbol::signature).joinToString("")})${method.rtn.signature}"
            with(builder) {
                loadArguments(builder, arguments, method.parameters.map(JFVariableSymbol::type))
                invokeStatic(methodClassName, method.name, methodSignature)
            }
        }

        override fun type(): TypeSig {
            return method.rtn
        }
    }

    data class When(val input: Expression?, val matches: List<Pair<Expression, Expression>>) : Expression() {
        override fun type(): TypeSig = matches.last().second.type() // TODO: find common type
    }

    fun ClassBuilder.MethodDSL.DSL.loadArguments(
        builder: ClassBuilder.MethodDSL.DSL,
        arguments: List<Expression>,
        parameters: List<TypeSig>,
    ) {
        arguments.zip(parameters).forEach { (argument, parameter) ->
            if (argument.type() == parameter) {
                compileAsExpression(argument, builder)
            } else {
                if (((argument.type() is JFClass) || (argument.type() is ClassType)) &&
                    ((parameter is JFClass) || (parameter is ClassType))
                ) {
                    compileAsExpression(argument, builder)
                } else if ((argument.type() == IntegerType) && ((parameter is JFClass) || (parameter is ClassType))) {
                    compileAsExpression(argument, builder)
                    invokeStatic("java/lang/Integer", "valueOf", "(I)Ljava/lang/Integer;")
                } else if ((argument.type() == BooleanType) && ((parameter is JFClass) || (parameter is ClassType))) {
                    compileAsExpression(argument, builder)
                    invokeStatic("java/lang/Boolean", "valueOf", "(Z)Ljava/lang/Boolean;")
                } else {
                    TODO()
                }
            }
        }
    }

    data class ValAssignment(val variableSymbol: JFVariableSymbol, val expression: Expression) : Expression() {
        override fun type() = expression.type()

        override fun compile(
            builder: ClassBuilder.MethodDSL.DSL,
            returnValue: Boolean,
        ) {
            with(builder) {
                expression.compile(builder, returnValue)
                if (returnValue) dup()
                when (variableSymbol.type) {
                    is JFClass -> astore("${variableSymbol.symbolMap.symbolMapId}.${variableSymbol.name}")
                    is ClassType -> astore("${variableSymbol.symbolMap.symbolMapId}.${variableSymbol.name}")
                    is IntegerType -> istore("${variableSymbol.symbolMap.symbolMapId}.${variableSymbol.name}")
                    is BooleanType -> istore("${variableSymbol.symbolMap.symbolMapId}.${variableSymbol.name}")
                    else -> TODO("Need to implement types")
                }
            }
        }
    }

    data class Variable(val variableSymbol: JFVariableSymbol) : Expression() {
        override fun compile(
            builder: ClassBuilder.MethodDSL.DSL,
            returnValue: Boolean,
        ) {
            with(builder) {
                when (variableSymbol.type) {
                    is JFClass -> aload("${variableSymbol.symbolMap.symbolMapId}.${variableSymbol.name}")
                    is ClassType -> aload("${variableSymbol.symbolMap.symbolMapId}.${variableSymbol.name}")
                    is IntegerType -> iload("${variableSymbol.symbolMap.symbolMapId}.${variableSymbol.name}")
                    is BooleanType -> iload("${variableSymbol.symbolMap.symbolMapId}.${variableSymbol.name}")
                    else -> TODO("Need to implement types")
                }
            }
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
