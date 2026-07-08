package nl.w8mr.jafun.symboltable

import nl.w8mr.jafun.OperandType
import nl.w8mr.jafun.compiler.Associativity
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.assertFailsWith

class SymbolTableTest {

    // --- FQDN ---

    @Test
    fun `FQDN extracts package name and simple name`() {
        val fqdn = FQDN("jafun.lang.Int")
        assertEquals("jafun.lang", fqdn.packageName)
        assertEquals("Int", fqdn.simpleName)
    }

    @Test
    fun `FQDN concatenation`() {
        val pkg = FQDN("jafun.lang")
        val full = pkg + "Int"
        assertEquals(FQDN("jafun.lang.Int"), full)
    }

    @Test
    fun `FQDN root`() {
        assertEquals(FQDN(""), FQDN.ROOT)
    }

    @Test
    fun `FQDN of varargs`() {
        assertEquals(FQDN("jafun.lang.Int"), FQDN.of("jafun", "lang", "Int"))
    }

    @Test
    fun `FQDN simple name for top-level entry`() {
        val fqdn = FQDN("Script")
        assertEquals("", fqdn.packageName)
        assertEquals("Script", fqdn.simpleName)
    }

    // --- Type registry ---

    @Test
    fun `register and lookup type by FQDN`() {
        val table = SymbolTable()
        val fqdn = FQDN("jafun.lang.Int")
        val entry = TypeEntry(fqdn, OperandType.SInt32)
        table.registerType(fqdn, entry)

        val result = table.lookupType(fqdn)
        assertNotNull(result)
        assertEquals(OperandType.SInt32, result.operandType)
    }

    @Test
    fun `lookup type by operand type`() {
        val table = SymbolTable()
        val fqdn = FQDN("jafun.lang.Int")
        table.registerType(fqdn, TypeEntry(fqdn, OperandType.SInt32))

        val result = table.lookupTypeByOperandType(OperandType.SInt32)
        assertNotNull(result)
        assertEquals(fqdn, result.fqdn)
    }

    @Test
    fun `lookup nonexistent type returns null`() {
        val table = SymbolTable()
        assertNull(table.lookupType(FQDN("jafun.lang.Nonexistent")))
    }

    // --- JVM bridge ---

    @Test
    fun `register and lookup JVM bridge`() {
        val table = SymbolTable()
        table.registerJvmBridge("java.lang.String", FQDN("jafun.lang.String"))

        val result = table.lookupJvmBridge("java.lang.String")
        assertNotNull(result)
        assertEquals(FQDN("jafun.lang.String"), result)
    }

    // --- Method registry ---

    @Test
    fun `register and lookup methods by name`() {
        val table = SymbolTable()
        val intPlus = MethodDef(
            id = FQDN("jafun.lang.Int.+"),
            name = "+",
            parentFqdn = FQDN("jafun.lang.Int"),
            parameters = listOf(Parameter("a", OperandType.SInt32), Parameter("b", OperandType.SInt32)),
            rtn = OperandType.SInt32,
            associativity = Associativity.INFIXL,
            precedence = 100,
            operator = true,
        )
        table.registerMethod(intPlus)

        val result = table.lookupMethods("+")
        assertNotNull(result)
        assertEquals(1, result.size)
        assertEquals(FQDN("jafun.lang.Int.+"), result.keys.first())
    }

    @Test
    fun `methods with same name from different types coexist`() {
        val table = SymbolTable()
        table.registerMethod(MethodDef(
            id = FQDN("jafun.lang.Int.+"),
            name = "+",
            parentFqdn = FQDN("jafun.lang.Int"),
            parameters = listOf(Parameter("a", OperandType.SInt32), Parameter("b", OperandType.SInt32)),
            rtn = OperandType.SInt32,
        ))
        table.registerMethod(MethodDef(
            id = FQDN("jafun.lang.Long.+"),
            name = "+",
            parentFqdn = FQDN("jafun.lang.Long"),
            parameters = listOf(Parameter("a", OperandType.SInt64), Parameter("b", OperandType.SInt64)),
            rtn = OperandType.SInt64,
        ))

        val result = table.lookupMethods("+")
        assertNotNull(result)
        assertEquals(2, result.size)
    }

