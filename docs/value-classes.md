# Value Classes

Value classes (`value class`) provide a way to create lightweight wrapper types that are semantically distinct at the source level but can be represented as their underlying fields at the JVM level to avoid heap allocation overhead.

## Two Representations

Value class instances have two representations:

- **Scalar (expanded)**: The value is decomposed into N local variables (one per constructor parameter). Used when the value is assigned to a local `val` or passed as a function parameter. No heap object is allocated.
- **Object (materialized)**: The value lives as a heap object (the JVM `.class` with private fields). Used as method return values, since the JVM cannot return multiple values from a single method.

The scalar/object duality is handled entirely at the compiler (IR) level — there is no runtime dispatch or wrapper type at the JVM level.

## Declaration (`Phase1Parser.kt:173-211`)

`valueClassDeclaration` is registered before `valDeclaration` in the `oneOf` list because `string("val")` matches the first 3 characters of `"value"`.

```kotlin
val valueClassDeclaration = combi {
    // Parses:  value class Address(number: Int, street: String)
    // Creates: JFClass with kind = ClassKind.VALUE_CLASS
    //          JFConstructor with parameters list
    //          Getter JFMethod for each parameter
    // Registers all in the symbol map
}
```

The parser:
1. Creates a `JFClass` with `kind = ClassKind.VALUE_CLASS` and registers it
2. Creates a `JFConstructor(parameters, jfClass)` and stores it on `jfClass.constructor`
3. For each constructor parameter, creates a getter `JFMethod` with empty parameter list and registers it on the class

## Symbol Table Types (`Types.kt`)

```kotlin
data class JFClass(
    override val name: String,
    override val parent: ClassParent? = null,
    val kind: ClassKind = ClassKind.NORMAL,
) {
    var constructor: JFConstructor? = null   // set during valueClassDeclaration
}

data class JFConstructor(
    val parameters: List<JFVariableSymbol>,
    override val parent: MethodParent,
    override val name: String = "<init>",
)

data class JFVariableSymbol(
    val name: String,
    val type: OperandType<*>,
    val symbolMap: SymbolMap = IdentifierCache,
    val mutable: Boolean = false,
    val initialized: Boolean = false,
) {
    var constructorArgs: List<Phase2_3Expression>? = null  // for val assignment expansion
    var expandedFields: List<Pair<String, OperandType<*>>>? = null  // for param expansion
}
```

## Parser Interception (`ParserJafun.kt`)

### Getter interception (`methodLhs` at `ParserJafun.kt:401-412`)

When `methodLhs` encounters a `JFVariableMethod` where the variable type is a value class and the method has no parameters (a getter), it produces a `FieldAccess` node instead of a `MethodInvocation`:

```kotlin
is JFVariableMethod -> {
    val lhsType = symbol.variable.type
    if (lhsType is Type.JFClass && lhsType.kind == Type.ClassKind.VALUE_CLASS && symbol.method.parameters.isEmpty()) {
        val fields = symbolMapManager.find(lhsType)
            .filterIsInstance<Type.JFConstructor>().flatMap { it.parameters }
        val fieldIndex = fields.indexOfFirst { it.name == symbol.method.name }
        ExpressionNode.FieldAccess(
            instance = ExpressionNode.Variable(symbol.variable),
            fieldName = symbol.method.name,
            fieldIndex = fieldIndex,
            fieldType = symbol.method.rtn,
            arguments = emptyList(),
        )
    } else {
        // normal method dispatch
    }
}
```

### Structure pass (`ParserJafun.kt:611-626`)

`structurePass` skips over value class declaration tokens entirely (they have no function body to extract):

```kotlin
if (token is ExpressionNode.Keyword && token.value == "value") {
    i++
    // skip "value class Name(...)"
    // ...
}
```

## `FieldAccess` Node (`ExpressionNode.kt`)

```kotlin
class FieldAccess(
    val instance: Phase2_3Expression,
    val fieldName: String,
    val fieldIndex: Int,
    val fieldType: OperandType<*>,
    override val arguments: List<Phase2_3Expression>,
) : Phase2_3Expression
```

