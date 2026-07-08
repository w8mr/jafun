package nl.w8mr.jafun.compiler

import nl.w8mr.jafun.OperandType
import nl.w8mr.jafun.Type
import nl.w8mr.jafun.TypeSymbol

actual fun IdentifierCache.findMethodsInClass(
    jClassName: String,
): List<Type.JFMethod> =
    when (jClassName) {
        "jafun.io.ConsoleKt" -> {
            listOf(
                Type.JFMethod(
                    listOf(Type.JFVariableSymbol("param1", Type.JFClass("java.lang.Object", parent=null), this, false)), // TODO: should not have full path in name
                    findClass(jClassName),
                    "println",
                    OperandType.Unit,
                    static = true,
                ),
                Type.JFMethod(
                    listOf(Type.JFVariableSymbol("param1", Type.JFClass("java.lang.Object", parent=null), this, false)), // TODO: should not have full path in name
                    findClass(jClassName),
                    "print",
                    OperandType.Unit,
                    static = true,
                )
            )
        }
        "jafun.test.TestKt" -> {
            listOf(
                Type.JFMethod(
                    listOf(Type.JFVariableSymbol("param1", OperandType.StringType, this, false), Type.JFVariableSymbol("param2", OperandType.StringType, this, false)),
                    findClass(jClassName),
                    "join",
                    OperandType.StringType,
                    static = true,
                ),
                Type.JFMethod(
                    listOf(Type.JFVariableSymbol("param1", OperandType.StringType, this, false)),
                    findClass(jClassName),
                    "reverse",
                    OperandType.StringType,
                    static = true,
                ),
                Type.JFMethod(
                    listOf(Type.JFVariableSymbol("param1", OperandType.Array(OperandType.StringType), this, false)),
                    findClass(jClassName),
                    "first",
                    OperandType.StringType,
                    static = true,
                ),
                Type.JFMethod(
                    listOf(Type.JFVariableSymbol("param1", OperandType.StringType, this, false)),
                    findClass(jClassName),
                    "length",
                    OperandType.SInt32,
                    static = true,
                ),
                Type.JFMethod(
                    listOf(Type.JFVariableSymbol("param1", OperandType.StringType, this, false), Type.JFVariableSymbol("param2", OperandType.SInt32, this, false)),
                    findClass(jClassName),
                    "charAt",
                    OperandType.CharType,
                    static = true,
                ),
                Type.JFMethod(
                    listOf(
                        Type.JFVariableSymbol("param1", OperandType.SInt32, this, false)
                    ),
                    findClass(jClassName),
                    "<=>",
                    OperandType.SInt32,
                    static = true,
                    operator = false, //TODO: check why this isn't true
                    associativity = Associativity.PREFIX,
                    precedence = 10,
                ),
                Type.JFMethod(
                    emptyList(),
                    findClass(jClassName),
                    "getSimpleObject5",
                    Type.JFClass("SimpleObject"),
                    static = true,
                    operator = false, //TODO: check why this isn't true
                    associativity = Associativity.PREFIX,
                    precedence = 10,
                )
            )
        }
        "jafun.lang.Int" -> {
            listOf(
                Type.JFMethod(
                    listOf(
                        Type.JFVariableSymbol("param1", OperandType.SInt32, this, false),
                        Type.JFVariableSymbol("param2", OperandType.SInt32, this, false)
                    ),
                    findClass(jClassName),
                    "==",
                    OperandType.UInt1,
                    static = true,
                    operator = true,
                    associativity = Associativity.INFIXL,
                    precedence = 40,
                ),
                Type.JFMethod(
                    listOf(
                        Type.JFVariableSymbol("param1", OperandType.SInt32, this, false),
                        Type.JFVariableSymbol("param2", OperandType.SInt32, this, false)
                    ),
                    findClass(jClassName),
                    "<",
                    OperandType.UInt1,
                    static = true,
                    operator = true,
                    associativity = Associativity.INFIXL,
                    precedence = 50,
                ),
                Type.JFMethod(
                    listOf(
                        Type.JFVariableSymbol("param1", OperandType.SInt32, this, false),
                        Type.JFVariableSymbol("param2", OperandType.SInt32, this, false)
                    ),
                    findClass(jClassName),
                    "<=",
                    OperandType.UInt1,
                    static = true,
                    operator = true,
                    associativity = Associativity.INFIXL,
                    precedence = 50,
                ),
                Type.JFMethod(
                    listOf(
                        Type.JFVariableSymbol("param1", OperandType.SInt32, this, false),
                        Type.JFVariableSymbol("param2", OperandType.SInt32, this, false)
                    ),
                    findClass(jClassName),
                    ">",
                    OperandType.UInt1,
                    static = true,
                    operator = true,
                    associativity = Associativity.INFIXL,
                    precedence = 50,
                ),
                Type.JFMethod(
                    listOf(
                        Type.JFVariableSymbol("param1", OperandType.SInt32, this, false),
                        Type.JFVariableSymbol("param2", OperandType.SInt32, this, false)
                    ),
                    findClass(jClassName),
                    ">=",
                    OperandType.UInt1,
                    static = true,
                    operator = true,
                    associativity = Associativity.INFIXL,
                    precedence = 50,
                ),
                Type.JFMethod(
                    listOf(
                        Type.JFVariableSymbol("param1", OperandType.SInt32, this, false),
                        Type.JFVariableSymbol("param2", OperandType.SInt32, this, false)
                    ),
                    findClass(jClassName),
                    "+",
                    OperandType.SInt32,
                    static = true,
                    operator = true,
                    associativity = Associativity.INFIXL,
                    precedence = 100,
                ),
                Type.JFMethod(
                    listOf(
                        Type.JFVariableSymbol("param1", OperandType.SInt32, this, false),
                        Type.JFVariableSymbol("param2", OperandType.SInt32, this, false)
                    ),
                    findClass(jClassName),
                    "-",
                    OperandType.SInt32,
                    static = true,
                    operator = true,
                    associativity = Associativity.INFIXL,
                    precedence = 100,
                ),
                Type.JFMethod(
                    listOf(
                        Type.JFVariableSymbol("param1", OperandType.SInt32, this, false),
                        Type.JFVariableSymbol("param2", OperandType.SInt32, this, false)
                    ),
                    findClass(jClassName),
                    "*",
                    OperandType.SInt32,
                    static = true,
                    operator = true,
                    associativity = Associativity.INFIXL,
                    precedence = 110,
                ),
                Type.JFMethod(
                    listOf(
                        Type.JFVariableSymbol("param1", OperandType.SInt32, this, false),
                        Type.JFVariableSymbol("param2", OperandType.SInt32, this, false)
                    ),
                    findClass(jClassName),
                    "/",
                    OperandType.SInt32,
                    static = true,
                    operator = true,
                    associativity = Associativity.INFIXL,
                    precedence = 110,
                )
            )
        }
        "jafun.lang.CharKt" -> {
            listOf(
                Type.JFMethod(
                    listOf(
                        Type.JFVariableSymbol("param1", OperandType.CharType, this, false),
                        Type.JFVariableSymbol("param2", OperandType.CharType, this, false)
                    ), // TODO: should not have full path in name
                    findClass(jClassName),
                    "==",
                    OperandType.UInt1,
                    static = true,
                    operator = true,
                    associativity = Associativity.INFIXL,
                    precedence = 40,
                )
            )
        }
        "java.io.PrintStream" -> {
            listOf(
                Type.JFMethod(
                    listOf(
                        Type.JFVariableSymbol("param1", OperandType.StringType, this, false)
                    ), // TODO: should not have full path in name
                    findClass(jClassName),
                    "println",
                    OperandType.Unit,
                    static = false,
                )
            )
        }
        else -> emptyList()
    }

actual fun IdentifierCache.findConstructorsInClass(
    jClassName: String
): List<Type.JFConstructor> = emptyList()


actual fun findClassInPackage(
    name: String,
    parent: Type.JFPackage
): List<TypeSymbol> {
    return emptyList<TypeSymbol>()
}
