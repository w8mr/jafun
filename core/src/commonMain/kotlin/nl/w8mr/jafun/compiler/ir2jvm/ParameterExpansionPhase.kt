package nl.w8mr.jafun.compiler.ir2jvm

import nl.w8mr.jafun.OperandType
import nl.w8mr.jafun.Type
import nl.w8mr.jafun.compiler.Parameter
import nl.w8mr.jafun.compiler.effectiveJvmType
import nl.w8mr.jafun.compiler.expandParameterRecursively
import nl.w8mr.jafun.compiler.shouldExpandVC

class ParameterExpansionPhase {
    fun handle(context: IRBuilder.ClassContext): IRBuilder.ClassContext {
        for (i in context.methods.indices) {
            val method = context.methods[i]
            val expandedParams = method.parameters.flatMap { param ->
                expandParameter(param, method.returnType)
            }.map { p ->
                val unwrapped = effectiveJvmType(p.type)
                if (unwrapped != p.type) Parameter(unwrapped, p.varName) else p
            }
            val expandedReturnType = effectiveJvmType(method.returnType)
            context.methods[i] = method.copy(
                parameters = expandedParams,
                returnType = expandedReturnType
            )
        }
        return context
    }

    private fun expandParameter(param: Parameter, returnType: OperandType<*>): List<Parameter> {
        val type = param.type
        if (type !is Type.JFClass || type.kind != Type.ClassKind.VALUE_CLASS) {
            return listOf(param)
        }
        if (!shouldExpandVC(type)) {
            return listOf(param)
        }

        val cons = type.constructor ?: return listOf(param)

        val singleFieldType = if (cons.parameters.size == 1) cons.parameters[0].type else null
        val returnsWrappedType = singleFieldType != null && singleFieldType == returnType

        if (returnsWrappedType) {
            return listOf(Parameter(effectiveJvmType(type), param.varName))
        }

        val baseName = param.varName
        return cons.parameters.flatMap { fieldParam ->
            val fieldName = fieldParam.name
            val newBaseName = if (baseName != null) "${baseName}_${fieldName}" else fieldName
            expandParameterRecursively(fieldParam.type, newBaseName)
        }
    }
}
