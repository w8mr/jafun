package nl.w8mr.jafun.compiler

import nl.w8mr.jafun.OperandType
import nl.w8mr.jafun.Type

actual fun IdentifierCache.findInClass(
    jClassName: String,
    name: String,
): Type.JFMethod? =
    when (jClassName) {
        "jafun.io.ConsoleKt" -> {
            when (name) {
                "println" -> Type.JFMethod(
                    listOf(Type.JFVariableSymbol("param1", Type.JFClass("java.lang.Object", parent=null), this, false)), // TODO: should not have full path in name
                    findClass(jClassName),
                    name,
                    OperandType.Unit,
                    static = true,
                )
                "print" -> Type.JFMethod(
                    listOf(Type.JFVariableSymbol("param1", Type.JFClass("java.lang.Object", parent=null), this, false)), // TODO: should not have full path in name
                    findClass(jClassName),
                    name,
                    OperandType.Unit,
                    static = true,
                )
                else ->  null
            }
        }
        "jafun.test.TestKt" -> {
            when (name) {
                "join" -> Type.JFMethod(
                    listOf(Type.JFVariableSymbol("param1", OperandType.StringType, this, false), Type.JFVariableSymbol("param2", OperandType.StringType, this, false)),
                    findClass(jClassName),
                    name,
                    OperandType.StringType,
                    static = true,
                )
                "reverse" -> Type.JFMethod(
                    listOf(Type.JFVariableSymbol("param1", OperandType.StringType, this, false)),
                    findClass(jClassName),
                    name,
                    OperandType.StringType,
                    static = true,
                )
                "first" -> Type.JFMethod(
                    listOf(Type.JFVariableSymbol("param1", OperandType.Array(OperandType.StringType), this, false)),
                    findClass(jClassName),
                    name,
                    OperandType.StringType,
                    static = true,
                )
                "length" -> Type.JFMethod(
                    listOf(Type.JFVariableSymbol("param1", OperandType.StringType, this, false)),
                    findClass(jClassName),
                    name,
                    OperandType.SInt32,
                    static = true,
                )
                "charAt" -> Type.JFMethod(
                    listOf(Type.JFVariableSymbol("param1", OperandType.StringType, this, false), Type.JFVariableSymbol("param2", OperandType.SInt32, this, false)),
                    findClass(jClassName),
                    name,
                    OperandType.CharType,
                    static = true,
                )
                "<=>" -> Type.JFMethod(
                    listOf(
                        Type.JFVariableSymbol("param1", OperandType.SInt32, this, false)
                    ),
                    findClass(jClassName),
                    name,
                    OperandType.SInt32,
                    static = true,
                    operator = false, //TODO: check why this isn't true
                    associativity = Associativity.PREFIX,
                    precedence = 10,
                )
                "getSimpleObject5" -> Type.JFMethod(
                    emptyList(),
                    findClass(jClassName),
                    name,
                    Type.JFClass("SimpleObject"),
                    static = true,
                    operator = false, //TODO: check why this isn't true
                    associativity = Associativity.PREFIX,
                    precedence = 10,
                )


                else ->  null
            }
        }
        "jafun.lang.IntKt" -> {
            when (name) {
                "==" -> Type.JFMethod(
                    listOf(
                        Type.JFVariableSymbol("param1", OperandType.SInt32, this, false),
                        Type.JFVariableSymbol("param2", OperandType.SInt32, this, false)
                    ),
                    findClass(jClassName),
                    name,
                    OperandType.UInt1,
                    static = true,
                    operator = true,
                    associativity = Associativity.INFIXL,
                    precedence = 40,
                )

                "<" -> Type.JFMethod(
                    listOf(
                        Type.JFVariableSymbol("param1", OperandType.SInt32, this, false),
                        Type.JFVariableSymbol("param2", OperandType.SInt32, this, false)
                    ),
                    findClass(jClassName),
                    name,
                    OperandType.UInt1,
                    static = true,
                    operator = true,
                    associativity = Associativity.INFIXL,
                    precedence = 50,
                )

                "<=" -> Type.JFMethod(
                    listOf(
                        Type.JFVariableSymbol("param1", OperandType.SInt32, this, false),
                        Type.JFVariableSymbol("param2", OperandType.SInt32, this, false)
                    ),
                    findClass(jClassName),
                    name,
                    OperandType.UInt1,
                    static = true,
                    operator = true,
                    associativity = Associativity.INFIXL,
                    precedence = 50,
                )

                ">" -> Type.JFMethod(
                    listOf(
                        Type.JFVariableSymbol("param1", OperandType.SInt32, this, false),
                        Type.JFVariableSymbol("param2", OperandType.SInt32, this, false)
                    ),
                    findClass(jClassName),
                    name,
                    OperandType.UInt1,
                    static = true,
                    operator = true,
                    associativity = Associativity.INFIXL,
                    precedence = 50,
                )

                ">=" -> Type.JFMethod(
                    listOf(
                        Type.JFVariableSymbol("param1", OperandType.SInt32, this, false),
                        Type.JFVariableSymbol("param2", OperandType.SInt32, this, false)
                    ),
                    findClass(jClassName),
                    name,
                    OperandType.UInt1,
                    static = true,
                    operator = true,
                    associativity = Associativity.INFIXL,
                    precedence = 50,
                )

                "+" -> Type.JFMethod(
                    listOf(
                        Type.JFVariableSymbol("param1", OperandType.SInt32, this, false),
                        Type.JFVariableSymbol("param2", OperandType.SInt32, this, false)
                    ),
                    findClass(jClassName),
                    name,
                    OperandType.SInt32,
                    static = true,
                    operator = true,
                    associativity = Associativity.INFIXL,
                    precedence = 100,
                )

                "-" -> Type.JFMethod(
                    listOf(
                        Type.JFVariableSymbol("param1", OperandType.SInt32, this, false),
                        Type.JFVariableSymbol("param2", OperandType.SInt32, this, false)
                    ),
                    findClass(jClassName),
                    name,
                    OperandType.SInt32,
                    static = true,
                    operator = true,
                    associativity = Associativity.INFIXL,
                    precedence = 100,
                )

                "*" -> Type.JFMethod(
                    listOf(
                        Type.JFVariableSymbol("param1", OperandType.SInt32, this, false),
                        Type.JFVariableSymbol("param2", OperandType.SInt32, this, false)
                    ),
                    findClass(jClassName),
                    name,
                    OperandType.SInt32,
                    static = true,
                    operator = true,
                    associativity = Associativity.INFIXL,
                    precedence = 110,
                )
                "/" -> Type.JFMethod(
                    listOf(
                        Type.JFVariableSymbol("param1", OperandType.SInt32, this, false),
                        Type.JFVariableSymbol("param2", OperandType.SInt32, this, false)
                    ),
                    findClass(jClassName),
                    name,
                    OperandType.SInt32,
                    static = true,
                    operator = true,
                    associativity = Associativity.INFIXL,
                    precedence = 110,
                )
                else -> null
            }
        }
        "jafun.lang.CharKt" -> {
            when (name) {
                "==" -> Type.JFMethod(
                    listOf(
                        Type.JFVariableSymbol("param1", OperandType.CharType, this, false),
                        Type.JFVariableSymbol("param2", OperandType.CharType, this, false)
                    ), // TODO: should not have full path in name
                    findClass(jClassName),
                    name,
                    OperandType.UInt1,
                    static = true,
                    operator = true,
                    associativity = Associativity.INFIXL,
                    precedence = 40,
                )

                else -> null
            }
        }
        "java.io.PrintStream" -> {
            when (name) {
                "println" -> Type.JFMethod(
                    listOf(
                        Type.JFVariableSymbol("param1", OperandType.StringType, this, false)
                    ), // TODO: should not have full path in name
                    findClass(jClassName),
                    name,
                    OperandType.Unit,
                    static = false,
                )

                else -> null
            }
        }
        else -> null
    }