- `instance` — the value class variable or expression being accessed
- `fieldName` — name of the getter (used for JVM `getfield`)
- `fieldIndex` — position in the constructor parameter list (used for expansion lookups)
- `fieldType` — scalar type of the field

## IR Transformation (`ast2ir/AST2IR.kt`)

### Expansion via `ExpansionContext`

`ExpansionContext` tracks which parameters have been expanded into scalar fields and which constructor arguments have been captured. It is passed through `compileAsCodeBlock` to nested function compilations.

### `ValAssignment` expansion (`line 211-214`)

When a value class is assigned to a local `val`, the `ConstructorInvocation` arguments are captured on the `JFVariableSymbol`:

```kotlin
if (varType is Type.JFClass && varType.kind == Type.ClassKind.VALUE_CLASS && expr is ExpressionNode.ConstructorInvocation) {
    node.variableSymbol.constructorArgs = expr.arguments
    return  // skip emitting the assignment — kept as a pure scalar
}
```

### `FieldAccess` resolution (`line 229-267`)

When the instance is a `Variable`:
1. First checks `constructorArgs` — if set (from `ValAssignment`), returns the corresponding argument directly
2. Then checks `expandedFields` — if set (from function parameter expansion), creates a new `Variable` referencing the expanded field's local variable
3. Falls through to JVM `getfield` (object materialization) if neither expansion is set

### Function parameter expansion (`line 158-206`)

When a user-defined `Function` has value class parameters:

1. For each parameter whose type is a value class, computes `fieldExpansions` from the constructor parameter names and types
2. Sets `param.expandedFields` on the parameter's `JFVariableSymbol` (both the `symbol.parameters[i]` object AND the symbol found in the body's symbol map — see Disjoint Symbols)
3. Creates expanded `Parameter` entries with `varName` in the format `{symbolMapId}.{paramName}_{fieldName}`
4. Pre-registers these var names via kasmine `parameter()` before body compilation, ensuring correct JVM local variable slot ordering

```kotlin
for (param in symbol.parameters) {
    if (type is Type.JFClass && type.kind == Type.ClassKind.VALUE_CLASS) {
        val cons = findConstructor(vc)
        val fieldExpansions = cons.parameters.map { it.name to it.type }
        param.expandedFields = fieldExpansions
        // Also set on the symbol in the body's scope (Phase1 object)
        if (paramRefSymbolMap != null) {
            (paramRefSymbolMap.findSingleOrNull(null, param.name) as? Type.JFVariableSymbol)
                ?.expandedFields = fieldExpansions
        }
        for ((fieldName, fieldType) in fieldExpansions) {
            val varName = "${actualSymbolMapId}.${param.name}_$fieldName"
            parameters.add(Parameter(fieldType, varName))
        }
    }
}
```

### Constructor argument unpacking (`line 108-121`)

When a `ConstructorInvocation` has value class arguments, each argument is expanded into individual `FieldAccess` nodes:

```kotlin
val unpackedArgs = cons.parameters.mapIndexed { index, param ->
    if (param.type is Type.JFClass && param.type.kind == Type.ClassKind.VALUE_CLASS) {
        val vc = param.type as Type.JFClass
        val innerCons = findConstructor(vc)
        innerCons?.parameters?.mapIndexed { innerIndex, innerParam ->
            ExpressionNode.FieldAccess(
                instance = arg,  // the original argument expression
                fieldName = innerParam.name,
                fieldIndex = innerIndex,
                fieldType = innerParam.type,
                arguments = emptyList(),
            )
        } ?: listOf(arg)
    } else listOf(arg)
}.flatten()
```

### `findConstructor` helper

Simplified to `vc.constructor` — avoids scope-dependent symbol map lookups:

```kotlin
private fun findConstructor(vc: Type.JFClass): Type.JFConstructor? = vc.constructor
```

## JVM Code Generation (`ir2jvm/JVMBackend.kt`)

### `FieldAccess` compilation (`line 169-178`)

Compiles the instance expression, then emits `getfield`:

```kotlin
is ExpressionNode.FieldAccess -> {
    compile(instruction.instance)
    val ownerType = instruction.instance.type() as Type.JFClass
    getField(
        ownerType.path.replace('.', '/'),
        instruction.fieldName,
        signature(instruction.fieldType),
    )
    if (asStatement && instruction.type() != OperandType.Unit) pop()
}
```

