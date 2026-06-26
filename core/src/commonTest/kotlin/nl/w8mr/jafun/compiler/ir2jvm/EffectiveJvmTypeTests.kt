package nl.w8mr.jafun.compiler.ir2jvm

import nl.w8mr.jafun.OperandType
import nl.w8mr.jafun.Type
import nl.w8mr.jafun.compiler.effectiveJvmType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * Comprehensive unit tests for effectiveJvmType() function.
 * 
 * This function is critical for correct bytecode generation, especially for
 * nested single-field value classes. It must:
 * 1. Leave non-VC types unchanged
 * 2. Unwrap single-field VCs to their field type
 * 3. Recursively unwrap nested single-field VCs all the way to a primitive
 * 4. Handle multi-field VCs that should NOT be unwrapped
 * 5. Handle null constructors gracefully
 */
class EffectiveJvmTypeTests {

    // Test primitive types - should pass through unchanged
    @Test
    fun testInt32PassesThrough() {
        val type = OperandType.SInt32
        val result = effectiveJvmType(type)
        assertEquals(OperandType.SInt32, result)
    }

    @Test
    fun testStringPassesThrough() {
        val type = OperandType.StringType
        val result = effectiveJvmType(type)
        assertEquals(OperandType.StringType, result)
    }

    @Test
    fun testBooleanPassesThrough() {
        val type = OperandType.UInt1
        val result = effectiveJvmType(type)
        assertEquals(OperandType.UInt1, result)
    }

    @Test
    fun testCharPassesThrough() {
        val type = OperandType.CharType
        val result = effectiveJvmType(type)
        assertEquals(OperandType.CharType, result)
    }

    @Test
    fun testArrayPassesThrough() {
        val type = OperandType.Array(OperandType.SInt32)
        val result = effectiveJvmType(type)
        assertEquals(type, result)
    }

    @Test
    fun testUnknownPassesThrough() {
        val type = OperandType.Unknown
        val result = effectiveJvmType(type)
        assertEquals(OperandType.Unknown, result)
    }

    // Test single-field value classes
    @Test
    fun testSingleFieldVCUnwrapsToInt32() {
        // value class Id(value: Int)
        val idVc = createSingleFieldVC("Id", OperandType.SInt32)
        val result = effectiveJvmType(idVc)
        assertEquals(OperandType.SInt32, result)
    }

    @Test
    fun testSingleFieldVCUnwrapsToString() {
        // value class UserId(id: String)
        val userIdVc = createSingleFieldVC("UserId", OperandType.StringType)
        val result = effectiveJvmType(userIdVc)
        assertEquals(OperandType.StringType, result)
    }

    @Test
    fun testSingleFieldVCUnwrapsToBoolean() {
        // value class Flag(enabled: Boolean)
        val flagVc = createSingleFieldVC("Flag", OperandType.UInt1)
        val result = effectiveJvmType(flagVc)
        assertEquals(OperandType.UInt1, result)
    }

    // Test two-level nesting (Box -> Point -> Int)
    @Test
    fun testTwoLevelSingleFieldVCUnwraps() {
        // value class Point(x: Int)
        // value class Box(topLeft: Point)
        val pointVc = createSingleFieldVC("Point", OperandType.SInt32)
        val boxVc = createSingleFieldVC("Box", pointVc)
        
        val result = effectiveJvmType(boxVc)
        assertEquals(OperandType.SInt32, result, "Box(Point(Int)) should unwrap to Int")
    }

    // Test three-level nesting (Wrapper -> Box -> Point -> Int)
    @Test
    fun testThreeLevelSingleFieldVCUnwraps() {
        // value class Point(x: Int)
        // value class Box(topLeft: Point)
        // value class Wrapper(box: Box)
        val pointVc = createSingleFieldVC("Point", OperandType.SInt32)
        val boxVc = createSingleFieldVC("Box", pointVc)
        val wrapperVc = createSingleFieldVC("Wrapper", boxVc)
        
        val result = effectiveJvmType(wrapperVc)
        assertEquals(OperandType.SInt32, result, "Wrapper(Box(Point(Int))) should unwrap to Int")
    }

    // Test four-level nesting (deep nesting scenario)
    @Test
    fun testFourLevelSingleFieldVCUnwraps() {
        // Deep nesting: Level4(Level3(Level2(Level1(Int))))
        val level1Vc = createSingleFieldVC("Level1", OperandType.SInt32)
        val level2Vc = createSingleFieldVC("Level2", level1Vc)
        val level3Vc = createSingleFieldVC("Level3", level2Vc)
        val level4Vc = createSingleFieldVC("Level4", level3Vc)
        
        val result = effectiveJvmType(level4Vc)
        assertEquals(OperandType.SInt32, result, "Four levels of nesting should unwrap to Int")
    }

