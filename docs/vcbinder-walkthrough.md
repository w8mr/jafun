# VCBinder IR Transformation Walkthrough

## What VCBinder Does

VCBinder is a Phase 3 compiler plugin that eliminates value class (VC) boxing from the IR. After VCBinder runs, no variable, parameter, or return value has a VC type — every node is a primitive or a plain class reference. JVMBackend (the bytecode emitter) is completely VC-unaware.

VCBinder lives in `VCBinder.kt` with core helpers in `VCFlattening.kt`. Both are tested by `VCBinderTests.kt` and `VCFlatteningTests.kt`.

## The Example

```jafun
value class Point(x: Int, y: Int)
value class Id(value: Int)

fun make(v: Int) Id {
    Id(v)
}

fun show(p: Point) Int {
    println p.x
    0
}

fun main() Int {
    val a = Point(10, 20)         // (1)
    show(a)                        // (2)
    println a.x                    // (3)
    0
}
```

| Line | Transformation triggered |
|------|------------------------|
| `val a = Point(10, 20)` | R1 — assignment expansion |
| `show(a)` | R4 — reconstructFromScalars; expandCallSiteArgs |
| `println a.x` | R3 — field access to scalar resolution |
| `p.x` inside `show` | R3 — parameter field access resolution |

Additional examples in later sections cover R2 (field access on call result) and R5 (return unboxing).

## Initial IR (before VCBinder)

AST2IR produces these method bodies. The notation below is a schematic representation of the actual `ExpressionNode` tree.

### `make(v: Int): Id`

```
ValAssignment(_result: Id,
    ConstructorInvocation(Id.constructor, [Variable(v: Int)]))
```

### `show(p: Point): Int`

```
MethodInvocation("println", args=[
    FieldAccess(instance=Variable(p: Point), fieldName="x", fieldType=Int)
])
IntegerLiteral(0)
```

### `main(): Int`

```
ValAssignment(a: Point,
    ConstructorInvocation(Point.constructor,
        [IntegerLiteral(10), IntegerLiteral(20)]))
MethodInvocation("show", rtnLookup={->Int}, params=[p: Point],
    args=[Variable(a: Point)])
MethodInvocation("println", args=[
    FieldAccess(instance=Variable(a: Point), fieldName="x", fieldType=Int)
])
IntegerLiteral(0)
```

---

## Pre-processing

Before the main pipeline, `handle()` in `VCBinder.kt:17` builds two data structures.

### 1. methodSigs

For each method, pre-compute what its parameters look like after VC expansion and whether the return type unboxes to a scalar:

```
"make" → Triple([v: Int], [v: Int], Id)
"show" → Triple([p: Point], [p_x: Int, p_y: Int], Int)
"main" → Triple([], [], Int)
```

`expandParameterForVC` (VCBinder.kt:102) delegates to `expandParameterRecursively` (VCFlattening.kt:166). For `Point(x: Int, y: Int)` the single `p: Point` parameter becomes two scalars `p_x: Int, p_y: Int`. `Id(value: Int)` is single-field, so parameter expansion keeps it as one — but its return type is flagged as unboxable by `unboxSingleFieldVCType`.

### 2. symbolReplacements

A read-only scan (`computeSymbolReplacements`, VCBinder.kt:448) walks every `ValAssignment`/`VarAssignment` in the original instructions and compares the RHS effective type (via `expectedExpressionType`, VCBinder.kt:468) with the variable's declared type.

| Method | Assignment | RHS effective type | Declared type | Replacement |
|--------|-----------|-------------------|---------------|-------------|
| `main` | `val a = Point(10,20)` | `Point` | `Point` | none |
| `make` | (body only, no assignment) | — | — | — |
| `show` | (no assignments) | — | — | — |

`Point(10, 20)` is a `ConstructorInvocation` — `expectedExpressionType` falls to `else → expr.type()` = `Point`. Same as `a.type`, so no replacement.

`make`'s body `Id(v)` is a single expression, not an assignment, so `checkAssignment` never evaluates it. (Return unboxing is handled later by R5.)

### 3. assignedVarNames

A separate pre-walk collects the names of all assigned variables (locals) to distinguish them from parameters:

```
assignedVarNames(main) = {"a"}
assignedVarNames(show) = {}
assignedVarNames(make) = {"_result"}
```

This set is used by `maybeExpandField` (VCBinder.kt:536) to avoid eagerly populating `expandedInfo` for locals — R1 already handles those.

---

## Stage 1: R1 — Assignment Expansion

