package nl.w8mr.jafun.compiler

import nl.w8mr.jafun.ParserJafun
import nl.w8mr.jafun.Type
import java.lang.reflect.AccessFlag

actual fun IdentifierCache.findInClass(
    jClassName: String,
    name: String,
): Type.JFMethod? {
    val jClass = Class.forName(jClassName)
    val jMethod = jClass.declaredMethods.find { it.name == name.replaceIllegalCharacters() }
    return jMethod?.let {
        val params = jMethod.parameters.map { jvmType(it.type.name) }
        val returnName = jMethod.returnType.name
        val rtn = jvmType(returnName)
        val associativity =
            jMethod.annotations.filterIsInstance<FunctionAssociativity>().map(FunctionAssociativity::associativity)
                .firstOrNull() ?: Associativity.PREFIX
        val precedence =
            jMethod.annotations.filterIsInstance<FunctionPrecedence>().map(FunctionPrecedence::precedence)
                .firstOrNull() ?: 10
        val jfClass = findOrAddClass(jClass.name, jClass.packageName, jClass.simpleName)

        val method =
            Type.JFMethod(
                params.mapIndexed { i, t -> Type.JFVariableSymbol("param${i + 1}", t, IdentifierCache) },
                jfClass,
                name,
                rtn,
                AccessFlag.STATIC in jMethod.accessFlags(),
                jMethod.name.all(ParserJafun.operatorSymbols::contains),
                associativity,
                precedence,
            )
        method
    }
}

