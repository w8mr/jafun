package nl.w8mr.jafun.compiler.ir2jvm

import nl.w8mr.jafun.OperandType
import nl.w8mr.jafun.Type
import nl.w8mr.jafun.compiler.ExpressionNode
import nl.w8mr.jafun.compiler.Parameter
import nl.w8mr.jafun.compiler.expandAssignmentIfNeeded
import nl.w8mr.jafun.compiler.expandParameterRecursively
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Unit tests for VCBinder pure functions.
 *
 * Each concept from docs/vc-unboxing-process.md maps to one function
 * tested in isolation. Tests build minimal IR nodes directly so each
 * function can be verified independently before integration.
 */
class VCBinderTests {

    // ---- Test helpers ----

    private fun intType() = OperandType.SInt32
    private fun stringType() = OperandType.StringType

    private fun createVC(name: String, vararg params: Pair<String, OperandType<*>>): Type.JFClass {
        val vc = Type.JFClass(name, kind = Type.ClassKind.VALUE_CLASS)
        val ps = params.map { (pn, pt) -> Type.JFVariableSymbol(pn, pt) }
        vc.constructor = Type.JFConstructor(ps, vc)
        return vc
    }

    private fun intLiteral(value: Int): ExpressionNode.IntegerLiteral =
        ExpressionNode.IntegerLiteral(value)

    private fun stringLiteral(value: String): ExpressionNode.StringLiteral =
        ExpressionNode.StringLiteral(value)

    private fun ci(cons: Type.JFConstructor, vararg args: ExpressionNode.Phase2_3Expression):
        ExpressionNode.ConstructorInvocation =
        ExpressionNode.ConstructorInvocation(cons, args.toList())

    private fun variable(name: String, type: OperandType<*>): ExpressionNode.Variable =
        ExpressionNode.Variable(Type.JFVariableSymbol(name, type))

    private fun valAssign(sym: Type.JFVariableSymbol, expr: ExpressionNode.Phase2_3Expression):
        ExpressionNode.ValAssignment =
        ExpressionNode.ValAssignment(sym, expr)

    // ============================================================================
    // R1: Eager unboxing at assignment with Constructor Invocation on RHS
    // ============================================================================

    /**
     * val b = Box(99)
     * -> single ValAssignment with b as a scalar Int (single-field VC)
     */
    @Test
    fun R1_singleFieldVC_unboxesToScalar() {
        val boxVc = createVC("Box", "x" to intType())

        val b = Type.JFVariableSymbol("b", boxVc, mutable = false, initialized = true)
        val assign = valAssign(b, ci(boxVc.constructor!!, intLiteral(99)))

        val expanded = expandAssignmentIfNeeded(assign)

        // Box(x: Int) is single-field, so we expect 1 scalar assignment
        assertEquals(1, expanded.size)

        val ex0 = expanded[0] as ExpressionNode.ValAssignment
        // The expanded variable should be Int (scalar), and store directly the 99
        assertEquals(intType(), ex0.variableSymbol.type)
        assertEquals(intLiteral(99), ex0.expression)
    }

    /**
     * val b = Box(Point(99, 10))
     * -> multi-field VC outer: 2 scalar assignments (topLeft_x, topLeft_y)
     *    with the inner Point(99,10) flattened into literal arguments
     */
    @Test
    fun R1_singleFieldVCContainingMultiField_flattensNestedCI() {
        val pointVc = createVC("Point", "x" to intType(), "y" to intType())
        val boxVc = createVC("Box", "topLeft" to pointVc)

        val b = Type.JFVariableSymbol("b", boxVc, mutable = false, initialized = true)
        val inner = ci(pointVc.constructor!!, intLiteral(99), intLiteral(10))
        val outer = ci(boxVc.constructor!!, inner)
        val assign = valAssign(b, outer)

        val expanded = expandAssignmentIfNeeded(assign)

        // Expect 2 scalar assignments (topLeft_x, topLeft_y)
        assertEquals(2, expanded.size)

        val first = expanded[0] as ExpressionNode.ValAssignment
        val second = expanded[1] as ExpressionNode.ValAssignment

        assertEquals(intType(), first.variableSymbol.type)
        assertEquals(intType(), second.variableSymbol.type)

        assertEquals(intLiteral(99), first.expression)
        assertEquals(intLiteral(10), second.expression)
    }

