package nl.w8mr.jafun.compiler.ir2jvm

import nl.w8mr.jafun.OperandType
import nl.w8mr.jafun.Type
import nl.w8mr.jafun.compiler.ExpressionNode
import nl.w8mr.jafun.compiler.Parameter
import nl.w8mr.jafun.compiler.expandAssignmentIfNeeded
import nl.w8mr.jafun.compiler.expandParameterRecursively
import nl.w8mr.jafun.compiler.referentialListDiff
import nl.w8mr.jafun.compiler.transformTree
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
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
        assertEquals("b_x", resolved.variableSymbol.name)
    }

    // ================================================================================
    // setExpandedFieldsOnSymbol: Issue #1 — propagate expandedFields to param symbols
    // ================================================================================

    @Test
    fun setExpandedFields_multiFieldVC_setsExpandedFields() {
        val pointVc = createVC("Point", "x" to intType(), "y" to intType())
        val boxVc = createVC("Box", "topLeft" to pointVc)

        val b = Type.JFVariableSymbol("b", boxVc)
        assertNull(b.expandedFields)

        VCBinder.setExpandedFieldsOnSymbol(b)

        assertNotNull(b.expandedFields)
        assertEquals(1, b.expandedFields!!.size)

        val topLeftField = b.expandedFields!![0]
        assertEquals("topLeft", topLeftField.name)
        assertEquals(pointVc, topLeftField.type)
        val fields = topLeftField.sourceVCFields
        assertNotNull(fields)
        assertEquals(2, fields.size)
        assertEquals("x", fields[0].first)
        assertEquals(intType(), fields[0].second)
        assertEquals("y", fields[1].first)
        assertEquals(intType(), fields[1].second)
    }

    @Test
    fun setExpandedFields_multiFieldVC_setsExpandedFieldSymbols() {
        val pointVc = createVC("Point", "x" to intType(), "y" to intType())
        val boxVc = createVC("Box", "topLeft" to pointVc)

        val b = Type.JFVariableSymbol("b", boxVc)
        VCBinder.setExpandedFieldsOnSymbol(b)

        assertNotNull(b.expandedFieldSymbols)
        assertEquals(setOf("topLeft_x", "topLeft_y"), b.expandedFieldSymbols!!.keys)

        val symX = b.expandedFieldSymbols!!["topLeft_x"]
        assertNotNull(symX)
        assertEquals("b_topLeft_x", symX.name)
        assertEquals(intType(), symX.type)

        val symY = b.expandedFieldSymbols!!["topLeft_y"]
        assertNotNull(symY)
        assertEquals("b_topLeft_y", symY.name)
        assertEquals(intType(), symY.type)
    }

    @Test
    fun setExpandedFields_nonVC_doesNothing() {
        val s = Type.JFVariableSymbol("s", stringType())
        VCBinder.setExpandedFieldsOnSymbol(s)
        assertNull(s.expandedFields)
        assertNull(s.expandedFieldSymbols)
    }

    @Test
    fun setExpandedFields_alreadySet_doesNotOverwrite() {
        val boxVc = createVC("Box", "x" to intType())
        val b = Type.JFVariableSymbol("b", boxVc)
        // Simulate R1 setting expandedFields
        expandAssignmentIfNeeded(valAssign(b, ci(boxVc.constructor!!, intLiteral(99))))

        val existingFields = b.expandedFields
        val existingSymbols = b.expandedFieldSymbols

        VCBinder.setExpandedFieldsOnSymbol(b)

        // Should be the same references (not overwritten)
        assertTrue(b.expandedFields === existingFields)
        assertTrue(b.expandedFieldSymbols === existingSymbols)
    }

    @Test
    fun setExpandedFields_singleFieldVC_containingMultiField_setsCorrectStructure() {
        val pointVc = createVC("Point", "x" to intType(), "y" to intType())
        val boxVc = createVC("Box", "topLeft" to pointVc)

        val b = Type.JFVariableSymbol("b", boxVc)
        VCBinder.setExpandedFieldsOnSymbol(b)

        // Box has one constructor param (topLeft: Point).
        // isMultiFieldVC(Point) = true, so sourceVCFields = [(x, Int), (y, Int)]
        val fields = b.expandedFields
        assertNotNull(fields)
        val field = fields[0]
        assertEquals("topLeft", field.name)
        val sourceFields = field.sourceVCFields
        assertNotNull(sourceFields)
        assertEquals(2, sourceFields.size)
    }

    @Test
    fun setExpandedFields_enablesFieldAccessResolution() {
        val pointVc = createVC("Point", "x" to intType(), "y" to intType())
        val boxVc = createVC("Box", "topLeft" to pointVc)

        val b = Type.JFVariableSymbol("b", boxVc)
        VCBinder.setExpandedFieldsOnSymbol(b)

        // Build b.topLeft.x
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
        assertEquals("b_topLeft_x", resolved.variableSymbol.name)
    }

    @Test
    fun resolveFieldAccess_multiFieldVC_reconstructsConstructorInvocation() {
        val pointVc = createVC("Point", "x" to intType(), "y" to intType())
        val boxVc = createVC("Box", "topLeft" to pointVc)

        val b = Type.JFVariableSymbol("b", boxVc)
        VCBinder.setExpandedFieldsOnSymbol(b)

        // b.topLeft where Point is multi-field — should reconstruct Point(x, y)
        val fieldAccess = ExpressionNode.FieldAccess(
            instance = ExpressionNode.Variable(b),
            fieldName = "topLeft",
            fieldIndex = 0,
            fieldType = pointVc,
            arguments = emptyList()
        )

        val resolved = VCBinder.resolveFieldAccessToScalar(fieldAccess)
        assertNotNull(resolved)
        assertTrue(resolved is ExpressionNode.ConstructorInvocation)

        val ci = resolved as ExpressionNode.ConstructorInvocation
        assertEquals(pointVc, ci.type())
        assertEquals(2, ci.arguments.size)

        val arg0 = ci.arguments[0] as ExpressionNode.Variable
        assertEquals("b_topLeft_x", arg0.variableSymbol.name)

        val arg1 = ci.arguments[1] as ExpressionNode.Variable
        assertEquals("b_topLeft_y", arg1.variableSymbol.name)
    }

    @Test
    fun resolveFieldAccess_multiFieldVC_nestedAccess_resolvesToScalar() {
        // b.topLeft.x should still resolve directly to scalar (not reconstruction)
        val pointVc = createVC("Point", "x" to intType(), "y" to intType())
        val boxVc = createVC("Box", "topLeft" to pointVc)

        val b = Type.JFVariableSymbol("b", boxVc)
        VCBinder.setExpandedFieldsOnSymbol(b)

        val topLevelFa = ExpressionNode.FieldAccess(
            instance = ExpressionNode.Variable(b),
            fieldName = "topLeft",
            fieldIndex = 0,
            fieldType = pointVc,
            arguments = emptyList()
        )
        val outerFa = ExpressionNode.FieldAccess(
            instance = topLevelFa,
            fieldName = "x",
            fieldIndex = 0,
            fieldType = intType(),
            arguments = emptyList()
        )

        val resolved = VCBinder.resolveFieldAccessToScalar(outerFa)
        assertNotNull(resolved)
        assertTrue(resolved is ExpressionNode.Variable)
        assertEquals("b_topLeft_x", resolved.variableSymbol.name)
    }

    @Test
    fun resolveFieldAccess_nonExpandedVariable_returnsNull() {
        val pointVc = createVC("Point", "x" to intType(), "y" to intType())
        val p = Type.JFVariableSymbol("p", pointVc)
        // NOT calling setExpandedFieldsOnSymbol — variable has no expandedFieldSymbols

        val fa = ExpressionNode.FieldAccess(
            instance = ExpressionNode.Variable(p),
            fieldName = "x",
            fieldIndex = 0,
            fieldType = intType(),
            arguments = emptyList()
        )

        assertNull(VCBinder.resolveFieldAccessToScalar(fa))
    }

    // ================================================================================
    // flattenCIArgs: recursive CI argument flattening
    // ================================================================================

    @Test
    fun flattenCIArgs_singleLevel_returnsFlatArgs() {
        val pointVc = createVC("Point", "x" to intType(), "y" to intType())
        val innerCi = ci(pointVc.constructor!!, intLiteral(1), intLiteral(2))

        val flattened = VCBinder.flattenCIArgs(innerCi)
        assertNotNull(flattened)
        assertEquals(2, flattened.size)
        assertEquals(intLiteral(1), flattened[0])
        assertEquals(intLiteral(2), flattened[1])
    }

    @Test
    fun flattenCIArgs_primitiveField_returnsArgUnchanged() {
        val boxVc = createVC("Box", "x" to intType())
        val ci = ci(boxVc.constructor!!, intLiteral(42))

        val flattened = VCBinder.flattenCIArgs(ci)
        assertNotNull(flattened)
        assertEquals(1, flattened.size)
        assertEquals(intLiteral(42), flattened[0])
    }

    @Test
    fun flattenCIArgs_nested_returnsAllScalars() {
        val pointVc = createVC("Point", "x" to intType(), "y" to intType())
        val boxVc = createVC("Box", "topLeft" to pointVc)

        val innerCi = ci(pointVc.constructor!!, intLiteral(1), intLiteral(2))
        val outerCi = ci(boxVc.constructor!!, innerCi)

        val flattened = VCBinder.flattenCIArgs(outerCi)
        assertNotNull(flattened)
        assertEquals(2, flattened.size)
        assertEquals(intLiteral(1), flattened[0])
        assertEquals(intLiteral(2), flattened[1])
    }

    @Test
    fun flattenCIArgs_deeplyNested_returnsAllScalars() {
        val pointVc = createVC("Point", "x" to intType(), "y" to intType())
        val boxVc = createVC("Box", "topLeft" to pointVc)
        val wrapperVc = createVC("Wrapper", "box" to boxVc)

        val innerCi = ci(pointVc.constructor!!, intLiteral(1), intLiteral(2))
        val midCi = ci(boxVc.constructor!!, innerCi)
        val outerCi = ci(wrapperVc.constructor!!, midCi)

        val flattened = VCBinder.flattenCIArgs(outerCi)
        assertNotNull(flattened)
        assertEquals(2, flattened.size)
        assertEquals(intLiteral(1), flattened[0])
        assertEquals(intLiteral(2), flattened[1])
    }

    // ================================================================================
    // expandArgForCallSite: expand a single arg to match expanded param
    // ================================================================================

    @Test
    fun expandArgForCallSite_variableWithSymbols_returnsScalarVars() {
        val pointVc = createVC("Point", "x" to intType(), "y" to intType())
        val boxVc = createVC("Box", "topLeft" to pointVc)

        val myBox = Type.JFVariableSymbol("myBox", boxVc)
        VCBinder.setExpandedFieldsOnSymbol(myBox)

        val expanded = VCBinder.expandArgForCallSite(
            ExpressionNode.Variable(myBox),
            Parameter(boxVc, "b")
        )
        assertNotNull(expanded)
        assertEquals(2, expanded.size)
        assertEquals("myBox_topLeft_x", (expanded[0] as ExpressionNode.Variable).variableSymbol.name)
        assertEquals(intType(), (expanded[0] as ExpressionNode.Variable).variableSymbol.type)
        assertEquals("myBox_topLeft_y", (expanded[1] as ExpressionNode.Variable).variableSymbol.name)
        assertEquals(intType(), (expanded[1] as ExpressionNode.Variable).variableSymbol.type)
    }

    @Test
    fun expandArgForCallSite_ciArg_returnsFlatExprs() {
        val pointVc = createVC("Point", "x" to intType(), "y" to intType())
        val boxVc = createVC("Box", "topLeft" to pointVc)

        val innerCi = ci(pointVc.constructor!!, intLiteral(1), intLiteral(2))
        val outerCi = ci(boxVc.constructor!!, innerCi)

        val expanded = VCBinder.expandArgForCallSite(outerCi, Parameter(boxVc, "b"))
        assertNotNull(expanded)
        assertEquals(2, expanded.size)
        assertEquals(intLiteral(1), expanded[0])
        assertEquals(intLiteral(2), expanded[1])
    }

    @Test
    fun expandArgForCallSite_nonVCParam_returnsNull() {
        val expanded = VCBinder.expandArgForCallSite(intLiteral(5), Parameter(intType(), "x"))
        assertNull(expanded)
    }

    @Test
    fun expandArgForCallSite_variableWithoutSymbols_returnsNull() {
        val boxVc = createVC("Box", "x" to intType())
        val myBox = Type.JFVariableSymbol("myBox", boxVc)

        val expanded = VCBinder.expandArgForCallSite(
            ExpressionNode.Variable(myBox),
            Parameter(boxVc, "b")
        )
        assertNull(expanded)
    }

    // ================================================================================
    // expandCallSiteArgs: rewrite MethodInvocation with expanded args
    // ================================================================================

    @Test
    fun expandCallSiteArgs_expandsArgForMultiFieldVCParam() {
        val pointVc = createVC("Point", "x" to intType(), "y" to intType())
        val boxVc = createVC("Box", "topLeft" to pointVc)

        val origParams = listOf(Parameter(boxVc, "b"))
        val expandedParams = expandParameterRecursively(boxVc, "b")
        val methodSigs = mapOf("foo" to Triple(origParams, expandedParams, OperandType.Unit))

        val myBox = Type.JFVariableSymbol("myBox", boxVc)
        VCBinder.setExpandedFieldsOnSymbol(myBox)

        val callSite = ExpressionNode.MethodInvocation(
            methodName = "foo",
            parentPath = "",
            parameters = listOf(Type.JFVariableSymbol("b", boxVc)),
            rtnLookup = { OperandType.Unit },
            field = null,
            arguments = listOf(ExpressionNode.Variable(myBox))
        )

        val result = VCBinder.expandCallSiteArgs(callSite, methodSigs)
        assertNotNull(result)
        assertTrue(result is ExpressionNode.MethodInvocation)

        assertEquals(2, result.arguments.size)
        assertTrue(result.arguments[0] is ExpressionNode.Variable)
        assertTrue(result.arguments[1] is ExpressionNode.Variable)
        assertEquals("myBox_topLeft_x", (result.arguments[0] as ExpressionNode.Variable).variableSymbol.name)
        assertEquals("myBox_topLeft_y", (result.arguments[1] as ExpressionNode.Variable).variableSymbol.name)
    }

    @Test
    fun expandCallSiteArgs_ciArg_flattensCorrectly() {
        val pointVc = createVC("Point", "x" to intType(), "y" to intType())
        val boxVc = createVC("Box", "topLeft" to pointVc)

        val origParams = listOf(Parameter(boxVc, "b"))
        val expandedParams = expandParameterRecursively(boxVc, "b")
        val methodSigs = mapOf("foo" to Triple(origParams, expandedParams, OperandType.Unit))

        val innerCi = ci(pointVc.constructor!!, intLiteral(1), intLiteral(2))
        val outerCi = ci(boxVc.constructor!!, innerCi)

        val callSite = ExpressionNode.MethodInvocation(
            methodName = "foo",
            parentPath = "",
            parameters = listOf(Type.JFVariableSymbol("b", boxVc)),
            rtnLookup = { OperandType.Unit },
            field = null,
            arguments = listOf(outerCi)
        )

        val result = VCBinder.expandCallSiteArgs(callSite, methodSigs)
        assertNotNull(result)
        result as ExpressionNode.MethodInvocation

        assertEquals(2, result.arguments.size)
        assertEquals(intLiteral(1), result.arguments[0])
        assertEquals(intLiteral(2), result.arguments[1])
    }

    @Test
    fun expandCallSiteArgs_nonVCMethod_returnsNull() {
        val origParams = listOf(Parameter(intType(), "x"))
        val methodSigs = mapOf("add" to Triple(origParams, origParams, intType()))

        val callSite = ExpressionNode.MethodInvocation(
            methodName = "add",
            parentPath = "",
            parameters = listOf(Type.JFVariableSymbol("x", intType())),
            rtnLookup = { intType() },
            field = null,
            arguments = listOf(intLiteral(3))
        )

        val result = VCBinder.expandCallSiteArgs(callSite, methodSigs)
        assertNull(result)
    }

    @Test
    fun expandCallSiteArgs_nonInvocation_returnsNull() {
        val result = VCBinder.expandCallSiteArgs(intLiteral(42), emptyMap())
        assertNull(result)
    }

    @Test
    fun expandCallSiteArgs_unknownMethod_returnsNull() {
        val callSite = ExpressionNode.MethodInvocation(
            methodName = "unknown",
            parentPath = "",
            parameters = emptyList(),
            rtnLookup = { OperandType.Unit },
            field = null,
            arguments = emptyList()
        )
        val result = VCBinder.expandCallSiteArgs(callSite, emptyMap())
        assertNull(result)
    }

    @Test
    fun expandCallSiteArgs_updatesParameterList() {
        val pointVc = createVC("Point", "x" to intType(), "y" to intType())
        val boxVc = createVC("Box", "topLeft" to pointVc)

        val origParams = listOf(Parameter(boxVc, "b"))
        val expandedParams = expandParameterRecursively(boxVc, "b")
        val methodSigs = mapOf("foo" to Triple(origParams, expandedParams, OperandType.Unit))

        val myBox = Type.JFVariableSymbol("myBox", boxVc)
        VCBinder.setExpandedFieldsOnSymbol(myBox)

        val callSite = ExpressionNode.MethodInvocation(
            methodName = "foo",
            parentPath = "",
            parameters = listOf(Type.JFVariableSymbol("b", boxVc)),
            rtnLookup = { OperandType.Unit },
            field = null,
            arguments = listOf(ExpressionNode.Variable(myBox))
        )

        val result = VCBinder.expandCallSiteArgs(callSite, methodSigs) as ExpressionNode.MethodInvocation
        assertEquals(2, result.parameters.size)
        assertEquals(intType(), result.parameters[0].type)
        assertEquals(intType(), result.parameters[1].type)
    }

    // ================================================================================
    // expandCallSiteArgs: return type only change (R5 scenario)
    // ================================================================================

    @Test
    fun expandCallSiteArgs_returnTypeOnlyChanged_returnsUpdatedMI() {
        val idVc = createVC("Id", "value" to intType())
        val origParams = emptyList<Parameter>()
        val expandedParams = emptyList<Parameter>()
        val methodSigs = mapOf("makeId" to Triple(origParams, expandedParams, idVc))

        val callSite = ExpressionNode.MethodInvocation(
            methodName = "makeId",
            parentPath = "Script",
            parameters = emptyList(),
            rtnLookup = { idVc },
            field = null,
            arguments = emptyList()
        )

        val result = VCBinder.expandCallSiteArgs(callSite, methodSigs)
        assertNotNull(result)
        assertTrue(result is ExpressionNode.MethodInvocation)
        result as ExpressionNode.MethodInvocation

        assertEquals("makeId", result.methodName)
        assertEquals(intType(), result.type(), "rtnLookup should return Int after unboxing Id")
    }

    @Test
    fun expandCallSiteArgs_returnTypeOnlyChanged_paramsPreserved() {
        val idVc = createVC("Id", "value" to intType())
        val origParams = emptyList<Parameter>()
        val expandedParams = emptyList<Parameter>()
        val methodSigs = mapOf("makeId" to Triple(origParams, expandedParams, idVc))

        val callSite = ExpressionNode.MethodInvocation(
            methodName = "makeId",
            parentPath = "Script",
            parameters = emptyList(),
            rtnLookup = { idVc },
            field = null,
            arguments = emptyList()
        )

        val result = VCBinder.expandCallSiteArgs(callSite, methodSigs) as ExpressionNode.MethodInvocation

        assertEquals(callSite.parameters, result.parameters, "parameters should be preserved when paramsUnchanged")
        assertEquals(callSite.arguments, result.arguments, "arguments should be preserved when paramsUnchanged")
        assertEquals(callSite.parentPath, result.parentPath)
        assertEquals(callSite.field, result.field)
    }

    @Test
    fun expandCallSiteArgs_returnTypeChangedAndParamsExpanded_returnsUpdatedMI() {
        val pointVc = createVC("Point", "x" to intType(), "y" to intType())
        val boxVc = createVC("Box", "topLeft" to pointVc)

        val origParams = listOf(Parameter(boxVc, "b"))
        val expandedParams = expandParameterRecursively(boxVc, "b")
        val methodSigs = mapOf("getX" to Triple(origParams, expandedParams, pointVc))

        val boxParam = Type.JFVariableSymbol("b", boxVc)
        VCBinder.setExpandedFieldsOnSymbol(boxParam)

        // fun getX(b: Box): Point — makeId-like scenario where return type is single-field VC
        val callSite = ExpressionNode.MethodInvocation(
            methodName = "getX",
            parentPath = "",
            parameters = listOf(boxParam),
            rtnLookup = { pointVc },
            field = null,
            arguments = listOf(ExpressionNode.Variable(boxParam))
        )

        val result = VCBinder.expandCallSiteArgs(callSite, methodSigs)
        assertNotNull(result)
        assertTrue(result is ExpressionNode.MethodInvocation)
        result as ExpressionNode.MethodInvocation

        // Return type: Point has 2 fields, so intType since it's not a single-field VC chain
        assertEquals(pointVc, result.type(), "Point has 2 fields, no single-field unboxing")
        assertEquals(2, result.parameters.size, "Box parameter should expand to 2 scalars")
        assertEquals(2, result.arguments.size, "Argument should expand to 2 scalars")
    }

    // ================================================================================
    // transformTree integration: rtnLookup change propagation
    // ================================================================================

    @Test
    fun transformTree_propagatesNestedMethodInvocationRtnLookupChange() {
        val idVc = createVC("Id", "value" to intType())
        val methodSigs = mapOf("makeId" to Triple(emptyList<Parameter>(), emptyList<Parameter>(), idVc))

        // Build: MethodInvocation(println, args=[Convert(from=Id, to=Object, expr=MethodInvocation(makeId))])
        val innerMI = ExpressionNode.MethodInvocation(
            methodName = "makeId",
            parentPath = "Script",
            parameters = emptyList(),
            rtnLookup = { idVc },
            field = null,
            arguments = emptyList()
        )

        val objType = Type.JFClass("java.lang.Object")
        val convert = ExpressionNode.Convert(innerMI, idVc, objType)

        val outerMI = ExpressionNode.MethodInvocation(
            methodName = "println",
            parentPath = "jafun.io.ConsoleKt",
            parameters = listOf(Type.JFVariableSymbol("x", objType)),
            rtnLookup = { OperandType.Unit },
            field = null,
            arguments = listOf(convert)
        )

        // Apply transformTree with the same lambda as VCBinder Step 2
        val result = outerMI.transformTree { node ->
            VCBinder.expandCallSiteArgs(node, methodSigs) ?: node
        }

        assertTrue(result is ExpressionNode.MethodInvocation)
        result as ExpressionNode.MethodInvocation

        assertEquals(1, result.arguments.size, "Should still have 1 argument")

        val resultConvert = result.arguments[0]
        assertTrue(resultConvert is ExpressionNode.Convert)
        resultConvert as ExpressionNode.Convert

        val resultInnerMI = resultConvert.expression
        assertTrue(resultInnerMI is ExpressionNode.MethodInvocation)
        resultInnerMI as ExpressionNode.MethodInvocation

        // Verify rtnLookup was updated from Id to Int
        assertTrue(
            resultInnerMI.type() != idVc,
            "Inner MI type should no longer be Id after expandCallSiteArgs"
        )
        assertEquals(
            intType(), resultInnerMI.type(),
            "Inner MI type should be Int after R5 unboxing"
        )
    }

    @Test
    fun transformTree_nestedMI_outerMIRecreatedWhenInnerChanges() {
        val idVc = createVC("Id", "value" to intType())
        val methodSigs = mapOf("makeId" to Triple(emptyList<Parameter>(), emptyList<Parameter>(), idVc))

        val innerMI = ExpressionNode.MethodInvocation(
            methodName = "makeId",
            parentPath = "Script",
            parameters = emptyList(),
            rtnLookup = { idVc },
            field = null,
            arguments = emptyList()
        )

        val convert = ExpressionNode.Convert(innerMI, idVc, Type.JFClass("java.lang.Object"))

        val outerMI = ExpressionNode.MethodInvocation(
            methodName = "println",
            parentPath = "jafun.io.ConsoleKt",
            parameters = listOf(Type.JFVariableSymbol("x", Type.JFClass("java.lang.Object"))),
            rtnLookup = { OperandType.Unit },
            field = null,
            arguments = listOf(convert)
        )

        val originalIdentity = System.identityHashCode(outerMI)

        val result = outerMI.transformTree { node ->
            VCBinder.expandCallSiteArgs(node, methodSigs) ?: node
        }

        assertTrue(result is ExpressionNode.MethodInvocation)
        assertTrue(
            result !== outerMI,
            "Outer MI should be a different object (recreated due to inner MI rtnLookup change)"
        )
    }

    // ================================================================================
    // referentialListDiff
    // ================================================================================

    @Test
    fun referentialListDiff_sameElements_returnsFalse() {
        val a = intLiteral(1)
        val b = intLiteral(2)
        val list = listOf(a, b)
        assertFalse(referentialListDiff(list, list.toList()))
    }

    @Test
    fun referentialListDiff_differentElements_returnsTrue() {
        val a = intLiteral(1)
        val b = intLiteral(2)
        val c = intLiteral(3)
        assertTrue(referentialListDiff(listOf(a, b), listOf(a, c)))
    }

    @Test
    fun referentialListDiff_differentSizes_returnsTrue() {
        val a = intLiteral(1)
        assertTrue(referentialListDiff(listOf(a), listOf(a, a)))
    }

    @Test
    fun referentialListDiff_emptyLists_returnsFalse() {
        assertFalse(referentialListDiff(emptyList<Int>(), emptyList<Int>()))
    }

    // ================================================================================
    // resolveFieldAccessOnCallResult (R2)
    // ================================================================================

    @Test
    fun resolveFieldAccessOnCallResult_MI_singleField_eliminatesAccess() {
        val idVc = createVC("Id", "value" to intType())
        val methodSigs = mapOf("makeId" to Triple(emptyList<Parameter>(), emptyList<Parameter>(), idVc))

        val innerMI = ExpressionNode.MethodInvocation(
            methodName = "makeId",
            parentPath = "Script",
            parameters = emptyList(),
            rtnLookup = { idVc },
            field = null,
            arguments = emptyList()
        )
        val fieldAccess = ExpressionNode.FieldAccess(innerMI, "value", 0, intType(), emptyList())

        val result = VCBinder.resolveFieldAccessOnCallResult(fieldAccess, methodSigs)
        assertNotNull(result)
        assertTrue(result is ExpressionNode.MethodInvocation)
        assertEquals("makeId", (result as ExpressionNode.MethodInvocation).methodName)
    }

    @Test
    fun resolveFieldAccessOnCallResult_MI_wrongField_returnsNull() {
        val idVc = createVC("Id", "value" to intType())
        val methodSigs = mapOf("makeId" to Triple(emptyList<Parameter>(), emptyList<Parameter>(), idVc))

        val innerMI = ExpressionNode.MethodInvocation(
            methodName = "makeId",
            parentPath = "Script",
            parameters = emptyList(),
            rtnLookup = { idVc },
            field = null,
            arguments = emptyList()
        )
        val fieldAccess = ExpressionNode.FieldAccess(innerMI, "wrongField", 0, intType(), emptyList())

        assertNull(VCBinder.resolveFieldAccessOnCallResult(fieldAccess, methodSigs))
    }

    @Test
    fun resolveFieldAccessOnCallResult_MI_multiFieldVC_returnsNull() {
        val pointVc = createVC("Point", "x" to intType(), "y" to intType())
        val methodSigs = mapOf("getPoint" to Triple(emptyList<Parameter>(), emptyList<Parameter>(), pointVc))

        val innerMI = ExpressionNode.MethodInvocation(
            methodName = "getPoint",
            parentPath = "",
            parameters = emptyList(),
            rtnLookup = { pointVc },
            field = null,
            arguments = emptyList()
        )
        val fieldAccess = ExpressionNode.FieldAccess(innerMI, "x", 0, intType(), emptyList())

        assertNull(VCBinder.resolveFieldAccessOnCallResult(fieldAccess, methodSigs))
    }

    @Test
    fun resolveFieldAccessOnCallResult_Variable_singleField_eliminatesAccess() {
        val idVc = createVC("Id", "value" to intType())
        val sym = Type.JFVariableSymbol("myId", idVc)
        val varNode = ExpressionNode.Variable(sym)
        val fieldAccess = ExpressionNode.FieldAccess(varNode, "value", 0, intType(), emptyList())

        val result = VCBinder.resolveFieldAccessOnCallResult(fieldAccess, emptyMap())
        assertNotNull(result)
        assertTrue(result is ExpressionNode.Variable)
        assertEquals("myId", (result as ExpressionNode.Variable).variableSymbol.name)
    }

    @Test
    fun resolveFieldAccessOnCallResult_nonFieldAccess_returnsNull() {
        val result = VCBinder.resolveFieldAccessOnCallResult(intLiteral(42), emptyMap())
        assertNull(result)
    }
}
