package nl.w8mr.jafun.compiler

import nl.w8mr.jafun.OperandType
import nl.w8mr.jafun.Phase1Parser
import nl.w8mr.jafun.ParserJafun
import nl.w8mr.jafun.Type
import nl.w8mr.jafun.symboltable.FQDN
import nl.w8mr.jafun.symboltable.MethodDef
import nl.w8mr.jafun.symboltable.Parameter
import nl.w8mr.jafun.symboltable.SymbolTable
import nl.w8mr.jafun.symboltable.TypeEntry

expect fun readStdlibResource(path: String): String?

object StdlibLoader {
    private val stdlibResources = listOf("jafun/lang/Int.jf", "jafun/lang/Char.jf", "jafun/lang/String.jf", "jafun/test/Test.jf")

    private val defaultOperatorMetadata = mapOf(
        "+" to Triple(Associativity.INFIXL, 100, true),
        "-" to Triple(Associativity.INFIXL, 100, true),
        "*" to Triple(Associativity.INFIXL, 110, true),
        "/" to Triple(Associativity.INFIXL, 110, true),
        "==" to Triple(Associativity.INFIXL, 40, true),
        "<" to Triple(Associativity.INFIXL, 50, true),
        "<=" to Triple(Associativity.INFIXL, 50, true),
        ">" to Triple(Associativity.INFIXL, 50, true),
        ">=" to Triple(Associativity.INFIXL, 50, true),
        "**" to Triple(Associativity.INFIXR, 115, true),
        "++" to Triple(Associativity.POSTFIX, 140, true),
        "--" to Triple(Associativity.POSTFIX, 140, true),
        "euro" to Triple(Associativity.POSTFIX, 40, true),
        "cent" to Triple(Associativity.POSTFIX, 40, true),
        "<=>" to Triple(Associativity.PREFIX, 10, true),
    )

    private val stdlibTypeMap = mapOf(
        "jafun/lang/Int.jf" to OperandType.SInt32,
        "jafun/lang/Char.jf" to OperandType.CharType,
        "jafun/lang/String.jf" to OperandType.StringType,
        "jafun/test/Test.jf" to Type.JFClass("Test"),
    )

    fun load(symbolMap: SymbolMapManager, symbolTable: SymbolTable = SymbolTable()): List<ExpressionNode.Function> {
        val allFunctions = mutableListOf<ExpressionNode.Function>()

        for ((resource, operandType) in stdlibTypeMap) {
            val fqdn = FQDN(resourceToClassName(resource))
            symbolTable.registerType(fqdn, TypeEntry(fqdn, operandType))
            val pkgParts = fqdn.packageName.split(".").filter { it.isNotEmpty() }
            if (pkgParts.isNotEmpty()) {
                symbolTable.findOrCreatePackage(*pkgParts.toTypedArray()).addClass(fqdn.simpleName, fqdn)
            }
        }
        symbolTable.addImport(FQDN("jafun.lang"))
        symbolTable.addImport(FQDN("jafun.test"))

        for (resource in stdlibResources) {
val content = readStdlibResource(resource) ?: continue
            val resourceFqdn = FQDN(resourceToClassName(resource))

            val phase1 = Phase1Parser(symbolMap, symbolTable).parse(content).first ?: continue
            val parsed = ParserJafun(symbolMap, symbolTable, moduleFqdn = resourceFqdn).parse(phase1).first ?: continue
            val functions = parsed.filterIsInstance<ExpressionNode.Function>()

            for (fn in functions) {
                val paramType = fn.symbol.parameters.firstOrNull()?.type

                val (defaultAssoc, defaultPrec, defaultOp) =
                    defaultOperatorMetadata[fn.symbol.name]
                        ?: Triple(fn.symbol.associativity, fn.symbol.precedence, fn.symbol.operator)

                val mergedSymbol = fn.symbol.copy(
                    associativity = defaultAssoc,
                    precedence = defaultPrec,
                    operator = defaultOp,
                    inline = true,
                )

                val methodId = resourceFqdn + fn.symbol.name
                symbolTable.registerMethod(
                    MethodDef(
                        id = methodId,
                        name = fn.symbol.name,
                        parentFqdn = resourceFqdn,
                        parameters = fn.symbol.parameters.map { Parameter(it.name, it.type) },
                        rtn = mergedSymbol.rtn,
                        static = mergedSymbol.static,
                        operator = mergedSymbol.operator,
                        associativity = mergedSymbol.associativity,
                        precedence = mergedSymbol.precedence,
                        inline = true,
                    )
                )
                val pkgParts = resourceFqdn.packageName.split(".").filter { it.isNotEmpty() }
                symbolTable.findOrCreatePackage(*pkgParts.toTypedArray()).addFunction(fn.symbol.name, methodId)

                allFunctions.add(fn.copy(symbol = mergedSymbol))
            }
        }
        return allFunctions
    }

    private fun resourceToClassName(resource: String): String =
        resource.removeSuffix(".jf").replace('/', '.')
}