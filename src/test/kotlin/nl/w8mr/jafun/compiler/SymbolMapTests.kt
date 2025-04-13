package nl.w8mr.jafun.compiler

import nl.w8mr.jafun.Type
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class SymbolMapTests {
    private lateinit var localSymbolMap: LocalSymbolMap

    @BeforeEach
    fun setUp() {
        // Mock parent SymbolMap
        val parentSymbolMap = object : SymbolMap {
            private val data = mutableMapOf<String, List<Type.OperandType<*>>>()

            init {
                add("parentPath", Type.StringType)
            }

            override fun find(path: String): List<Type.OperandType<*>> {
                return data[path] ?: emptyList()
            }

            override fun add(path: String, typeSig: Type.OperandType<*>) {
                data[path] = listOf(typeSig)
            }

            override fun incSymbolMapCount(): Int = 0
            override val symbolMapId: Int = 0
            override fun contains(path: String): Boolean = data.containsKey(path)
        }

        localSymbolMap = LocalSymbolMap(parentSymbolMap)
    }

    @Test
    fun testAddAndFind() {
        val typeString = Type.StringType
        val typeInt = Type.SInt32
        localSymbolMap.add("testPath", typeString)
        assertTrue(localSymbolMap.contains("testPath"))
        assertEquals(listOf(typeString), localSymbolMap.find("testPath"))
    }

    @Test
    fun testFindFromParent() {
        val type = Type.StringType
        assertEquals(listOf(type), localSymbolMap.find("parentPath"))
    }

    @Test
    fun testFindSingle() {
        val type = Type.StringType
        localSymbolMap.add("singlePath", type)
        assertEquals(type, localSymbolMap.findSingle("singlePath"))
    }

    @Test
    fun testFindSingleOrNull() {
        val type = Type.StringType
        localSymbolMap.add("singleOrNullPath", type)
        assertEquals(type, localSymbolMap.findSingleOrNull("singleOrNullPath"))
        assertNull(localSymbolMap.findSingleOrNull("nonExistentPath"))
    }

    @Test
    fun testFindMethod() {
        // Implement method tests
    }
}
