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

fun runMethod(
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
