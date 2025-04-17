package nl.w8mr.jafun.nl.w8mr.jafun

import nl.w8mr.jafun.writeFile
import nl.w8mr.kasmine.ClassBuilder
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

actual fun compareDecompiled(
    expected: ByteArray,
    bytecode: (ClassBuilder.ClassDSL.DSL.() -> Unit)?,
    result: String,
    tested: Pair<String, ByteArray>
) {
    val resultDecompiled = "javap -v Script.class".runCommand(File("./build/classes/jafun/test"))
    writeFile("Script", expected)
    val expectedDecompiled = "javap -v Script.class".runCommand(File("./build/classes/jafun/test"))
    if (bytecode == null) println(resultDecompiled)
    assertEquals(expectedDecompiled, resultDecompiled)
    assertEquals(result, tested.first)
    assertContentEquals(expected, tested.second)
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