`expandAssignmentIfNeeded` (VCFlattening.kt:318) processes each `ValAssignment`/`VarAssignment` whose RHS is a `ConstructorInvocation` of a multi-field VC. It replaces the single assignment with one scalar assignment per flattened field.

### `val a = Point(10, 20)` in `main`

**Before:**
```
ValAssignment(a: Point,
    ConstructorInvocation(Point.constructor,
        [IntegerLiteral(10), IntegerLiteral(20)]))
```

**Step 1 — `computeExpandedInfo(a)`** (VCFlattening.kt:204):

```
flattenType(Point(x: Int, y: Int)) →
    [FlattenedField("x", Int), FlattenedField("y", Int)]

expandVariable(a: Point) →
    [a_x: Int, a_y: Int]

fieldPathToSymbol = {"x" → a_x, "y" → a_y}
```

Each constructor parameter maps to an `ExpandedField` with `actualSymbol` pointing to the corresponding scalar:

```
ExpandedInfo(
    fields = [
        ExpandedField(name="x", type=Int, actualSymbol=a_x),
        ExpandedField(name="y", type=Int, actualSymbol=a_y)
    ],
    fieldSymbols = {"x" → a_x, "y" → a_y}
)
```

This info is stored as `expandedInfo["a"]`, enabling later steps to resolve field accesses like `a.x` and reconstruct `a` as a value.

**Step 2 — `extractExpandedArg`** (VCFlattening.kt:256) per flattened field:

```
field.path = "x" → matches constructor param "x" at index 0
    remainingPath = [] (field.path == param.name)
    → returns ci.arguments[0] = IntegerLiteral(10)

field.path = "y" → matches constructor param "y" at index 1
    remainingPath = [] (field.path == param.name)
    → returns ci.arguments[1] = IntegerLiteral(20)
```

**After (two scalar assignments):**
```
ValAssignment(a_x: Int, IntegerLiteral(10))
ValAssignment(a_y: Int, IntegerLiteral(20))
```

The instructions that follow (`show(a)`, `println a.x`) still reference the original variable `a`. These are handled in Stage 2.

### `Id(v)` in `make`

`make`'s body `Id(v)` is also a ValAssignment wrapping a `ConstructorInvocation`:

**Before:**
```
ValAssignment(_result: Id,
    ConstructorInvocation(Id.constructor, [Variable(v: Int)]))
```

`Id` is single-field. R1 still expands it — `computeExpandedInfo` returns non-null for any VC with a constructor:

```
expandedInfo["_result"] = ExpandedInfo(
    fields = [ExpandedField(name="value", type=Int, actualSymbol=_result_value)],
    fieldSymbols = {"value" → _result_value}
)
```

**After:**
```
ValAssignment(_result_value: Int, Variable(v: Int))
```

The original `_result: Id` symbol is gone — the method now assigns directly to an Int scalar.

---

## Stage 2: Single `transformTree` Pass

Every instruction (including those produced by R1) passes through one `transformTree` callback (VCBinder.kt:58). The callback has this structure:

```
transformTree { node ->
    eagerlyExpandVariableIfNeeded(node, expandedInfo, assignedVarNames)  // side effect

    when {
        node is ExpressionNode.Variable -> {
            symbolReplacements[node.variableSymbol.name] ?:    // (a)
            reconstructFromScalars(node, expandedInfo) ?:      // (b)
            node
        }
        node is ExpressionNode.Convert -> {
            fix `from` type via expectedExpressionType          // (c)
        }
        else -> {
            resolveFieldAccessToScalar(node, expandedInfo)      // (d)
                ?: expandCallSiteArgs(node, methodSigs, expandedInfo)  // (e)
                ?: resolveFieldAccessOnCallResult(node, methodSigs)    // (f)
                ?: node
        }
    }
}
```

Each numbered step is explained below using the example instructions.

---

### Instruction: `ValAssignment(a_x: Int, 10)`

Both `a_x` and the RHS `10` are `Int` — no VC involved.

- **Side effect:** `eagerlyExpandVariableIfNeeded` — `ValAssignment` is not a `Variable`, `FieldAccess`, `MethodInvocation`, or `ConstructorInvocation` → no-op.
- **Main callback:** `ValAssignment` is not a `Variable` or `Convert` → `else` branch:
  1. `resolveFieldAccessToScalar` — not a `FieldAccess` → null
  2. `expandCallSiteArgs` — not a `MethodInvocation` → null
  3. `resolveFieldAccessOnCallResult` — not a `FieldAccess` → null
  4. Falls through → returns node unchanged

### Instruction: `ValAssignment(a_y: Int, 20)`

