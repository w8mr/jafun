# Phase 4: Eliminate JVMBackend knowledge of value class unboxing

## Goal

Remove all remaining VC-awareness from `JVMBackend.kt` by having Phase 3 (IR transformations) fully unwrap types before they reach the JVM backend.

## Current state (after Phase 3 refactoring)

`JVMBackend.kt` still has 7 `effectiveJvmType` call sites and 1 `isInlineValueClass` check:

| Location | Line | What it does |
|---|---|---|
| `storeVariable` | 192 | Unwraps expression type to pick `istore` vs `astore` |
| `loadVariable` | 239 | Unwraps variable symbol type to pick `iload` vs `aload` |
| `conversion` | 209 | Unwraps `from` type before dispatch |
| ConstructorInvocation handler | 39 | Inlines single-field VC constructors (`isInlineValueClass`) |
| MethodInvocation descriptor | 85-86 | Unwraps param types + return type for JVM signature |
| `buildClass` descriptor | 318-319 | Unwraps param types + return type for method signature |
| `buildClass` return check | 333-337 | Unwraps instruction/return types for comparison |

## Root cause

After Phase 3 processing, variable symbols and `rtnLookup` closures still carry the **original** VC types (`Id`, `Box`, etc.) even though the actual JVM stack values are already unwrapped to primitives or inner VC types. The JVM backend must call `effectiveJvmType` to paper over this gap.

## Plan (4 sub-phases)

### Phase 4.1 — Fix variable symbol types at assignment time

**Problem**: `val id = makeId()` creates variable `id` with type `Id`, but the stack holds `int`. `storeVariable`/`loadVariable` need `effectiveJvmType(Id)` = `Int` to emit the correct opcode.

**Fix**: In the ValAssignment handler (AST2IR.kt line ~286), after compiling the expression, update the variable symbol's type to match the compiled expression's effective type:

```kotlin
val expr = compileAsCodeBlock(builder, expanded.expression)
val effectiveType = effectiveJvmType(expr.type())
if (effectiveType != expanded.variableSymbol.type)
    // replace the symbol type
```

Same for VarAssignment handler (line ~311): after `compileAsCodeBlock(builder, node.expression)`, update the variable symbol's type.

Since `JFVariableSymbol.type` is `val`, this requires either:
- A mutable `effectiveType` field (like `skipExpansion`)
- Adding a `copy(type = unwrapped)` step

**Eliminates**: `effectiveJvmType` from `storeVariable` (L192) and `loadVariable` (L239).

### Phase 4.2 — Fix return types on MethodInvocation

**Problem**: `instruction.type()` for a MethodInvocation returns the original VC type (via `rtnLookup`) even though the JVM descriptor says `()I`.

**Fix**: In the MethodInvocation handler (AST2IR.kt line ~202), wrap `rtnLookup` to apply `effectiveJvmType`:

```kotlin
val originalRtnLookup = node.rtnLookup
builder.add(ExpressionNode.MethodInvocation(
    ...
    rtnLookup = { effectiveJvmType(originalRtnLookup()) },
    ...
))
```

**Eliminates**: `effectiveJvmType` from MethodInvocation descriptor (L85-86). Also helps Phase 4.1 since `expr.type()` on a MethodInvocation now returns the correct unwrapped type.

### Phase 4.3 — Fix method symbol types for buildClass

**Problem**: `m.parameters` and `m.returnType` on the method symbol (`Type.JFMethod`) carry original VC types. `buildClass` uses these for the JVM descriptor.

**Fix**: In the Function handler (AST2IR.kt line ~268), after computing unwrapped parameters and return type, update the method symbol's types:

Since `JFMethod.parameters` is `val`, options:
1. Add mutable `effectiveParameters` / `effectiveReturnType` fields to `JFMethod`
2. Create a wrapper or copy+replace approach in the compilation flow

The descriptor builder then uses these effective types directly instead of calling `effectiveJvmType`.

**Eliminates**: `effectiveJvmType` from `buildClass` descriptor (L318-319) and return type check (L333-337).

### Phase 4.4 — Inline single-field VC constructors in Phase 3

**Problem**: The reconstruction path creates `ConstructorInvocation(Box, [ConstructorInvocation(Point, ...)])` nodes. The JVM backend inlines these via `isInlineValueClass`.

**Fix**: In the ValAssignment handler (or a new IR pass), when the RHS is a `ConstructorInvocation` of an inline VC, replace the RHS with its single argument and update the variable's type. This eliminates the `ConstructorInvocation` node before it reaches JVMBackend.

Before:
```
ValAssignment(b, ConstructorInvocation(Box, [ConstructorInvocation(Point, [99, 10])]))
```

After:
```
ValAssignment(b, ConstructorInvocation(Point, [99, 10]))
```

**Eliminates**: `isInlineValueClass` check in ConstructorInvocation handler (L39).

## Verification

Run `./gradlew core:jvmTest --rerun` after each sub-phase. The IR tests with explicit `istore`/`iload`/`I` bytecode expectations in `jvmIr` blocks act as canaries for type mismatch regressions.
