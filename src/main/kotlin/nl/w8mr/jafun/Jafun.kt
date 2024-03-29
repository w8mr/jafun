package nl.w8mr.jafun

import jafun.compiler.IntegerType
import jafun.compiler.JFClass
import jafun.compiler.VoidType
import nl.w8mr.kasmine.ClassBuilder
import nl.w8mr.kasmine.DynamicClassLoader
import nl.w8mr.kasmine.classBuilder
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.PrintStream
import java.util.concurrent.TimeUnit
import kotlin.Array

fun compile(
    code: String,
    className: String = "HelloWorld",
    methodName: String = "main",
    methodSig: String = "([Ljava/lang/String;)V",
): ByteArray {
    val lexed = lexer.parse(code).filter { it !is Token.WS }
    println("LEXED: $lexed")
    val parsed = Parser.parse(lexed)
    println("PARSED: $parsed")
    println()
    return compile(parsed, className, methodName, methodSig)
}

fun runMain(bytes: ByteArray) {
    writeFile("HelloWorld", bytes)
    runMethod(bytes, "HelloWorld", "main")
}

fun test(
    code: String,
    className: String = "HelloWorld",
    methodName: String = "main",
    methodSig: String = "([Ljava/lang/String;)V",
): String {
    val bytes = compile(code.trimIndent(), className, methodName, methodSig)
    writeFile(className, bytes)
    val oldOut = System.out
    val output = ByteArrayOutputStream()
    System.setOut(PrintStream(output))
    try {
        runMethod(bytes, className, methodName)
        System.setOut(oldOut)
    } catch (t: Throwable) {
        System.setOut(oldOut)
        println(t.message)
        t.printStackTrace()
    }
    val result = String(output.toByteArray())
    println("OUTPUT: $result")
    print("DISASSEMBLE: ")
    println("javap -v HelloWorld.class".runCommand(File("./build/classes/jafun/test")))
    return result
}

private fun runMethod(
    bytes: ByteArray,
    className: String,
    methodName: String,
) {
    val loader = DynamicClassLoader(Thread.currentThread().contextClassLoader)
    val helloWorldClass = loader.define(className, bytes)
    helloWorldClass.getMethod(methodName, Array<String>::class.java).invoke(null, null)
}

private fun writeFile(
    className: String,
    bytes: ByteArray,
) {
    val dir = File("./build/classes/jafun/test")
    dir.mkdirs()
    val file = File(dir, "$className.class")
    file.writeBytes(bytes)
}

fun compile(
    statements: List<ASTNode.Expression>,
    className: String = "HelloWorld",
    methodName: String = "main",
    methodSig: String = "([Ljava/lang/String;)V",
): ByteArray {
    val clazz =
        classBuilder {
            name = className
            compileMethod(this, statements, methodName, methodSig)
        }

    return clazz.write()
}

fun compileMethod(
    classBuilder: ClassBuilder.ClassDSL.DSL,
    statements: List<ASTNode.Expression>,
    methodName: String,
    methodSig: String,
) {
    with(classBuilder) {
        method {
            name = methodName
            signature = methodSig
            val lastIndex = statements.size - 1
            statements.forEachIndexed { index, statement ->
                if ((lastIndex == index)) {
                    if (methodSig.endsWith('V')) {
                        compileAsStatement(statement, this)
                        `return`()
                    } else {
                        compileAsExpression(statement, this)
                        when (statement.type()) {
                            is IntegerType -> ireturn()
                            is JFClass -> areturn()
                            else -> TODO("Need to implement other return types")
                        }
                    }
                } else {
                    compileAsStatement(statement, this)
                }
            }
        }
    }
}

fun compileAsStatement(
    expression: ASTNode.Expression,
    builder: ClassBuilder.MethodDSL.DSL,
) {
    expression.compile(builder, false)
}

fun compileAsExpression(
    expression: ASTNode.Expression,
    builder: ClassBuilder.MethodDSL.DSL,
) {
    expression.compile(builder, true)
    if (expression.type() == VoidType) builder.getStatic("jafun/Unit", "INSTANCE", "Ljafun/Unit;")
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