    @Test
    fun `find method by parameter types`() {
        val table = SymbolTable()
        table.registerMethod(MethodDef(
            id = FQDN("jafun.lang.Int.+"),
            name = "+",
            parentFqdn = FQDN("jafun.lang.Int"),
            parameters = listOf(Parameter("a", OperandType.SInt32), Parameter("b", OperandType.SInt32)),
            rtn = OperandType.SInt32,
        ))
        table.registerMethod(MethodDef(
            id = FQDN("jafun.lang.Long.+"),
            name = "+",
            parentFqdn = FQDN("jafun.lang.Long"),
            parameters = listOf(Parameter("a", OperandType.SInt64), Parameter("b", OperandType.SInt64)),
            rtn = OperandType.SInt64,
        ))

        val result = table.findMethod("+", listOf(OperandType.SInt32, OperandType.SInt32))
        assertNotNull(result)
        assertEquals(FQDN("jafun.lang.Int.+"), result.id)
    }

    @Test
    fun `find method by first parameter type (infix resolution)`() {
        val table = SymbolTable()
        table.registerMethod(MethodDef(
            id = FQDN("jafun.lang.Int.+"),
            name = "+",
            parentFqdn = FQDN("jafun.lang.Int"),
            parameters = listOf(Parameter("a", OperandType.SInt32), Parameter("b", OperandType.SInt32)),
            rtn = OperandType.SInt32,
        ))
        table.registerMethod(MethodDef(
            id = FQDN("jafun.lang.Long.+"),
            name = "+",
            parentFqdn = FQDN("jafun.lang.Long"),
            parameters = listOf(Parameter("a", OperandType.SInt64), Parameter("b", OperandType.SInt64)),
            rtn = OperandType.SInt64,
        ))

        val result = table.findMethodByFirstParamType("+", OperandType.SInt32)
        assertNotNull(result)
        assertEquals(FQDN("jafun.lang.Int.+"), result.id)
    }

    @Test
    fun `find method with no matching parameter types returns null`() {
        val table = SymbolTable()
        table.registerMethod(MethodDef(
            id = FQDN("jafun.lang.Int.+"),
            name = "+",
            parentFqdn = FQDN("jafun.lang.Int"),
            parameters = listOf(Parameter("a", OperandType.SInt32), Parameter("b", OperandType.SInt32)),
            rtn = OperandType.SInt32,
        ))

        assertNull(table.findMethod("+", listOf(OperandType.SInt64, OperandType.SInt64)))
    }

    @Test
    fun `lookup method by FQDN`() {
        val table = SymbolTable()
        val id = FQDN("jafun.lang.Int.+")
        table.registerMethod(MethodDef(
            id = id,
            name = "+",
            parentFqdn = FQDN("jafun.lang.Int"),
            parameters = listOf(Parameter("a", OperandType.SInt32), Parameter("b", OperandType.SInt32)),
            rtn = OperandType.SInt32,
        ))

        val result = table.lookupMethodById(id)
        assertNotNull(result)
        assertEquals("+", result.name)
    }

    @Test
    fun `lookup method by FQDN for nonexistent id returns null`() {
        val table = SymbolTable()
        assertNull(table.lookupMethodById(FQDN("jafun.lang.Int.+")))
    }

