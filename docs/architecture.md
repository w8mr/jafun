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
    │  Produces: Map<String, ByteArray> (one .class per class/value class)
    │  Uses: kasmine library for bytecode generation
    ▼
JVMIR / JVM plugins (optional)
    │
    ▼
.class files (Map<String, ByteArray>)
```

## Entry Point

`Compiler.compile(code, className, methodName)` in `Compiler.kt:44` orchestrates the pipeline, returning a `Map<String, ByteArray>` of all generated `.class` files (main class + value classes).

## Plugin System

The compiler supports four extension points:
- `Phase2Plugin` — transforms the typed AST before IR conversion
- `Phase3Plugin` — transforms the class IR before bytecode emission
- `JVMIRPlugin` — transforms the `ClassDef` (kasmine model) before writing
- `JVMPlugin` — transforms the `Map<String, ByteArray>`

## Key Design Decisions

- **Two-phase parsing**: Phase1Parser handles tokenization and basic structure (function signatures, variable declarations). ParserJafun is a Pratt parser that handles operator precedence and type resolution.
- **Two-phase body parsing**: ParserJafun runs a `structurePass()` to extract function signatures first, then `parseBodies()` to iteratively parse function bodies with forward-reference resolution.
  - `structurePass()` strips function declarations from the CST token stream, registering their symbols via `replaceType()` and collecting `FunDescriptor` objects. Nested functions are registered in the local scope but NOT collected into the flat function list — they stay inline for the `function` parser inside `prattParser`.
  - `parse()` calls `structurePass(input)`, then parses the remaining `mainTokens` via `expressions.parse(ListContext(mainTokens))`, then delegates to `parseBodies()`.
  - `parseBodies()` processes only top-level functions (those collected by the outermost `structurePass` call). It runs a fixed-point loop over function descriptors, parsing each body inside its correct symbol map scope (`symbolMapManager.override(fn.descriptor.symbolMap)`) and updating the return type via `replaceType()` when the inferred type differs. Symbol lookups and replacements happen inside the override.
- **`MethodInvocation` lazy symbol lookup**: Stores `methodName`/`parentPath`/`parameters` and a `rtnLookup` lambda instead of a direct `JFMethod` reference. `type()` re-looks up from the symbol map, ensuring forward-referenced return types resolve correctly.
- **Immutable IR nodes**: All `ExpressionNode` classes use `val` fields. `MethodInvocation` is a regular class (not data class) — its `equals`/`hashCode` explicitly exclude the `rtnLookup` lambda to avoid broken structural equality.
  - **Multi-class output**: `compile()` returns `Map<String, ByteArray>` — the main class plus any value class `.class` files. `compileAll()` in `JVMBackend.kt` iterates builder classes and value classes, generating each via `buildClass`/`buildValueClass`.
- **VCBinder — JVMBackend is VC-unaware**: Value-class unboxing and field-access rewriting happen exclusively in the `VCBinder` Phase3 plugin. `JFVariableSymbol` stores no boxed-state metadata (`effectiveType` was removed); JVMBackend reads `variable.type` / `expression.type()` directly. VCBinder replaces symbols with unboxed copies and rewrites the IR so downstream consumers see the correct primitive type.
- **VCBinder single-pass pipeline**: The `handle()` method processes each method in one `flatMap`:
  1. **Expand assignments** — `expandAssignmentIfNeeded` splits `b = Box(Point(1,2), ...)` into one scalar assignment per flattened field, storing the field hierarchy in `expandedInfo`.
  2. **Single transformTree pass** — each instruction (expanded or not) is visited once. Inside the callback:
     - `eagerlyExpandVariableIfNeeded` (side effect) pre-populates `expandedInfo` for referenced variables so subsequent resolvers can look up their structure.
     - `Variable` nodes check a pre-computed symbol replacement map; if none, `reconstructFromScalars` rebuilds a boxed VC from scalars when an expanded variable is used as a value.
     - `Convert` nodes get their `from` type fixed via `expectedExpressionType`.
     - Other nodes fall through a resolver chain: `resolveFieldAccessToScalar` → `expandCallSiteArgs` → `resolveFieldAccessOnCallResult` → identity.
  3. **Unbox return** — if the method's return type is a single-field VC, `unboxSingleFieldReturnExpr` strips the wrapper.
  The three call-site handlers each return `null` to pass to the next: `resolveFieldAccessToScalar` rewrites `p.x` to direct scalar variables (or reconstructs a multi-field VC from scalars), `expandCallSiteArgs` rewrites method arguments/return types when a callee's parameters were flattened, and `resolveFieldAccessOnCallResult` strips redundant `.field` on single-field VC results.
