package nl.w8mr.jafun

import nl.w8mr.kasmine.ClassBuilder
import nl.w8mr.kasmine.classBuilder

class JVMBackend {
    class Context(val method: ClassBuilder.MethodDSL.DSL) {
        fun compile(
            instruction: IR.Instruction,
        ): Unit = with(method) {
            when (instruction) {
                is IR.LoadConstant<*, *> ->
                    when (instruction.type) {
                        is IR.StringType -> loadConstant(instruction.type.operand1(instruction))
                        is IR.SInt32 -> loadConstant(instruction.type.operand1(instruction))
                        is IR.UInt1 ->
                            loadConstant(
                                when (instruction.type.operand1(instruction)) {
                                    false -> 0
                                    true -> 1
                                },
                            )
                        is IR.CharType -> loadConstant(instruction.type.operand1(instruction).toInt())

                        is IR.Reference -> TODO()
                        is IR.Array -> TODO()
                        is IR.JFClass -> TODO()
                        is IR.JFMethod -> TODO()
                        is IR.JFVariableSymbol -> TODO()
                    }
                is IR.Invoke -> {
                    with(method) {
                        val methodClassName = instruction.method.parent.path
                        val methodSignature =
                            "(${instruction.method.parameters.joinToString("") { signature(it.type) }})" +
                                    signature(instruction.method.rtn)
                        when (instruction.field) {
                            null -> invokeStatic(methodClassName, instruction.method.name, methodSignature)
                            else -> invokeVirtual(methodClassName, instruction.method.name, methodSignature)
                        }
                    }
                }
                is IR.Pop -> pop()
                is IR.Dup -> dup()
                is IR.Store<*> ->
                    when (instruction.type) {
                        is IR.Reference -> astore(instruction.registerName)
                        is IR.SInt32 -> istore(instruction.registerName)
                        is IR.StringType -> astore(instruction.registerName)
                        is IR.UInt1 -> istore(instruction.registerName)
                        is IR.CharType -> istore(instruction.registerName)
                        is IR.Array -> TODO()
                        is IR.JFClass -> astore(instruction.registerName)
                        is IR.JFMethod -> TODO()
                        is IR.JFVariableSymbol -> TODO()
                    }
                is IR.Load<*> ->
                    when (instruction.type) {
                        is IR.Reference -> aload(instruction.registerName)
                        is IR.SInt32 -> iload(instruction.registerName)
                        is IR.StringType -> aload(instruction.registerName)
                        is IR.UInt1 -> iload(instruction.registerName)
                        is IR.CharType -> iload(instruction.registerName)
                        is IR.Array -> aload(instruction.registerName)
                        is IR.JFClass -> aload(instruction.registerName)
                        is IR.JFMethod -> TODO()
                        is IR.JFVariableSymbol -> TODO()
                    }
                is IR.Return<*> ->
                    when (instruction.type) {
                        is IR.Unit -> `return`() // TODO: check how to handle unit.
                        is IR.Reference -> areturn()
                        is IR.SInt32 -> ireturn()
                        is IR.StringType -> areturn()
                        is IR.UInt1 -> ireturn()
                        is IR.CharType -> ireturn()
                        is IR.Array -> TODO()
                        is IR.JFClass -> areturn()
                        is IR.JFMethod -> TODO()
                        is IR.JFVariableSymbol -> TODO()
                    }

                is IR.GetStatic -> getStatic(instruction.className, instruction.fieldName, signature(instruction.type))
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
    builder: IRBuilder.BuilderContext
): ClassBuilder = classBuilder {
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

fun signature(type: IR.OperandType<*>): String =
    when (type) {
        is IR.Array -> "[${signature(type.type)}"
        is IR.Unit -> "V"
        is IR.StringType -> "Ljava/lang/String;"
        is IR.Reference -> "L${type.type.replace('.', '/')};"
        is IR.SInt32 -> "I"
        is IR.CharType -> "C"
        is IR.UInt1 -> "Z"
        is IR.JFMethod -> TODO()
        is IR.JFClass -> "L${type.path.replace('.', '/')};"
        is IR.JFVariableSymbol -> TODO()
    }