    @Test
    fun `lookup method by FQDN works when multiple methods are registered`() {
        val table = SymbolTable()
        table.registerMethod(MethodDef(
            id = FQDN("jafun.lang.Int.+"),
            name = "+",
            parentFqdn = FQDN("jafun.lang.Int"),
            parameters = listOf(Parameter("a", OperandType.SInt32), Parameter("b", OperandType.SInt32)),
            rtn = OperandType.SInt32,
        ))
        table.registerMethod(MethodDef(
            id = FQDN("jafun.lang.Long.+"),
            name = "+",
            parentFqdn = FQDN("jafun.lang.Long"),
            parameters = listOf(Parameter("a", OperandType.SInt64), Parameter("b", OperandType.SInt64)),
            rtn = OperandType.SInt64,
        ))

        assertEquals("jafun.lang.Int.+", table.lookupMethodById(FQDN("jafun.lang.Int.+"))?.id?.value)
        assertEquals("jafun.lang.Long.+", table.lookupMethodById(FQDN("jafun.lang.Long.+"))?.id?.value)
    }

    // --- Inferred return types ---

    @Test
    fun `declared return type takes precedence over inferred`() {
        val table = SymbolTable()
        val methodDef = MethodDef(
            id = FQDN("Script.add"),
            name = "add",
            parentFqdn = null,
            parameters = listOf(Parameter("a", OperandType.SInt32), Parameter("b", OperandType.SInt32)),
            rtn = OperandType.SInt32, // declared
        )

        // Inference would also produce SInt32, but it doesn't matter
        table.setInferredReturnType(FQDN("Script.add"), OperandType.SInt32)

        assertEquals(OperandType.SInt32, table.actualReturnType(methodDef))
    }

    @Test
    fun `inferred return type used when no explicit return type declared`() {
        val table = SymbolTable()
        val methodDef = MethodDef(
            id = FQDN("Script.test"),
            name = "test",
            parentFqdn = null,
            parameters = emptyList(),
            rtn = OperandType.Unknown, // no explicit return type
        )

        table.setInferredReturnType(FQDN("Script.test"), OperandType.SInt32)

        assertEquals(OperandType.SInt32, table.actualReturnType(methodDef))
    }

    @Test
    fun `inferred return type starts at Unknown`() {
        val table = SymbolTable()
        val methodDef = MethodDef(
            id = FQDN("Script.test"),
            name = "test",
            parentFqdn = null,
            parameters = emptyList(),
            rtn = OperandType.Unknown,
        )
        assertEquals(OperandType.Unknown, table.actualReturnType(methodDef))
    }

    @Test
    fun `inferred return type narrows over multiple iterations`() {
        val table = SymbolTable()
        val id = FQDN("Script.test")
        val methodDef = MethodDef(id, "test", null, emptyList(), OperandType.Unknown)

        // Iteration 1: body not fully resolved yet
        assertEquals(OperandType.Unknown, table.actualReturnType(methodDef))

        // Iteration 2: callees resolved, body inferred as Int
        table.setInferredReturnType(id, OperandType.SInt32)
        assertEquals(OperandType.SInt32, table.actualReturnType(methodDef))
    }

    // --- Package tree ---

    @Test
    fun `find or create package`() {
        val table = SymbolTable()
        val pkg = table.findOrCreatePackage("jafun", "lang")
        assertEquals("lang", pkg.name)
        assertEquals("jafun.lang", pkg.fqdn.value)
    }

    @Test
    fun `find existing package`() {
        val table = SymbolTable()
        table.findOrCreatePackage("jafun", "lang")
        val pkg = table.findPackage("jafun", "lang")
        assertNotNull(pkg)
    }

    @Test
    fun `find nonexistent package returns null`() {
        val table = SymbolTable()
        assertNull(table.findPackage("jafun", "nonexistent"))
    }

    @Test
    fun `register class in package`() {
        val table = SymbolTable()
        val pkg = table.findOrCreatePackage("jafun", "lang")
        val typeFqdn = FQDN("jafun.lang.Int")
        pkg.addClass("Int", typeFqdn)

        assertEquals(typeFqdn, pkg.findClass("Int"))
    }

    @Test
    fun `register function in package`() {
        val table = SymbolTable()
        val pkg = table.findOrCreatePackage("jafun", "lang")
        val id = FQDN("jafun.lang.Int.+")
        pkg.addFunction("+", id)

        assertEquals(setOf(id), pkg.findFunctionIds("+"))
    }

