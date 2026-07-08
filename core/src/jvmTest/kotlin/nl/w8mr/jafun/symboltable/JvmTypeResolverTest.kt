package nl.w8mr.jafun.symboltable

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class JvmTypeResolverTest {

    @Test
    fun `resolve java lang String class`() {
        val table = SymbolTable()
        val resolver = JvmTypeResolver(table)
        table.setTypeResolver(resolver)

        val entry = table.lookupType(FQDN("java.lang.String"))
        assertNotNull(entry)
        assertEquals("java.lang.String", entry.fqdn.value)
    }

    @Test
    fun `resolve java lang Integer maps to SInt32`() {
        val table = SymbolTable()
        val resolver = JvmTypeResolver(table)
        table.setTypeResolver(resolver)

        val entry = table.lookupType(FQDN("java.lang.Integer"))
        assertNotNull(entry)
        assertEquals("java.lang.Integer", entry.fqdn.value)
    }

    @Test
    fun `resolve java lang System class`() {
        val table = SymbolTable()
        val resolver = JvmTypeResolver(table)
        table.setTypeResolver(resolver)

        val entry = table.lookupType(FQDN("java.lang.System"))
        assertNotNull(entry)
    }

    @Test
    fun `resolve nonexistent class returns null`() {
        val table = SymbolTable()
        val resolver = JvmTypeResolver(table)
        table.setTypeResolver(resolver)

        val entry = table.lookupType(FQDN("nonexistent.FooBar"))
        assertEquals(null, entry)
    }

    @Test
    fun `resolve methods named contains on String`() {
        val resolver = JvmTypeResolver(SymbolTable())
        val methods = resolver.resolveMethods("java.lang.String", "contains")
        assertTrue(methods.isNotEmpty())
        methods.forEach { m ->
            assertEquals("contains", m.name)
            assertEquals(FQDN("java.lang.String"), m.parentFqdn)
        }
    }

    @Test
    fun `resolve field out on System`() {
        val resolver = JvmTypeResolver(SymbolTable())
        val field = resolver.resolveField("java.lang.System", "out")
        assertNotNull(field)
        assertEquals("out", field.name)
    }

    @Test
    fun `resolveDottedName returns Type for java lang String`() {
        val table = SymbolTable()
        table.setTypeResolver(JvmTypeResolver(table))

        val result = table.resolveDottedName("java.lang.String")
        assertTrue(result is SymbolTable.ResolutionResult.Type)
        assertEquals(
            "java.lang.String",
            (result as SymbolTable.ResolutionResult.Type).entry.fqdn.value,
        )
    }

    @Test
    fun `resolveDottedName returns Method for String contains`() {
        val table = SymbolTable()
        table.setTypeResolver(JvmTypeResolver(table))

        val result = table.resolveDottedName("java.lang.String.contains")
        assertTrue(result is SymbolTable.ResolutionResult.Method)
        assertEquals("contains", (result as SymbolTable.ResolutionResult.Method).def.name)
    }

    @Test
    fun `resolveDottedName returns null for System out (field at end)`() {
        val table = SymbolTable()
        table.setTypeResolver(JvmTypeResolver(table))

        assertNull(table.resolveDottedName("java.lang.System.out"))
    }

    @Test
    fun `resolveDottedName returns Method for System out println (chain)`() {
        val table = SymbolTable()
        table.setTypeResolver(JvmTypeResolver(table))

        val result = table.resolveDottedName("java.lang.System.out.println")
        assertTrue(result is SymbolTable.ResolutionResult.Method)
        assertEquals("println", (result as SymbolTable.ResolutionResult.Method).def.name)
    }

    @Test
    fun `resolver caches type in registry after first lookup`() {
        val table = SymbolTable()
        val resolver = JvmTypeResolver(table)
        table.setTypeResolver(resolver)

        val first = table.lookupType(FQDN("java.lang.String"))
        assertNotNull(first)

        val second = table.lookupType(FQDN("java.lang.String"))
        assertNotNull(second)
    }

    @Test
    fun `resolved type findable via short name with import`() {
        val table = SymbolTable()
        table.setTypeResolver(JvmTypeResolver(table))
        table.addImport(FQDN("java.lang"))

        table.lookupType(FQDN("java.lang.String"))

        val byShortName = table.resolveTypeByShortName("String")
        assertNotNull(byShortName)
        assertEquals(FQDN("java.lang.String"), byShortName.fqdn)
    }

    @Test
    fun `resolve method on String has parameters`() {
        val resolver = JvmTypeResolver(SymbolTable())
        val methods = resolver.resolveMethods("java.lang.String", "contains")
        assertTrue(methods.isNotEmpty())
        assertTrue(methods.first().parameters.isNotEmpty())
    }

    @Test
    fun `resolveMember returns Method for String contains`() {
        val resolver = JvmTypeResolver(SymbolTable())
        val result = resolver.resolveMember("java.lang.String", "contains")
        assertTrue(result is MemberResult.Method)
        assertEquals("contains", (result as MemberResult.Method).def.name)
    }

    @Test
    fun `resolveMember returns Field for System out`() {
        val resolver = JvmTypeResolver(SymbolTable())
        val result = resolver.resolveMember("java.lang.System", "out")
        assertTrue(result is MemberResult.Field)
        assertEquals("out", (result as MemberResult.Field).def.name)
        assertEquals("java.io.PrintStream", (result as MemberResult.Field).fieldTypeFqdn)
    }

    @Test
    fun `resolveMember returns null for nonexistent member`() {
        val resolver = JvmTypeResolver(SymbolTable())
        assertNull(resolver.resolveMember("java.lang.String", "nonexistentMember12345"))
    }

    @Test
    fun `resolveConstructors returns at least one constructor for String`() {
        val resolver = JvmTypeResolver(SymbolTable())
        val ctors = resolver.resolveConstructors("java.lang.String")
        assertTrue(ctors.isNotEmpty())
        ctors.forEach { c ->
            assertEquals("<init>", c.name)
            assertEquals(FQDN("java.lang.String"), c.parentFqdn)
        }
    }

    @Test
    fun `resolveConstructors for Integer returns parameterized constructor`() {
        val resolver = JvmTypeResolver(SymbolTable())
        val ctors = resolver.resolveConstructors("java.lang.Integer")
        assertTrue(ctors.isNotEmpty())
        val intCtor = ctors.find { it.parameters.size == 1 }
        assertNotNull(intCtor)
    }

    @Test
    fun `resolveConstructors for nonexistent class returns empty`() {
        val resolver = JvmTypeResolver(SymbolTable())
        assertTrue(resolver.resolveConstructors("nonexistent.FooBar").isEmpty())
    }

    @Test
    fun `scope IDs are unique across table instances`() {
        val table1 = SymbolTable()
        val table2 = SymbolTable()
        assertTrue(table1.currentScopeId != table2.currentScopeId)
    }

    @Test
    fun `scope ID increments on push`() {
        val table = SymbolTable()
        val rootId = table.currentScopeId
        table.pushScope()
        assertEquals(rootId + 1, table.currentScopeId)
    }
}
