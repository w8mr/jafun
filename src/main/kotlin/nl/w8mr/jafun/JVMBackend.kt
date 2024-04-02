package nl.w8mr.jafun

import jafun.compiler.JFVariableSymbol
import nl.w8mr.kasmine.ClassBuilder

class JVMBackend {
    class Context(val method: ClassBuilder.MethodDSL.DSL) {
        fun compile(instruction: IR.Instruction) =
            when (instruction) {
                is IR.LoadConstant<*, *> ->
                    when (instruction.type) {
                        is IR.StringType -> method.loadConstant(instruction.type.operand1(instruction))
                        is IR.SInt32 -> method.loadConstant(instruction.type.operand1(instruction))
                        is IR.UInt1 ->
                            method.loadConstant(
                                when (instruction.type.operand1(instruction)) {
                                    false -> 0
                                    true -> 1
                                },
                            )

                        is IR.Reference -> TODO()
                        is IR.Array -> TODO()
                    }
                is IR.Invoke -> {
                    with(method) {
                        val methodClassName = instruction.method.parent.path
                        val methodSignature = "(${instruction.method.parameters.map(
                            JFVariableSymbol::signature,
                        ).joinToString("")})${instruction.method.rtn.signature}"
                        when (instruction.field) {
                            null -> invokeStatic(methodClassName, instruction.method.name, methodSignature)
                            else -> invokeVirtual(methodClassName, instruction.method.name, methodSignature)
                        }
                    }
                }
                is IR.Pop -> method.pop()
                is IR.Dup -> method.dup()
                is IR.Store<*> ->
                    when (instruction.type) {
                        is IR.Reference -> method.astore(instruction.registerName)
                        is IR.SInt32 -> method.istore(instruction.registerName)
                        is IR.StringType -> method.astore(instruction.registerName)
                        is IR.UInt1 -> method.istore(instruction.registerName)
                        is IR.Array -> TODO()
                    }
                is IR.Load<*> ->
                    when (instruction.type) {
                        is IR.Reference -> method.aload(instruction.registerName)
                        is IR.SInt32 -> method.iload(instruction.registerName)
                        is IR.StringType -> method.aload(instruction.registerName)
                        is IR.UInt1 -> method.iload(instruction.registerName)
                        is IR.Array -> TODO()
                    }
                is IR.Return<*> ->
                    when (instruction.type) {
                        is IR.Unit -> method.`return`() // TODO: check how to handle unit.
                        is IR.Reference -> method.areturn()
                        is IR.SInt32 -> method.ireturn()
                        is IR.StringType -> method.ireturn()
                        is IR.UInt1 -> method.ireturn()
                        is IR.Array -> TODO()
                    }

                is IR.GetStatic -> method.getStatic(instruction.className, instruction.fieldName, instruction.type)
            }
    }
}
