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

                    is ExpressionNode.ConstructorInvocation -> {
                        val classType = instruction.type()
                        val internalName = classType.path.replace('.', '/')
                        val consSignature =
                            "(${instruction.cons.parameters.joinToString("") { signature(it.type) }})V"
                        new(internalName)
                        dup()
                        instruction.arguments.forEach { compile(it) }
                        invokeSpecial(internalName, "<init>", consSignature)
                    }
                    is ExpressionNode.MethodInvocation -> {
                        when (instruction.field) {
                            is Type.JFField -> {
                                getStatic(
                                    instruction.field.parentPath.replace('.', '/'),
                                    instruction.field.name,
                                    signature(instruction.field.type ?: error("Type is null")),
                                )
                            }

                            is Type.JFVariableSymbol -> {
                                val variableName = "${if (instruction.field.scopeId != 0) instruction.field.scopeId else instruction.field.symbolMap.symbolMapId}.${instruction.field.name}"
                                when (instruction.field.type) {
                                    is OperandType.SInt32 -> iload(variableName)
                                    is OperandType.SInt64 -> lload(variableName)
                                    is OperandType.StringType -> aload(variableName)
                                    is OperandType.UInt1 -> iload(variableName)
                                    is OperandType.CharType -> iload(variableName)
                                    is OperandType.Array -> aload(variableName)
                                    is OperandType.Generic -> TODO()
                                    is OperandType.Unit -> TODO()
                                    is OperandType.Unknown -> aload(variableName) // Default to aload for Unknown type
                                    is Type.JFClass -> aload(variableName)
                                }

                            }

                            else -> {}
                        }

                        instruction.arguments.forEach { compile(it) }
                        with(method) {
                            val methodClassName = instruction.parentPath.replace('.', '/')
                            val methodSignature =
                                "(${instruction.parameters.joinToString("") { signature(it.type) }})" +
                                        signature(instruction.type())
                            when (instruction.field) {
                                null -> invokeStatic(
                                    methodClassName,
                                    instruction.methodName.replaceIllegalCharacters(),
                                    methodSignature
                                )

                                else -> invokeVirtual(
                                    methodClassName,
                                    instruction.methodName.replaceIllegalCharacters(),
                                    methodSignature
                                )
                            }
                        }
                        if ((asStatement) && (instruction.type() != OperandType.Unit)) pop()
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

                    is ExpressionNode.When -> {
                        val after = label()
                        val matches = instruction.matches.iterator()

                        fun processMatches() {
                            if (!matches.hasNext()) return
                            val match = matches.next()
                            when (match.first) {
                                ExpressionNode.BooleanLiteral(true) -> {
                                    compile(match.second, asStatement)
                                }

                                else -> {
                                    if (instruction.subject != null) {
                                        compile(instruction.subject!!)
                                        compile(match.first)
                                        val trueCmp = label()
                                        val endCmp = label()
                                        if_icmpeq(trueCmp)
                                        loadConstant(0)
                                        goto(endCmp)
                                        trueCmp { loadConstant(1) }
                                        endCmp {}
                                    } else {
                                        compile(match.first)
                                    }
                                    val next = label()
                                    ifequal(next)
                                    compile(match.second, asStatement)
                                    goto(after)
                                    next { processMatches() }
                                }
                            }
                        }

                        processMatches()
                        after { }
                    }

                    is ExpressionNode.WhenPhase3 -> {
                        val after = label()
                        val matches = instruction.matches.iterator()

                        fun processMatches() {
                            if (!matches.hasNext()) return
                            val match = matches.next()
                            when (match.first) {
                                ExpressionNode.BooleanLiteral(true) -> {
                                    compile(match.second, asStatement)
                                }

                                else -> {
                                    compile(match.first)
                                    val next = label()
                                    ifequal(next)
                                    compile(match.second, asStatement)
                                    goto(after)
                                    next { processMatches() }
                                }
                            }
                        }

                        processMatches()
                        after { }
                    }

                    is ExpressionNode.ExpressionList -> {

                        val lastIndex = instruction.expressions.size - 1
                        instruction.expressions.forEachIndexed { index, it ->
                            compile(it, asStatement || (index != lastIndex))
                        }

                    }

                    is ExpressionNode.DoWhile -> {
                        val body = label()
                        body {
                            compile(instruction.expressions, false)
                            compile(instruction.condition)
                            ifnotequal(body)
                        }
                    }

                    is ExpressionNode.While -> {
                        val after = label()
                        val body = label()
                        body {
                            compile(instruction.condition)
                            ifequal(after)
                            compile(instruction.expressions, true)
                            goto(body)
                        }
                        after { }
                    }

                    is ExpressionNode.WhilePhase3 -> {
                        val after = label()
                        val body = label()
                        body {
                            compile(instruction.condition)
                            ifequal(after)
                            compile(instruction.expressions, true)
                            goto(body)
                        }
                        after { }
                    }

                    is ExpressionNode.IRBlock -> {
                        compile(instruction.operation, asStatement)
                    }

                    is ExpressionNode.Mul -> {
                        compile(instruction.left)
                        compile(instruction.right)
                        when (instruction.left.type()) {
                            is OperandType.SInt32 -> imul()
                            is OperandType.SInt64 -> lmul()
                            else -> error("Mul not supported for ${instruction.left.type()}")
                        }
                        if (asStatement) pop()
                    }

                    is ExpressionNode.Add -> {
                        compile(instruction.left)
                        compile(instruction.right)
                        when (instruction.left.type()) {
                            is OperandType.SInt32 -> iadd()
                            is OperandType.SInt64 -> ladd()
                            else -> error("Add not supported for ${instruction.left.type()}")
                        }
                        if (asStatement) pop()
                    }

                    is ExpressionNode.Sub -> {
                        compile(instruction.left)
                        compile(instruction.right)
                        when (instruction.left.type()) {
                            is OperandType.SInt32 -> isub()
                            is OperandType.SInt64 -> lsub()
                            else -> error("Sub not supported for ${instruction.left.type()}")
                        }
                        if (asStatement) pop()
                    }

                    is ExpressionNode.Div -> {
                        compile(instruction.left)
                        compile(instruction.right)
                        when (instruction.left.type()) {
                            is OperandType.SInt32 -> idiv()
                            is OperandType.SInt64 -> ldiv()
                            else -> error("Div not supported for ${instruction.left.type()}")
                        }
                        if (asStatement) pop()
                    }

                    is ExpressionNode.CmpEq -> {
                        compile(instruction.left)
                        compile(instruction.right)
                        when (instruction.left.type()) {
                            is OperandType.SInt32, is OperandType.CharType -> {
                                val trueLabel = label(); val endLabel = label()
                                if_icmpeq(trueLabel); loadConstant(0); goto(endLabel)
                                trueLabel { loadConstant(1) }; endLabel {}
                            }
                            is OperandType.SInt64 -> {
                                val trueLabel = label(); val endLabel = label()
                                lcmp(); loadConstant(0); if_icmpeq(trueLabel); loadConstant(0); goto(endLabel)
                                trueLabel { loadConstant(1) }; endLabel {}
                            }
                            else -> error("CmpEq not supported for ${instruction.left.type()}")
                        }
                        if (asStatement) pop()
                    }

                    is ExpressionNode.CmpLt -> {
                        compile(instruction.left)
                        compile(instruction.right)
                        when (instruction.left.type()) {
                            is OperandType.SInt32, is OperandType.CharType -> {
                                val trueLabel = label(); val endLabel = label()
                                if_icmplt(trueLabel); loadConstant(0); goto(endLabel)
                                trueLabel { loadConstant(1) }; endLabel {}
                            }
                            is OperandType.SInt64 -> {
                                val trueLabel = label(); val endLabel = label()
                                lcmp(); loadConstant(0); if_icmplt(trueLabel); loadConstant(0); goto(endLabel)
                                trueLabel { loadConstant(1) }; endLabel {}
                            }
                            else -> error("CmpLt not supported for ${instruction.left.type()}")
                        }
                        if (asStatement) pop()
                    }

                    is ExpressionNode.CmpLe -> {
                        compile(instruction.left)
                        compile(instruction.right)
                        when (instruction.left.type()) {
                            is OperandType.SInt32, is OperandType.CharType -> {
                                val trueLabel = label(); val endLabel = label()
                                if_icmple(trueLabel); loadConstant(0); goto(endLabel)
                                trueLabel { loadConstant(1) }; endLabel {}
                            }
                            is OperandType.SInt64 -> {
                                val trueLabel = label(); val endLabel = label()
                                lcmp(); loadConstant(0); if_icmple(trueLabel); loadConstant(0); goto(endLabel)
                                trueLabel { loadConstant(1) }; endLabel {}
                            }
                            else -> error("CmpLe not supported for ${instruction.left.type()}")
                        }
                        if (asStatement) pop()
                    }

                    is ExpressionNode.CmpGt -> {
                        compile(instruction.left)
                        compile(instruction.right)
                        when (instruction.left.type()) {
                            is OperandType.SInt32, is OperandType.CharType -> {
                                val trueLabel = label(); val endLabel = label()
                                if_icmpgt(trueLabel); loadConstant(0); goto(endLabel)
                                trueLabel { loadConstant(1) }; endLabel {}
                            }
                            is OperandType.SInt64 -> {
                                val trueLabel = label(); val endLabel = label()
                                lcmp(); loadConstant(0); if_icmpgt(trueLabel); loadConstant(0); goto(endLabel)
                                trueLabel { loadConstant(1) }; endLabel {}
                            }
                            else -> error("CmpGt not supported for ${instruction.left.type()}")
                        }
                        if (asStatement) pop()
                    }

                    is ExpressionNode.CmpGe -> {
                        compile(instruction.left)
                        compile(instruction.right)
                        when (instruction.left.type()) {
                            is OperandType.SInt32, is OperandType.CharType -> {
                                val trueLabel = label(); val endLabel = label()
                                if_icmpge(trueLabel); loadConstant(0); goto(endLabel)
                                trueLabel { loadConstant(1) }; endLabel {}
                            }
                            is OperandType.SInt64 -> {
                                val trueLabel = label(); val endLabel = label()
                                lcmp(); loadConstant(0); if_icmpge(trueLabel); loadConstant(0); goto(endLabel)
                                trueLabel { loadConstant(1) }; endLabel {}
                            }
                            else -> error("CmpGe not supported for ${instruction.left.type()}")
                        }
                        if (asStatement) pop()
                    }

                    is ExpressionNode.InvokeStatic -> {
                        instruction.arguments.forEach { compile(it) }
                        invokeStatic(instruction.className, instruction.methodName, instruction.signature)
                        if (asStatement && !instruction.signature.endsWith(")V")) pop()
                    }

                    is ExpressionNode.InvokeVirtual -> {
                        instruction.arguments.forEach { compile(it) }
                        invokeVirtual(instruction.className, instruction.methodName, instruction.signature)
                        if (asStatement && !instruction.signature.endsWith(")V")) pop()
                    }

                    is ExpressionNode.Convert -> {
                        compile(instruction.expression)
                        conversion(instruction.from, instruction.to)
                    }

                    is ExpressionNode.FieldAccess -> {
                        compile(instruction.instance)
                        val ownerType = instruction.instance.type() as Type.JFClass
                        getField(
                            ownerType.path.replace('.', '/'),
                            instruction.fieldName,
                            signature(instruction.fieldType),
                        )
                        if (asStatement && instruction.type() != OperandType.Unit) pop()
                    }

                    else -> TODO()
                }
            }

        private fun ClassBuilder.MethodDSL.DSL.storeVariable(instruction: ExpressionNode.Assignment) {
            val variableName =
                "${if (instruction.variableSymbol.scopeId != 0) instruction.variableSymbol.scopeId else instruction.variableSymbol.symbolMap.symbolMapId}.${instruction.variableSymbol.name}"
            when (instruction.expression.type()) {
                is OperandType.SInt32 -> istore(variableName)
                is OperandType.SInt64 -> lstore(variableName)
                is OperandType.StringType -> astore(variableName)
                is OperandType.UInt1 -> istore(variableName)
                is OperandType.CharType -> istore(variableName)
                is OperandType.Array -> astore(variableName)
                is OperandType.Unknown -> astore(variableName) // Default to astore for Unknown type
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
                is OperandType.SInt64 -> when (to) {
                    is OperandType.StringType -> invokeStatic("java/lang/String", "valueOf", "(J)Ljava/lang/String;")
                    is Type.JFClass -> invokeStatic("java/lang/Long", "valueOf", "(J)Ljava/lang/Long;")
                    else -> TODO("Conversion not defined for SInt64 -> $to")
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
            val variableName = "${if (instruction.variableSymbol.scopeId != 0) instruction.variableSymbol.scopeId else instruction.variableSymbol.symbolMap.symbolMapId}.${instruction.variableSymbol.name}"
            when (instruction.variableSymbol.type) {
                is OperandType.SInt32 -> iload(variableName)
                is OperandType.SInt64 -> lload(variableName)
                is OperandType.StringType -> aload(variableName)
                is OperandType.UInt1 -> iload(variableName)
                is OperandType.CharType -> iload(variableName)
                is OperandType.Array -> aload(variableName)
                is OperandType.Unknown -> aload(variableName) // Default to aload for Unknown type
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

fun compileAll(
    builder: IRBuilder.BuilderContext,
): Map<String, ByteArray> {
    val result = mutableMapOf<String, ByteArray>()
    builder.classes.forEach { (name, classContext) ->
        result[name] = buildClass(name, classContext).write()
    }
    return result
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
        classContext.fields.forEach { (fieldName, fieldType) ->
            field(access = 1u, name = fieldName, type = signature(fieldType))
        }
        val hasInit = classContext.methods.any { it.name == "<init>" }
        if (classContext.fields.isNotEmpty() && !hasInit) {
            method {
                name = "<init>"
                access = 1u
                signature = "(${classContext.fields.joinToString("") { signature(it.second) }})V"
                parameter("thisRef")
                classContext.fields.forEach { (fieldName, _) -> parameter("p_$fieldName") }
                aload("thisRef")
                invokeSpecial("java/lang/Object", "<init>", "()V")
                classContext.fields.forEach { (fieldName, fieldType) ->
                    aload("thisRef")
                    when (fieldType) {
                        is OperandType.SInt32, is OperandType.UInt1, is OperandType.CharType -> iload("p_$fieldName")
                        is OperandType.SInt64 -> lload("p_$fieldName")
                        else -> aload("p_$fieldName")
                    }
                    putField(className, fieldName, signature(fieldType))
                }
                `return`()
            }
        }
        // Generate toString() for data-class-style output: ClassName(field1=val1, field2=val2)
        if (classContext.fields.isNotEmpty()) {
            val simpleName = className.split(".").last().split("$").last()
            method {
                name = "toString"
                access = 1u
                signature = "()Ljava/lang/String;"
                parameter("thisRef")
                new("java/lang/StringBuilder")
                dup()
                invokeSpecial("java/lang/StringBuilder", "<init>", "()V")
                loadConstant("$simpleName(")
                invokeVirtual("java/lang/StringBuilder", "append", "(Ljava/lang/String;)Ljava/lang/StringBuilder;")
                classContext.fields.forEachIndexed { index, (fieldName, fieldType) ->
                    if (index > 0) {
                        loadConstant(", ")
                        invokeVirtual("java/lang/StringBuilder", "append", "(Ljava/lang/String;)Ljava/lang/StringBuilder;")
                    }
                    loadConstant("$fieldName=")
                    invokeVirtual("java/lang/StringBuilder", "append", "(Ljava/lang/String;)Ljava/lang/StringBuilder;")
                    aload("thisRef")
                    getField(className, fieldName, signature(fieldType))
                    when (fieldType) {
                        is OperandType.StringType ->
                            invokeVirtual("java/lang/StringBuilder", "append", "(Ljava/lang/String;)Ljava/lang/StringBuilder;")
                        is OperandType.SInt32 ->
                            invokeVirtual("java/lang/StringBuilder", "append", "(I)Ljava/lang/StringBuilder;")
                        is OperandType.UInt1 ->
                            invokeVirtual("java/lang/StringBuilder", "append", "(Z)Ljava/lang/StringBuilder;")
                        is OperandType.CharType ->
                            invokeVirtual("java/lang/StringBuilder", "append", "(C)Ljava/lang/StringBuilder;")
                        is Type.JFClass ->
                            invokeVirtual("java/lang/StringBuilder", "append", "(Ljava/lang/Object;)Ljava/lang/StringBuilder;")
                        else -> error("Unexpected field type: $fieldType")
                    }
                }
                loadConstant(")")
                invokeVirtual("java/lang/StringBuilder", "append", "(Ljava/lang/String;)Ljava/lang/StringBuilder;")
                invokeVirtual("java/lang/StringBuilder", "toString", "()Ljava/lang/String;")
                areturn()
            }
            // Generate equals(Object) — based on toString() output
            method {
                name = "equals"
                access = 1u
                signature = "(Ljava/lang/Object;)Z"
                parameter("thisRef")
                parameter("other")
                val returnFalse = label()
                aload("other")
                invokeStatic("java/util/Objects", "isNull", "(Ljava/lang/Object;)Z")
                ifnotequal(returnFalse)
                aload("thisRef")
                invokeVirtual("java/lang/Object", "getClass", "()Ljava/lang/Class;")
                invokeVirtual("java/lang/Class", "getName", "()Ljava/lang/String;")
                aload("other")
                invokeVirtual("java/lang/Object", "getClass", "()Ljava/lang/Class;")
                invokeVirtual("java/lang/Class", "getName", "()Ljava/lang/String;")
                invokeVirtual("java/lang/String", "equals", "(Ljava/lang/Object;)Z")
                ifequal(returnFalse)
                aload("thisRef")
                invokeVirtual("java/lang/Object", "toString", "()Ljava/lang/String;")
                aload("other")
                invokeVirtual("java/lang/Object", "toString", "()Ljava/lang/String;")
                invokeVirtual("java/lang/String", "equals", "(Ljava/lang/Object;)Z")
                ifequal(returnFalse)
                loadConstant(1)
                ireturn()
                returnFalse {
                    loadConstant(0)
                    ireturn()
                }
            }
            // Generate hashCode()
            method {
                name = "hashCode"
                access = 1u
                signature = "()I"
                parameter("thisRef")
                new("java/lang/StringBuilder")
                dup()
                invokeSpecial("java/lang/StringBuilder", "<init>", "()V")
                classContext.fields.forEach { (fieldName, fieldType) ->
                    aload("thisRef")
                    getField(className, fieldName, signature(fieldType))
                    when (fieldType) {
                        is OperandType.StringType ->
                            invokeVirtual("java/lang/StringBuilder", "append", "(Ljava/lang/String;)Ljava/lang/StringBuilder;")
                        is OperandType.SInt32 ->
                            invokeVirtual("java/lang/StringBuilder", "append", "(I)Ljava/lang/StringBuilder;")
                        is OperandType.SInt64 ->
                            invokeVirtual("java/lang/StringBuilder", "append", "(J)Ljava/lang/StringBuilder;")
                        is OperandType.UInt1 ->
                            invokeVirtual("java/lang/StringBuilder", "append", "(Z)Ljava/lang/StringBuilder;")
                        is OperandType.CharType ->
                            invokeVirtual("java/lang/StringBuilder", "append", "(C)Ljava/lang/StringBuilder;")
                        is Type.JFClass ->
                            invokeVirtual("java/lang/StringBuilder", "append", "(Ljava/lang/Object;)Ljava/lang/StringBuilder;")
                        else -> error("Unexpected field type: $fieldType")
                    }
                }
                invokeVirtual("java/lang/StringBuilder", "toString", "()Ljava/lang/String;")
                invokeVirtual("java/lang/String", "hashCode", "()I")
                ireturn()
            }
        }
        classContext.methods.forEach { m ->
            method {
                name = m.name
                signature = "(${m.parameters.joinToString("", transform = { signature(it.type) })})" +
                    signature(m.returnType)
                // Pre-register parameter variable names to reserve correct local slots
                m.parameters.forEach { p -> p.varName?.let { parameter(it) } }
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
                    is OperandType.SInt32, is OperandType.SInt64, is OperandType.UInt1, is OperandType.CharType,
                    is OperandType.StringType, is Type.JFClass -> {
                        val lastType = m.instructions.last().type()
                        if (lastType != m.returnType) error("Type issue: $lastType vs ${m.returnType}")
                        when (m.returnType) {
                            is OperandType.SInt32, is OperandType.UInt1, is OperandType.CharType -> ireturn()
                            is OperandType.SInt64 -> lreturn()
                            is OperandType.StringType, is Type.JFClass -> areturn()
                            else -> error("Unreachable")
                        }
                    }
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
        is OperandType.SInt64 -> "J"
        is OperandType.CharType -> "C"
        is OperandType.Unknown -> "Ljava/lang/Object;" // Default to Object for Unknown type
        is OperandType.UInt1 -> "Z"
        is OperandType.Generic -> TODO()
        is Type.JFClass -> "L${type.path.replace('.', '/')};" // TODO: check if this needs to bee JFObject?
    }