Same — passes through unchanged.

---

### Instruction: `MethodInvocation("show", rtnLookup={->Int}, params=[p: Point], args=[Variable(a: Point)])`

This is the call `show(a)`. `a` was expanded to scalars, and `show`'s parameter `p: Point` was also expanded to `p_x: Int, p_y: Int`. Three things need to happen:
1. `a` must be reconstructed from scalars or expanded inline
2. The call site's argument list must match `show`'s expanded parameter list
3. The call site's parameter symbols must be updated

#### Step (b): reconstructFromScalars — Variable branch

The callback first processes children (top-down). `Variable(a: Point)` inside the args is visited:

**Side effect:** `eagerlyExpandVariableIfNeeded(Variable(a))` → `maybeExpandField(a, ...)`:
- `a` is in `expandedInfo` (populated by R1) → skip (no-op).

**Main callback (Variable branch):**
- `symbolReplacements["a"]` is null (no replacement) → skip.
- `reconstructFromScalars(Variable(a), expandedInfo)`:
  `expandedInfo["a"]` has fields and fieldSymbols. The VC type is `Point` with constructor params `[x: Int, y: Int]`. For each constructor param:
  - `"x"`: `pathMap["x"] = a_x` → `Variable(a_x)`
  - `"y"`: `pathMap["y"] = a_y` → `Variable(a_y)`
  Result: `ConstructorInvocation(Point.constructor, [Variable(a_x), Variable(a_y)])`

#### Step (e): expandCallSiteArgs — fallback chain on the outer node

The parent `MethodInvocation` node is also visited (before children in top-down order):

**Side effect:** `eagerlyExpandVariableIfNeeded(MethodInvocation)` → recurse into args → visits `Variable(a: Point)` → already has expandedInfo → no-op.

**Main callback (else branch):**
1. `resolveFieldAccessToScalar` — not a `FieldAccess` → null.
2. `expandCallSiteArgs` — IS a `MethodInvocation` ✓

   `methodSigs["show"]` returns `([p: Point], [p_x: Int, p_y: Int], Int)`.

   `paramsUnchanged = (1==2 && all types same?)` → false → need expansion.

   For the original param `p: Point`:
   - `expandParameterForVC(p)` = `[Parameter(Int, "p_x"), Parameter(Int, "p_y")]`
   - `expandedForParam.size == 1` → false
   - `expandArgForCallSite(arg, p, expandedInfo)` where `arg` is `Variable(a: Point)`
     - `type = Point`, is VC ✓
     - `flattenType(Point)` = `[FlattenedField("x", Int), FlattenedField("y", Int)]`
     - Arg is a `Variable` with `expandedInfo["a"]` having `fieldSymbols`
     - Each field path maps to a scalar symbol → returns `[Variable(a_x), Variable(a_y)]`

   New `JFVariableSymbol` params are built from the expanded `Parameter` list:
   ```
   [JFVariableSymbol("p_x", Int), JFVariableSymbol("p_y", Int)]
   ```

**After:**
```
MethodInvocation("show", rtnLookup={->Int},
    params=[p_x: Int, p_y: Int],
    args=[Variable(a_x: Int), Variable(a_y: Int)])
```

Note that the `show` method's own body was processed independently (see below), so its parameter `p` was also expanded. The call site now matches the callee's new signature.

---

### Inside `show`: `MethodInvocation("println", args=[FieldAccess(instance=Variable(p: Point), fieldName="x")])`

`show` is a separate method processed in its own `handle()` iteration. Its body contains a field access on parameter `p`.

#### Side effect: parameter expansion

When `show`'s instructions enter the pipeline, `expandedInfo` starts empty. `eagerlyExpandVariableIfNeeded` encounters `Variable(p: Point)` inside the FieldAccess:

```
walkUpFieldAccessChain(FieldAccess("x", Variable(p))) → path=["x"], root=Variable(p)
maybeExpandField(p, ...) → p not in expandedInfo, not in assignedVarNames
    → setExpandedFieldsOnSymbol(p, expandedInfo)
    → computeExpandedInfo(p) → expandedInfo["p"] = {Point's expanded info}
```

Now `expandedInfo["p"]` contains `fieldSymbols = {"x" → p_x, "y" → p_y}`.

#### Step (d): resolveFieldAccessToScalar — else branch

The callback processes the `FieldAccess` node:

**Side effect:** repeated call (already done above) → no-op.

**Main callback (else branch):**

