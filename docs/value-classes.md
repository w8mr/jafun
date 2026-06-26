# Value Classes

Value classes (`value class`) provide lightweight wrapper types that are semantically distinct at the source level but decompose to their underlying fields at the JVM level, avoiding heap allocation.

## Two Representations

Value class instances have two representations:

- **Scalar (expanded)**: The value is decomposed into N local variables (one per flattened primitive field). Used when the value is assigned to a `val` at the top level or passed as a function parameter. No heap object is allocated.
- **Object (materialized)**: The value lives as a heap object (the JVM `.class` with private fields). Used as method return values — the JVM cannot return multiple values from a single method.

The scalar/object duality is handled entirely at the IR level — there is no runtime dispatch.

## Phase 1: Declaration (`Phase1Parser.kt`)

`valueClassDeclaration` is registered before `valDeclaration` because `string("val")` matches the first 3 characters of `"value"`.

```kotlin
value class Address(number: Int, street: String)
```

Creates `JFClass(kind = VALUE_CLASS)` with:
- A `JFConstructor` stored on `jfClass.constructor`
- One getter `JFMethod` per constructor parameter

## Phase 2: Parsing

### Getter interception

When `methodLhs` encounters a value class field access (a getter with no parameters), it produces a `FieldAccess` node instead of `MethodInvocation`. This is what turns `address.number` into a field read rather than a method call.

### Structure pass

`structurePass` skips value class declarations — they have no function body to extract.

## Phase 3: IR Transformation

The core flattening logic lives in `VCFlattening.kt`, with the integration in `AST2IR.kt`.

### Flattening model (`VCFlattening.kt`)

`flattenType` recursively decomposes a VC type into its primitive components:

| Type | Flattens to |
|------|------------|
| `Int` | `[("", Int)]` |
| `Id(value: Int)` | `[("value", Int)]` |
| `Point(x: Int, y: Int)` | `[("x", Int), ("y", Int)]` |
| `Box(topLeft: Point)` | `[("topLeft_x", Int), ("topLeft_y", Int)]` |
| `Box(tl: Point, br: Point)` | `[("tl_x", Int), ("tl_y", Int), ("br_x", Int), ("br_y", Int)]` |

Supporting functions:

- **`isMultiFieldVC(type)`** — Returns true if a type expands to multiple primitives at the top level. Recursively checks single-field VCs: `Box(topLeft: Point)` returns true because `Point` is multi-field, but `Id(value: Int)` returns false because `Int` is a single primitive. This determines whether a `val` assignment gets expanded into separate variables.
- **`effectiveVariableType(type)`** — Unwraps single-field VC chains to the innermost type. `Box(topLeft: Point)` → `Point`, `Id(value: Int)` → `Int`, `UserId(id: Id(value: Int))` → `Int`.
- **`expandVariable(variable)`** — Expands a `JFVariableSymbol` into one symbol per flattened primitive, appending the flatten path: `b` of type `Box(topLeft: Point)` → `[b_topLeft_x: Int, b_topLeft_y: Int]`.
- **`createNestedFieldAccess(pathComponents, arg)`** — Creates a chain of `FieldAccess` nodes from a path like `["topLeft", "x"]`.
- **`signatureForType(type)`** — Generates a JVM descriptor character for a type.

### Variable expansion (`expandValAssignmentIfNeeded`)

When a `val` is assigned a `ConstructorInvocation`, `isMultiFieldVC` determines if the target type needs expansion:

```kotlin
val b = Box(Point(99, 10))  // isMultiFieldVC(Box) = true → expands
```
Becomes:
```kotlin
val b_topLeft_x = 99
val b_topLeft_y = 10
```

The original `b` variable is NOT stored as a real JVM local — only the expanded primitives exist. If `b` is later passed to a function expecting a `Box`, the system reconstructs the object from the primitives (see Reconstruction below).

Expansion only happens when the RHS is a `ConstructorInvocation`. Function calls like `val a = makeAddress()` do NOT trigger expansion — the returned object stays materialized.

### Function parameter expansion (`buildFunctionParameters`)

`shouldExpandVC` determines if a function parameter type should be expanded (when `buildFunctionParameters` creates the JVM method signature):

```
shouldExpandVC(Id(value: Int))      → false  (single-field wrapping primitive)
shouldExpandVC(Point(x: Int, y: Int)) → true  (multi-field)
shouldExpandVC(Box(topLeft: Point)) → true   (single-field wrapping multi-field VC)
```

#### `returnsWrappedType` guard

For a single-field VC where the function's return type matches the wrapped field type, expansion is **skipped**:

