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

actual fun runAndCatchOutput(
    bytes: ByteArray,
    className: String,
    methodName: String,
    params: Array<String>?
): String {
    TODO("Not yet implemented")
}

actual fun writeFile(
    className: String,
    bytes: ByteArray,
) {
    TODO("Not yet implemented")
}

