package nl.w8mr.jafun.compiler

import nl.w8mr.jafun.OperandType
import nl.w8mr.jafun.Type
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Unit tests for Value Class flattening and expansion.
 * 
 * Tests the recursive flattening of nested VCs to primitive types,
 * which is essential for the variable expansion model where:
 * - val a = Box(Point(1,2), Point(3,4)) creates 4 separate int variables
 * - Functions receive flattened parameters: (IIII)I instead of (LBox;)I
 */
class VCFlatteningTests {
    
    private fun createIntType(): OperandType<*> = OperandType.SInt32
    
    /**
     * Create a VC class with given name and parameters
     */
    private fun createVC(name: String, vararg params: Pair<String, OperandType<*>>): Type.JFClass {
        val vc = Type.JFClass(name, kind = Type.ClassKind.VALUE_CLASS)
        val params = params.map { (paramName, paramType) ->
            Type.JFVariableSymbol(paramName, paramType)
        }
        vc.constructor = Type.JFConstructor(params, vc)
        return vc
    }
    
    // ============================================================================
    // Test 1: Simple primitive type (no flattening needed)
    // ============================================================================
    
    @Test
    fun testFlattenPrimitiveType() {
        val intType = createIntType()
        val flattened = flattenType(intType)
        
        assertEquals(1, flattened.size, "Primitive type should flatten to itself")
        assertEquals(intType, flattened[0].type)
        assertEquals("", flattened[0].path)
    }
    
    // ============================================================================
    // Test 2: Simple single-field VC containing primitive (one level)
    // ============================================================================
    
    @Test
    fun testFlattenSingleFieldVC() {
        // value class Id(value: Int)
        val id = createVC("Id", "value" to createIntType())
        val flattened = flattenType(id)
        
        assertEquals(1, flattened.size, "Single-field VC should flatten to 1 primitive")
        assertEquals(createIntType(), flattened[0].type)
        assertEquals("value", flattened[0].path)
    }
    
    // ============================================================================
    // Test 3: Multi-field VC containing primitives (one level)
    // ============================================================================
    
    @Test
    fun testFlattenMultiFieldVC() {
        // value class Point(x: Int, y: Int)
        val point = createVC("Point", 
            "x" to createIntType(),
            "y" to createIntType()
        )
        val flattened = flattenType(point)
        
        assertEquals(2, flattened.size, "Two-field VC should flatten to 2 primitives")
        assertEquals("x", flattened[0].path)
        assertEquals("y", flattened[1].path)
        assertEquals(createIntType(), flattened[0].type)
        assertEquals(createIntType(), flattened[1].type)
    }
    
    // ============================================================================
    // Test 4: Single-field VC containing multi-field VC (two levels)
    // ============================================================================
    
    @Test
    fun testFlattenNestedSingleFieldVC() {
        // value class Point(x: Int, y: Int)
        val point = createVC("Point",
            "x" to createIntType(),
            "y" to createIntType()
        )
        
        // value class Box(topLeft: Point)
        val box = createVC("Box", "topLeft" to point)
        val flattened = flattenType(box)
        
        assertEquals(2, flattened.size, "Box(Point(x,y)) should flatten to 2 primitives")
        assertEquals("topLeft_x", flattened[0].path)
        assertEquals("topLeft_y", flattened[1].path)
        assertEquals(createIntType(), flattened[0].type)
        assertEquals(createIntType(), flattened[1].type)
    }
    
    // ============================================================================
    // Test 5: Multi-field VC containing multi-field VCs (two levels)
    // ============================================================================
    
    @Test
    fun testFlattenMultiFieldWithMultiFieldNesting() {
        // value class Point(x: Int, y: Int)
        val point = createVC("Point",
            "x" to createIntType(),
            "y" to createIntType()
        )
        
        // value class Box(topLeft: Point, bottomRight: Point)
        val box = createVC("Box",
            "topLeft" to point,
            "bottomRight" to point
        )
        val flattened = flattenType(box)
        
        assertEquals(4, flattened.size, "Box with 2 Points should flatten to 4 primitives")
        assertEquals("topLeft_x", flattened[0].path)
        assertEquals("topLeft_y", flattened[1].path)
        assertEquals("bottomRight_x", flattened[2].path)
        assertEquals("bottomRight_y", flattened[3].path)
        
        for (i in 0..3) {
            assertEquals(createIntType(), flattened[i].type, "All should be Int")
        }
    }
    
