package nl.w8mr.jafun

import nl.w8mr.kasmine.ClassBuilder
import nl.w8mr.kasmine.Instruction
import nl.w8mr.kasmine.classBuilder

class JVMBackend {
    class Context(val method: ClassBuilder.MethodDSL.DSL) {
        fun compile(
            instruction: IR.Instruction,
            index: Int,
            codeBlocks: MutableList<IRBuilder.CodeBlock>,
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
                            "(${instruction.method.parameters.map { signature(it.type) }.joinToString("")})" +
                                    "${signature(instruction.method.rtn)}"
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
                        is IR.Array -> TODO()
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
                        is IR.Array -> TODO()
                        is IR.JFClass -> areturn()
                        is IR.JFMethod -> TODO()
                        is IR.JFVariableSymbol -> TODO()
                    }

                is IR.GetStatic -> getStatic(instruction.className, instruction.fieldName, signature(instruction.type))
                is IR.IfFalse -> ifequal(calculateJump(codeBlocks, index, instruction.block))
                is IR.Goto -> goto(calculateJump(codeBlocks, index, instruction.block))
                is IR.When -> {
                    val after = createTarget()
                    instruction.cases.forEach { case ->
                        when (case) {
                            is IR.When.WhenConditionCase -> {
                                case.condition.forEach { compile(it, index, codeBlocks) }
                                val next = createTarget()
                                ifequal(next)
                                case.execution.forEach { compile(it, index, codeBlocks) }
                                goto(after)
                                insertCodeblock(next)
                            }
                            is IR.When.WhenElseCase -> {
                                case.execution.forEach { compile(it, index, codeBlocks) }
                            }
                        }
                    }
                    insertCodeblock(after)
                    
                }
            }
        }

        private fun calculateJump(
            codeBlocks: MutableList<IRBuilder.CodeBlock>,
            index: Int,
            block: IRBuilder.CodeBlock,
        ): Short = (((index + 1)..(codeBlocks.indexOf(block) - 1)).sumOf { codeBlocks[it].byteSize } + 3).toShort()
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
            signature = "(${m.parameterTypes.map(::signature).joinToString(separator = "")})" +
                    "${signature(m.returnType)}"
            m.codeBlocks.forEachIndexed { index, codeBlock ->
                val context = JVMBackend.Context(this)
                codeBlock.instructions.forEach { context.compile(it, index, m.codeBlocks) }
            }
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
        is IR.UInt1 -> "Z"
        is IR.JFMethod -> TODO()
        is IR.JFClass -> "L${type.path.replace('.', '/')};"
        is IR.JFVariableSymbol -> TODO()
    }

fun byteSize(instruction: IR.Instruction) =
    when (instruction) {
        is IR.Dup -> 1
        is IR.GetStatic -> 3
        is IR.Goto -> 3
        is IR.IfFalse -> 3
        is IR.Invoke -> 3
        is IR.Load<*> -> 2
        is IR.LoadConstant<*, *> ->
            when (instruction.type) {
                is IR.StringType -> 3
                is IR.SInt32 ->
                    when (instruction.type.operand1(instruction) as Int) {
                        in -1..5 -> 1
                        in -128..-2 -> 2
                        in 6..127 -> 2
                        else -> 3
                    }
                is IR.UInt1 -> 1
                else -> TODO()
            }
        is IR.Pop -> 1
        is IR.Return<*> -> 1
        is IR.Store<*> -> 2
    }
