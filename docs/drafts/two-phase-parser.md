# Two-Phase Parser — Implementation Plan

## Problem

Functions are parsed in source order. When function A calls function B (defined later in source), A sees B's Phase1-registered type (`OperandType.Unknown`) instead of B's inferred return type. This causes a crash in `JVMBackend.buildClass` when it encounters `Unknown` as a method return type.

## Solution

Split `ParserJafun` processing into two passes working on the same Phase1 token output:

- **Phase 1 (Structure)**: Walk the Phase1 token stream once. Find all function declarations. Extract signatures (name, parameters, return type annotations). Register `JFMethod` symbols with `OperandType.Unknown` as the default return type (no annotation) or the explicitly annotated type. Remove function declarations from the main token stream. Store body tokens for Phase 2.

- **Phase 2 (Body parsing, iterative)**: Parse the remaining main token stream. Then iteratively parse function body tokens using the existing `expressions` Pratt parser. Each iteration re-parses ALL pending function bodies from scratch — creating fresh AST nodes that do fresh symbol map lookups. Retry any bodies whose callees had type changes in the previous iteration. Converges when types stabilize.

No mutation of `JFMethod` fields. No fixup passes. The re-parse approach handles everything.

## How Re-parsing Resolves Forward References

The key insight: when Phase 2 re-parses body tokens, the `expressions` parser creates new AST nodes. `MethodInvocation.type()` reads `method.rtn` from the `JFMethod` object returned by the symbol map lookup. If a previous iteration updated the callee's symbol via `replaceType()`, the fresh lookup returns the updated object. No mutation or fixup needed.

```
Iteration 1:
  parse B body → B.rtn = Int → replaceType("B", B_with_Int)
  parse A body → looks up B → gets B_with_Int (updated) → A.rtn = Int
  (order within iteration depends on processing order, but convergence is guaranteed)

Iteration 2 (if needed):
  re-parse A body → looks up B → gets B_with_Int → A.rtn = Int (stable)
```

If A was processed before B in iteration 1, A saw B.rtn = Unknown (Phase 1 default). After B is processed and B.rtn = Int, iteration 2 re-parses A and gets the correct type.

## Phase 1 — Structure Pass

### What it does

Walk the flattened `List<Phase1Token>` and find `Keyword("fun")` tokens. Each function declaration has this shape:

```
Keyword("fun"), Whitespace, Identifier(name),
owsnl, LeftParen, owsnl, parameters..., owsnl, RightParen,
owsnl, optionalType, owsnl, CurlyBlock(symbolMap, [body tokens...])
```

For each match:

1. **Read the signature** — name identifier, parameter list between parens, optional return type annotation
2. **Register a `JFMethod`** in the symbol map:
   - `rtn` = explicitly annotated type, or `OperandType.Unknown` if no annotation
   - parameters parsed from parameter tokens
   - `parent = JFClass("Script")`, `static = true`
3. **Remove** the matched token group from the token stream (skip when building the main token list)
4. **Store a `FunDescriptor`**:
   ```kotlin
   data class FunDescriptor(
       val name: String,
       val bodyTokens: List<Phase1Token>,  // contents of the CurlyBlock
       val parameters: List<JFVariableSymbol>,
   )
   ```

### Output

- `mainTokens: List<Phase1Token>` — function declarations removed
- `functions: List<FunDescriptor>` — all extracted function descriptors

### What about nested/local functions?

A `CurlyBlock` token contains a nested token list. Nested `fun` declarations inside a CurlyBlock are NOT extracted by Phase 1 — they remain inside the parent's body tokens and are handled by the existing `function` parser when Phase 2 parses that body. For local functions, the existing inline parsing (register + parse body immediately) is fine since they're defined and used within the same scope.

## Phase 2 — Iterative Body Parsing

### Data

```kotlin
data class PendingFunction(
    val descriptor: FunDescriptor,
    var parsedBody: List<Phase2Expression>? = null,
)
```

### Algorithm

