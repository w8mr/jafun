# AST, IR, and Code Generation

## ExpressionNode (AST/IR Hierarchy)

**File**: `ExpressionNode.kt`

The AST and IR share a single sealed interface hierarchy in `ExpressionNode`:

| Phase | Type | Description |
|-------|------|-------------|
| Phase1 | `Phase1Token` | Parser tokens (literals, keywords, punctuation) |
| Phase2 | `Phase2Expression` | Typed AST after semantic analysis |
| Phase3 | `Phase3Expression` | IR nodes (control flow, converted expressions) |
| Phase2_3 | `Phase2_3Expression` | Common parent for Phase2 and Phase3 nodes |

The convention `Phase2_3Expression` is used throughout to accept either phase.

### Key Node Types

**Literals and Values:**
- `IntegerLiteral(value: Int)` — int constant, type = `SInt32`
- `StringLiteral(value: String)` — string constant, type = `StringType`
- `BooleanLiteral(value: Boolean)` — boolean constant, type = `UInt1`
- `CharLiteral(value: Char)` — char constant, type = `CharType`
- `Variable(variableSymbol: JFVariableSymbol)` — variable reference, type = `variableSymbol.type`

**Control Flow:**
- `Function(symbol: JFMethod, block: List<Phase2Expression>)` — function definition
- `When(subject, matches)` — when expression (Phase2)
- `WhenPhase3(matches)` — when expression with compiled conditions (Phase3)
- `While(condition, body)` — while loop (Phase2)
- `WhilePhase3(condition, body)` — while loop with compiled condition (Phase3)

**Calls and Assignments:**
- `MethodInvocation(method, field, arguments)` — function/method call
- `ConstructorInvocation(cons, arguments)` — constructor call
- `ValAssignment(variableSymbol, expression)` — immutable variable assignment
- `VarAssignment(variableSymbol, expression)` — mutable variable assignment
- `Convert(expression, from, to)` — type conversion node

**Structure:**
- `ExpressionList(expressions)` — sequence of expressions
- `StringTemplate(expressions)` — interpolated string

## AST2IR (AST → IR Transformation)

**File**: `ast2ir/AST2IR.kt`

Transforms `Phase2Expression` nodes into `Phase3Expression` nodes. Key transformations:

- **Argument loading** (`loadArguments`): Converts arguments to match parameter types, inserting `Convert` nodes where needed (e.g., `Int → Integer` boxing)
- **When expressions**: Creates a subject variable if one doesn't exist, compiles each branch's condition and body
- **While loops**: Compiles the condition and body into `WhilePhase3`
- **Function definitions**: Creates a new method via `compileMethod`, embedding the function body

### `loadArguments`

Arguments are matched to parameters by type. If types differ, a `Convert` node is inserted:

```kotlin
fun loadArguments(builder, arguments, parameters) = arguments.zip(parameters).map { (arg, param) ->
    if (arg.type() == param) -> compileAsCodeBlock(builder, arg)
    else if (arg.type() == StringType && param is JFClass && param.name == "Object") 
        -> compileAsCodeBlock(builder, arg)
    else -> Convert(compileAsCodeBlock(builder, arg), arg.type(), param)
}
```

## IRBuilder (IR Construction DSL)

**File**: `ir2jvm/IRBuilder.kt`

A Kotlin DSL for building the IR structure:

```
BuilderContext
  └── ClassContext (one per class)
        └── MethodContext (one per method)
              └── List<Phase2_3Expression> (instructions)
```

The DSL:
```kotlin
IRBuilder.define {
    `class`("Script") {
        method("main", OperandType.Unit, listOf(ArrayType)) {
            codeBlock {
                // instructions added here
            }
        }
    }
}
```

The `codeBlock` collects expressions into the `MethodContext.instructions` list. Method contexts also track `parameterTypes` and `returnType`.

## JVMBackend (Bytecode Emission)

**File**: `ir2jvm/JVMBackend.kt`

Walks the IR and emits JVM bytecode using the `kasmine` library.

### `buildClass` (line 253)

For each method in the class context:
1. Compute the JVM method descriptor from parameter types and return type
2. Compile each instruction using `context.compile()`
3. Emit the appropriate return instruction based on the return type:

```kotlin
when (m.returnType) {
    is OperandType.Unit -> {
        if (last instruction type != Unit) pop()
        `return`()
    }
    is OperandType.SInt32, is OperandType.UInt1, is OperandType.CharType -> ireturn()
    is OperandType.StringType -> areturn()
    else -> TODO()  // ← Unknown and JFClass hit this branch
}
```

**Known issue**: `OperandType.Unknown` and `Type.JFClass` are not handled in this `when` block. If a method's return type is `Unknown` (due to forward-referenced functions with implicit return types), the compiler crashes with `NotImplementedError`.

### `compile` method

Handles each expression type:
- **IntegerLiteral**: `loadConstant(value)` → pushes int onto stack
- **StringLiteral**: `loadConstant(value)` → pushes String reference
- **MethodInvocation**: load arguments, emit `invokeStatic` or `invokeVirtual`, optionally `pop()` if used as statement
- **Variable**: `iload`/`aload` with symbol-map-prefixed variable name
- **WhenPhase3**: conditional branches with labels (`ifequal`, `goto`)
- **WhilePhase3**: loop with labels (`body`, `after`)
- **Convert**: compiles source expression then applies type conversion
- **ValAssignment/VarAssignment**: compile expression, optionally `dup()`, store variable

### Variable Naming

Variables are stored with a prefix to avoid collisions across scopes:
```kotlin
val variableName = "${instruction.variableSymbol.symbolMap.symbolMapId}.${instruction.variableSymbol.name}"
```

This means variables in different scopes get distinct JVM local variable slots.

### Type Conversion

The `conversion(from, to)` function handles boxing conversions:
- `SInt32 → JFClass`: `Integer.valueOf(int)` → `Integer`
- `UInt1 → JFClass`: `Boolean.valueOf(boolean)` → `Boolean`
- `CharType → JFClass`: `Character.valueOf(char)` → `Character`
- `JFClass → JFClass`: no-op if target is `Object` or same class