```kotlin
fun getPoint(box: Box): Point { box.topLeft }
```

Here `Box.wrappedType = Point = returnType`, so the Box parameter stays unexpanded in the JVM signature (`(LPoint;)LPoint;`). When this guard triggers, `param.skipExpansion = true` is set on the parameter symbol.

The `skipExpansion` param retains the original VC type (`Box`) in `variableSymbol.type`, while `variableSymbol.effectiveType` carries the unwrapped type (`Point`) for JVM load/store decisions. The `effectiveType` field was introduced in Phase 4.1 to eliminate `effectiveJvmType` calls from `loadVariable`/`storeVariable`.

`expandedFieldSymbols` is a map on `JFVariableSymbol` tracking the actual symbol objects created during variable expansion — keyed by flattened field path (e.g., `"topLeft_x"`) and valued by the real `JFVariableSymbol`. This is used during reconstruction to reference the exact same symbols.

#### Multi-field VC parameter expansion

For multi-field VCs, the parameter expands to individual primitives:

```kotlin
fun getX(p: Point): Int { p.x }
// JVM: getX(I, I): I
```

The function body's `p.x` resolves through `expandedFields` metadata on the variable symbol, which points `x` → the first expanded int param.

#### `setExpandedFieldsOnParameterVariables`

Since structure pass creates separate `JFVariableSymbol` objects from Phase 1, `expandedFields` must be set on BOTH the `symbol.parameters[i]` object AND the symbol found in the body's scope. This is done in `buildFunctionParameters`.

### Call site expansion (`expandValueClassParams`)

When a `MethodInvocation` is compiled, `expandValueClassParams` processes each (param, arg) pair:

1. **If `param.skipExpansion`**: Pass the arg as-is (or reconstruct from expanded fields — see below)
2. **If arg is an expanded Variable AND param should expand**: Extract the flattened components from the expanded variable using `createNestedFieldAccess`
3. **If param should expand but arg is not an expanded Variable** (e.g., inline ConstructorInvocation): Extract `FieldAccess` nodes for each constructor parameter

This creates matching expanded parameter lists at both the call site and function definition.

### Reconstruction (`reconstructVCFromExpanded`)

When a variable was expanded at the val level but the function parameter couldn't be expanded (due to `returnsWrappedType`), the compiler reconstructs the VC object from the expanded primitives:

```kotlin
val b = Box(Point(99, 10))    // b expanded to b_topLeft_x, b_topLeft_y
println getPoint(b).x          // getPoint takes Box, returns Point
```

At the call to `getPoint(b)`:
1. `param.skipExpansion` is true (function returns the wrapped type)
2. `b` has `expandedFieldSymbols`: `{"topLeft_x" → b_topLeft_x, "topLeft_y" → b_topLeft_y}`
3. Reconstructs `ConstructorInvocation(Point, [Variable(b_topLeft_x), Variable(b_topLeft_y)])` by walking the type hierarchy (single-field VC wrappers like `Box` are stripped by `reconstructVCFromExpanded`)
4. The reconstructed `ConstructorInvocation(Point, ...)` is the argument to `getPoint`

Single-field VC wrappers (`Box` wrapping `Point`) are eliminated from reconstructed `ConstructorInvocation` nodes in Phase 4.4 by the `compileExpressionNode` ConstructorInvocation handler, which checks `isInlineValueClass` and unwraps the constructor to its single argument. This avoids JVM `getfield`/`getstatic` of the wrapper class.

### Field access resolution (`compileExpressionNode` — FieldAccess handler)

Field access uses two paths:

- **`tryResolveExpandedFieldAccess`** (extracted helper in `AST2IR.kt`): Handles Variable instances with `constructorArgs` or `expandedFields`, resolving field accesses through the expanded representation without materializing the object.
- **Identity shortcut**: If the instance type is a single-field VC (`isInlineValueClass`) and does NOT have `expandedFields`, just return the instance itself (the VC IS its field).
- **JVM `getfield`**: Fall through to a real `FieldAccess` node → `getfield` in bytecode. This happens for function return values (object materialization).

### Return type unwrapping

When a function returns a single-field VC, the return type is unwrapped:

```kotlin
fun makeId(): Id { Id(42) }
// JVM: ()I, not ()LId;
```

Multi-field VC returns stay as object references:
```kotlin
fun makeAddress(): Address { Address(1, "street") }
// JVM: ()LAddress;
```

Unwrapping happens in the Function handler (`compileExpressionNode` in `AST2IR.kt`) via `effectiveJvmType(symbol.rtn)`. The unwrapped type propagates through `MethodContext.returnType` and into the JVM descriptor via `buildClass`. Return type consistency between declaration and call site is enforced by wrapping `rtnLookup` with `effectiveJvmType` in the MethodInvocation handler (Phase 4.2).

