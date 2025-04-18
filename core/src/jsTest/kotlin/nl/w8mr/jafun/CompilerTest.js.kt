package nl.w8mr.jafun.nl.w8mr.jafun

import nl.w8mr.kasmine.ClassBuilder

actual fun compareDecompiled(
    expected: ByteArray,
    bytecode: (ClassBuilder.ClassDSL.DSL.() -> Unit)?,
    expectedResult: String,
    actualResult : String,
    actualBytes: ByteArray
) {
    println("Cannot compare decompiled JVM bytecode on JS")
}

actual fun writeFile(
    className: String,
    bytes: ByteArray,
) {
    println("Cannot write class on JS")
}

/**
 * Skipping run for now in JS, always assert as true
 */
actual fun runAndAssertOutput(
    actualBytes: ByteArray,
    className: String,
    methodName: String,
    params: Array<String>?,
    expectedOutput: String
): String = expectedOutput