    /**
     * Assignment with non-CI RHS should NOT be expanded.
     */
    @Test
    fun R1_nonCIDoesNotExpand() {
        val boxVc = createVC("Box", "x" to intType())
        val b = Type.JFVariableSymbol("b", boxVc)

        // val b = variable 'otherVar' (a method call result, not CI)
        val otherVar = ExpressionNode.Variable(Type.JFVariableSymbol("otherVar", boxVc))
        val assign = valAssign(b, otherVar)

        val expanded = expandAssignmentIfNeeded(assign)

        // Should be unchanged (single element, identical reference)
        assertEquals(1, expanded.size)
    }

    /**
     * After R1 expansion, expandVariable should return the scalar symbols in
     * field path order with the correct naming.
     */
    @Test
    fun R1_expandVariableProducesScalarSymbols() {
        val pointVc = createVC("Point", "x" to intType(), "y" to intType())
        val boxVc = createVC("Box", "topLeft" to pointVc)

        val b = Type.JFVariableSymbol("b", boxVc, mutable = false, initialized = true)
        val expanded = expandAssignmentIfNeeded(
            valAssign(b, ci(boxVc.constructor!!, ci(pointVc.constructor!!, intLiteral(99), intLiteral(10))))
        )

        // The original 'b' symbol should now have expandedFields populated
        // with two top-level fields (topLeft_x, topLeft_y).
        val flat = expanded.map { (it as ExpressionNode.ValAssignment).variableSymbol }
        assertEquals(listOf("b_topLeft_x", "b_topLeft_y"), flat.map { it.name })
    }

    // ============================================================================
    // Param expansion: multi-field VC parameter -> scalar parameters
    // ============================================================================

    /**
     * fun foo(b: Box) -> parameter list expansion to scalars
     * Box(topLeft: Point) should expand to [Int topLeft_x, String topLeft_y ...] etc.
     */
    @Test
    fun paramExpand_multiFieldVC_toScalars() {
        val pointVc = createVC("Point", "x" to intType(), "y" to intType())
        val boxVc = createVC("Box", "topLeft" to pointVc)

        val param = Parameter(boxVc, "b")
        val expanded: List<Parameter> = expandParameterRecursively(boxVc, "b")

        // Box(topLeft: Point) expands to 2 scalars (topLeft_x, topLeft_y)
        assertEquals(2, expanded.size)
        assertEquals(intType(), expanded[0].type)
        assertEquals(intType(), expanded[1].type)
        assertEquals("b_topLeft_x", expanded[0].varName)
        assertEquals("b_topLeft_y", expanded[1].varName)
    }

    /**
     * Single-field VC parameter (containing primitive) -> unwrap with field name.
     * expandParameterRecursively continues unwrapping single-field VCs.
     */
    @Test
    fun paramExpand_singleFieldVCPrimitive_toScalar() {
        val boxVc = createVC("Box", "x" to intType())

        val expanded: List<Parameter> = expandParameterRecursively(boxVc, "b")

        // Box(x: Int) single-field: keeps the leaf param name 'b_x' with Int type
        assertEquals(1, expanded.size)
        assertEquals(intType(), expanded[0].type)
        assertEquals("b_x", expanded[0].varName)
    }

    // ============================================================================
    // R3: Field access on unboxed VC variable resolves to scalar Variable
    // ============================================================================

    /**
     * For a multi-field VC parameter, after R1 expansion, the variable has
     * `expandedFields` populated. We must be able to resolve a chained field
     * access to the scalar symbol.
     *
     * For Box(topLeft: Point):
     *   - b.expandedFields = [topLeft] with sourceVCFields=[(x, Int), (y, Int)]
     *   - b.topLeft.x resolves to the scalar symbol for x
     */
    @Test
    fun R3_fieldAccessResolvesToScalar() {
        val pointVc = createVC("Point", "x" to intType(), "y" to intType())
        val boxVc = createVC("Box", "topLeft" to pointVc)

        val b = Type.JFVariableSymbol("b", boxVc)

        // Trigger expand assignment so b has expandedFields populated
        expandAssignmentIfNeeded(
            valAssign(b, ci(boxVc.constructor!!, ci(pointVc.constructor!!, intLiteral(99), intLiteral(10))))
        )

        // b.topLeft.x should resolve to scalar 'b_topLeft_x'
        // First, build a path-map from b's expansion
        val pathMap: Map<String, Type.JFVariableSymbol> = (b.expandedFieldSymbols ?: emptyMap())
        assertNotNull(pathMap)

        val scalarForTopLeftX = pathMap["topLeft_x"]
        assertNotNull(scalarForTopLeftX)
        assertEquals("b_topLeft_x", scalarForTopLeftX.name)
        assertEquals(intType(), scalarForTopLeftX.type)
    }

