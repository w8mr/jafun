package nl.w8mr.jafun

import nl.w8mr.jafun.compiler.replaceIllegalCharacters
import nl.w8mr.kasmine.ClassBuilder
import nl.w8mr.kasmine.classBuilder

class JVMBackend {
    class Context(val method: ClassBuilder.MethodDSL.DSL) {
        fun compile(
            instruction: IR.Instruction,
        ): Unit = with(method) {
            when (instruction) {
                is IR.LoadConstant<*> ->
                    when (instruction.type) {
                        is Type.StringType -> loadConstant(instruction.type.operand1(instruction))
                        is Type.SInt32 -> loadConstant(instruction.type.operand1(instruction))
                        is Type.UInt1 ->
                            loadConstant(
                                when (instruction.type.operand1(instruction)) {
                                    false -> 0
                                    true -> 1
                                },
                            )
                        is Type.CharType -> loadConstant(instruction.type.operand1(instruction).code)

                        is Type.Reference -> TODO()
                        is Type.Array -> TODO()
                        is Type.JFClass -> TODO()
                        is Type.JFMethod -> TODO()
                        is Type.JFVariableSymbol -> TODO()
                        is Type.JFField -> TODO()
                        is Type.JFPackage -> TODO()
                        is Type.Generic -> TODO()
                    }
                is IR.Invoke -> {
                    with(method) {
                        val methodClassName = instruction.method.parent.path
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
                        is Type.Reference -> astore(instruction.registerName)
                        is Type.SInt32 -> istore(instruction.registerName)
                        is Type.StringType -> astore(instruction.registerName)
                        is Type.UInt1 -> istore(instruction.registerName)
                        is Type.CharType -> istore(instruction.registerName)
                        is Type.Array -> TODO()
                        is Type.JFClass -> astore(instruction.registerName)
                        is Type.JFMethod -> TODO()
                        is Type.JFVariableSymbol -> TODO()
                        is Type.JFField -> TODO()
                        is Type.JFPackage -> TODO()
                        is Type.Generic -> TODO()
                    }
                is IR.Load<*> ->
                    when (instruction.type) {
                        is Type.Reference -> aload(instruction.registerName)
                        is Type.SInt32 -> iload(instruction.registerName)
                        is Type.StringType -> aload(instruction.registerName)
                        is Type.UInt1 -> iload(instruction.registerName)
                        is Type.CharType -> iload(instruction.registerName)
                        is Type.Array -> aload(instruction.registerName)
                        is Type.JFClass -> aload(instruction.registerName)
                        is Type.JFMethod -> TODO()
                        is Type.JFVariableSymbol -> TODO()
                        is Type.JFField -> TODO()
                        is Type.JFPackage -> TODO()
                        is Type.Generic -> TODO()
                    }
                is IR.Return<*> ->
                    when (instruction.type) {
                        is Type.Unit -> `return`() // TODO: check how to handle unit.
                        is Type.Reference -> areturn()
                        is Type.SInt32 -> ireturn()
                        is Type.StringType -> areturn()
                        is Type.UInt1 -> ireturn()
                        is Type.CharType -> ireturn()
                        is Type.Array -> TODO()
                        is Type.JFClass -> areturn()
                        is Type.JFMethod -> TODO()
                        is Type.JFVariableSymbol -> TODO()
                        is Type.JFField -> TODO()
                        is Type.JFPackage -> TODO()
                        is Type.Generic -> TODO()
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

fun signature(type: Type.OperandType<*>): String =
    when (type) {
        is Type.Array -> "[${signature(type.genericTypes[0])}"
        is Type.Unit -> "V"
        is Type.StringType -> "Ljava/lang/String;"
        is Type.Reference -> "L${type.type.replace('.', '/')};"
        is Type.SInt32 -> "I"
        is Type.CharType -> "C"
        is Type.UInt1 -> "Z"
        is Type.JFMethod -> TODO()
        is Type.JFClass -> "L${type.path.replace('.', '/')};" //TODO: check if this needs to bee JFObject?
        is Type.JFVariableSymbol -> TODO()
        is Type.JFField -> TODO()
        is Type.JFPackage -> TODO()
        is Type.Generic -> TODO()
    }