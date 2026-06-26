# Phase 4: Eliminate JVMBackend knowledge of value class unboxing — COMPLETED

## Goal

Remove all VC-awareness from `JVMBackend.kt` by having Phase 3 (IR transformations) fully unwrap types before they reach the JVM backend.

**Result**: `JVMBackend.kt` now has zero references to `effectiveJvmType` and zero `isInlineValueClass` checks. All VC type unwrapping happens in Phase 3 (`AST2IR.kt`) or lives in the type system (`VCFlattening.kt`).

## What was eliminated

| Before | After |
|---|---|
| 7 `effectiveJvmType` call sites in JVMBackend | 0 |
| 1 `isInlineValueClass` check in JVMBackend | 0 |
| storeVariable/loadVariable needed VC unwrap | Use `variableSymbol.effectiveType` set by Phase 3 |
| MethodInvocation `type()` returned original VC type | `rtnLookup` wrapped → returns effective type |
| ConstructorInvocation inlining in JVM backend | Inlined in Phase 3 `compileExpressionNode` |
| buildClass descriptor used `effectiveJvmType` | Already-unwrapped types from Function handler |
| conversion function unwrapped `from` type | Inline unwrap loop |
| Return type check unwrapped instruction type | Inline unwrap loop |

## Sub-phases

### Phase 4.1 — Variable symbol types
Added `effectiveType: OperandType<*>?` mutable field to `JFVariableSymbol`.
Set it in ValAssignment/VarAssignment handlers, `tryResolveExpandedFieldAccess`,
and `buildFunctionParameters`. `storeVariable`/`loadVariable` use `effectiveType ?: type`.

### Phase 4.2 — MethodInvocation return types
Wrapped `rtnLookup` with `effectiveJvmType` in the MethodInvocation handler so
`instruction.type()` returns the unwrapped JVM type. Removed redundant
`effectiveJvmType` from call site return descriptor.

### Phase 4.3 — buildClass descriptor
`MethodContext.returnType` and `MethodContext.parameters[i].type` are already set
by the Function handler with `effectiveJvmType` applied. Removed redundant
wrapping from the JVM descriptor and return type comparisons.

### Phase 4.4 — ConstructorInvocation inlining
Moved the `isInlineValueClass` shortcut from JVMBackend's ConstructorInvocation
handler to Phase 3's `compileExpressionNode`. The Phase 3 handler unwraps
single-field VC constructors by extracting the single argument.

### Phase 4.5 — Last call sites
Replaced the last 3 `effectiveJvmType` calls in `conversion()` and the return
type check with inline unwrap loops. Removed the import.

## Key insight

The three remaining call sites from the plan couldn't be eliminated by
changing `Variable.type()` because that method serves dual purposes:
JVM load opcode selection (needs effective type) and `getfield` owner
lookup (needs original type). The inline unwrap loops keep the logic
local without pulling in the VCFlattening dependency.
