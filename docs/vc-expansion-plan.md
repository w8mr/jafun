# VC Expansion Plan

## Problem

JVM limits method/constructor parameters to **255** (effectively 254 for instance
methods, since `this` counts). Recursive multi-field value classes can easily
exceed this limit when flattened:

```
value class Matrix(rows: Row)       // 20 rows
value class Row(a: Int, ..., t: Int) // 20 columns
// → 400 flattened params → VerificationError
```

Additionally, there may be other reasons to keep a VC boxed (JVM interop,
internal passing conventions, etc.).

## Current Situation

The unboxing decision is **distributed and implicit** — each function in
`VCFlattening.kt` and `VCBinder.kt` independently checks `type.kind == VALUE_CLASS`
and recursively inspects the type. There is no size awareness, no single
decision point, and no way to selectively expand some fields while keeping
others boxed.

## Goal

A **single computation point** that produces a decision tree per method
parameter. The decision tree is stored and read by all downstream functions,
eliminating repeated type inspection and enabling consistent, size-aware
expansion.

## Data Structures

```kotlin
sealed interface FieldDecision {
    /** Expand this field recursively. Primitives are always Flatten (no-op). */
    data object Flatten : FieldDecision
    /** Keep this field boxed — don't expand, even if it's a VC. */
    data object Keep : FieldDecision
}

data class ParamExpansionPlan(
    val paramName: String,
    val paramType: OperandType<*>,
    val decision: FieldDecision,
    val expandedParams: List<Parameter>,         // empty if Keep
    val fields: Map<String, FieldDecision>,      // child field path → decision, empty if Keep
)

data class MethodExpansionPlan(
    val methodName: String,
    val originalParams: List<Parameter>,
    val expandedParams: List<Parameter>,
    val expandedReturnType: OperandType<*>?,
    val paramPlans: List<ParamExpansionPlan>,
)
```

`fields` maps from field path relative to the root param — e.g. `"rows"`,
`"rows.x"`, `"title"`. If a parent is `Keep`, its children are implicitly kept
and can be absent from the map.

## Computation Point

In `VCBinder.handle()` as the **first step**, before anything else:

```
handle(context):
    plans = context.methods.map { computeMethodPlan(it) }
    // rest of pipeline uses plans instead of re-computing

computeMethodPlan(method):
    rawPlans = method.parameters.map { computeParamPlan(it) }
    total = rawPlans.sumOf { it.expandedParams.size }
    adjusted = if (total > MAX_PARAMS) capToFit(rawPlans) else rawPlans
    return MethodExpansionPlan(
        methodName, method.parameters,
        adjusted.flatMap { it.expandedParams },
        unboxSingleFieldVCType(method.returnType),
        adjusted,
    )

computeParamPlan(param, path = param.varName):
    type = param.type
    if type is not a VC:
        return Plan(param.varName, type, Flatten,
                    expandedParams = [param], fields = emptyMap())
    if type has no constructor:
        return Plan(param.varName, type, Keep,
                    expandedParams = [param], fields = emptyMap())
    subPlans = type.constructor.parameters.map { fieldParam ->
        fieldPath = path + "_" + fieldParam.name
        computeParamPlan(fieldParam, fieldPath)
    }
    return Plan(param.varName, type, Flatten,
                expandedParams = subPlans.flatMap { it.expandedParams },
                fields = subPlans.flatMap { buildFieldMap(it) }.toMap())

capToFit(plans):               // simple first strategy
    if total <= MAX_PARAMS: return plans
    return plans.map { setAllKeep(it) }
```

## How Downstream Functions Change

### A. Direct replacements (no type inspection needed)

| Before | After |
|---|---|
| `expandParameterForVC(param)` | Read `expansionPlans[methodName].expandedParams` |
| `methodSigs` triple (params, expanded, returnType) | Read `MethodExpansionPlan` |
| `unboxedReturnType = unboxSingleFieldVCType(method.returnType)` | `plan.expandedReturnType` |

### B. Decision-aware overloads in VCFlattening.kt

Each function gets a new overload that takes a `ParamExpansionPlan` or
`Map<String, FieldDecision>`:

| Function | Change |
|---|---|
| `flattenType(type, prefix)` | New overload: `flattenType(type, decisions, prefix)` — filters out paths whose parent decision is `Keep`. |
| `expandVariable(symbol)` | New overload: `expandVariable(symbol, plan)` — uses decision-aware `flattenType`. |
| `computeExpandedInfo(symbol)` | New overload: `computeExpandedInfo(symbol, plan)` — only populates `ExpandedInfo` for paths marked `Flatten`. |

### C. VCBinder functions use plans

| Function | Change |
|---|---|
| `setExpandedFieldsOnSymbol(symbol, info)` | Receives plan for the containing method; skips if `Keep`. |
| `eagerlyExpandVariableIfNeeded(node, info, assigned)` | Checks plan; only populates `expandedInfo` for expanded fields. |
| `reconstructFromScalars(node, info)` | Uses plan — a `Keep` field is not in the scalar symbol map; returns null (let the boxed param through). |
| `resolveFieldAccessToScalar(node, info)` | A field access ending in a `Keep` sub-tree should NOT resolve to a scalar — stays as `FieldAccess` on a boxed object. |
| `expandCallSiteArgs(node, methodSigs, info)` | Reads `expansionPlans[node.methodName]` — no re-flattening. |
| `expandArgForCallSite(arg, param, info)` | Reads the param's decision from the plan. |
| `flattenCIArgs(ci)` | Checks each CI arg's path against the plan; keeps `Keep` fields as-is. |
| `expectedExpressionType(expr, methodSigs, replacements)` | Reads expanded types from plan. |

### D. Unchanged

`walkUpFieldAccessChain`, `resolveFieldAccessOnCallResult`, `fieldTypeForAccess`,
`createNestedFieldAccess`, `isMultiFieldVC` — these don't make expansion decisions.

## Implementation Order

1. Add `FieldDecision`, `ParamExpansionPlan`, `MethodExpansionPlan` types
2. Add `computeMethodPlan` / `computeParamPlan` to `VCBinder.kt`
3. Add decision-aware overloads to `VCFlattening.kt`
4. Refactor `VCBinder.handle()` to use plans as the first step
5. Push plan through all downstream functions
6. Run test suite, fix failures
7. Clean up: remove `expandParameterForVC` if no longer needed,
   simplify `methodSigs`, remove dead `isMultiFieldVC` calls

## Key Properties

- **Single computation point** — `computeMethodPlan` runs once per method.
- **Consistent** — every function reads from the same plan.
- **Extensible** — `capToFit` can evolve (e.g., selectively keep largest
  subtrees) without changing downstream code.
- **No signature change on `JFClass`** — the plan is per-method, not per-type.
- **Backward compatible** — `Flatten`-all plan == current behavior.
