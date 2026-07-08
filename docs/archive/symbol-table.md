# Symbol Table System

The symbol table is organized as a chain of scoped maps, rooted in a global cache of built-in types.

## Architecture

```
IdentifierCache (root / global scope)
    ↑
LocalSymbolMap (scope level N)
    ↑
LocalSymbolMap (scope level N-1)
    ↑
  ...
    ↑
LocalSymbolMap (innermost scope)
```

Lookups walk the chain upward until a match is found. Insertions happen at the current (innermost) scope.

## IdentifierCache (Root Scope)

**File**: `IdentifierCache.kt`

Singleton object implementing `SymbolMap`. Seeds the compiler with all built-in types:

- Standard library packages: `jafun.lang`, `jafun.io`, `jafun.test`
- Java stdlib classes: `java.lang.*`, `java.io.*`, `java.util.*`
- Platform-specific method discovery via `expect fun` declarations

### Platform Differences

- **JVM** (`IdentifierCacheJvm.kt`): Uses `Class.forName()` and reflection to dynamically discover methods and constructors from Java/Kotlin classes at compile time. Method annotations (`@FunctionPrecedence`, `@FunctionAssociativity`, `@FunctionName`) are read from the actual JVM classes.
- **JS** (`IdentifierCacheJs.kt`): Hardcoded method definitions since JS has no reflection.

## SymbolMap Interface

**File**: `SymbolMap.kt`

Defines the lookup operations:

| Method | Description |
|--------|-------------|
| `find(type, path)` | Find all symbols matching type + path |
| `findSingle(path)` | Find exactly one symbol, error if not unique |
| `findSingleOrNull(path)` | Find exactly one, return null if not found |
| `findMethod(targetType, name, argTypes)` | Method dispatch resolution |
| `findClass(className)` | Find a class by qualified name |
| `findFromPath(path)` | Walk a dotted path resolving at each step |
| `add(type, path, symbol)` | Add a symbol to the current scope |
| `replaceType(type, path, symbol)` | Replace an existing symbol |
| `findOrAddClass(className)` | Find or create a class symbol |

## LocalSymbolMap (Scoped Map)

**File**: `LocalSymbolMap.kt`

Each scope has a unique `symbolMapId` (incremented global counter). This ID is used in variable naming during code generation to prevent collisions.

```kotlin
data class LocalSymbolMap(
    val parent: SymbolMap,
    val symbolMapId: Int = IdentifierCache.incSymbolMapCount()
)
```

### Scope Management

Scopes are managed via `SymbolMapManager`:

```kotlin
// Push a new scope
symbolMapManager.push()
// ... add symbols to the new scope
// Pop scope, restoring the previous one
val symbolMap = symbolMapManager.pop()

// Or use the scoped block form:
symbolMapManager.override(symbolMap) {
    // operations within this scope
}
```

### Scopes Created During Parsing

1. **Per-function parameter scope**: Created in Phase1Parser for parameter type resolution
2. **Per-block body scope**: Created for each `{ ... }` block via `betweenCurly1`
3. **Per-function body scope**: Created in ParserJafun via `symbolMapManager.override(it.symbolMap)` using the `CurlyBlock`'s captured symbol map

## SymbolMapManager

**File**: `SymbolMapManager.kt`

Maintains a stack of `SymbolMap` instances with `currentSymbolMap` pointing to the innermost scope. Provides:

- `reset()` — re-initialize with fresh IdentifierCache
- `push()` / `pop()` — scope lifetime management
- `local { }` — execute within a new child scope
- `override(symbolMap) { }` — temporarily switch to a given symbol map
- `newVariableSymbol(name, type, mutable)` — convenience for variable declaration
- `replaceType(name, symbol)` — update a symbol in the current scope