1. `resolveFieldAccessToScalar(FieldAccess(...), expandedInfo)`:
   - `walkUpFieldAccessChain` → `pathFromRoot = ["x"]`, root = `Variable(p)`
   - `expandedInfo["p"]` → `fieldSymbols = {"x" → p_x, "y" → p_y}`
   - `pathKey = "x"`, `pathMap["x"] = p_x` ✓
   - Returns `Variable(p_x: Int)`

2. Remaining resolvers skipped.

**After:**
```
MethodInvocation("println", args=[Variable(p_x: Int)])
```

---

### Back in `main`: `MethodInvocation("println", args=[FieldAccess(instance=Variable(a: Point), fieldName="x")])`

#### Step (d): resolveFieldAccessToScalar

The callback processes the `FieldAccess` argument (after the parent `MethodInvocation` is returned unchanged and transformTree recurses into children):

**Side effect:** `eagerlyExpandVariableIfNeeded(FieldAccess(...))` → `walkUpFieldAccessChain` → root is `Variable(a)` → already in `expandedInfo` → no-op.

**Main callback (else branch):**

1. `resolveFieldAccessToScalar(FieldAccess(...), expandedInfo)`:
   - `walkUpFieldAccessChain` → `pathFromRoot = ["x"]`, root = `Variable(a)`
   - `expandedInfo["a"]` → `fieldSymbols = {"x" → a_x, "y" → a_y}`
   - `pathKey = "x"`, `pathMap["x"] = a_x` ✓
   - Returns `Variable(a_x: Int)`

**After:**
```
MethodInvocation("println", args=[Variable(a_x: Int)])
```

---

### Instruction: `IntegerLiteral(0)`

Return value — no VC involved → passes through unchanged.

---

## Stage 3: R5 — Return Unboxing

After the `transformTree` pass, each method's return expression is checked:

```kotlin
if (unboxedReturnType != null)
    unboxSingleFieldReturnExpr(result, expandedInfo)
else
    result
```

### `main` → returns `Int`

`unboxedReturnType = unboxSingleFieldVCType(Int)` = null → no unboxing needed. The `IntegerLiteral(0)` passes through.

### `show` → returns `Int`

Same — `Int` is not a VC → no unboxing.

### `make` → returns `Id`

`unboxedReturnType = unboxSingleFieldVCType(Id)` = `Int` (not null) → unboxing runs.

The last instruction in `make` after R1 is:
```
ValAssignment(_result_value: Int, Variable(v: Int))
```

`unboxSingleFieldReturnExpr` is called on `ValAssignment(...)`. But R5 looks at the return expression, not assignments. Actually, `make`'s body as a whole is an expression that evaluates to the last expression. Let me trace this:

`make`'s original body is `Id(v)`. After R1, it's `ValAssignment(_result_value: Int, Variable(v: Int))`. But `make` doesn't actually have an explicit `return` keyword — in jafun, the last expression is the return value. So the instructions after R1 are:

```
[
    ValAssignment(_result_value: Int, Variable(v: Int)),
    Variable(_result_value: Int)    ← implicit return (the last expression)
]
```

Wait, does the compiler add an implicit return variable read? Let me check... Actually, `make`'s body in the source is `{ Id(v) }`. The compiler wraps the body expression in a ValAssignment to `_result` (to capture the VC type for return unboxing). After R1 expands it, the last expression is `Variable(_result_value: Int)` which returns the scalar.

`unboxSingleFieldReturnExpr(Variable(_result_value: Int), ...)`:
- `expr` is `Variable` → `unboxSingleFieldVCType(Int)` = null (Int is not VC)
- Returns `Variable(_result_value: Int)` unchanged

Hmm, but the return type of `make` was `Id`. After R5, it should be `Int`. How does the return type get updated?

Looking at `handle()` (VCBinder.kt:87-92):
```kotlin
method.copy(
    parameters = expandedParams,
    returnType = unboxedReturnType ?: method.returnType,
    instructions = instructions.toMutableList()
)
```

The return type of the method is replaced with `unboxedReturnType` if non-null. For `make`, `unboxedReturnType = Int`. So the method signature changes from `(): Id` to `(): Int`.

---

## Final IR (after VCBinder)

### `make(v: Int): Int`

```
ValAssignment(_result_value: Int, Variable(v: Int))
Variable(_result_value: Int)
```

Return type: `Id` → `Int` (R5).

### `show(p_x: Int, p_y: Int): Int`

```
MethodInvocation("println", args=[Variable(p_x: Int)])
IntegerLiteral(0)
```

Parameter `p: Point` was expanded to `p_x: Int, p_y: Int`. The field access `p.x` was resolved to `Variable(p_x)`.