    // ============================================================================
    // Test 6: Three levels of nesting
    // ============================================================================
    
    @Test
    fun testFlattenThreeLevelNesting() {
        // value class Point(x: Int, y: Int)
        val point = createVC("Point",
            "x" to createIntType(),
            "y" to createIntType()
        )
        
        // value class Box(topLeft: Point, bottomRight: Point)
        val box = createVC("Box",
            "topLeft" to point,
            "bottomRight" to point
        )
        
        // value class Container(box: Box)
        val container = createVC("Container", "box" to box)
        val flattened = flattenType(container)
        
        assertEquals(4, flattened.size, "Container(Box(Point,Point)) should flatten to 4 primitives")
        assertEquals("box_topLeft_x", flattened[0].path)
        assertEquals("box_topLeft_y", flattened[1].path)
        assertEquals("box_bottomRight_x", flattened[2].path)
        assertEquals("box_bottomRight_y", flattened[3].path)
    }
    
    // ============================================================================
    // Test 7: Single-field VC chain (three levels, all single-field)
    // ============================================================================
    
    @Test
    fun testFlattenSingleFieldChain() {
        // value class Id(value: Int)
        val id = createVC("Id", "value" to createIntType())
        
        // value class UserId(id: Id)
        val userId = createVC("UserId", "id" to id)
        
        // value class User(userId: UserId)
        val user = createVC("User", "userId" to userId)
        
        val flattened = flattenType(user)
        
        assertEquals(1, flattened.size, "Chain of single-field VCs should flatten to 1 primitive")
        assertEquals("userId_id_value", flattened[0].path)
        assertEquals(createIntType(), flattened[0].type)
    }
    
    // ============================================================================
    // Test 8: Complex mixed nesting
    // ============================================================================
    
    @Test
    fun testFlattenComplexMixedNesting() {
        // value class Coordinate(x: Int, y: Int)
        val coordinate = createVC("Coordinate",
            "x" to createIntType(),
            "y" to createIntType()
        )
        
        // value class Location(coord: Coordinate) - single-field containing multi-field
        val location = createVC("Location", "coord" to coordinate)
        
        // value class Size(width: Int, height: Int) - multi-field
        val size = createVC("Size",
            "width" to createIntType(),
            "height" to createIntType()
        )
        
        // value class Rectangle(location: Location, size: Size)
        val rectangle = createVC("Rectangle",
            "location" to location,
            "size" to size
        )
        
        val flattened = flattenType(rectangle)
        
        assertEquals(4, flattened.size, "Rectangle should flatten to 4 primitives (2 from location.coord + 2 from size)")
        assertEquals("location_coord_x", flattened[0].path)
        assertEquals("location_coord_y", flattened[1].path)
        assertEquals("size_width", flattened[2].path)
        assertEquals("size_height", flattened[3].path)
    }
    
    // ============================================================================
    // Test 9: Variable expansion metadata
    // ============================================================================
    
    @Test
    fun testVariableExpansionMetadata() {
        // value class Point(x: Int, y: Int)
        val point = createVC("Point",
            "x" to createIntType(),
            "y" to createIntType()
        )
        
        // value class Box(topLeft: Point)
        val box = createVC("Box", "topLeft" to point)
        
        // Create a variable: val b = Box(Point(1, 2))
        val bVar = Type.JFVariableSymbol("b", box)
        
        // Expand the variable
        val expansion = expandVariable(bVar)
        
        assertEquals(2, expansion.size, "Variable b should expand to 2 primitives")
        
        // Check first expanded variable (b_topLeft_x)
        assertEquals("b_topLeft_x", expansion[0].name)
        assertEquals(createIntType(), expansion[0].type)
        
        // Check second expanded variable (b_topLeft_y)
        assertEquals("b_topLeft_y", expansion[1].name)
        assertEquals(createIntType(), expansion[1].type)
    }
    
    // ============================================================================
    // Test 10: Verify flattened types match signature
    // ============================================================================
    