    @Test
    fun `class findable by FQDN via type registry`() {
        val table = SymbolTable()
        val intFqdn = FQDN("jafun.lang.Int")
        table.registerType(intFqdn, TypeEntry(intFqdn, OperandType.SInt32))
        table.findOrCreatePackage("jafun", "lang").addClass("Int", intFqdn)

        val entry = table.lookupType(intFqdn)
        assertNotNull(entry)
        assertEquals(intFqdn, entry.fqdn)
        assertEquals(OperandType.SInt32, entry.operandType)
    }

    @Test
    fun `class findable by both short name and FQDN`() {
        val table = SymbolTable()
        val intFqdn = FQDN("jafun.lang.Int")
        table.registerType(intFqdn, TypeEntry(intFqdn, OperandType.SInt32))
        table.findOrCreatePackage("jafun", "lang").addClass("Int", intFqdn)
        table.addImport(FQDN("jafun.lang"))

        val byShortName = table.resolveTypeByShortName("Int")
        assertNotNull(byShortName)
        assertEquals(intFqdn, byShortName.fqdn)

        val byFqdn = table.lookupType(intFqdn)
        assertNotNull(byFqdn)
        assertEquals(byShortName, byFqdn)
    }

    // --- Imports and name resolution ---

    @Test
    fun `resolve type by short name through imports`() {
        val table = SymbolTable()
        val intFqdn = FQDN("jafun.lang.Int")
        table.registerType(intFqdn, TypeEntry(intFqdn, OperandType.SInt32))
        table.findOrCreatePackage("jafun", "lang").addClass("Int", intFqdn)
        table.addImport(FQDN("jafun.lang"))

        val result = table.resolveTypeByShortName("Int")
        assertNotNull(result)
        assertEquals(OperandType.SInt32, result.operandType)
    }

    @Test
    fun `resolve type by short name fails without import`() {
        val table = SymbolTable()
        val intFqdn = FQDN("jafun.lang.Int")
        table.registerType(intFqdn, TypeEntry(intFqdn, OperandType.SInt32))
        table.findOrCreatePackage("jafun", "lang").addClass("Int", intFqdn)
        // No import added

        assertNull(table.resolveTypeByShortName("Int"))
    }

    @Test
    fun `resolve functions by name through imports`() {
        val table = SymbolTable()
        val id = FQDN("jafun.lang.Int.+")
        val def = MethodDef(id, "+", FQDN("jafun.lang.Int"),
            listOf(Parameter("a", OperandType.SInt32), Parameter("b", OperandType.SInt32)),
            OperandType.SInt32)
        table.registerMethod(def)
        table.findOrCreatePackage("jafun", "lang").addFunction("+", id)
        table.addImport(FQDN("jafun.lang"))

        val result = table.resolveFunctionsByName("+")
        assertEquals(1, result.size)
        assertEquals(id, result.keys.first())
    }

    @Test
    fun `resolve functions across multiple imported packages`() {
        val table = SymbolTable()
        val intId = FQDN("jafun.lang.Int.+")
        val longId = FQDN("jafun.lang.Long.+")
        table.registerMethod(MethodDef(intId, "+", FQDN("jafun.lang.Int"),
            listOf(Parameter("a", OperandType.SInt32), Parameter("b", OperandType.SInt32)), OperandType.SInt32))
        table.registerMethod(MethodDef(longId, "+", FQDN("jafun.lang.Long"),
            listOf(Parameter("a", OperandType.SInt64), Parameter("b", OperandType.SInt64)), OperandType.SInt64))
        table.findOrCreatePackage("jafun", "lang").addFunction("+", intId)
        table.findOrCreatePackage("jafun", "lang").addFunction("+", longId)
        table.addImport(FQDN("jafun.lang"))

        val result = table.resolveFunctionsByName("+")
        assertEquals(2, result.size)
    }

