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

Note: The `optionalType` parsing starts with `owsnl` (optional whitespace/newline), so `tokens.firstOrNull()` is always whitespace, never an `Identifier`. This means explicit return type annotations (`: Int`) are NOT resolved correctly in Phase1 — the default always falls through to `OperandType.Unknown`. Phase2 re-parses the return type annotation independently (and correctly).

## ParserJafun (Pratt Parser + Semantic Analysis)

**File**: `ParserJafun.kt`

ParserJafun is a Pratt (top-down operator precedence) parser that consumes Phase1 tokens and produces a typed AST (`Phase2Expression`).

### Responsibilities

- Resolve identifiers through the symbol map (variables, functions, types)
- Handle operator precedence and associativity (infixL, infixR, prefix, postfix)
- Resolve method dispatch (including operator methods in `jafun.lang.*Kt`)
- Infer function return types from body expressions
- Compile `when` expressions, `while` loops, string templates
- Handle function definitions with parameter scoping

### Function Parsing

At `ParserJafun.kt:238-284`:

```kotlin
val function = combi {
    // Parse signature
    -funTerm                                      // "fun" keyword
    val name = identifier.bind()
    -lParenTerm
    val parameters = (identifier ... complexIdentifier sepByAllowEmpty commaTerm).bind()
    -rParenTerm
    val returnType = optional(colon ... identifier).bind()
    
    // Parse body (inside CurlyBlock token)
    val (symbol, block) = token<CurlyBlock>().map {
        symbolMapManager.override(it.symbolMap) {
            // Register parameters as variables
            val arguments = parameters.map { ... }
            val symbol = JFMethod(arguments, "Script", name.value,
                returnType?.let { resolveType } ?: OperandType.Unit)
            symbolMapManager.replaceType(name.value, symbol)
            
            // Queue body for parsing
            queue.add(Phase1Method(name.value, it.tokens.drop(1).dropLast(1)))
            
            // Parse body immediately
            val block = expressions(it.tokens.drop(1).dropLast(1))
            symbol to block
        }
    }.bind()
    
    // Infer return type from body and update symbol map
    val symbolWithReturnType = symbol.copy(rtn = block.expressions.lastOrNull()?.type() ?: OperandType.Unit)
    symbolMapManager.replaceType(name.value, symbolWithReturnType)
    
    ExpressionNode.Function(symbolWithReturnType, block.expressions)
}
```

### Queue-Based Processing

The `parse()` method at line 530 uses a queue (`ArrayDeque<Phase1Method>`) to process nested functions:

```kotlin
fun parse(input: List<Phase1Token>): ... {
    queue.add(Phase1Method("main", input))
    while (queue.isNotEmpty()) {
        val method = queue.removeFirst()
        val source = ListContext(method.body)
        result[method.methodName] = Phase2Method(method.methodName, expressions.parse(source))
    }
    return result["main"]?.body
}
```

The main method is processed first. When a `fun` declaration is encountered inside it, the function's body tokens are added to the queue. The queue is drained in FIFO order. Functions defined inside other functions are processed in the same iteration (since they're added during body parsing, which happens inline).

### Operator Precedence and Associativity

Operators are just methods with metadata. The standard library defines operators in `jafun/lang/Int.kt` using annotations:

```kotlin
@FunctionPrecedence(100)
@FunctionAssociativity(INFIXL)
@FunctionName("+")
fun Int.+(other: Int): Int = this + other
```

The Pratt parser uses precedence and associativity to build the correct parse tree. Higher precedence binds tighter. `INFIXL` groups left-to-right, `INFIXR` groups right-to-left.
