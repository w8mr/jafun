package nl.w8mr.jafun

import nl.w8mr.jafun.compiler.IdentifierCache
import nl.w8mr.jafun.compiler.LocalSymbolMap
import nl.w8mr.jafun.compiler.ast2ir.compileExpressionNode
import nl.w8mr.jafun.debug.IRPrintTree
import nl.w8mr.jafun.debug.prettyPrint
import nl.w8mr.jafun.debug.print
import nl.w8mr.kasmine.DynamicClassLoader
import nl.w8mr.parsek.Parser
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.PrintStream
import kotlin.Array
import kotlin.collections.toByteArray
import kotlin.printStackTrace

actual fun runAndCatchOutput(
    bytes: ByteArray,
    className: String,
    methodName: String,
    params: Array<String>?
): String {
    val oldOut = System.out
    val output = ByteArrayOutputStream()
    System.setOut(PrintStream(output))
    try {
        runMethod(bytes, className, methodName, params)
        System.setOut(oldOut)
    } catch (t: Throwable) {
        System.setOut(oldOut)
        println(t.message)
        t.printStackTrace()
    }
    val result = String(output.toByteArray())
    return result
}

actual fun runMethod(
    bytes: ByteArray,
    className: String,
    methodName: String,
    params: Array<String>?,
) {
    val loader = DynamicClassLoader(Thread.currentThread().contextClassLoader)
    val scriptClass = loader.define(className, bytes)
    scriptClass.getMethod(methodName, Array<String>::class.java).invoke(null, params)
}

actual fun writeFile(
    className: String,
    bytes: ByteArray,
) {
    val dir = File("./build/classes/jafun/test")
    dir.mkdirs()
    val file = File(dir, "$className.class")
    file.writeBytes(bytes)
}

