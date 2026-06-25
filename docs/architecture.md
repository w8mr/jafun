# Compiler Pipeline

The Jafun compiler compiles Jafun source code to JVM bytecode through a multi-phase pipeline.

## Pipeline Overview

```
Source code (String)
    │
    ▼
Phase1Parser (lexer + basic structure)
    │  Produces: List<Phase1Token> (flat token stream)
    │  Registers: function signatures, val/var declarations in symbol map
    ▼
ParserJafun (Pratt parser + semantic analysis)
    │  Produces: List<Phase2Expression> (typed AST)
    │  Handles: operator precedence, associativity, type resolution
    ▼
Phase2 plugins (optional)
    │
    ▼
AST2IR (AST → IR transformation)
    │  Produces: IRBuilder.ClassContext (class with methods containing Phase3 instructions)
    │  Handles: argument loading with type conversion, when/while compilation
    ▼
Phase3 plugins (optional)
    │
    ▼
JVMBackend (JVM bytecode emission)
    │  Produces: ByteArray (.class file)
    │  Uses: kasmine library for bytecode generation
    ▼
JVMIR / JVM plugins (optional)
    │
    ▼
.class file (ByteArray)
```

## Entry Point

`Compiler.compile(code, className, methodName)` in `Compiler.kt:43` orchestrates the pipeline.

## Plugin System

The compiler supports four extension points:
- `Phase2Plugin` — transforms the typed AST before IR conversion
- `Phase3Plugin` — transforms the class IR before bytecode emission
- `JVMIRPlugin` — transforms the `ClassDef` (kasmine model) before writing
- `JVMPlugin` — transforms the final byte array

## Key Design Decisions

- **Two-phase parsing**: Phase1Parser handles tokenization and basic structure (function signatures, variable declarations). ParserJafun is a Pratt parser that handles operator precedence and type resolution.
- **Two-phase body parsing**: ParserJafun runs a `structurePass()` to extract function signatures first, then `parseBodies()` to iteratively parse function bodies with forward-reference resolution.
  - `structurePass()` strips function declarations from the CST token stream, registering their symbols via `replaceType()` and collecting `FunDescriptor` objects. Nested functions are registered in the local scope but NOT collected into the flat function list — they stay inline for the `function` parser inside `prattParser`.
  - `parse()` calls `structurePass(input)`, then parses the remaining `mainTokens` via `expressions.parse(ListContext(mainTokens))`, then delegates to `parseBodies()`.
  - `parseBodies()` processes only top-level functions (those collected by the outermost `structurePass` call). It runs a fixed-point loop over function descriptors, parsing each body inside its correct symbol map scope (`symbolMapManager.override(fn.descriptor.symbolMap)`) and updating the return type via `replaceType()` when the inferred type differs. Symbol lookups and replacements happen inside the override.
- **`MethodInvocation` lazy symbol lookup**: Stores `methodName`/`parentPath`/`parameters` and a `rtnLookup` lambda instead of a direct `JFMethod` reference. `type()` re-looks up from the symbol map, ensuring forward-referenced return types resolve correctly.
- **Immutable IR nodes**: All `ExpressionNode` classes use `val` fields. `MethodInvocation` is a regular class (not data class) — its `equals`/`hashCode` explicitly exclude the `rtnLookup` lambda to avoid broken structural equality.
- **Plugin-based compilation**: The pipeline allows injecting custom transformations at multiple stages without modifying core code.
