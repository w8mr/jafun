package nl.w8mr.jafun.nl.w8mr.jafun

import nl.w8mr.kasmine.ClassBuilder

actual fun compareDecompiled(
    expected: ByteArray,
    bytecode: (ClassBuilder.ClassDSL.DSL.() -> Unit)?,
    result: String,
    tested: Pair<String, ByteArray>
) {
    TODO("Not yet implemented")
}
