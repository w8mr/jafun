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
            private val data2 = mutableMapOf<Type.OperandType<*>?, Map<String, List<Type.OperandType<*>>>>()

            init {
                add(null, "parentPath", Type.StringType)
            }

            override fun find(type: Type.OperandType<*>?, path: String): List<Type.OperandType<*>> {
                return data2[type]?.get(path) ?: emptyList()
            }

            override fun contains(type: Type.OperandType<*>?, path: String): Boolean {
                return data2[type]?.containsKey(path) ?: false
            }

            override fun incSymbolMapCount(): Int = 0
            override val symbolMapId: Int = 0
            override fun add(type: Type.OperandType<*>?, path: String, typeSig: Type.OperandType<*>) {
                data2[type] = mapOf(path to listOf(typeSig))
            }
        }

        localSymbolMap = LocalSymbolMap(parentSymbolMap)
    }

    @Test
    fun testAddAndFind() {
        val typeString = Type.StringType
        val typeInt = Type.SInt32
        localSymbolMap.add(null, "testPath", typeString)
        assertTrue(localSymbolMap.contains(null, "testPath"))
        assertEquals(listOf(typeString), localSymbolMap.find(null, "testPath"))
    }

    @Test
    fun testFindFromParent() {
        val type = Type.StringType
        assertEquals(listOf(type), localSymbolMap.find(null, "parentPath"))
    }

    @Test
    fun testFindSingle() {
        val type = Type.StringType
        localSymbolMap.add(null, "singlePath", type)
        assertEquals(type, localSymbolMap.findSingle(null, "singlePath"))
    }

    @Test
    fun testFindSingleOrNull() {
        val type = Type.StringType
        localSymbolMap.add(null, "singleOrNullPath", type)
        assertEquals(type, localSymbolMap.findSingleOrNull(null, "singleOrNullPath"))
        assertNull(localSymbolMap.findSingleOrNull(null, "nonExistentPath"))
    }

    @Test
    fun testFindMethod() {
        // Implement method tests
    }
}
