# Parsing

Parsing is split into two phases. Phase1Parser handles lexing and basic structure. ParserJafun handles semantic analysis with a Pratt parser.

## Phase1Parser (Lexer + Structure)

**File**: `Phase1Parser.kt`

Phase1 converts raw source text into a flat list of `Phase1Token` tokens. It uses the `parsek` parser combinator library.

### Responsibilities

- Tokenize literals (integers, strings, chars, booleans)
- Recognize identifiers and operators
- Handle whitespace and newlines (significant for statement separation)
- Parse `val`/`var` declarations and register variables in the symbol map
- Parse function declarations and register `JFMethod` symbols
- Wrap block bodies (`{ ... }`) in `CurlyBlock` tokens (which carry the nested symbol map)

### Key Parsers

| Parser | Produces |
|--------|----------|
| `integerLiteral_term` | `IntegerLiteral` |
| `stringLiteral_term` | `StringLiteral` + interpolation expressions |
| `valDeclaration` / `varDeclaration` | Registers variable, produces keywords + identifier tokens |
| `funDeclaration` | Registers `JFMethod`, produces keyword + signature tokens + `CurlyBlock` |

### Phase1 Function Registration

At `Phase1Parser.kt:158-169`, each function is registered in the symbol map:

```kotlin
val symbol = JFMethod(
    arguments,
    JFClass("Script"),
    name.value,
    (optionalType.tokens.firstOrNull() as? Identifier)?.value
        ?.let { symbolMapManager.findSingleOrNull(it) as? OperandType<*> }
        ?: OperandType.Unknown,     // ← default when no return type annotation
    ...
)
symbolMapManager.pop()
symbolMapManager.add(name.value, symbol)
```

Note: The `optionalType` parsing starts with `owsnl` (optional whitespace/newline), so `tokens.firstOrNull()` is always whitespace, never an `Identifier`. This means explicit return type annotations (`: Int`) are NOT resolved correctly in Phase1 — the default always falls through to `OperandType.Unknown`. ParserJafun re-parses the return type annotation independently (and correctly) via `extractReturnType()`.

## ParserJafun (Two-Phase Pratt Parser)

**File**: `ParserJafun.kt`

ParserJafun uses a two-phase architecture to handle forward-referenced function return types.

### Two-Phase Flow

```
parse(input)
  │
  ├─ structurePass(input) → (mainTokens, functions)
  │     Walk tokens, extract function declarations, register symbols,
  │     strip functions from token stream.
  │     Nested functions registered but NOT collected (stay inline).
  │
  ├─ expressions.parse(ListContext(mainTokens)) → mainExprs
  │     Parse remaining expressions (no top-level function declarations).
  │     MethodInvocation nodes use lazy rtnLookup to resolve return types.
  │
  └─ parseBodies(functions, mainExprs) → allExpressions
        Fixed-point loop: parse function bodies, infer return types,
        update symbol map via replaceType(). Functions placed BEFORE main.
```

### Structure Pass

`structurePass()` at `ParserJafun.kt:559` walks the token list linearly, finding `Keyword("fun")` tokens. For each function:

1. Reads name, parameters (between parentheses), and optional return type annotation
2. Calls `replaceType(name, JFMethod(...))` to register the symbol (replaces Phase1's registration)
3. Recursively processes the CurlyBlock's tokens (registers nested functions in local scope)
4. Collects top-level `FunDescriptor` objects (nested ones are NOT collected)
5. Adds non-function tokens to `mainTokens`

### Body Parsing

`parseBodies()` at `ParserJafun.kt:683` iteratively parses function bodies:

```kotlin
for (fn in pending.filter { it.parsedBody == null }) {
    val body = symbolMapManager.override(fn.descriptor.symbolMap) {
        expressions(fn.descriptor.bodyTokens)   // body tokens without curly braces
    }
    val inferredType = body.lastOrNull()?.type() ?: OperandType.Unit
    val currentType = rtnCache[fn.descriptor.name]
    if (inferredType != currentType) {
        rtnCache[fn.descriptor.name] = inferredType
        val currentSymbol = symbolMapManager.override(fn.descriptor.symbolMap) {
            symbolMapManager.findSingleOrNull(fn.descriptor.name) as? JFMethod
        }
        if (currentSymbol != null) {
            symbolMapManager.override(fn.descriptor.symbolMap) {
                symbolMapManager.replaceType(fn.descriptor.name, currentSymbol.copy(rtn = inferredType))
            }
        }
    }
    fn.parsedBody = body
}
```

Key details:
- Symbol lookups and replacements happen INSIDE `override(fn.descriptor.symbolMap)` to handle nested function scopes correctly
- The fixed-point loop converges when all function types stabilize (typically 1-2 iterations)
- Functions are placed BEFORE main expressions in the final output (source order)

### `MethodInvocation` Lazy Symbol Lookup

`MethodInvocation` at `ExpressionNode.kt:81` is a **regular class** (not data class) that stores:

```kotlin
class MethodInvocation(
    val methodName: String,
    val parentPath: String,
    val parameters: List<Type.JFVariableSymbol>,
    val rtnLookup: () -> OperandType<*>,
    val field: Type.InvocationTarget?,
    override val arguments: List<Phase2_3Expression>
) : Invocation {
    override fun type(): OperandType<*> = rtnLookup()
}
```

`rtnLookup` re-looks up the method from the symbol map each time `type()` is called. This ensures `MethodInvocation` nodes created during main-token parsing (before `parseBodies` has run) return the correct type after function body type inference completes.

### Function Parsing (Nested/Local)

The existing `function` parser at `ParserJafun.kt:239-283` handles nested/local functions inside CurlyBlocks. It registers the symbol, parses the body inline, infers the return type from the body, and creates `ExpressionNode.Function`. This parser is only reached for nested functions — top-level functions are stripped by `structurePass`.

### Operator Precedence and Associativity

Operators are just methods with metadata. The standard library defines operators in `jafun/lang/Int.kt` using annotations:

```kotlin
@FunctionPrecedence(100)
@FunctionAssociativity(INFIXL)
@FunctionName("+")
fun Int.+(other: Int): Int = this + other
```

The Pratt parser uses precedence and associativity to build the correct parse tree. Higher precedence binds tighter. `INFIXL` groups left-to-right, `INFIXR` groups right-to-left.