## `JFVariableSymbol` Runtime State

`JFVariableSymbol` has several mutable fields set during IR transformation:

| Field | Set by | Purpose |
|-------|--------|---------|
| `constructorArgs` | `ValAssignment` handler | Constructor arg extraction shortcut |
| `expandedFields` | `buildFunctionParameters` or `expandValAssignmentIfNeeded` | Field-to-primitive mapping |
| `expandedFieldSymbols` | `expandValAssignmentIfNeeded` | Maps flattened paths to actual symbols (for reconstruction) |
| `skipExpansion` | `buildFunctionParameters` | Prevents call-site expansion when `returnsWrappedType` |
| `effectiveType` | ValAssignment/VarAssignment handlers, `buildFunctionParameters`, `tryResolveExpandedFieldAccess` | Unwrapped JVM type for load/store opcode selection; `null` means same as `type` |

## Key Decisions

- **Recursive flattening at IR level**: `VCFlattening.kt` handles all nesting via `flattenType`, separate from `AST2IR.kt` which integrates it.
- **`isMultiFieldVC` vs `shouldExpandVC`**: `isMultiFieldVC` (in VCFlattening.kt) is the pure type-check used for val expansion; `shouldExpandVC` (in VCFlattening.kt) determines if a function parameter should be expanded in the JVM signature, with the integration in AST2IR.kt applying the `returnsWrappedType` guard.
- **Reconstruction, not identity pass-through**: When an expanded variable is passed to a function needing the object form, the compiler reconstructs the VC from primitives rather than trying to keep a reference. This is simpler and avoids aliasing issues.
- **`expandedFieldSymbols` as a side-channel**: Tracks the actual `JFVariableSymbol` objects created during expansion so that reconstruction creates `Variable` nodes referencing the correct symbols.

## Tests

### VCFlatteningTests (44 tests)

| Tests | What |
|-------|------|
| 1-8 | `flattenType` — primitive, single-field, multi-field, nested, three-level, chain, mixed, complex |
| 9-10 | `expandVariable`, `signatureForType` |
| 11-17 | `isMultiFieldVC` — all edge cases (primitive, normal class, single-field wrapping primitive, multi-field, single-field wrapping multi-field, deep chain true, deep chain false) |
| 18-23 | `effectiveVariableType` — all unwrapping cases |
| 24-26 | `createNestedFieldAccess` — empty, single, two-level paths |
| 27-30 | `shouldExpandVC` — no constructor, multi-field, single-field wrapping primitive, single-field wrapping VC |
| 31-36 | `reconstructVCFromExpanded` — no constructor, multi-field all present, missing field, nested VC, nested missing field, single-field wrapping primitive |
| 37-44 | `expandParameterRecursively` — primitive, null baseName, multi-field, single-field, single-field wrapping multi-field (guard), non-VC, no constructor, multi-field with nested single-field VCs |

### CompilerTest (integration)

| Test | Scenarios |
|------|-----------|
| `testSimpleBoxAccess` | Single-field VC wrapping multi-field VC, chained field access |
| `testBoxSingleField` | Function returning wrapped type with intermediate expanded val (reconstruction + inline wrapper) |
| `testBoxSingleFieldWorking` | Same function, inline ConstructorInvocation arg |
| `debugChainedAccess` | Field access chain on expanded function parameter |
| `testMultiFieldVCFieldAccess` | Multi-field VC as function parameter |
| `testMultiArgReconstruction` | Function with both skipExpansion (Box) and normal-expansion (Point) params |
| `vcChainedNestedAccess` | Box with two multi-field VCs, function returning Int |
| `vcChangeAddress` | Full multi-field VC lifecycle with multi-class bytecode validation |
| `vcReturnMultiFieldWithVal` | Function return assigned to val, field access |
| `vcNestedIdAccess` | Nested single-field VCs through function boundary |
| `vcNestedIdInUser` | Nested Id inside User, function returning String |

## Disjoint-Symbols Problem

`structurePass.parseParameters()` creates **new** `JFVariableSymbol` objects for function parameters, distinct from the Phase 1 objects in `curlyBlock.symbolMap`. When `AST2IR.Function` sets `expandedFields` on `symbol.parameters[i]`, the Phase 1 objects (which `Variable(field)` resolves through) remain unset.

**Fix**: After setting on `symbol.parameters[i]`, also look up the parameter via `paramRefSymbolMap` and set `expandedFields` on whatever object is found there.