    @Test
    fun testSignatureGeneration() {
        // value class Point(x: Int, y: Int)
        val point = createVC("Point",
            "x" to createIntType(),
            "y" to createIntType()
        )
        
        // value class Box(topLeft: Point, bottomRight: Point)
        val box = createVC("Box",
            "topLeft" to point,
            "bottomRight" to point
        )
        
        val flattened = flattenType(box)
        
        // Generate signature: (IIII)I for fun getTopLeftX(b: Box): Int
        val paramSignature = flattened.joinToString("") { signatureForType(it.type) }
        val returnSignature = signatureForType(createIntType())
        val fullSignature = "($paramSignature)$returnSignature"
        
        assertEquals("(IIII)I", fullSignature, "Signature should be (IIII)I for Box parameter")
    }
    
    // ============================================================================
    // Test 11: isMultiFieldVC — primitive is not multi-field
    // ============================================================================
    
    @Test
    fun testIsMultiFieldPrimitive() {
        assertTrue(!isMultiFieldVC(createIntType()), "Primitive is not multi-field")
    }
    
    // ============================================================================
    // Test 12: isMultiFieldVC — non-VC class is not multi-field
    // ============================================================================
    
    @Test
    fun testIsMultiFieldNormalClass() {
        val cls = Type.JFClass("Normal")
        assertTrue(!isMultiFieldVC(cls), "Normal class is not multi-field")
    }
    
    // ============================================================================
    // Test 13: isMultiFieldVC — single-field VC wrapping primitive
    // ============================================================================
    
    @Test
    fun testIsMultiFieldSingleFieldPrimitive() {
        val id = createVC("Id", "value" to createIntType())
        assertTrue(!isMultiFieldVC(id), "Id(value: Int) should not be multi-field")
    }
    
    // ============================================================================
    // Test 14: isMultiFieldVC — multi-field VC
    // ============================================================================
    
    @Test
    fun testIsMultiFieldDirect() {
        val point = createVC("Point",
            "x" to createIntType(),
            "y" to createIntType()
        )
        assertTrue(isMultiFieldVC(point), "Point(x: Int, y: Int) should be multi-field")
    }
    
    // ============================================================================
    // Test 15: isMultiFieldVC — single-field VC wrapping multi-field VC
    // ============================================================================
    
    @Test
    fun testIsMultiFieldSingleFieldWrappingMultiField() {
        val point = createVC("Point",
            "x" to createIntType(),
            "y" to createIntType()
        )
        val box = createVC("Box", "topLeft" to point)
        assertTrue(isMultiFieldVC(box), "Box(topLeft: Point) should be multi-field")
    }
    
    // ============================================================================
    // Test 16: isMultiFieldVC — chain: single wrapping single wrapping multi-field
    // ============================================================================
    
    @Test
    fun testIsMultiFieldDeepWrapping() {
        val point = createVC("Point",
            "x" to createIntType(),
            "y" to createIntType()
        )
        val inner = createVC("Inner", "topLeft" to point)
        val outer = createVC("Outer", "inner" to inner)
        assertTrue(isMultiFieldVC(outer), "Outer(inner: Inner(topLeft: Point)) should be multi-field")
    }
    
    // ============================================================================
    // Test 17: isMultiFieldVC — chain: single wrapping single wrapping primitive
    // ============================================================================
    
    @Test
    fun testIsMultiFieldDeepPrimitive() {
        val id = createVC("Id", "value" to createIntType())
        val userId = createVC("UserId", "id" to id)
        assertTrue(!isMultiFieldVC(userId), "UserId(id: Id(value: Int)) should not be multi-field")
    }
    
    // ============================================================================
    // Test 18: effectiveVariableType — primitive stays primitive
    // ============================================================================
    
    @Test
    fun testEffectiveTypePrimitive() {
        val result = effectiveVariableType(createIntType())
        assertEquals(createIntType(), result, "Primitive should stay unchanged")
    }
    
    // ============================================================================
    // Test 19: effectiveVariableType — multi-field VC stays as VC
    // ============================================================================
    
    @Test
    fun testEffectiveTypeMultiField() {
        val point = createVC("Point",
            "x" to createIntType(),
            "y" to createIntType()
        )
        val result = effectiveVariableType(point)
        assertEquals(point, result, "Multi-field VC should stay unchanged")
    }
    
