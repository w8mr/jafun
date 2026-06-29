package nl.w8mr.jafun.nl.w8mr.jafun

import nl.w8mr.kasmine.ClassBuilder
import nl.w8mr.kasmine.DynamicClassLoader
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.PrintStream
import java.util.concurrent.TimeUnit
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

actual fun compareDecompiled(
    expected: ByteArray,
    bytecode: (ClassBuilder.ClassDSL.DSL.() -> Unit)?,
    expectedResult: String,
    actualResult : String,
    actualBytes: ByteArray
) {
    val resultDecompiled = "javap -v Script.class".runCommand(File("./build/classes/jafun/test"))
    writeFile("Script", expected)
    val expectedDecompiled = "javap -v Script.class".runCommand(File("./build/classes/jafun/test"))
    if (bytecode == null) println(resultDecompiled)
    assertEquals(expectedDecompiled, resultDecompiled)
    assertEquals(expectedResult, actualResult)
    assertContentEquals(expected, actualBytes)
}

fun runAndCatchOutput(
    classes: Map<String, ByteArray>,
    className: String,
    methodName: String,
    params: Array<String>?
): String {
    val oldOut = System.out
    val output = ByteArrayOutputStream()
    System.setOut(PrintStream(output))
    try {
        val loader = DynamicClassLoader(Thread.currentThread().contextClassLoader)
        var scriptClass: Class<*>? = null
        classes.forEach { (name, bytes) ->
            val cls = loader.define(name, bytes)
            if (name == className) scriptClass = cls
        }
        scriptClass!!.getMethod(methodName, Array<String>::class.java).invoke(null, params)
        System.setOut(oldOut)
    } catch (t: Throwable) {
        System.setOut(oldOut)
        println(t.message)
        t.printStackTrace()
    }
    val result = String(output.toByteArray())
    return result
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

actual fun runAndAssertOutput(
    actualBytes: Map<String, ByteArray>,
    className: String,
    methodName: String,
    params: Array<String>?,
    expectedOutput: String
): String {
    val result = runAndCatchOutput(actualBytes, className, methodName, params)
    println("OUTPUT: $result")
    try {
        assertEquals(expectedOutput, result)
    } catch (e: Throwable) {
        writeFile(className, actualBytes[className] ?: error("No bytes"))
        val javap = "javap -c -p $className".runCommand(File("./build/classes/jafun/test"))
        println("=== JAVAP OUTPUT ===")
        println(javap)
        throw e
    }
    return result
}



fun String.runCommand(workingDir: File): String? {
    try {
        val parts = this.split("\\s".toRegex())
        val proc =
            ProcessBuilder(*parts.toTypedArray())
                .directory(workingDir)
                .redirectOutput(ProcessBuilder.Redirect.PIPE)
                .redirectError(ProcessBuilder.Redirect.PIPE)
                .start()

        proc.waitFor(60, TimeUnit.MINUTES)
        return proc.inputStream.bufferedReader().readText()
    } catch (e: IOException) {
        e.printStackTrace()
        return null
    }
}
