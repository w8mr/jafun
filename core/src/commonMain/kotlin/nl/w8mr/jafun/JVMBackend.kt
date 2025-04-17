package nl.w8mr.jafun

import nl.w8mr.jafun.compiler.replaceIllegalCharacters
import nl.w8mr.kasmine.ClassBuilder
import nl.w8mr.kasmine.classBuilder

class JVMBackend {
    class Context(val method: ClassBuilder.MethodDSL.DSL) {
        fun compile(instruction: IR.Instruction): Unit =
            with(method) {
                when (instruction) {
                    is IR.LoadConstant<*> ->
                        when (instruction.type) {
                            is OperandType.StringType -> loadConstant(instruction.type.operand1(instruction))
                            is OperandType.SInt32 -> loadConstant(instruction.type.operand1(instruction))
                            is OperandType.UInt1 ->
                                loadConstant(
                                    when (instruction.type.operand1(instruction)) {
                                        false -> 0
                                        true -> 1
                                    },
                                )
                            is OperandType.CharType -> loadConstant(instruction.type.operand1(instruction).code)

                            is OperandType.Reference -> TODO()
                            is OperandType.Array -> TODO()
                            is OperandType.Generic -> TODO()
                            is Type.JFClass -> TODO()
                        }
                    is IR.Invoke -> {
                        with(method) {
                            val methodClassName = instruction.method.parentPath.replace('.', '/')
                            val methodSignature =
                                "(${instruction.method.parameters.joinToString("") { signature(it.type) }})" +
                                    signature(instruction.method.rtn)
                            when (instruction.field) {
                                null -> invokeStatic(methodClassName, instruction.method.name.replaceIllegalCharacters(), methodSignature)
                                else -> invokeVirtual(methodClassName, instruction.method.name.replaceIllegalCharacters(), methodSignature)
                            }
                        }
                    }
                    is IR.Pop -> pop()
                    is IR.Dup -> dup()
                    is IR.Store<*> ->
                        when (instruction.type) {
                            is OperandType.Reference -> astore(instruction.registerName)
                            is OperandType.SInt32 -> istore(instruction.registerName)
                            is OperandType.StringType -> astore(instruction.registerName)
                            is OperandType.UInt1 -> istore(instruction.registerName)
                            is OperandType.CharType -> istore(instruction.registerName)
                            is OperandType.Array -> TODO()
                            is OperandType.Generic -> TODO()
                            is Type.JFClass -> astore(instruction.registerName)
                        }
                    is IR.Load<*> ->
                        when (instruction.type) {
                            is OperandType.Reference -> aload(instruction.registerName)
                            is OperandType.SInt32 -> iload(instruction.registerName)
                            is OperandType.StringType -> aload(instruction.registerName)
                            is OperandType.UInt1 -> iload(instruction.registerName)
                            is OperandType.CharType -> iload(instruction.registerName)
                            is OperandType.Array -> aload(instruction.registerName)
                            is OperandType.Generic -> TODO()
                            is Type.JFClass -> aload(instruction.registerName)
                        }
                    is IR.Return<*> ->
                        when (instruction.type) {
                            is OperandType.Unit -> `return`() // TODO: check how to handle unit.
                            is OperandType.Reference -> areturn()
                            is OperandType.SInt32 -> ireturn()
                            is OperandType.StringType -> areturn()
                            is OperandType.UInt1 -> ireturn()
                            is OperandType.CharType -> ireturn()
                            is OperandType.Array -> TODO()
                            is OperandType.Generic -> TODO()
                            is Type.JFClass -> areturn()
                        }

                    is IR.GetStatic ->
                        getStatic(
                            instruction.className.replace('.', '/'),
                            instruction.fieldName,
                            signature(instruction.type),
                        )
                    is IR.When -> {
                        val after = createTarget()
                        instruction.cases.forEach { case ->
                            when (case) {
                                is IR.When.WhenConditionCase -> {
                                    case.condition.forEach { compile(it) }
                                    val next = createTarget()
                                    ifequal(next)
                                    case.execution.forEach { compile(it) }
                                    goto(after)
                                    insertInstructionBlock(next)
                                }
                                is IR.When.WhenElseCase -> {
                                    case.execution.forEach { compile(it) }
                                }
                            }
                        }
                        insertInstructionBlock(after)
                    }
                    is IR.DoWhile -> {
                        val body = createTarget()
                        insertInstructionBlock(body)
                        instruction.expressions.forEach { compile(it) }
                        instruction.condition.forEach { compile(it) }
                        ifnotequal(body)
                    }

                    is IR.While -> {
                        val after = createTarget()
                        val body = createTarget()
                        insertInstructionBlock(body)
                        instruction.condition.forEach { compile(it) }
                        ifequal(after)
                        instruction.expressions.forEach { compile(it) }
                        goto(body)
                        insertInstructionBlock(after)
                    }
                }
            }
    }
}

fun compileJVM(
    className: String,
    builder: IRBuilder.BuilderContext,
): ByteArray {
    val clazz = buildClass(className, builder)

    return clazz.write()
}

fun buildClass(
    className: String,
    builder: IRBuilder.BuilderContext,
): ClassBuilder =
    classBuilder {
        name = className
        builder.classes[className]?.methods?.forEach { m ->
            method {
                name = m.name
                signature = "(${m.parameterTypes.joinToString("", transform = ::signature)})" +
                    signature(m.returnType)
                val context = JVMBackend.Context(this)
                m.instructions.forEach { context.compile(it) }
            }
        }
    }

fun signature(type: OperandType<*>): String =
    when (type) {
        is OperandType.Array -> "[${signature(type.genericTypes[0])}"
        is OperandType.Unit -> "V"
        is OperandType.StringType -> "Ljava/lang/String;"
        is OperandType.Reference -> "L${type.type.replace('.', '/')};"
        is OperandType.SInt32 -> "I"
        is OperandType.CharType -> "C"
        is OperandType.UInt1 -> "Z"
        is OperandType.Generic -> TODO()
        is Type.JFClass -> "L${type.path.replace('.', '/')};" // TODO: check if this needs to bee JFObject?
    }