    @Test
    fun `import with star suffix is normalized`() {
        val table = SymbolTable()
        table.addImport(FQDN("jafun.lang.*"))
        assertTrue(table.hasImport(FQDN("jafun.lang")))
    }

    // --- Local scope ---

    @Test
    fun `add and lookup variable in current scope`() {
        val table = SymbolTable()
        val variable = VariableDef("x", OperandType.SInt32, mutable = true)
        table.addVariable("x", variable)

        val result = table.lookupVariable("x")
        assertNotNull(result)
        assertEquals("x", result.name)
        assertEquals(OperandType.SInt32, result.type)
        assertTrue(result.mutable)
    }

    @Test
    fun `push and pop scope isolates variables`() {
        val table = SymbolTable()
        table.addVariable("x", VariableDef("x", OperandType.SInt32))

        table.pushScope()
        assertNotNull(table.lookupVariable("x")) // still visible from parent
        table.addVariable("y", VariableDef("y", OperandType.SInt64))
        assertNotNull(table.lookupVariable("y"))

        table.popScope()
        assertNotNull(table.lookupVariable("x")) // still visible
        assertNull(table.lookupVariable("y")) // gone
    }

    @Test
    fun `inner scope shadows outer variable`() {
        val table = SymbolTable()
        table.addVariable("x", VariableDef("x", OperandType.SInt32))

        table.pushScope()
        table.addVariable("x", VariableDef("x", OperandType.SInt64))
        val result = table.lookupVariable("x")
        assertNotNull(result)
        assertEquals(OperandType.SInt64, result.type)

        table.popScope()
        val outer = table.lookupVariable("x")
        assertNotNull(outer)
        assertEquals(OperandType.SInt32, outer.type)
    }

    @Test
    fun `cannot add duplicate variable in same scope`() {
        val table = SymbolTable()
        table.addVariable("x", VariableDef("x", OperandType.SInt32))
        assertFailsWith<IllegalStateException> {
            table.addVariable("x", VariableDef("x", OperandType.SInt64))
        }
    }

    @Test
    fun `replace variable updates existing entry`() {
        val table = SymbolTable()
        table.addVariable("x", VariableDef("x", OperandType.SInt32, initialized = false))
        table.replaceVariable("x", VariableDef("x", OperandType.SInt32, initialized = true))

        val result = table.lookupVariable("x")
        assertNotNull(result)
        assertTrue(result.initialized)
    }

    // --- Operator resolution scenario ---

    @Test
    fun `operator resolution for 1 + 2`() {
        val table = SymbolTable()

        // Register types
        val intFqdn = FQDN("jafun.lang.Int")
        table.registerType(intFqdn, TypeEntry(intFqdn, OperandType.SInt32))
        val longFqdn = FQDN("jafun.lang.Long")
        table.registerType(longFqdn, TypeEntry(longFqdn, OperandType.SInt64))

        // Register + for Int
        val intPlusId = FQDN("jafun.lang.Int.+")
        table.registerMethod(MethodDef(
            id = intPlusId,
            name = "+",
            parentFqdn = FQDN("jafun.lang.Int"),
            parameters = listOf(Parameter("a", OperandType.SInt32), Parameter("b", OperandType.SInt32)),
            rtn = OperandType.SInt32,
            associativity = Associativity.INFIXL,
            precedence = 100,
            operator = true,
        ))

        // Register + for Long
        val longPlusId = FQDN("jafun.lang.Long.+")
        table.registerMethod(MethodDef(
            id = longPlusId,
            name = "+",
            parentFqdn = FQDN("jafun.lang.Long"),
            parameters = listOf(Parameter("a", OperandType.SInt64), Parameter("b", OperandType.SInt64)),
            rtn = OperandType.SInt64,
            associativity = Associativity.INFIXL,
            precedence = 100,
            operator = true,
        ))

        // Setup package tree and imports
        val langPkg = table.findOrCreatePackage("jafun", "lang")
        langPkg.addFunction("+", intPlusId)
        langPkg.addFunction("+", longPlusId)
        table.addImport(FQDN("jafun.lang"))

        // Simulate: lhs is 1 (SInt32), see +
        val candidates = table.resolveFunctionsByName("+")
        assertEquals(2, candidates.size)

        // Filter by first parameter type (lhs is SInt32)
        val matched = candidates.values.firstOrNull { def ->
            def.parameters.isNotEmpty() && def.parameters[0].type == OperandType.SInt32
        }
        assertNotNull(matched)
        assertEquals(intPlusId, matched.id)
        assertEquals(OperandType.SInt32, matched.rtn) // declared return type
    }

