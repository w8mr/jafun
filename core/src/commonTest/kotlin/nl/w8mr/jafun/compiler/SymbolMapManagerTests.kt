package nl.w8mr.jafun.compiler

import nl.w8mr.jafun.OperandType
import nl.w8mr.jafun.Type.JFClass
import nl.w8mr.jafun.Type.JFVariableSymbol
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SymbolMapManagerTests {
    private lateinit var symbolMapManager: SymbolMapManager

    @BeforeTest
    fun setUp() {
        symbolMapManager = SymbolMapManager()
    }

    @Test
    fun testInitialization() {
        val type = JFVariableSymbol("arguments", OperandType.Array(JFClass("java.lang.String")), symbolMapManager.currentSymbolMap, false)
        assertTrue(symbolMapManager.currentSymbolMap.contains(null, "arguments"))
        assertEquals(listOf(type), symbolMapManager.currentSymbolMap.find(null, "arguments"))
    }

    @Test
    fun testReset() {
        val type = JFVariableSymbol("myVar", OperandType.SInt32, symbolMapManager.currentSymbolMap, true)
        symbolMapManager.add("myVar", type)
        assertTrue(symbolMapManager.currentSymbolMap.contains(null, "myVar"))
        symbolMapManager.reset()
        assertFalse(symbolMapManager.currentSymbolMap.contains(null, "myVar"))
    }

    @Test
    fun testAddSymbol() {
        assertFalse(symbolMapManager.currentSymbolMap.contains(null, "myVar"))
        val type = JFVariableSymbol("myVar", OperandType.SInt32, symbolMapManager.currentSymbolMap, true)
        symbolMapManager.add("myVar", type)
        assertTrue(symbolMapManager.currentSymbolMap.contains(null, "myVar"))
        assertEquals(type, symbolMapManager.currentSymbolMap.findSingle(null, "myVar"))
    }

    @Test
    fun testNewVariableSymbol() {
        val type = OperandType.SInt32
        val variableSymbol = symbolMapManager.newVariableSymbol("myVar", type, true)
        assertEquals("myVar", variableSymbol.name)
        assertEquals(OperandType.SInt32, variableSymbol.type)
        assertTrue(symbolMapManager.currentSymbolMap.contains(null, "myVar"))
        assertEquals(symbolMapManager.currentSymbolMap, variableSymbol.symbolMap)
        assertTrue(variableSymbol.mutable)
    }

    @Test
    fun testNewVariableSymbolThrowsExceptionOnDuplicate() {
        val type = OperandType.SInt32
        symbolMapManager.newVariableSymbol("myVar", type, true)
        assertFailsWith<IllegalStateException> {
            symbolMapManager.newVariableSymbol("myVar", type, true)
        }
    }
}