    // ============================================================================
    // Test 20: effectiveVariableType — single-field VC unwraps to primitive
    // ============================================================================
    
    @Test
    fun testEffectiveTypeSingleFieldToPrimitive() {
        val id = createVC("Id", "value" to createIntType())
        val result = effectiveVariableType(id)
        assertEquals(createIntType(), result, "Id(value: Int) should unwrap to Int")
    }
    
    // ============================================================================
    // Test 21: effectiveVariableType — single-field VC unwraps to multi-field VC
    // ============================================================================
    
    @Test
    fun testEffectiveTypeSingleFieldToMultiField() {
        val point = createVC("Point",
            "x" to createIntType(),
            "y" to createIntType()
        )
        val box = createVC("Box", "topLeft" to point)
        val result = effectiveVariableType(box)
        assertEquals(point, result, "Box(topLeft: Point) should unwrap to Point")
    }
    
    // ============================================================================
    // Test 22: effectiveVariableType — three-level unwrapping
    // ============================================================================
    
    @Test
    fun testEffectiveTypeThreeLevel() {
        val point = createVC("Point",
            "x" to createIntType(),
            "y" to createIntType()
        )
        val inner = createVC("Inner", "topLeft" to point)
        val outer = createVC("Outer", "inner" to inner)
        val result = effectiveVariableType(outer)
        assertEquals(point, result, "Outer(inner: Inner(topLeft: Point)) should unwrap to Point")
    }
    
    // ============================================================================
    // Test 23: effectiveVariableType — normal class stays unchanged
    // ============================================================================
    
    @Test
    fun testEffectiveTypeNormalClass() {
        val cls = Type.JFClass("Normal")
        val result = effectiveVariableType(cls)
        assertEquals(cls, result, "Normal class should stay unchanged")
    }
    
    // ============================================================================
    // Test 24: createNestedFieldAccess — empty path returns arg
    // ============================================================================
    
    @Test
    fun testNestedAccessEmptyPath() {
        val arg = ExpressionNode.IntegerLiteral(42)
        val result = createNestedFieldAccess(emptyList(), arg)
        assertEquals(arg, result, "Empty path should return arg unchanged")
    }
    
    // ============================================================================
    // Test 25: createNestedFieldAccess — single component
    // ============================================================================
    
    @Test
    fun testNestedAccessSingleField() {
        val point = createVC("Point",
            "x" to createIntType(),
            "y" to createIntType()
        )
        val arg = ExpressionNode.Variable(Type.JFVariableSymbol("p", point))
        val result = createNestedFieldAccess(listOf("x"), arg)
        assertTrue(result is ExpressionNode.FieldAccess, "Should return a FieldAccess")
        val fa = result as ExpressionNode.FieldAccess
        assertEquals("x", fa.fieldName)
        assertTrue(fa.instance is ExpressionNode.Variable)
        assertEquals("p", (fa.instance as ExpressionNode.Variable).variableSymbol.name)
    }
    
    // ============================================================================
    // Test 26: createNestedFieldAccess — two components
    // ============================================================================
    
    @Test
    fun testNestedAccessTwoFields() {
        val point = createVC("Point",
            "x" to createIntType(),
            "y" to createIntType()
        )
        val box = createVC("Box", "topLeft" to point)
        val arg = ExpressionNode.Variable(Type.JFVariableSymbol("b", box))
        val result = createNestedFieldAccess(listOf("topLeft", "x"), arg)
        assertTrue(result is ExpressionNode.FieldAccess, "Outer should be a FieldAccess")
        val outer = result as ExpressionNode.FieldAccess
        assertEquals("x", outer.fieldName, "Outer should access 'x'")
        assertTrue(outer.instance is ExpressionNode.FieldAccess, "Inner should be a FieldAccess")
        val inner = outer.instance as ExpressionNode.FieldAccess
        assertEquals("topLeft", inner.fieldName, "Inner should access 'topLeft'")
        assertTrue(inner.instance is ExpressionNode.Variable, "Base should be a Variable")
        assertEquals("b", (inner.instance as ExpressionNode.Variable).variableSymbol.name)
    }
}
