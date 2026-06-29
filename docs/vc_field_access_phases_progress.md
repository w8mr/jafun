# Value Class Field Access Pipeline: Progress (2026-06-29)

## Approach Shift: From Phase3 Metadata to VCBinder Phase5 Transform

We moved VC parameter expansion from a metadata-only approach in Phase3 (marking `expandedFields`/`expandedFieldSymbols` on `JFVariableSymbol`) to a structural IR transform in VCBinder Phase5. This avoids the complexity of propagating metadata through all IR nodes and instead rewrites the IR directly.

### Why the change
- Metadata propagation to all nested field accesses inside function bodies was incomplete
- Tests like `vcInvalidDeepField` couldn't reliably error because intermediate Variable nodes lacked `expandedFields`
- A direct IR rewrite (expand params → transform body field accesses → expand call sites) is more predictable

---

## Current Status (2026-06-29)

### Phase 5 Implementation in VCBinder.kt

**Phase 5a: Parameter Expansion + Body Transform**
- Expands VC method parameters to their primitive fields using `expandParameter` + `expandParameterRecursively`
- Single-field VCs matching the return type are unwrapped via `returnsWrappedType` check
- Nested single-field VCs inside multi-field VCs are expanded via `expandParameterRecursively` in `VCFlattening.kt` (modified 2026-06-29)
- Body field accesses (`user.id.value`) are matched against flattened fields using `extractFieldPath` + `buildParamFieldMap` (keyed by `flattenType` paths)

**Phase 5b: Call Site Expansion**
- Expands `ConstructorInvocation` arguments at call sites to match expanded parameter signatures
- Creates new `MethodInvocation` with flattened args and updated `parameters` list so JVMBackend emits correct method signatures

**Phase 4b: Identity-Access Return Type**
- Single-field VCs used as function arguments where the return type matches the unwrapped field → skip `getfield`, return the instance directly
- Handles `Address(x).number` pattern (val user = Address(1, "x"); user.number)

---

## Test Status

### Passing (7/7)
| Test | Description |
|------|-------------|
| `valueClassFunctionParam` | Multi-field VC function param with val assignment |
| `vcNestedIdAccess` | Nested VC field access through multi-field param: `user.id.value` |
| `vcNestedIdInUser` | Nested single-field VC inside multi-field VC param |
| `valueClassTwoParamsReverseFieldOrder` | Two multi-field VC params with reversed field access order |
| `vcChangeAddress` | Multi-field VC function param returned as VC (box class construction) |
| `vcDeepNesting` | Deep nesting through single-field VC chain `C(B(A(42)))` |
| `vcFieldAccessOnCallResult` | Field access on function call result: `getTopLeft(b).x` |

---

## Known Issues

### 1. `expandCallSite` doesn't respect `returnsWrappedType` params (RESOLVED)
- Fixed by checking `expandedParams[expandedIdx].type` — if still a VC (boxed), pass arg as-is; if primitive, flatten
- Also fixed call site param symbol update when only params change (not args)

### 2. `vcInvalidDeepField` — parser drops invalid `.z` field access
- `fieldRhs` in Phase 2 fails silently (break Pratt loop) instead of erroring
- Dotted expression `c.b.z` becomes just `c.b`, missing the `.z` access
- Error surfaces later as JVMBackend type mismatch ("B vs Int32Type") instead of "field 'z' not found in B"
- **Fix needed**: `fieldRhs` or Phase 5a body transform should validate field existence

### 3. `resolveParamFieldAccess` reconstructs nested VCs for function param references
- For `b` (type Box, single-field VC wrapping Point), field access `b.topLeft` resolves to just `b` (now Point type)
- Enabled by checking if the param's original type is a single-field VC whose field name matches
- Works for both body transform and call site expansion

---

## Next Steps

1. **Fix `vcInvalidDeepField`** — add field existence validation in Phase 2 parser's `fieldRhs` or in Phase 5a's `resolveParamFieldAccess` for unresolved FieldAccess chains
2. **Clean up debug output** — remove temporary `println` statements from VCBinder.kt
3. **Add compile-time error detection** for invalid field access on nested VCs in general (not just function params)

---

## Relevant Files

- `core/src/commonMain/kotlin/nl/w8mr/jafun/compiler/ir2jvm/VCBinder.kt` — Phase 4 + 5 implementation (~870 lines)
- `core/src/commonMain/kotlin/nl/w8mr/jafun/compiler/VCFlattening.kt` — `expandParameterRecursively`, `flattenType`, `effectiveJvmType`, `expandVariable`, `reconstructVCFromExpanded` utilities
- `core/src/commonMain/kotlin/nl/w8mr/jafun/compiler/ir2jvm/JVMBackend.kt` — Bytecode emission, `buildValueClass`
- `core/src/commonMain/kotlin/nl/w8mr/jafun/compiler/ExpressionNode.kt` — `MethodInvocation`, `FieldAccess`, `Variable` node definitions
- `core/src/commonTest/kotlin/nl/w8mr/jafun/CompilerTest.kt` — All VC tests

---

*This document is up to date as of 2026-06-29.*