    // --- Function return type inference scenario ---

    @Test
    fun `function return type inference narrows over iterations`() {
        val table = SymbolTable()
        val methodId = FQDN("Script.test")

        // Register function with no explicit return type
        val methodDef = MethodDef(
            id = methodId,
            name = "test",
            parentFqdn = null,
            parameters = emptyList(),
            rtn = OperandType.Unknown,
        )
        table.registerMethod(methodDef)

        // Iteration 1: body not yet resolved
        assertEquals(OperandType.Unknown, table.actualReturnType(methodDef))

        // Iteration 2: body resolved to Int
        table.setInferredReturnType(methodId, OperandType.SInt32)
        assertEquals(OperandType.SInt32, table.actualReturnType(methodDef))
    }

    @Test
    fun `function with explicit return type ignores inference`() {
        val table = SymbolTable()
        val methodId = FQDN("Script.add")

        val methodDef = MethodDef(
            id = methodId,
            name = "add",
            parentFqdn = null,
            parameters = listOf(Parameter("a", OperandType.SInt32), Parameter("b", OperandType.SInt32)),
            rtn = OperandType.SInt32, // explicitly declared
        )
        table.registerMethod(methodDef)

        // Even if inference produces something else (e.g. Unknown if body broken),
        // the declared type wins
        table.setInferredReturnType(methodId, OperandType.Unknown)
        assertEquals(OperandType.SInt32, table.actualReturnType(methodDef))
    }

    // --- Runtime fallback via TypeResolver ---

    @Test
    fun `lookupType falls back to TypeResolver when not in registry`() {
        val table = SymbolTable()
        val intFqdn = FQDN("jafun.lang.Int")
        val mock = TestResolver(
            classes = mapOf("jafun.lang.Int" to TypeEntry(intFqdn, OperandType.SInt32)),
        )
        table.setTypeResolver(mock)

        val result = table.lookupType(intFqdn)
        assertNotNull(result)
        assertEquals(OperandType.SInt32, result.operandType)
    }

    @Test
    fun `lookupType caches type resolved by fallback`() {
        val table = SymbolTable()
        val intFqdn = FQDN("jafun.lang.Int")
        var resolveCount = 0
        val mock = TestResolver(
            classes = mapOf("jafun.lang.Int" to TypeEntry(intFqdn, OperandType.SInt32)),
            onResolveClass = { resolveCount++ },
        )
        table.setTypeResolver(mock)

        table.lookupType(intFqdn)
        table.lookupType(intFqdn) // second call should use cache
        assertEquals(1, resolveCount)
    }

    @Test
    fun `lookupType returns null when resolver cannot find it`() {
        val table = SymbolTable()
        val mock = TestResolver()
        table.setTypeResolver(mock)
        assertNull(table.lookupType(FQDN("nonexistent.Foo")))
    }

    @Test
    fun `lookupType returns null without resolver`() {
        val table = SymbolTable()
        assertNull(table.lookupType(FQDN("jafun.lang.Int")))
    }

