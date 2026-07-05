package nl.w8mr.jafun.compiler

import nl.w8mr.jafun.OperandType
import nl.w8mr.jafun.Type

/**
 * Decision for a single field in a VC expansion tree.
 * [Flatten] — expand this field recursively (always for primitives).
 * [Keep] — keep this field boxed (don't expand, even if it's a VC).
 */
sealed interface FieldDecision {
    data object Flatten : FieldDecision
    data object Keep : FieldDecision
}

/**
 * Expansion plan for a single method parameter.
 *
 * @param paramName  The original parameter name (e.g. "table")
 * @param paramType  The original parameter type
 * @param decision   Root-level decision for this parameter
 * @param expandedParams  The flattened parameter list.
 *   Empty if [decision] is [FieldDecision.Keep] (param stays as-is).
 * @param fields     Child field path → decision map.
 *   Empty if [decision] is [FieldDecision.Keep].
 *   Non-VC fields always have [FieldDecision.Flatten] (no-op).
 */
data class ParamExpansionPlan(
    val paramName: String?,
    val paramType: OperandType<*>,
    val decision: FieldDecision,
    val expandedParams: List<Parameter>,
    val fields: Map<String, FieldDecision>,
)

/**
 * Expansion plan for an entire method (parameters + return type).
 *
 * @property returnType  The original return type (before unboxing).
 */
data class MethodExpansionPlan(
    val methodName: String,
    val returnType: OperandType<*>,
    val originalParams: List<Parameter>,
    val expandedParams: List<Parameter>,
    val expandedReturnType: OperandType<*>?,
    val paramPlans: List<ParamExpansionPlan>,
)

const val MAX_EXPANDED_PARAMS = 254

/**
 * Compute expansion plan for a single parameter, walking the type tree.
 * Primitives and non-VCs are always [FieldDecision.Flatten].
 * VCs without constructors are [FieldDecision.Keep].
 */
fun computeParamPlan(
    baseName: String?,
    type: OperandType<*>,
    path: String? = baseName,
): ParamExpansionPlan {
    if (type !is Type.JFClass || type.kind != Type.ClassKind.VALUE_CLASS) {
        return ParamExpansionPlan(
            paramName = baseName,
            paramType = type,
            decision = FieldDecision.Flatten,
            expandedParams = listOf(Parameter(type, path)),
            fields = emptyMap(),
        )
    }
    val cons = type.constructor
    if (cons == null) {
        return ParamExpansionPlan(
            paramName = baseName,
            paramType = type,
            decision = FieldDecision.Keep,
            expandedParams = listOf(Parameter(type, path)),
            fields = emptyMap(),
        )
    }
    val subPlans = cons.parameters.map { fieldParam ->
        val fieldPath = if (path != null) "${path}_${fieldParam.name}" else fieldParam.name
        computeParamPlan(fieldParam.name, fieldParam.type, fieldPath)
    }
    val fields = subPlans.flatMap { plan ->
        val prefix = plan.paramName ?: ""
        if (plan.decision == FieldDecision.Keep) {
            listOf(prefix to FieldDecision.Keep)
        } else {
            listOf(prefix to FieldDecision.Flatten) + plan.fields.map { (k, v) ->
                "${prefix}_$k" to v
            }
        }
    }.toMap()
    val expanded = subPlans.flatMap { it.expandedParams }
    return ParamExpansionPlan(
        paramName = baseName,
        paramType = type,
        decision = FieldDecision.Flatten,
        expandedParams = expanded,
        fields = fields,
    )
}

/**
 * Greedy left-to-right cap-to-fit strategy.
 *
 * Iterates plans in order; keeps each parameter expanded as long as the
 * running total stays ≤ [limit].  Once the next expanded parameter would
 * push the total over the limit, that parameter (and all remaining) are
 * kept boxed (each contributes exactly 1 slot).
 *
 * @param limit  Maximum number of expanded parameters allowed (default [MAX_EXPANDED_PARAMS]).
 */
fun capToFitGreedy(
    plans: List<ParamExpansionPlan>,
    limit: Int = MAX_EXPANDED_PARAMS,
): List<ParamExpansionPlan> {
    var running = 0
    return plans.map { plan ->
        val expandedSize = plan.expandedParams.size
        if (running + expandedSize <= limit) {
            running += expandedSize
            plan
        } else {
            running += 1
            ParamExpansionPlan(
                paramName = plan.paramName,
                paramType = plan.paramType,
                decision = FieldDecision.Keep,
                expandedParams = listOf(Parameter(plan.paramType, plan.paramName)),
                fields = emptyMap(),
            )
        }
    }
}