```
fun parseBodies(
    functions: List<FunDescriptor>,
    symbolMapManager: SymbolMapManager,
    mainResult: List<Phase2Expression>,
): List<Phase2Expression> {

    val pending = functions.map { PendingFunction(it) }
    val rtnCache = functions.associate { it.name to it.symbol.rtn }.toMutableMap()

    var changed = true
    while (changed) {
        changed = false

        for (fn in pending.filter { it.parsedBody == null }) {
            try {
                val source = ListContext(fn.descriptor.bodyTokens)
                val result = expressions.parse(source)
                val body = result.first ?: continue

                val inferredType = body.lastOrNull()?.type() ?: OperandType.Unit
                val currentType = rtnCache[fn.descriptor.name] ?: OperandType.Unknown

                if (inferredType != currentType) {
                    rtnCache[fn.descriptor.name] = inferredType
                    changed = true

                    // Update symbol map so future lookups see the new type
                    val currentSymbol = symbolMapManager.findSingleOrNull(fn.descriptor.name) as? JFMethod
                    if (currentSymbol != null) {
                        val updatedSymbol = currentSymbol.copy(rtn = inferredType)
                        symbolMapManager.replaceType(fn.descriptor.name, updatedSymbol)
                    }
                }

                fn.parsedBody = body

            } catch (e: Exception) {
                // Not yet parseable — retry next iteration
            }
        }

        if (!changed && pending.any { it.parsedBody == null }) {
            error("Unresolvable functions: ${pending.filter { it.parsedBody == null }.map { it.descriptor.name }}")
        }
    }

    // Create Function AST nodes and append to main result
    return mainResult + pending.map { fn ->
        val symbol = symbolMapManager.findSingleOrNull(fn.descriptor.name) as? JFMethod
            ?: error("Symbol for ${fn.descriptor.name} not found")
        ExpressionNode.Function(symbol, fn.parsedBody ?: emptyList())
    }
}
```

### Why it converges

Each iteration moves types from `Unknown` toward their final value. Once a type stabilizes, it doesn't change again. The number of iterations equals the longest dependency chain length — typically 1-2 for real code.

### What "not parseable" means

In the current parser, parsing body tokens always succeeds for valid code. The catch block is a safety net for edge cases like overload ambiguity where multiple candidates exist with current type info. A more refined approach could detect ambiguity and skip selectively.

## Interaction with Existing Code

The current `function` parser in `expressions` handles both signature and body inline. After the refactoring:

- **Top-level functions**: Removed from the main token stream by Phase 1. The `function` parser never sees them. Phase 2 handles their body tokens directly using `expressions`.
- **Nested/local functions**: Still inside CurlyBlock tokens. Phase 2's body parsing uses `expressions`, which includes the `function` parser. Local functions are handled inline as before.

The `expressions` parser itself is unchanged — it just runs on smaller token lists.

## Refactoring Steps (Tests Green Between Each)

The key to incremental refactoring: the existing `function` parser handles both signature registration and body parsing inline. We add the new code paths alongside, then gradually shift responsibility. At no point do we remove functionality that tests depend on.

### Step 1: Add `structurePass()` utility (additive)

Add a new function to `ParserJafun` that walks Phase1 tokens and extracts `FunDescriptor`s. **Do not call it yet.**

```kotlin
fun structurePass(tokens: List<Phase1Token>): StructureResult

data class FunDescriptor(
    val name: String,
    val bodyTokens: List<Phase1Token>,   // contents of the CurlyBlock
    val parameters: List<JFVariableSymbol>,
    val returnType: OperandType<*>,
)

data class StructureResult(
    val mainTokens: List<Phase1Token>,    // function declarations removed
    val functions: List<FunDescriptor>,
)
```

The function:
1. Walks the token list, finds `Keyword("fun")` groups
2. Extracts signature (name, params between parens, return type annotation)
3. Registers a `JFMethod` with `rtn = annotation or Unknown`
4. Removes the matched tokens from the main stream
5. Returns `mainTokens` + `functions`

Tests pass — unused code doesn't affect anything.

### Step 2: Pre-register functions (additive)

Call `structurePass()` at the start of `parse()`. Register all discovered functions in the symbol map. Then run the existing queue processing as before (still on the original `input`, not `mainTokens`).

