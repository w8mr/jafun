package nl.w8mr.jafun.compiler.ir2jvm

import nl.w8mr.jafun.OperandType
import nl.w8mr.jafun.Type
import nl.w8mr.jafun.compiler.ExpressionNode
import nl.w8mr.jafun.compiler.replaceIllegalCharacters
import nl.w8mr.kasmine.ClassBuilder
import nl.w8mr.kasmine.classBuilder

class JVMBackend {
    class Context(val method: ClassBuilder.MethodDSL.DSL) {
        fun compile(instruction: ExpressionNode.Phase2_3Expression, asStatement: Boolean = false): Unit =
            with(method) {
                when (instruction) {
                    is ExpressionNode.IntegerLiteral -> loadConstant(instruction.value)
                    is ExpressionNode.StringLiteral -> loadConstant(instruction.value)
                    is ExpressionNode.BooleanLiteral -> loadConstant(if (instruction.value) 1 else 0)
                    is ExpressionNode.CharLiteral -> loadConstant(instruction.value)
                    is ExpressionNode.StringTemplate -> {
                        when (instruction.expressions.size) {
                            0 -> {}
                            1 -> loadStringPart(instruction.expressions[0])
                            else -> {
                                new("java/lang/StringBuilder")
                                dup()
                                invokeSpecial("java/lang/StringBuilder", "<init>", "()V")
                                instruction.expressions.forEach {
                                    loadStringPart(it)
                                    invokeVirtual("java/lang/StringBuilder", "append", "(Ljava/lang/String;)Ljava/lang/StringBuilder;")
                                }
                                invokeVirtual("java/lang/StringBuilder", "toString", "()Ljava/lang/String;")
                            }
                        }
                    }

                    is ExpressionNode.Constructor -> {
                        val consSignature = "(${instruction.arguments.joinToString("")})V"
                        new(instruction.type().path.replace('.', '/'))
                        dup()
                        invokeSpecial(instruction.type().path.replace('.', '/'), "<init>", consSignature)
                    }
                    is ExpressionNode.Invocation -> {
                        when (instruction.field) {
                            is Type.JFField -> {
                                getStatic(
                                    instruction.field.parentPath.replace('.', '/'),
                                    instruction.field.name,
                                    signature(instruction.field.type ?: error("Type is null")),
                                )
                            }

                            is Type.JFVariableSymbol -> {
                                val variableName =
                                    "${instruction.field.symbolMap.symbolMapId}.${instruction.field.name}"
                                when (instruction.field.type) {
                                    is OperandType.SInt32 -> iload(variableName)
                                    is OperandType.StringType -> aload(variableName)
                                    is OperandType.UInt1 -> iload(variableName)
                                    is OperandType.CharType -> iload(variableName)
                                    is OperandType.Array -> aload(variableName)
                                    is OperandType.Generic -> TODO()
                                    is OperandType.Unit -> TODO()
                                    is Type.JFClass -> aload(variableName)
                                }

                            }

                            else -> {}
                        }

                        instruction.arguments.forEach { compile(it) }
                        with(method) {
                            val methodClassName = instruction.method.parentPath.replace('.', '/')
                            val methodSignature =
                                "(${instruction.method.parameters.joinToString("") { signature(it.type) }})" +
                                        signature(instruction.method.rtn)
                            when (instruction.field) {
                                null -> invokeStatic(
                                    methodClassName,
                                    instruction.method.name.replaceIllegalCharacters(),
                                    methodSignature
                                )

                                else -> invokeVirtual(
                                    methodClassName,
                                    instruction.method.name.replaceIllegalCharacters(),
                                    methodSignature
                                )
                            }
                        }
                        if ((asStatement) && (instruction.method.rtn != OperandType.Unit)) pop()
                    }

                    is ExpressionNode.ValAssignment -> {
                        compile(instruction.expression)
                        if (!asStatement) dup()
                        storeVariable(instruction)
                    }

                    is ExpressionNode.VarAssignment -> {
                        compile(instruction.expression)
                        if (!asStatement) dup()
                        storeVariable(instruction)
                    }

                    is ExpressionNode.Variable -> {
                        loadVariable(instruction)
                    }

                    is ExpressionNode.WhenPhase3 -> {
                        val after = createTarget()
                        instruction.matches.forEach { match ->
                            when (match.first) {
                                ExpressionNode.BooleanLiteral(true) -> {
                                    compile(match.second, asStatement)
                                }

                                else -> {
                                    compile(match.first)
                                    val next = createTarget()
                                    ifequal(next)
                                    compile(match.second, asStatement)
                                    goto(after)
                                    insertInstructionBlock(next)
                                }
                            }
                        }
                        insertInstructionBlock(after)
                    }

                    is ExpressionNode.ExpressionList -> {

                        val lastIndex = instruction.expressions.size - 1
                        instruction.expressions.forEachIndexed { index, it ->
                            compile(it, asStatement || (index != lastIndex))
                        }

                    }

                    is ExpressionNode.DoWhile -> {
                        val body = createTarget()
                        insertInstructionBlock(body)
                        compile(instruction.expressions, false) // TODO: evaluate: Should DoWhile be an expression?
                        compile(instruction.condition)
                        ifnotequal(body)
                    }

                    is ExpressionNode.WhilePhase3 -> {
                        val after = createTarget()
                        val body = createTarget()
                        insertInstructionBlock(body)
                        compile(instruction.condition)
                        ifequal(after)
                        compile(instruction.expressions, true)
                        goto(body)
                        insertInstructionBlock(after)
                    }

                    is ExpressionNode.Convert -> {
                        compile(instruction.expression)
                        conversion(instruction.from, instruction.to)
                    }

                    else -> TODO()
                }
            }

