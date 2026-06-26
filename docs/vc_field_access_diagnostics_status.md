# Value Class Field Access Diagnostic Status (2026-06-26)

## Goal
- Ensure all value class (VC) field accesses—especially deep/nested or chained—are checked at compile time for field existence. Invalid accesses (e.g., `c.b.z` if `z` is not a field of `B`) must produce a clear compiler error, not a runtime JVM error.

## Recent Changes
- Added a negative test `vcInvalidDeepField` that attempts to access `c.b.z` where `z` does not exist.
- Added and restored positive tests, including accessing a field on a function call result (e.g., `getTopLeft(b).x`).
- Implemented a catch for unresolved field accesses: If the receiver of a field access is a VC Variable with expansion metadata, and the field is not present, an error is thrown.
- Confirmed that valid field accesses on function call results are NOT blocked by this logic.

## Current Diagnostic Handling
- Valid: Field accesses on the result of a function returning a value class (e.g., `getTopLeft(b).x`) pass and emit code as expected.
- **NOT-YET-FIXED**: Invalid field accesses on parameters, including nested chains, may still make it to JVM codegen with no error, because expansion metadata is not always propagated to all intermediate Variable nodes in a chain (e.g., `c.b` in `c.b.z`).

## Issues Remaining
- The `vcInvalidDeepField` negative test does NOT fail at compile time. This shows that VC metadata (`expandedFields`) is not present on every intermediate variable in the chain, so error handling is not triggered. The test is important and is left as a regression detector.
- Intermediate step: Patch or debug function body Variable handling so all VC-typed parameter references have correct `expandedFields` assigned throughout the function. This will allow the error handler to trigger.

## Next Steps
1. Audit and, if necessary, fix the logic around `setExpandedFieldsOnParameterVariables` in AST2IR so that any Variable representing a VC parameter in a function body and its subfields gets `expandedFields` set.
2. Add debug output if needed. Confirm that for `fun getInvalid(c: C): Int { c.b.z }` both `c` and `c.b` are Variables with `expandedFields` present.
3. Ensure that with the above, the test `vcInvalidDeepField` now fails at compile time with a meaningful message.
4. Commit, then update docs to state that diagnostic coverage is complete.

## Test Coverage
| Test                      | Behavior            | Status           |
|---------------------------|---------------------|------------------|
| vcDeepNesting             | Valid, should pass  | Passes           |
| vcFieldAccessOnCallResult | Valid, should pass  | Passes           |
| vcInvalidDeepField        | Invalid, should ERR | NOT YET FAILING  |

## Pending
- Once `vcInvalidDeepField` test fails (i.e., triggers error as intended), document the finished state and clean up temporary debug/test code.

---

*This status document will be updated as propagation handling and diagnostics are finished.*
