package nl.w8mr.jafun.compiler

import nl.w8mr.jafun.OperandType
import nl.w8mr.jafun.ParserJafun
import nl.w8mr.jafun.Type
import nl.w8mr.jafun.TypeSymbol
import java.lang.reflect.AccessFlag

actual fun IdentifierCache.findMethodsInClass(
    jClassName: String,
): List<Type.JFMethod> {
    val jClass = Class.forName(jClassName)
    val jMethods = jClass.declaredMethods.toList()
    val methods = jMethods.map { jMethod ->
        val params = jMethod.parameters.map { jvmType(it.type.name) }
        val returnName = jMethod.returnType.name
        val rtn = jvmType(returnName)
        val associativity =
            jMethod.annotations.filterIsInstance<FunctionAssociativity>().map(FunctionAssociativity::associativity)
                .firstOrNull() ?: Associativity.PREFIX
        val precedence =
            jMethod.annotations.filterIsInstance<FunctionPrecedence>().map(FunctionPrecedence::precedence)
                .firstOrNull() ?: 10
        val functionName =
            jMethod.annotations.filterIsInstance<FunctionName>().map { it.name } .firstOrNull() ?: jMethod.name
        val jfClass = findOrAddClass(jClass.name, jClass.packageName, jClass.simpleName)

        val method =
            Type.JFMethod(
                params.mapIndexed { i, t -> Type.JFVariableSymbol("param${i + 1}", t, IdentifierCache) },
                jfClass,
                functionName,
                rtn,
                AccessFlag.STATIC in jMethod.accessFlags(),
                jMethod.name.all(ParserJafun.operatorSymbols::contains),
                associativity,
                precedence,
            )
        method
    }
    return methods
}

actual fun IdentifierCache.findConstructorsInClass(
    jClassName: String,
): List<Type.JFConstructor> {
    val jClass = Class.forName(jClassName)
    val jConstructors = jClass.constructors.toList()
    val constructors = jConstructors.map { jConstructors ->
        val params = jConstructors.parameters.map { jvmType(it.type.name) }
        val jfClass = findOrAddClass(jClass.name, jClass.packageName, jClass.simpleName)

        val constructor =
            Type.JFConstructor(
                params.mapIndexed { i, t -> Type.JFVariableSymbol("param${i + 1}", t, IdentifierCache) },
                jfClass,
            )
        constructor

    }


    return  constructors
}

actual fun findClassInPackage(name: String, parent: Type.JFPackage): List<TypeSymbol> {
    try {
        val clazz = Class.forName("${parent.path}.${name}")
        return listOf(Type.JFClass(name, parent))
    } catch (e: ClassNotFoundException) {

    }
    return emptyList()
}