    // Test multi-field VC (should NOT unwrap)
    @Test
    fun testMultiFieldVCDoesNotUnwrap() {
        // value class User(id: Int, name: String) - multi-field
        val userVc = Type.JFClass("User", null, Type.ClassKind.VALUE_CLASS)
        val idParam = Type.JFVariableSymbol("id", OperandType.SInt32)
        val nameParam = Type.JFVariableSymbol("name", OperandType.StringType)
        userVc.constructor = Type.JFConstructor(
            listOf(idParam, nameParam),
            userVc
        )
        
        val result = effectiveJvmType(userVc)
        assertIs<Type.JFClass>(result, "Multi-field VC should NOT be unwrapped")
        assertEquals(userVc, result)
    }

    // Test single-field VC wrapped around a multi-field VC
    @Test
    fun testSingleFieldVCWrappingMultiFieldVC() {
        // value class User(id: Int, name: String)
        // value class UserWrapper(user: User)
        val userVc = Type.JFClass("User", null, Type.ClassKind.VALUE_CLASS)
        val idParam = Type.JFVariableSymbol("id", OperandType.SInt32)
        val nameParam = Type.JFVariableSymbol("name", OperandType.StringType)
        userVc.constructor = Type.JFConstructor(
            listOf(idParam, nameParam),
            userVc
        )
        
        val wrapperVc = createSingleFieldVC("UserWrapper", userVc)
        
        val result = effectiveJvmType(wrapperVc)
        assertIs<Type.JFClass>(result, "VC wrapping multi-field VC should stop at multi-field VC")
        assertEquals(userVc, result)
    }

    // Test VC with no constructor (edge case)
    @Test
    fun testVCWithNoConstructorReturnsSelf() {
        val vcNoConstructor = Type.JFClass("BrokenVC", null, Type.ClassKind.VALUE_CLASS)
        // Don't set constructor (it stays null)
        
        val result = effectiveJvmType(vcNoConstructor)
        assertEquals(vcNoConstructor, result, "VC with no constructor should return itself")
    }

    // Test regular class (not a VC) should pass through
    @Test
    fun testRegularClassPassesThrough() {
        val regularClass = Type.JFClass("User", null, Type.ClassKind.NORMAL)
        val result = effectiveJvmType(regularClass)
        assertEquals(regularClass, result)
    }

    // Test deeply nested with different leaf types
    @Test
    fun testDeeplyNestedWithStringLeaf() {
        // String -> Wrapper2 -> Wrapper1 -> IdType
        val idTypeVc = createSingleFieldVC("IdType", OperandType.StringType)
        val wrapper1Vc = createSingleFieldVC("Wrapper1", idTypeVc)
        val wrapper2Vc = createSingleFieldVC("Wrapper2", wrapper1Vc)
        
        val result = effectiveJvmType(wrapper2Vc)
        assertEquals(OperandType.StringType, result, "Nested VCs with String leaf should unwrap to String")
    }

    @Test
    fun testDeeplyNestedWithBooleanLeaf() {
        // Boolean -> Wrapper3 -> Wrapper2 -> Wrapper1 -> FlagType
        val flagTypeVc = createSingleFieldVC("FlagType", OperandType.UInt1)
        val wrapper1Vc = createSingleFieldVC("Wrapper1", flagTypeVc)
        val wrapper2Vc = createSingleFieldVC("Wrapper2", wrapper1Vc)
        val wrapper3Vc = createSingleFieldVC("Wrapper3", wrapper2Vc)
        
        val result = effectiveJvmType(wrapper3Vc)
        assertEquals(OperandType.UInt1, result, "Nested VCs with Boolean leaf should unwrap to Boolean")
    }

    // Test VC with Array field
    @Test
    fun testVCWithArrayFieldUnwraps() {
        // value class ArrayWrapper(data: Array<Int>)
        val arrayType = OperandType.Array(OperandType.SInt32)
        val arrayVc = createSingleFieldVC("ArrayWrapper", arrayType)
        
        val result = effectiveJvmType(arrayVc)
        assertEquals(arrayType, result, "Single-field VC wrapping array should unwrap to array")
    }

    // Test alternating: single-field, multi-field, single-field
    @Test
    fun testAlternatingFieldCounts() {
        // value class A(x: Int) - single
        // value class B(a: A, y: Int) - multi
        // value class C(b: B) - single
        
        val aVc = createSingleFieldVC("A", OperandType.SInt32)
        
        val bVc = Type.JFClass("B", null, Type.ClassKind.VALUE_CLASS)
        val bParam1 = Type.JFVariableSymbol("a", aVc)
        val bParam2 = Type.JFVariableSymbol("y", OperandType.SInt32)
        bVc.constructor = Type.JFConstructor(listOf(bParam1, bParam2), bVc)
        
        val cVc = createSingleFieldVC("C", bVc)
        
        val result = effectiveJvmType(cVc)
        assertIs<Type.JFClass>(result, "Should stop at multi-field VC B")
        assertEquals(bVc, result)
    }

    // Helper function to create a single-field VC
    private fun createSingleFieldVC(name: String, fieldType: OperandType<*>): Type.JFClass {
        val vc = Type.JFClass(name, null, Type.ClassKind.VALUE_CLASS)
        val param = Type.JFVariableSymbol("value", fieldType)
        vc.constructor = Type.JFConstructor(listOf(param), vc)
        return vc
    }
}