### Constructor compilation

`ConstructorInvocation` uses unified `new`/`dup`/`invokeSpecial` sequence. Arguments are compiled and passed to the constructor.

### Parameter naming via `buildClass` (`line 278`)

kasmine maps variable names to JVM local variable slots via an internal counter:

```kotlin
private val localVarMap = mutableMapOf<String, UByte>()

private fun localVar(name: String): UByte {
    return localVarMap.getOrPut(name) { localVarMap.size.toUByte() }
    // The first unknown name gets slot 0, the next slot 1, etc.
}
```

When JVMBackend encounters `iload("1.input_number")`, kasmine calls `localVar("1.input_number")`. If the name is new, it gets the **next sequential slot** — which depends on what was first loaded/stored in the body. A body accessing `input.street` before `input.number` would give `street` slot 0 and `number` slot 1, **reversing** the JVM parameter slot order and loading wrong values.

`parameter(name)` calls `localVar(name)` **before any body code runs**, pinning slot 0 to `number` and slot 1 to `street` regardless of access order:

```kotlin
m.parameters.forEach { p -> p.varName?.let { parameter(it) } }
```

This is not a value-class-specific issue — it affects **all** function parameters. A normal function `fun subtract(a: Int, b: Int): Int { b - a }` has the same vulnerability: without pre-registration, accessing `b` first would give it slot 0 (a's slot), producing `a - b` instead of `b - a`. The `parameter()` call in the parameter expansion path (via `varName`) fixes this for both VC and non-VC parameters.

Variable names follow the format `{symbolMapId}.{paramName}_{fieldName}` for expanded fields and `{symbolMapId}.{paramName}` for scalar parameters.

## Key Decisions

- **Scalar expansion at IR level**: The `ExpansionContext` is passed through `compileAsCodeBlock`/`compileExpressionNode`, not at the JVM level. This keeps JVMBackend simple (it only sees scalar types).
- **`JFClass.constructor` as a `var` property**: Added outside the primary constructor to avoid scope-dependent symbol-map lookups for the constructor.
- **No getter methods on value class `.class` files**: Field access uses `getfield` directly. The value class JVM `.class` has private fields and a constructor.
- **`string("val")` matching**: The `valueClassDeclaration` parser must appear before `valDeclaration` in the `oneOf` list because `string("val")` matches the first 3 characters of `"value"`.

## Disjoint-Symbols Problem (Resolved)

### Problem

`structurePass.parseParameters()` (`ParserJafun.kt:693`) creates **new** `JFVariableSymbol` objects for function parameters:

```kotlin
params.add(JFVariableSymbol(name, type))  // new object!
```

These are distinct from the ones Phase1's `funDeclaration` created and stored in `curlyBlock.symbolMap`. When AST2IR's `Function` handler set `expandedFields` on `symbol.parameters[i]` (the structurePass objects), the Phase1 objects in the body's scope (which `Variable(input)` resolves through) remained unset. `FieldAccess` at `AST2IR.kt:241` then read `null` for `expandedFields` and fell through to JVM `getfield`, causing a `VerifyError`.

### Fix

After setting `expandedFields` on `symbol.parameters[i]`, also look up the parameter via `paramRefSymbolMap.findSingleOrNull(null, param.name)` (which points to `curlyBlock.symbolMap`) and set `expandedFields` on whatever object is found there:

```kotlin
param.expandedFields = fieldExpansions
if (paramRefSymbolMap != null) {
    (paramRefSymbolMap.findSingleOrNull(null, param.name) as? Type.JFVariableSymbol)
        ?.expandedFields = fieldExpansions
}
```

## Single-field vs Multi-field Value Classes

### Single-field Value Classes (Inlined)

A single-field value class is inlined to its field type at the JVM level:

```kotlin
value class Id(id: Int)
```

- In JVM signatures: `I` (int), not `LId;`
- Method return type: unwrapped to `Int` via `effectiveJvmType()`
- Constructor arguments: passed as single primitive, not object reference
- Example: `increaseHouseNumber(address: Address, increment: Int)` with single-field `increment: Int` becomes `(...)I` in JVM signature

### Multi-field Value Classes (Boxed)

A multi-field value class is boxed as an object reference:

```kotlin
value class Address(number: Int, street: String)
```

- In JVM signatures: `LAddress;` (object reference)
- Method return type: stays as object reference
- Constructor arguments: expanded into individual field parameters in JVM signature, e.g., `(I, Ljava/lang/String;, ...)`
- Field access: resolved via `expandedFields` shortcut for parameters, or `getfield` for object materialization

## Effective JVM Type (`effectiveJvmType()`)

To ensure consistency between single-field VC declarations and call sites, the compiler applies `effectiveJvmType()` mapping:

- **Single-field VC**: `effectiveJvmType(Id) → I` (unwrapped to field type)
- **Multi-field VC**: `effectiveJvmType(Address) → LAddress;` (stays boxed)

This is applied in three places:
1. **Method declaration signature** (`JVMBackend.buildClass()`, line 38)
2. **Method invocation signature** (`JVMBackend` MethodInvocation handler, line 85)
3. **Return type handling** (`AST2IR.buildFunctionParameters()`, line 145)

Without `effectiveJvmType()`, a single-field VC return would mismatch: the declaration would produce `()LId;` but the call site would try to unwrap it to `()I`, causing a `VerifyError`.

## Helper Property: `isInlineValueClass`

To avoid scattered `kind == VALUE_CLASS && cons.parameters.size == 1` checks throughout the codebase, a helper property was added to `Type.JFClass`:

```kotlin
val isInlineValueClass: Boolean
    get() = kind == ClassKind.VALUE_CLASS && constructor?.parameters?.size == 1
```

Usage locations:
- `JVMBackend.kt:38` — Determine if method return type should be unwrapped
- `JVMBackend.kt:85` — Match method invocation signature to declaration
- `JVMBackend.kt:320` — Handle constructor argument expansion
- `JVMBackend.kt:361` — Compile ConstructorInvocation with proper unwrapping
- `AST2IR.kt:85` — Inline single-field VC in constructor invocation
- `AST2IR.kt:145` — Build function return type signature

This consolidation makes the compiler logic more maintainable and reduces the risk of missing cases.

## Tests

| Test | Description |
|------|-------------|
| `valueClass()` | Local `val` declaration and field access (scalar expansion only) |
| `valueClassFunctionParam()` | Value class passed as a function parameter with scalar expansion |
| `valueClassTwoParamsReverseFieldOrder()` | Multi-field VC with field access in different order than declaration |
| `vcReturnMultiField()` | Return multi-field VC instance from method |
| `vcReturnMultiFieldWithVal()` | Assign multi-field VC to local `val`, then return |
| `vcReturnSingleField()` | Return single-field VC instance from method (unwrapped in JVM signature) |
| `vcReturnSingleFieldWithVal()` | Assign single-field VC to local `val`, then return (unwrapped) |
| `vcReturnSingleFieldFieldAccess()` | Access field on single-field VC return value |
| `vcReturnSingleFieldFieldAccessWithVal()` | Assign single-field VC to `val`, access field, return |
| `vcChangeAddress()` | Multi-field VC parameter passing, field access in nested function, chained method calls returning new VC instance |

## Implementation Details: Value Class Method Parameters

When a method takes a value class parameter:

### For Single-field VCs:
- Parameter is expanded to its field type in JVM signature, e.g., `fun increase(id: Id)` → JVM `(I)`
- Field access on parameter resolves via `expandedFields` to the individual field local variable
- No object materialization needed

### For Multi-field VCs:
- Constructor parameters are expanded as individual fields in JVM signature, e.g., `fun change(address: Address, inc: Int)` → JVM `(ILjava/lang/String;I)`
- Field access on parameter resolves via `expandedFields` shortcut to the corresponding expanded field local variable
- Example: `address.number` resolves to `Variable("address_number")` (the local variable for the expanded field)
- Allows zero-copy field access within function body

### Return Type Mapping:
- Single-field VC returns unwrap to field type via `effectiveJvmType()`
- Multi-field VC returns stay as object references
- Consistency enforced at both declaration and call site