The existing `function` parser will overwrite these registrations with its own `JFMethod` objects and produce `Function` AST nodes as before. The end result is identical.

```kotlin
fun parse(input: List<Phase1Token>): ... {
    val (mainTokens, functions) = structurePass(input)  // pre-register

    queue.add(Phase1Method("main", input))  // still original input
    while (queue.isNotEmpty()) { ... }
    return result["main"]?.body ?: error("No main method")
}
```

Tests pass — the existing path produces the same output. Pre-registration is invisible.

### Step 3: Add `parseBodies()` alongside (additive)

Add the iterative body parsing function. Call it AFTER the existing queue processing, and verify its output matches. The Function AST nodes from the existing path are used; the Phase 2 results are only used for comparison/verification.

```kotlin
fun parse(input: List<Phase1Token>): ... {
    val (mainTokens, functions) = structurePass(input)

    // Existing path (unchanged)
    queue.add(Phase1Method("main", input))
    while (queue.isNotEmpty()) { ... }
    val existingResult = result["main"]?.body?.first ?: error("No main")

    // Phase 2 path (runs alongside, only for verification in this step)
    val phase2Functions = parseBodies(functions, symbolMapManager, emptyList())
    // Optional: assert phase2 output matches existingResult for function nodes
    // (debug-only, not a test assertion)

    return existingResult to ...
}
```

Tests pass — no behavior change, Phase 2 processing is unused.

### Step 4: Switch main tokens and activate Phase 2

Change the main queue entry from `input` to `mainTokens`. Add Phase 2's `Function` AST nodes to the final result.

```kotlin
fun parse(input: List<Phase1Token>): ... {
    val (mainTokens, functions) = structurePass(input)

    // Main method: no function declarations in the token stream
    queue.add(Phase1Method("main", mainTokens))
    while (queue.isNotEmpty()) {
        val method = queue.removeFirst()
        val source = ListContext(method.body)
        result[method.methodName] = Phase2Method(method.methodName, expressions.parse(source))
    }
    val mainExprs = result["main"]?.body?.first ?: emptyList()

    // Phase 2: iterative body parsing + create Function AST nodes
    val allExpressions = parseBodies(functions, symbolMapManager, mainExprs)

    return allExpressions to Parser.Success(emptyList())
}
```

The `expressions` parser never encounters `Keyword("fun")` in the main stream, so the `function` parser never fires for top-level functions. It's still available for nested functions inside CurlyBlocks.

Phase 2 re-parses each function body from scratch using `expressions`, infers return types, iterates until stable, then creates `Function` AST nodes appended to the main result.

AST2IR receives `mainExprs` + `Function` nodes → compiles all methods. Result: same bytecode as before, but forward references now resolve correctly because all symbols are registered before any body is parsed.

Tests pass — and `funForwardsFunctionCallFromFunction` (currently `@Ignore`) should now pass without the annotation.

### Step 5: Remove old function handling from `function` parser (optional)

The `function` parser's inline signature registration and body parsing is now dead code for top-level functions. It's still needed for nested/local functions. The body-parsing branch inside the `function` parser can be simplified but doesn't need to be removed.

### Step 6: Remove `@Ignore` from test

Remove the `@Ignore` annotation from `funForwardsFunctionCallFromFunction`. Verify it passes.

## Test Verification

After each step, run:
```
./gradlew :core:jvmTest
```

The key test to verify: `funForwardsFunctionCallFromFunction` (currently annotated `@Ignore`). After Step 4, it should pass without the `@Ignore`.

## Summary of Changes

| File | Change |
|------|--------|
| `ParserJafun.kt` | Add `structurePass()`, `parseBodies()`, `FunDescriptor`, `StructureResult`. Modify `parse()` to use two-phase flow. |
| `Phase1Parser.kt` | No change needed (can keep `Unknown` default). |
| `Types.kt` | No change needed (no `var` on `JFMethod`). |
| `JVMBackend.kt` | No change needed (`Unknown` is handled by re-parsing, never reaches bytecode). |