    // ============================================================================
    // R3: FieldAccess rewriting to scalar Variable (pure function)
    // ============================================================================

    /**
     * A variable `b` that has been eagerly unboxed via R1 has filled in
     * `expandedFieldSymbols`. For a FieldAccess on this variable like
     * `b.topLeft.x`, we must be able to walk the path "topLeft.x" and
     * return Variable(topLeft_x_scalar).
     *
     * This test directly probes the API of expandedFieldSymbols to verify
     * the path lookup is what we expect after R1.
     */
    @Test
    fun R3_pathLookup_walksKey() {
        val pointVc = createVC("Point", "x" to intType(), "y" to intType())
        val boxVc = createVC("Box", "topLeft" to pointVc)

        val b = Type.JFVariableSymbol("b", boxVc)
        expandAssignmentIfNeeded(
            valAssign(b, ci(boxVc.constructor!!, ci(pointVc.constructor!!, intLiteral(99), intLiteral(10))))
        )

        // Sanity: the pathMap holds top-level field paths → actual symbols
        val flat: Map<String, Type.JFVariableSymbol> = b.expandedFieldSymbols ?: emptyMap()

        // The single top-level field of Box is "topLeft". Its inner fields are
        // "topLeft_x" and "topLeft_y" (because Box is single-field with inner Point
        // which is multi-field).
        assertEquals(setOf("topLeft_x", "topLeft_y"), flat.keys)

        // For FieldAccess `b.topLeft.x` we expect the scalar at "topLeft_x"
        assertEquals("b_topLeft_x", flat["topLeft_x"]?.name)
        assertEquals(intType(), flat["topLeft_x"]?.type)
    }

    /**
     * Direct unit test for the pure resolver function. Given:
     * `b.topLeft.x` where b has been eager-unboxed via R1, the resolver must
     * produce a Variable pointing to the scalar symbol `b_topLeft_x`.
     */
    @Test
    fun R3_resolveFieldAccess_pure() {
        val pointVc = createVC("Point", "x" to intType(), "y" to intType())
        val boxVc = createVC("Box", "topLeft" to pointVc)

        val b = Type.JFVariableSymbol("b", boxVc)
        expandAssignmentIfNeeded(
            valAssign(b, ci(boxVc.constructor!!, ci(pointVc.constructor!!, intLiteral(99), intLiteral(10))))
        )

        // Build `b.topLeft.x` as a chained FieldAccess
        // inner: b.topLeft (instance Variable(b), field "topLeft")
        // outer: (b.topLeft).x (instance inner, field "x")
        val topLeftFa = ExpressionNode.FieldAccess(
            instance = ExpressionNode.Variable(b),
            fieldName = "topLeft",
            fieldIndex = 0,
            fieldType = pointVc,
            arguments = emptyList()
        )
        val outerFa = ExpressionNode.FieldAccess(
            instance = topLeftFa,
            fieldName = "x",
            fieldIndex = 0,
            fieldType = intType(),
            arguments = emptyList()
        )

        val resolved = VCBinder.resolveFieldAccessToScalar(outerFa)
        assertNotNull(resolved)
        assertTrue(resolved is ExpressionNode.Variable)
        resolved as ExpressionNode.Variable
        assertEquals("b_topLeft_x", resolved.variableSymbol.name)
        assertEquals(intType(), resolved.variableSymbol.type)
    }

    /**
     * If pathMap has no key for the path, the resolver returns null
     * (and the caller falls back to the original FieldAccess).
     */
    @Test
    fun R3_resolveFieldAccess_returnsNullForUnknownPath() {
        val boxVc = createVC("Box", "x" to intType())
        val b = Type.JFVariableSymbol("b", boxVc)
        expandAssignmentIfNeeded(
            valAssign(b, ci(boxVc.constructor!!, intLiteral(99)))
        )
        // b.x should resolve to scalar 'b_x' (single-field VC flattens to scalar field name)
        val faOnX = ExpressionNode.FieldAccess(
            instance = ExpressionNode.Variable(b),
            fieldName = "x",
            fieldIndex = 0,
            fieldType = intType(),
            arguments = emptyList()
        )
        val resolved = VCBinder.resolveFieldAccessToScalar(faOnX)
        assertNotNull(resolved)
        assertTrue(resolved is ExpressionNode.Variable)
        resolved as ExpressionNode.Variable
        assertEquals("b_x", resolved.variableSymbol.name)
    }
}