    @Test
    fun `resolveDottedName returns Type for registered type`() {
        val table = SymbolTable()
        val intFqdn = FQDN("jafun.lang.Int")
        table.registerType(intFqdn, TypeEntry(intFqdn, OperandType.SInt32))

        val result = table.resolveDottedName("jafun.lang.Int")
        assertTrue(result is SymbolTable.ResolutionResult.Type)
        assertEquals(intFqdn, (result as SymbolTable.ResolutionResult.Type).entry.fqdn)
    }

    @Test
    fun `resolveDottedName returns Type when resolver finds it`() {
        val table = SymbolTable()
        val stringFqdn = FQDN("java.lang.String")
        val mock = TestResolver(
            classes = mapOf("java.lang.String" to TypeEntry(stringFqdn, OperandType.StringType)),
        )
        table.setTypeResolver(mock)

        val result = table.resolveDottedName("java.lang.String")
        assertTrue(result is SymbolTable.ResolutionResult.Type)
        assertEquals(stringFqdn, (result as SymbolTable.ResolutionResult.Type).entry.fqdn)
    }

    @Test
    fun `resolveDottedName returns Method for type with method member`() {
        val table = SymbolTable()
        val stringFqdn = FQDN("java.lang.String")
        val containsId = FQDN("java.lang.String.contains(java.lang.CharSequence)")
        val containsMethod = MethodDef(
            id = containsId, name = "contains", parentFqdn = stringFqdn,
            parameters = listOf(Parameter("cs", OperandType.StringType)),
            rtn = OperandType.UInt1,
        )
        val mock = TestResolver(
            classes = mapOf("java.lang.String" to TypeEntry(stringFqdn, OperandType.StringType)),
            members = mapOf("java.lang.String.contains" to MemberResult.Method(containsMethod)),
        )
        table.setTypeResolver(mock)

        val result = table.resolveDottedName("java.lang.String.contains")
        assertTrue(result is SymbolTable.ResolutionResult.Method)
        assertEquals("contains", (result as SymbolTable.ResolutionResult.Method).def.name)
    }

    @Test
    fun `resolveDottedName returns null for type with field`() {
        val table = SymbolTable()
        val sysFqdn = FQDN("java.lang.System")
        val mock = TestResolver(
            classes = mapOf("java.lang.System" to TypeEntry(sysFqdn, OperandType.Unknown)),
            members = mapOf(
                "java.lang.System.out" to MemberResult.Field(
                    VariableDef("out", OperandType.Unknown),
                    "java.io.PrintStream",
                ),
            ),
        )
        table.setTypeResolver(mock)

        assertNull(table.resolveDottedName("java.lang.System.out"))
    }

    @Test
    fun `resolveDottedName returns Method for chain field then method`() {
        val table = SymbolTable()
        val sysFqdn = FQDN("java.lang.System")
        val printStreamFqdn = FQDN("java.io.PrintStream")
        val printlnId = FQDN("java.io.PrintStream.println(java.lang.String)")
        val printlnMethod = MethodDef(
            id = printlnId, name = "println", parentFqdn = printStreamFqdn,
            parameters = listOf(Parameter("s", OperandType.StringType)),
            rtn = OperandType.Unit,
        )
        val mock = TestResolver(
            classes = mapOf(
                "java.lang.System" to TypeEntry(sysFqdn, OperandType.Unknown),
                "java.io.PrintStream" to TypeEntry(printStreamFqdn, OperandType.Unknown),
            ),
            members = mapOf(
                "java.lang.System.out" to MemberResult.Field(
                    VariableDef("out", OperandType.Unknown),
                    "java.io.PrintStream",
                ),
                "java.io.PrintStream.println" to MemberResult.Method(printlnMethod),
            ),
        )
        table.setTypeResolver(mock)

        val result = table.resolveDottedName("java.lang.System.out.println")
        assertTrue(result is SymbolTable.ResolutionResult.Method)
        assertEquals("println", (result as SymbolTable.ResolutionResult.Method).def.name)
    }

    @Test
    fun `resolveDottedName returns null when no prefix resolves`() {
        val table = SymbolTable()
        val mock = TestResolver()
        table.setTypeResolver(mock)
        assertNull(table.resolveDottedName("nonexistent.Foo"))
    }

