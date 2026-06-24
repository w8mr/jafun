# Type System

The type system is defined in `Types.kt`. It has two parallel hierarchies: `OperandType<J>` (value-level types) and `Type` (Jafun language-level types).

## OperandType (Value Types)

`OperandType<J>` is a sealed interface parameterized by the JVM runtime type `J`. These represent the actual types of values on the stack.

| Type | JVM Mapping | Description |
|------|-------------|-------------|
| `SInt32` | `int` | 32-bit signed integer |
| `UInt1` | `boolean` | Boolean (1-bit unsigned) |
| `CharType` | `char` | 16-bit Unicode character |
| `StringType` | `String` | String reference |
| `Unit` | `void` | Unit type (no value) |
| `Unknown` | `Object` | Unknown/placeholder type |
| `Array` | `[L...` | Array with generic element type |
| `Generic` | varies | Generic type with type parameters |
| `JFClass` | `L...;` | A Jafun or Java class reference |

`JFClass` implements both `Type` and `OperandType<Any?>`, serving as a bridge between the two hierarchies.

## Type Hierarchy (Symbol Types)

The `Type` sealed interface represents named entities in the type system:

- **`JFClass`** — a class (e.g., `java.lang.String`, `Script`)
- **`JFPackage`** — a package (e.g., `java.lang`, `jafun.io`)
- **`JFMethod`** — a method with parameters, return type, associativity, and precedence
- **`JFConstructor`** — a constructor
- **`JFField`** — a field with a name and type
- **`JFVariableSymbol`** — a variable or parameter with name, type, mutability
- **`JFFieldMethod`** — a method accessed via a field (e.g., `obj.method`)
- **`JFVariableMethod`** — a method accessed via a variable

## Method Representation

`JFMethod` is the central type for function resolution:

```kotlin
data class JFMethod(
    val parameters: List<JFVariableSymbol>,
    val parent: MethodParent,
    val name: String,
    val rtn: OperandType<*>,       // return type
    val static: Boolean,
    val operator: Boolean,
    val associativity: Associativity,
    val precedence: Int,
)
```

Key fields:
- `parameters` — list of parameter symbols (each carries name + type)
- `rtn` — return type, defaulting to `OperandType.Unit` for functions without explicit return type
- `associativity` — `INFIXL`, `INFIXR`, `PREFIX`, or `POSTFIX`
- `precedence` — numeric precedence level (used by the Pratt parser)
- `operator` — whether this method is an operator
- `parent` — the class or package this method belongs to

## Return Type Inference

When a function has no explicit return type annotation (e.g., `fun foo() { ... }` instead of `fun foo(): Int { ... }`):

1. **Phase1Parser** sets `rtn` to `OperandType.Unknown` (for functions without annotation) or resolves explicit annotations.
2. **ParserJafun** (line 280) overrides the return type based on the last expression in the function body:
   ```kotlin
   val symbolWithReturnType = symbol.copy(rtn = block.expressions.lastOrNull()?.type() ?: OperandType.Unit)
   ```

**Known limitation**: Because functions are parsed in source order and `JFMethod` is an immutable data class, forward references (A calls B, B defined later) see B's Phase1-registered type, not B's final inferred type. Functions without explicit return types that are called via forward reference will have `rtn = OperandType.Unknown`, which `JVMBackend` cannot handle (hits `else -> TODO()`). See [forward-reference-issue.md](forward-reference-issue.md) for details and potential fixes.
