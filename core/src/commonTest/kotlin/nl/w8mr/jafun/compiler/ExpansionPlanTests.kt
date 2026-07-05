package nl.w8mr.jafun.compiler

import nl.w8mr.jafun.OperandType
import nl.w8mr.jafun.Type
import kotlin.test.Test
import kotlin.test.assertEquals

class ExpansionPlanTests {

    private fun intType() = OperandType.SInt32
    private fun stringType() = OperandType.StringType

    private fun createVC(name: String, vararg params: Pair<String, OperandType<*>>): Type.JFClass {
        val vc = Type.JFClass(name, kind = Type.ClassKind.VALUE_CLASS)
        val ps = params.map { (pn, pt) -> Type.JFVariableSymbol(pn, pt) }
        val cons = Type.JFConstructor(ps, vc)
        return vc.copy(constructor = cons)
    }

    // ── capToFitGreedy ──────────────────────────────────────────────────

    @Test
    fun `capToFitGreedy keeps all expanded when total fits`() {
        val plans = listOf(
            computeParamPlan("a", intType()),
            computeParamPlan("b", stringType()),
        )
        val result = capToFitGreedy(plans, limit = 5)
        assertEquals(FieldDecision.Flatten, result[0].decision)
        assertEquals(FieldDecision.Flatten, result[1].decision)
    }

    @Test
    fun `capToFitGreedy boxes rightmost VCs when limit exceeded`() {
        val addr = createVC("Addr", "street" to stringType(), "nr" to intType())
        val plans = listOf(
            computeParamPlan("a", addr),          // 2 slots
            computeParamPlan("b", addr),          // 2 slots
            computeParamPlan("c", addr),          // 2 slots
        )
        // total expanded = 6 > 3 → greedy: expand first, box rest
        val result = capToFitGreedy(plans, limit = 3)
        assertEquals(FieldDecision.Flatten, result[0].decision, "a — expanded (2 ≤ 3)")
        assertEquals(FieldDecision.Keep,   result[1].decision, "b — boxed (next 2 would exceed)")
        assertEquals(FieldDecision.Keep,   result[2].decision, "c — boxed (all remaining)")
        // First param expanded (2 slots) + two boxed (1 each) = 4 total slots
        assertEquals(4, result.flatMap { it.expandedParams }.size)
    }

    @Test
    fun `capToFitGreedy expands leftmost that fits then boxes subsequent`() {
        val addr = createVC("Addr", "street" to stringType(), "nr" to intType())
        val plans = listOf(
            computeParamPlan("a", intType()),       // 1 slot
            computeParamPlan("b", addr),            // 2 slots
            computeParamPlan("c", stringType()),     // 1 slot
            computeParamPlan("d", addr),            // 2 slots
        )
        // total expanded = 6 > 4 → greedy: expand a, b, c — d boxed
        val result = capToFitGreedy(plans, limit = 4)
        assertEquals(FieldDecision.Flatten, result[0].decision, "a")
        assertEquals(FieldDecision.Flatten, result[1].decision, "b")
        assertEquals(FieldDecision.Flatten, result[2].decision, "c")
        assertEquals(FieldDecision.Keep,   result[3].decision, "d")
        assertEquals(1 + 2 + 1 + 1, result.flatMap { it.expandedParams }.size)
    }

    @Test
    fun `capToFitGreedy boxes first VC when its expansion alone exceeds limit`() {
        val huge = createVC("Huge", "x" to intType(), "y" to intType(), "z" to intType())
        val plans = listOf(
            computeParamPlan("p", huge),   // 3 slots
            computeParamPlan("q", intType()),
        )
        val result = capToFitGreedy(plans, limit = 2)
        assertEquals(FieldDecision.Keep,   result[0].decision, "p — boxed (3 slots exceed limit=2)")
        assertEquals(FieldDecision.Flatten, result[1].decision, "q — fits in remaining slot")
        assertEquals(2, result.flatMap { it.expandedParams }.size)
    }

    @Test
    fun `capToFitGreedy boxes everything when no single VC can fit`() {
        val big = createVC("Big", "x" to intType(), "y" to intType(), "z" to intType())
        val plans = listOf(
            computeParamPlan("p", big),   // 3 slots — exceeds limit=2
            computeParamPlan("q", big),   // 3 slots — also exceeds
        )
        val result = capToFitGreedy(plans, limit = 2)
        assertEquals(FieldDecision.Keep, result[0].decision, "p")
        assertEquals(FieldDecision.Keep, result[1].decision, "q")
        assertEquals(2, result.flatMap { it.expandedParams }.size)
    }

    @Test
    fun `capToFitGreedy with huge limit is a no-op`() {
        val big = createVC("Big", "x" to intType(), "y" to intType())
        val plans = listOf(computeParamPlan("p", big), computeParamPlan("q", intType()))
        val result = capToFitGreedy(plans, limit = 100)
        assertEquals(FieldDecision.Flatten, result[0].decision)
        assertEquals(FieldDecision.Flatten, result[1].decision)
    }

    @Test
    fun `capToFitGreedy with default limit expands as many leftmost VCs as possible`() {
        val pt = createVC("Pt", "x" to intType(), "y" to intType())
        val plans = List(200) { computeParamPlan("p$it", pt) }
        // each VC → 2 slots, so with limit=254 we can expand 127 before hitting the cap
        val result = capToFitGreedy(plans)
        assertEquals(FieldDecision.Flatten, result[0].decision,   "first VC expanded")
        assertEquals(FieldDecision.Flatten, result[126].decision, "VC #127 expanded")
        assertEquals(FieldDecision.Keep,    result[127].decision, "VC #128 boxed")
        assertEquals(FieldDecision.Keep,    result[199].decision, "last VC boxed")
        // 127 expanded × 2 + 73 boxed × 1 = 327 total slots
        assertEquals(127 * 2 + 73, result.flatMap { it.expandedParams }.size)
    }

    // ── computeParamPlan field paths ────────────────────────────────────

    @Test
    fun `computeParamPlan varName preserves null for unnamed params`() {
        val plan = computeParamPlan(null, intType())
        assertEquals(null, plan.paramName)
        assertEquals(1, plan.expandedParams.size)
        assertEquals(null, plan.expandedParams[0].varName)
    }

    @Test
    fun `computeParamPlan uses full path for nested VC fields`() {
        val pt = createVC("Pt", "x" to intType(), "y" to intType())
        val plan = computeParamPlan("p", pt)
        val names = plan.expandedParams.map { it.varName }
        assertEquals(listOf("p_x", "p_y"), names)
    }

    @Test
    fun `computeParamPlan omits prefix when root is unnamed`() {
        val pt = createVC("Pt", "x" to intType())
        val plan = computeParamPlan(null, pt)
        val names = plan.expandedParams.map { it.varName }
        assertEquals(listOf("x"), names)
    }
}
