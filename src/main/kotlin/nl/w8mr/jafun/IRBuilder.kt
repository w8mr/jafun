package nl.w8mr.jafun

import jafun.compiler.JFField
import jafun.compiler.JFMethod

object IRBuilder {
    fun define(init: BuilderDSL.() -> Unit): BuilderContext {
        val builderContext = BuilderContext()
        init.invoke(BuilderDSL(builderContext))
        return builderContext
    }

    data class BuilderContext(val classes: MutableMap<String, ClassContext> = mutableMapOf())

    data class ClassContext(val name: String, val methods: MutableList<MethodContext> = mutableListOf(), val parent: BuilderContext)

    data class MethodContext(
        val name: String,
        val returnType: IR.OperandType<*>,
        val parameterTypes: List<IR.OperandType<*>>,
        val codeBlocks: MutableList<CodeBlock> = mutableListOf(),
        val parent: ClassContext,
    )

    data class CodeBlock(val instructions: MutableList<IR.Instruction> = mutableListOf(), val parent: MethodContext)

    class BuilderDSL(val context: BuilderContext) {
        fun `class`(
            name: String,
            init: ClassDSL.() -> Unit,
        ) {
            val classContext = ClassContext(name, parent = context)
            context.classes.put(name, classContext)
            init.invoke(ClassDSL(classContext, this))
        }
    }

    class ClassDSL(val context: ClassContext, val parent: BuilderDSL) {
        fun method(
            name: String,
            returnType: IR.OperandType<*>,
            parameterTypes: List<IR.OperandType<*>>,
            init: MethodDSL.() -> Unit,
        ) {
            val methodContext = MethodContext(name, returnType, parameterTypes, parent = context)
            context.methods.add(methodContext)
            init.invoke(MethodDSL(methodContext, this))
        }
    }

    class MethodDSL(val context: MethodContext, val parent: ClassDSL) {
        fun codeBlock(init: CodeBlockDSL.() -> Unit) {
            val codeBlock = CodeBlock(parent = context)
            context.codeBlocks.add(codeBlock)
            init.invoke(CodeBlockDSL(codeBlock, this))
        }
    }

    class CodeBlockDSL(val context: CodeBlock, val parent: MethodDSL) {
        fun <J> loadConstant(
            operand1: J,
            type: IR.OperandType<J>,
        ) {
            context.instructions.add(IR.LoadConstant(operand1, type))
        }

        fun <J> store(
            registerName: String,
            type: IR.OperandType<J>,
        ) {
            context.instructions.add(IR.Store(registerName, type))
        }

        fun <J> load(
            registerName: String,
            type: IR.OperandType<J>,
        ) {
            context.instructions.add(IR.Load(registerName, type))
        }

        fun invoke(
            method: JFMethod,
            field: JFField?,
        ) {
            context.instructions.add(IR.Invoke(method, field))
        }

        fun getStatic(
            className: String,
            fieldName: String,
            type: String,
        ) {
            context.instructions.add(IR.GetStatic(className, fieldName, type))
        }

        fun pop() {
            context.instructions.add(IR.Pop)
        }

        fun dup() {
            context.instructions.add(IR.Dup)
        }

        fun <J> `return`(type: IR.OperandType<J>) {
            context.instructions.add(IR.Return(type))
        }
    }
}