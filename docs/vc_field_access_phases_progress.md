# Value Class Field Access Pipeline: Progress (2026-06-27)

## Phases

### 1. Parameter Expansion (After Phase3, Pre-JVMBackend)
- **Goal:** Every VC parameter in a function is expanded into its primitive fields if needed; all metadata is tracked (`expandedFields`).
- **Status:** ✔️ `ParameterExpansionPhase` class exists. Still called inline from `buildFunctionParameters` in `AST2IR.kt` (line ~469). Core VC expansion logic (`shouldExpandVC`, `expandParameterRecursively`) lives in `VCFlattening.kt`.
- **Open:** `buildFunctionParameters` and `setExpandedFieldsInExpression` tree walker (line ~630) not yet extracted into the phase.

### 2. Assignment Expansion (Val and Var)
- **Goal:** All assignments to VC variables (`val`/`var`) are expanded into parallel assignments to their fields, preserving mutability.
- **Status:** ✔️ `ValExpansionPhase` class exists. Inline VC detection and expansion still called from `compileExpressionNode` in `AST2IR.kt`.

### 3. Nested Field Access Logic
- **Goal:** Deep field access like `a.b.c` for nested VCs is compiled into the primitive or field chain, or errors if not valid.
- **Status:** ✔️ Correct for valid chains. `tryResolveExpandedFieldAccess` resolves one level at a time through `expandedFields` metadata. Valid chains through functions and naive field access patterns work.

### 4. Diagnostics and Metadata Propagation
- **Goal:** All VC parameter references and their nested chains in function bodies have correct `expandedFields` data; invalid field accesses are reliably flagged at compile time.
- **Status:** 🚧 **Blocked — pre-existing parser bug.** The pratt parser silently drops unconsumed `.identifier` tokens when `fieldRhs` fails (no getter found). Invalid chains like `c.b.z` never reach AST2IR as `FieldAccess` nodes, so the diagnostic layer (`expandedFields` checks) never fires. Test `vcDeepInvalidAccess` is `@kotlin.test.Ignore` tracking this.

## What’s Left
- [ ] Fix parser bug: `fieldRhs` must always consume `.identifier` and produce a `FieldAccess` node so AST2IR can report missing-field errors.
- [ ] Extract remaining inline VC sections from AST2IR.kt into the dedicated phase classes (see `vc_field_access_current_plan.md` for the list of 6 items).
- [ ] Clear error messages for invalid nested access — ensure the diagnostic path works end-to-end.

---

*This pipeline progress doc reflects the live test and implementation status.*
