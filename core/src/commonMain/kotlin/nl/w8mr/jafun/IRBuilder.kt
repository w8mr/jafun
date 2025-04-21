package nl.w8mr.jafun

import nl.w8mr.jafun.compiler.ExpressionNode
import nl.w8mr.jafun.debug.Printable

object IRBuilder {
    fun define(init: BuilderDSL.() -> Unit): BuilderContext {
        val builderContext = BuilderContext()
        init.invoke(BuilderDSL(builderContext))
        return builderContext
    }

    data class BuilderContext(val classes: MutableMap<String, ClassContext> = mutableMapOf())

    data class ClassContext(val name: String, val methods: MutableList<MethodContext> = mutableListOf(), val parent: BuilderContext): Printable{
        override fun toString(): String =
            "ClassContext(name=$name, methods=$methods)"
    }

    data class MethodContext(
        val name: String,
        val returnType: OperandType<*>,
        val parameterTypes: List<OperandType<*>>,
        val instructions: MutableList<ExpressionNode.Phase2_3Expression> = mutableListOf(),
        val parent: ClassContext,
    ): Printable {
        override fun toString(): String =
            "MethodContext(name=$name, returnType=$returnType, parameterType=$parameterTypes, instructions=$instructions"
    }

    class BuilderDSL(val context: BuilderContext) {
        @Suppress("ktlint:standard:function-naming")
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
            returnType: OperandType<*>,
            parameterTypes: List<OperandType<*>>,
            init: MethodDSL.() -> Unit,
        ) {
            val methodContext = MethodContext(name, returnType, parameterTypes, parent = context)
            context.methods.add(methodContext)
            init.invoke(MethodDSL(methodContext, this))
        }
    }

    class MethodDSL(val context: MethodContext, val parent: ClassDSL) {
        fun codeBlock(init: CodeBlockDSL.() -> Unit) {
            init.invoke(CodeBlockDSL(context.instructions, this))
        }
    }

    class CodeBlockDSL(val instructions: MutableList<ExpressionNode.Phase2_3Expression> = mutableListOf(), val parent: MethodDSL) {
        fun add(node: ExpressionNode.Phase2_3Expression) {
            instructions.add(node)
        }
    }
}
