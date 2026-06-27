# Value Class Field Access Diagnostic Status (2026-06-27)

## Goal
- Ensure all value class (VC) field accesses—especially deep/nested or chained—are checked at compile time for field existence. Invalid accesses (e.g., `c.b.z` if `z` is not a field of `B`) must produce a clear compiler error, not a runtime JVM error.

## Current Diagnostic Handling
- **Valid chains**: Field accesses like `c.b.a.value`, `user.id.value`, `box.topLeft.x` compile and emit correct bytecode.
- **Field access on function call results** (e.g., `getTopLeft(b).x`): Works — falls through to JVM `getfield` on the materialized object.
- **Invalid field on expanded val** (e.g., `val p = Point(1,2); p.z`): Caught by JVM backend as a type mismatch. Not a clear compiler error but does fail at compile time.
- **Invalid field on function parameter / chain** (e.g., `c.b.z` where `z` is not a field of `B`): **Silently succeeds** — the parser drops `.z` before AST2IR ever sees it.

## Known Bug: Parser Drops Unconsumed `.identifier` Tokens

**Root cause**: In `ParserJafun.kt`'s pratt parser, when `fieldRhs` (or `methodRhs`) fails to resolve a `.identifier` on the LHS type, it returns `Failure`. The pratt loop then `break`s silently. The `.identifier` tokens remain unconsumed and are effectively discarded. The expression `c.b.z` is parsed as just `c.b`, so the missing-field error never fires.

**Fix required**: `fieldRhs` must always consume `.identifier` and produce a `FieldAccess` node (even for unknown fields), letting AST2IR's diagnostic layer report the error. See `docs/vc_field_access_current_plan.md` for details.

## Test Coverage

| Test                      | Behavior            | Status            |
|---------------------------|---------------------|-------------------|
| vcDeepNesting             | Valid, should pass  | Passes            |
| vcFieldAccessOnCallResult | Valid, should pass  | Passes            |
| vcDeepInvalidAccess       | Invalid, should ERR | @Ignore (parser bug) |

---

*Last updated: 2026-06-27*
