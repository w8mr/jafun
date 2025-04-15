package nl.w8mr.jafun.compiler

import nl.w8mr.jafun.OperandType
import nl.w8mr.jafun.TypeSymbol
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class SymbolMapTests {
    private lateinit var localSymbolMap: LocalSymbolMap

    @BeforeEach
    fun setUp() {
        // Mock parent SymbolMap
        val parentSymbolMap = object : SymbolMap {
            private val data = mutableMapOf<TypeSymbol?, Map<String, List<TypeSymbol>>>()

            init {
                add(null, "parentPath", OperandType.StringType)
            }

            override fun find(type: TypeSymbol?, path: String): List<TypeSymbol> {
                return data[type]?.get(path) ?: emptyList()
            }

            override fun contains(type: TypeSymbol?, path: String): Boolean {
                return data[type]?.containsKey(path) ?: false
            }

            override fun incSymbolMapCount(): Int = 0
            override val symbolMapId: Int = 0
            override fun add(type: TypeSymbol?, path: String, typeSig: TypeSymbol) {
                data[type] = mapOf(path to listOf(typeSig))
            }
        }

        localSymbolMap = LocalSymbolMap(parentSymbolMap)
    }

    @Test
    fun testAddAndFind() {
        val typeString = OperandType.StringType
        localSymbolMap.add(null, "testPath", typeString)
        assertTrue(localSymbolMap.contains(null, "testPath"))
        assertEquals(listOf(typeString), localSymbolMap.find(null, "testPath"))
    }

    @Test
    fun testFindFromParent() {
        val type = OperandType.StringType
        assertEquals(listOf(type), localSymbolMap.find(null, "parentPath"))
    }

    @Test
    fun testFindSingle() {
        val type = OperandType.StringType
        localSymbolMap.add(null, "singlePath", type)
        assertEquals(type, localSymbolMap.findSingle(null, "singlePath"))
    }

    @Test
    fun testFindSingleOrNull() {
        val type = OperandType.StringType
        localSymbolMap.add(null, "singleOrNullPath", type)
        assertEquals(type, localSymbolMap.findSingleOrNull(null, "singleOrNullPath"))
        assertNull(localSymbolMap.findSingleOrNull(null, "nonExistentPath"))
    }

    @Test
    fun testFindMethod() {
        // Implement method tests
    }
}