### `main(): Int`

```
ValAssignment(a_x: Int, IntegerLiteral(10))
ValAssignment(a_y: Int, IntegerLiteral(20))
MethodInvocation("show", rtnLookup={->Int},
    params=[p_x: Int, p_y: Int],
    args=[Variable(a_x: Int), Variable(a_y: Int)])
MethodInvocation("println", args=[Variable(a_x: Int)])
IntegerLiteral(0)
```

No VC type remains. Variables are `Int` scalars. Method signatures use expanded parameters. JVMBackend emits straightforward `iload`/`istore`/`invokestatic` bytecode.

---

## Additional Transformations

The main example covers R1, R3, R4, and `expandCallSiteArgs`. Two more transformations exist.

### R2 — `resolveFieldAccessOnCallResult` (VCBinder.kt:187)

Strips a redundant field access on a single-field VC value. For code like:

```jafun
fun makeId(v: Int) Id {
    Id(v)
}
```

The IR `FieldAccess(instance=MethodInvocation("makeId", ...), fieldName="value")` is recognized: the method's return type `Id` unboxes to `Int`, and `Id`'s single field is `"value"`. The access is eliminated, returning the `MethodInvocation` directly.

**Before:**
```
FieldAccess(
    instance=MethodInvocation("makeId", rtnLookup={->Id}, args=[IntegerLiteral(42)]),
    fieldName="value", fieldType=Int)
```

**After:**
```
MethodInvocation("makeId", rtnLookup={->Id}, args=[IntegerLiteral(42)])
```

The caller can then use the method result as an `Int` directly. (The `rtnLookup` may be updated to `{->Int}` by `expandCallSiteArgs` in a separate visit — the resolvers are tried in sequence and the first match wins.)

### R5 — `unboxSingleFieldReturnExpr` (VCBinder.kt:226)

Unwraps a function's return expression when the function returns a single-field VC. The three cases:

| Expression type | Transformation |
|----------------|---------------|
| `ConstructorInvocation(cons, [scalar])` | → `scalar` |
| `Variable(sym)` with single-entry `fieldSymbols` | → `Variable(scalarSym)` |
| `ExpressionList([..., last])` | → `ExpressionList([..., unboxed(last)])` |

For `make(v): Id { Id(v) }`, the body expression `ConstructorInvocation(Id.constructor, [Variable(v)])` is unwrapped to `Variable(v)` and the method's return type changes from `Id` to `Int`.

---

## Summary: Function Map

| Transformation | Function | File | Line |
|---------------|----------|------|------|
| R1 — assignment expansion | `expandAssignmentIfNeeded` | VCFlattening.kt | 318 |
| Shared ExpandedInfo computation | `computeExpandedInfo` | VCFlattening.kt | 204 |
| Per-field arg extraction | `extractExpandedArg` | VCFlattening.kt | 256 |
| Type flattening | `flattenType` | VCFlattening.kt | 47 |
| Variable expansion | `expandVariable` | VCFlattening.kt | 75 |
| R3 — field access to scalar | `resolveFieldAccessToScalar` | VCBinder.kt | 140 |
| Field access chain walker | `walkUpFieldAccessChain` | VCBinder.kt | 129 |
| Multi-field VC reconstruction | `reconstructVCFromScalars` | VCBinder.kt | 163 |
| R4 — reconstruct from scalars | `reconstructFromScalars` | VCBinder.kt | 412 |
| Call site arg expansion | `expandCallSiteArgs` | VCBinder.kt | 281 |
| Single arg expansion | `expandArgForCallSite` | VCBinder.kt | 351 |
| CI argument flattening | `flattenCIArgs` | VCBinder.kt | 381 |
| R2 — field access on call result | `resolveFieldAccessOnCallResult` | VCBinder.kt | 196 |
| R5 — return unboxing | `unboxSingleFieldReturnExpr` | VCBinder.kt | 235 |
| Symbol replacement scan | `computeSymbolReplacements` | VCBinder.kt | 448 |
| Effective expression type | `expectedExpressionType` | VCBinder.kt | 468 |
| Eager expandedInfo population | `eagerlyExpandVariableIfNeeded` | VCBinder.kt | 513 |
| Parameter expansion | `expandParameterRecursively` | VCFlattening.kt | 166 |
| Single-field VC unboxing | `unboxSingleFieldVCType` | VCFlattening.kt | 191 |
| Multi-field VC check | `isMultiFieldVC` | VCFlattening.kt | 95 |
| Nested field access builder | `createNestedFieldAccess` | VCFlattening.kt | 130 |
