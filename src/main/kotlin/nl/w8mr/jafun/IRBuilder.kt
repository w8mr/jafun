package nl.w8mr.jafun

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
        val returnType: Type.OperandType<*>,
        val parameterTypes: List<Type.OperandType<*>>,
        val instructions: MutableList<IR.Instruction> = mutableListOf(),
        val parent: ClassContext,
    )

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
            returnType: Type.OperandType<*>,
            parameterTypes: List<Type.OperandType<*>>,
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

    class CodeBlockDSL(val instructions: MutableList<IR.Instruction> = mutableListOf(), val parent: MethodDSL) {

        fun <J> loadConstant(
            operand1: J,
            type: Type.OperandType<J>,
        ) {
            instructions.add(IR.LoadConstant(operand1, type))
        }

        fun <J> store(
            registerName: String,
            type: Type.OperandType<J>,
        ) {
            instructions.add(IR.Store(registerName, type))
        }

        fun <J> load(
            registerName: String,
            type: Type.OperandType<J>,
        ) {
            instructions.add(IR.Load(registerName, type))
        }

        fun invoke(
            method: Type.JFMethod,
            field: Type.JFField?,
        ) {
            instructions.add(IR.Invoke(method, field))
        }

        fun getStatic(
            className: String,
            fieldName: String,
            type: Type.Reference<*>,
        ) {
            instructions.add(IR.GetStatic(className, fieldName, type))
        }

        fun pop() {
            instructions.add(IR.Pop)
        }

        fun dup() {
            instructions.add(IR.Dup)
        }

        @Suppress("ktlint:standard:function-naming")
        fun <J> `return`(type: Type.OperandType<J>) {
            instructions.add(IR.Return(type))
        }

        fun `when`(matches: List<Pair<List<IR.Instruction>, List<IR.Instruction>>>, elseBlock: List<IR.Instruction>?) {
            instructions.add(IR.When(matches.map { IR.When.WhenConditionCase(it.first, it.second) } + (elseBlock?.let { listOf(IR.When.WhenElseCase(it)) } ?: emptyList())))
        }

        fun `doWhile`(condition: List<IR.Instruction>, expression: List<IR.Instruction>) {
            instructions.add(IR.DoWhile(condition, expression))
        }

        fun `while`(condition: List<IR.Instruction>, expression: List<IR.Instruction>) {
            instructions.add(IR.While(condition, expression))
        }

    }
}