        private fun ClassBuilder.MethodDSL.DSL.storeVariable(instruction: ExpressionNode.Assignment) {
            val variableName =
                "${instruction.variableSymbol.symbolMap.symbolMapId}.${instruction.variableSymbol.name}"
            when (instruction.expression.type()) {
                is OperandType.SInt32 -> istore(variableName)
                is OperandType.StringType -> astore(variableName)
                is OperandType.UInt1 -> istore(variableName)
                is OperandType.CharType -> istore(variableName)
                is OperandType.Array -> astore(variableName)
                is OperandType.Generic -> TODO()
                is OperandType.Unit -> TODO()
                is Type.JFClass -> astore(variableName)
            }
        }

        private fun ClassBuilder.MethodDSL.DSL.conversion(
            from: OperandType<*>,
            to: OperandType<*>
        ) {
            when (from) {
                is OperandType.SInt32 -> when (to) {
                    is OperandType.StringType -> invokeStatic("java/lang/String", "valueOf", "(I)Ljava/lang/String;")
                    is Type.JFClass -> invokeStatic("java/lang/Integer", "valueOf", "(I)Ljava/lang/Integer;")
                    else -> TODO("Conversion not defined for SInt32 -> $to")
                }
                is OperandType.UInt1 -> when (to) {
                    is Type.JFClass -> invokeStatic("java/lang/Boolean", "valueOf", "(Z)Ljava/lang/Boolean;")
                    else -> TODO("Conversion not defined for UInt1 -> $to")
                }
                is OperandType.CharType -> when (to) {
                    is Type.JFClass -> invokeStatic("java/lang/Character", "valueOf", "(C)Ljava/lang/Character;")
                    else -> TODO("Conversion not defined for CharType -> ${to}")
                }
                is Type.JFClass -> when (to) {
                    is Type.JFClass -> when {
                        to.path == "java.lang.Object" -> {}
                        from.path == to.path -> {}
                        else -> TODO("Conversion not defined for ${from.path} -> ${to.path}")
                    }
                    else -> TODO("Conversion not defined for ${from.path} -> ${to}")
                }
                else -> TODO("Conversion not defined for ${from} -> ${to}")
            }
        }

        fun ClassBuilder.MethodDSL.DSL.loadVariable(instruction: ExpressionNode.Variable) {
            val variableName = "${instruction.variableSymbol.symbolMap.symbolMapId}.${instruction.variableSymbol.name}"
            when (instruction.variableSymbol.type) {
                is OperandType.SInt32 -> iload(variableName)
                is OperandType.StringType -> aload(variableName)
                is OperandType.UInt1 -> iload(variableName)
                is OperandType.CharType -> iload(variableName)
                is OperandType.Array -> aload(variableName)
                is OperandType.Generic -> TODO()
                is OperandType.Unit -> TODO()
                is Type.JFClass -> aload(variableName)
            }
        }

        fun ClassBuilder.MethodDSL.DSL.loadStringPart(expression: ExpressionNode.Phase2Expression) {
            when (expression) {
                is ExpressionNode.StringLiteral -> loadConstant(expression.value)
                else -> {
                    compile(expression)
                    conversion(expression.type(), OperandType.StringType)
                }
            }
        }
    }
}

fun compileJVM(
    className: String,
    builder: IRBuilder.BuilderContext,
): ByteArray {
    val clazz = buildClass(className, builder.classes[className] ?: error("Class not found: $className"))

    return clazz.write()
}

fun buildClass(
    className: String,
    classContext: IRBuilder.ClassContext,
): ClassBuilder =
    classBuilder {
        name = className
        classContext.methods.forEach { m ->
            method {
                name = m.name
                signature = "(${m.parameterTypes.joinToString("", transform = ::signature)})" +
                    signature(m.returnType)
                val context = JVMBackend.Context(this)
                val lastIndex = m.instructions.size - 1
                m.instructions.forEachIndexed { index, instruction ->
                    context.compile(instruction, asStatement = (index != lastIndex))
                }
                when (m.returnType) {
                    is OperandType.Unit -> {
                        if ((m.instructions.lastOrNull()?.type()?: OperandType.Unit) != m.returnType) pop()
                        `return`()
                    }
                    is OperandType.SInt32, is OperandType.UInt1, is OperandType.CharType ->
                        if (m.instructions.last().type()==m.returnType) ireturn() else error("Type issue")
                    is OperandType.StringType ->
                        if (m.instructions.last().type()==m.returnType) areturn() else error("Type issue")
                    else -> TODO()
                }
            }
        }
    }

fun signature(type: OperandType<*>): String =
    when (type) {
        is OperandType.Array -> "[${signature(type.genericTypes[0])}"
        is OperandType.Unit -> "V"
        is OperandType.StringType -> "Ljava/lang/String;"
        is OperandType.SInt32 -> "I"
        is OperandType.CharType -> "C"
        is OperandType.UInt1 -> "Z"
        is OperandType.Generic -> TODO()
        is Type.JFClass -> "L${type.path.replace('.', '/')};" // TODO: check if this needs to bee JFObject?
    }
