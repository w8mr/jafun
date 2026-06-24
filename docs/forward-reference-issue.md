# Forward Reference Return Type Inference

## Problem

When function A is defined before function B, and A calls B, A sees B's Phase1-registered return type rather than B's final inferred type. This can cause:

1. **Crash**: If Phase1 defaulted to `OperandType.Unknown` (which `JVMBackend.buildClass` cannot handle), the compiler crashes with `NotImplementedError`.
2. **Incorrect return type**: A's inferred return type may be wrong if it depends on B's return type.

## Root Cause

The issue has two contributing factors:

### 1. Phase1 Defaults to `Unknown`

In `Phase1Parser.kt:163`, functions without explicit return type annotations are registered with `rtn = OperandType.Unknown`:

```kotlin
(optionalType.tokens.firstOrNull() as? Identifier)?.value
    ?.let { symbolMapManager.findSingleOrNull(it) as? OperandType<*> }
    ?: OperandType.Unknown,  // ← default when no annotation
```

Additionally, the `optionalType` parsing at line 153-155 starts with `owsnl` (whitespace), so even explicit `: Int` annotations fail to resolve in Phase1 — the `firstOrNull() as? Identifier` always returns null because the first token is whitespace.

### 2. `JFMethod` is Immutable

In `ParserJafun.kt:280-281`, the return type inference creates a new `JFMethod` via `copy()` and calls `replaceType()`:

```kotlin
val symbolWithReturnType = symbol.copy(rtn = block.expressions.lastOrNull()?.type() ?: OperandType.Unit)
symbolMapManager.replaceType(name.value, symbolWithReturnType)
```

This updates the symbol map entry, but existing `MethodInvocation` nodes in A's body still hold a reference to the old `JFMethod` object with the stale `rtn`.

### Walkthrough

For this Jafun code:
```
fun test(prefix: String) {      // A
    test2(prefix, 10)
}
fun test2(prefix: String, a: Int) {  // B
    print prefix
    println a
}
```

1. **Phase1**: `test` and `test2` both registered with `rtn = Unknown`
2. **Phase2 parses `test`**: `test2(prefix, 10)` resolves to B's Phase1 object with `rtn = Unknown`. `MethodInvocation.type()` returns `Unknown`. `test.rtn` is inferred as `Unknown`.
3. **Phase2 parses `test2`**: Body ends with `println a` (type `Unit`). A new `JFMethod` is created with `rtn = Unit` and stored in the symbol map.
4. **Compilation**: `test.rtn = Unknown` → `JVMBackend` hits `else -> TODO()` and crashes.

## Potential Solutions

### Option A: Change Phase1 Default to `Unit` (Minimal Fix)

Change `Phase1Parser.kt:163` from `OperandType.Unknown` to `OperandType.Unit`.

- ✅ Prevents crash for `Unit`-returning functions (the common case)
- ✅ One-line change
- ❌ Does not fix non-Unit return types inferred via forward references
- ❌ Does not fix Phase1's broken return type annotation parsing

### Option B: Two-Phase Parsing (Signature-First)

Split `ParserJafun` processing into two passes:

1. **Phase 1 (signatures)**: Scan all tokens, parse all function signatures (name, parameters, return type), register with correct defaults (`Unit` or explicit type). Do not parse bodies.
2. **Phase 2 (bodies)**: Parse all function bodies. All symbols are already registered, so forward references resolve correctly.

- ✅ Fixes the crash
- ✅ Handles explicit return type annotations correctly
- ✅ No mutable state needed
- ❌ Still relies on Phase1 default for functions without return type annotations
- ❌ Requires pipeline restructuring

### Option C: Lazy/Re-resolving `MethodInvocation.type()`

Instead of capturing a `JFMethod` reference, store the method name and resolve from the symbol map on each `type()` call. This would require changes to `MethodInvocation` and all code that accesses `method.rtn`, `method.parameters`, etc.

- ✅ Always returns the current type from the symbol map
- ✅ Handles all cases including transitive inference
- ❌ Complex change touching many call sites
- ❌ Hidden dependency on symbol map within the AST
