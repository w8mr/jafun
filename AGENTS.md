# Working agreement

1. **Plan first, code second** — Before writing any code, describe the implementation approach and wait for approval.
2. **Scope discipline** — Change only what was explicitly discussed and agreed upon. No scope creep.
3. **Feedback, not silent fixes** — If something else seems off, call it out as feedback rather than fixing it unilaterally.
4. **Minimal diffs** — Each change is as small and targeted as possible to the agreed scope.
5. **Ask, don't assume** — When in doubt about intent, ask before acting.
6. **Match existing style** — Code, naming, formatting, and patterns must follow conventions already present in the surrounding code.

# Project context

## Two-phase parser architecture (`ParserJafun.kt`)
- `structurePass()` strips function declarations from the CST token stream, registering their symbols via `replaceType()` and collecting `FunDescriptor` objects. Nested functions are registered in the local scope but NOT collected into the flat function list — they stay inline for the `function` parser inside `prattParser`.
- `parse()` calls `structurePass(input)`, then parses the remaining `mainTokens` via `expressions.parse(ListContext(mainTokens))`, then delegates to `parseBodies()`.
- `parseBodies()` processes only top-level functions (those collected by the outermost `structurePass` call). It runs a fixed-point loop over function descriptors, parsing each body inside its correct symbol map scope (`symbolMapManager.override(fn.descriptor.symbolMap)`) and updating the return type via `replaceType()` when the inferred type differs. Symbol lookups and replacements happen inside the override.

## `MethodInvocation` lazy symbol lookup
- `MethodInvocation` is a regular class (not data class) that stores `methodName`, `parentPath`, `parameters`, and a `rtnLookup: () -> OperandType<*>` lambda.
- `type()` calls `rtnLookup()` which re-looks up the method from the symbol map, so it always returns the current return type even if the map was updated after node creation.
- `equals`/`hashCode` exclude `rtnLookup` (lambda equality is by reference and would break structural comparison).

## Known constraints
- `CurlyBlock.tokens` includes `LeftCurly` and `RightCurly` brace tokens — must drop both ends before passing to `expressions`.
- Parsek `zeroOrMore` silently returns an empty list when its inner parser can't match (never fails).
- `Parser.invoke` (operator) throws on failure; `Parser.parse` returns `Pair<R?, Result<R>>`.
- Phase1's `funDeclaration` parser already registers the function symbol via `add(name.value, symbol)` before `ParserJafun` runs. `structurePass` uses `replaceType()` instead of `add()` to avoid duplicate entries.
- Nested functions are handled by the inline `function` parser in `prattParser` during body parsing, not by `parseBodies`.
- Functions are placed BEFORE main expressions in the final output list (source order).
- `extractReturnType` defaults to `OperandType.Unknown` (not `Unit`) for functions without explicit return type — `parseBodies` infers and updates via `replaceType()`.
