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
- **Immutable IR nodes**: All `ExpressionNode` data classes use `val` fields. Updates to symbols (e.g., return type inference) create new instances via `copy()`.
- **Plugin-based compilation**: The pipeline allows injecting custom transformations at multiple stages without modifying core code.
