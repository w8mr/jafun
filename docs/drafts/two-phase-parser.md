# Two-Phase Parser — Implementation Summary

## Problem

Functions are parsed in source order. When function A calls function B (defined later in source), A sees B's Phase1-registered type (`OperandType.Unknown`) instead of B's inferred return type. This causes a crash in `JVMBackend` when it encounters `Unknown` as a method return type.

## Solution

Split `ParserJafun` processing into two passes:

- **Structure Pass** (`structurePass()`): Walk the Phase1 token stream once. Find all function declarations, extract signatures, register `JFMethod` symbols, and collect `FunDescriptor` objects. Strip function declarations from the token stream. Nested functions are recursively registered but NOT collected into the flat function list — they stay inline for the existing `function` parser.

- **Body Parsing** (`parseBodies()`): Parse the remaining main token stream. Then iteratively parse function body tokens inside the correct symbol map scope. Uses `replaceType()` to update return types when inference changes between iterations.

## Key Design Decisions

### 1. `MethodInvocation` Lazy Symbol Lookup

`MethodInvocation` is a **regular class** (not data class) that stores `methodName`, `parentPath`, `parameters`, and a `rtnLookup: () -> OperandType<*>` lambda. `type()` calls `rtnLookup()` which re-looks up the method from the symbol map, returning the current return type even if the map was updated after node creation. `equals`/`hashCode` exclude `rtnLookup` (lambda reference equality would break structural comparison).

This solves the stale-reference problem: main expressions are parsed before `parseBodies` runs, but their `MethodInvocation` nodes lazily resolve the return type at `type()` call time.

### 2. `replaceType()` instead of `add()`

Phase1's `funDeclaration` parser already registers the function symbol via `add(name.value, symbol)`. `structurePass` uses `replaceType()` instead of `add()` to avoid duplicate entries in the symbol map. `findSingleOrNull()` returns null (via `singleOrNull()`) when a set has >1 element, which would break symbol resolution.

### 3. Symbol Map Scope Handling

`parseBodies` operates inside the correct symbol map scope for each function. Symbol lookups (`findSingleOrNull`) and updates (`replaceType`) happen inside `symbolMapManager.override(fn.descriptor.symbolMap) { ... }`, ensuring nested function symbols are found and updated in their local scope.

### 4. Nested Functions

Nested functions are registered in their local scope by `structurePass` (recursively) but NOT collected into the flat function list. They remain inside the parent's body tokens and are handled by the existing inline `function` parser in `prattParser` when the parent body is parsed via `expressions()`.

### 5. Output Order

Functions are placed BEFORE main expressions in the final expression list (matching source order).

### 6. Default Return Type

`extractReturnType` uses `OperandType.Unknown` as the default for functions without explicit return type annotations. `parseBodies` infers the actual type from the body and updates via `replaceType()`.

## Structure Pass (`structurePass`)

```kotlin
fun structurePass(tokens: List<Phase1Token>): StructureResult
```

Walks the token stream, finds `Keyword("fun")` tokens. For each match:
1. Reads name, parameter list, optional return type
2. Calls `replaceType(name, JFMethod(...))` to register the symbol
3. Recursively processes the CurlyBlock's tokens (to register nested functions)
4. Stores a `FunDescriptor` (but NOT for nested functions)
5. Adds non-function tokens to `mainTokens`

Returns `StructureResult(mainTokens, functions)`.

## Body Parsing (`parseBodies`)

```kotlin
private fun parseBodies(
    functions: List<FunDescriptor>,
    mainResult: List<Phase2Expression>,
): List<Phase2Expression>
```

1. Initializes `rtnCache` from each descriptor's `returnType`
2. Fixed-point loop: for each unparsed function, parse body inside `override(descriptor.symbolMap)`:
   - Parse: `expressions(descriptor.bodyTokens)` (body tokens without curly braces)
   - Infer: `body.lastOrNull()?.type() ?: OperandType.Unit`
   - If inferred != cached: update cache, call `replaceType` inside the override
3. Creates `ExpressionNode.Function` nodes for each descriptor, placed BEFORE main expressions

## Interaction with Existing Code

The existing `function` parser in `prattParser` is still active:
- **Top-level functions**: Never reached by `structurePass` stripping them from main tokens.
- **Nested/local functions**: Inside CurlyBlock tokens. When body parsing calls `expressions()`, the `function` parser creates `Function` nodes inline. No queue re-processing needed.

## Files Changed

| File | Change |
|------|--------|
| `ExpressionNode.kt` | `MethodInvocation` changed from data class to regular class with lazy `rtnLookup` lambda. Excluded from equals/hashCode. |
| `ParserJafun.kt` | Added `structurePass()`, `parseBodies()`, `FunDescriptor`, `StructureResult`. Removed old queue/Phase1Method/Phase2Method/result. Modified `function` parser (removed `queue.add`). |
| `AST2IR.kt` | Updated `MethodInvocation` creation and passthrough to use new fields. |
| `JVMBackend.kt` | Changed `instruction.method.*` → `instruction.methodName`, `instruction.parentPath`, `instruction.parameters`, `instruction.type()`. |
| `NodePrintTree.kt` | Updated `method.name` → `methodName`, `method.rtn` → `type()`. |
| `CompilerTest.kt` | Updated `invocation()` helper to use new `MethodInvocation` constructor. |
| `SimpleParserTest.kt` | Updated direct `MethodInvocation` construction. |