    @Test
    fun `resolveDottedName prefers longest matching prefix for pure type`() {
        val table = SymbolTable()
        val langFqdn = FQDN("java.lang")
        val sysFqdn = FQDN("java.lang.System")
        table.registerType(langFqdn, TypeEntry(langFqdn, OperandType.Unknown))
        table.registerType(sysFqdn, TypeEntry(sysFqdn, OperandType.Unknown))

        // With no members, the longest prefix wins
        val result = table.resolveDottedName("java.lang.System")
        assertTrue(result is SymbolTable.ResolutionResult.Type)
        assertEquals(sysFqdn, (result as SymbolTable.ResolutionResult.Type).entry.fqdn)
    }

    @Test
    fun `resolveDottedName returns null when member cannot be resolved`() {
        val table = SymbolTable()
        table.registerType(FQDN("java.lang.System"), TypeEntry(FQDN("java.lang.System"), OperandType.Unknown))

        // No resolver set, so members can't be resolved
        assertNull(table.resolveDottedName("java.lang.System.out"))
    }

    @Test
    fun `lookupType fallback registers type in package tree for import resolution`() {
        val table = SymbolTable()
        val stringFqdn = FQDN("java.lang.String")
        val mock = TestResolver(
            classes = mapOf("java.lang.String" to TypeEntry(stringFqdn, OperandType.StringType)),
        )
        table.setTypeResolver(mock)
        table.addImport(FQDN("java.lang"))

        table.lookupType(FQDN("java.lang.String")) // triggers resolver -> registers type + package tree

        val resolved = table.resolveTypeByShortName("String")
        assertNotNull(resolved)
        assertEquals(stringFqdn, resolved.fqdn)
    }

    @Test
    fun `MemberResult Field from resolver`() {
        val resolver = TestResolver(
            members = mapOf(
                "java.lang.System.out" to MemberResult.Field(
                    VariableDef("out", OperandType.Unknown),
                    "java.io.PrintStream",
                ),
            ),
        )
        val result = resolver.resolveMember("java.lang.System", "out")
        assertTrue(result is MemberResult.Field)
        assertEquals("java.io.PrintStream", (result as MemberResult.Field).fieldTypeFqdn)
    }

    @Test
    fun `scope IDs are unique and increment`() {
        val table = SymbolTable()
        val id1 = table.currentScopeId
        table.pushScope()
        val id2 = table.currentScopeId
        table.pushScope()
        val id3 = table.currentScopeId
        table.popScope()
        assertEquals(id1 + 1, id2)
        assertEquals(id2 + 1, id3)
    }

    @Test
    fun `scope ID persists through pop`() {
        val table = SymbolTable()
        table.pushScope()
        val innerId = table.currentScopeId
        table.popScope()
        assertTrue(table.currentScopeId != innerId) // root scope has a different id
    }
}

private class TestResolver(
    private val classes: Map<String, TypeEntry> = emptyMap(),
    private val methods: Map<String, List<MethodDef>> = emptyMap(),
    private val fields: Map<String, VariableDef> = emptyMap(),
    private val members: Map<String, MemberResult> = emptyMap(),
    private val onResolveClass: () -> Unit = {},
) : TypeResolver {
    override fun resolveClass(fqdn: String): TypeEntry? {
        onResolveClass()
        return classes[fqdn]
    }

    override fun resolveMethods(classFqdn: String, methodName: String): List<MethodDef> =
        methods["$classFqdn.$methodName"] ?: emptyList()

    override fun resolveField(classFqdn: String, fieldName: String): VariableDef? =
        fields["$classFqdn.$fieldName"]

    override fun resolveMember(classFqdn: String, memberName: String): MemberResult? =
        members["$classFqdn.$memberName"]

    override fun resolveConstructors(classFqdn: String): List<MethodDef> = emptyList()
}
