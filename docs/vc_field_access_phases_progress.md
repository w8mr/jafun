# Value Class Field Access Pipeline: Progress (2026-06-26)

## Phases

### 1. Parameter Expansion (After Phase3, Pre-JVMBackend)
- **Goal:** Every VC parameter in a function is expanded into its primitive fields if needed; all metadata is tracked (`expandedFields`, `expandedFieldSymbols`).
- **Status:** ✔️ Implemented. Multi-field VCs are expanded; single-field VCs normalized flat. Parameter Variable instances in headers receive expansion metadata.
- **Open:** Propagation of `expandedFields` to all parameter references/usages in function bodies (not just headers) is partial. Deep/nested cases inside bodies may not have the right metadata.

### 2. Assignment Expansion (Val and Var)
- **Goal:** All assignments to VC variables (`val`/`var`) are expanded into parallel assignments to their fields, preserving mutability.
- **Status:** ✔️ Complete. Value assignments and reassignments of both immutable and mutable VC vars are consistently expanded. Metadata mirrors the semantics of flat primitives.

### 3. Nested Field Access Logic
- **Goal:** Deep field access like `a.b.c` for nested VCs is compiled into the primitive or field chain, or errors if not valid.
- **Status:** ✔️ Correct for valid chains. New fallback disables silent backend errors by raising an error if access is attempted on a VC variable with no matching field. Valid chains through functions and naive field access patterns work.
- **Open:** Error not always triggered for invalid chains when the intermediate Variable lacks `expandedFields` (e.g. on parameter usage in bodies). Diagnostic only fires when metadata is present.

### 4. Diagnostics and Metadata Propagation
- **Goal:** All VC parameter references and their nested chains in function bodies have correct `expandedFields` data; invalid field accesses are reliably flagged at compile time.
- **Status:** 🚧 Incomplete/In Progress. Tests and fallback logic exist; propagation to all variables inside function bodies is pending. Test `vcInvalidDeepField` is the regression/canary.

---

## What’s Left
- [ ] Ensure all variables referencing VC parameters anywhere in the body, including all intermediate field access nodes, have their `expandedFields` set (full propagation).
- [ ] When propagation is correct, all bogus field accesses will error at compile time; `vcInvalidDeepField` will pass.
- [ ] Final doc update to confirm end-to-end safety and correct error coverage.

---

*This pipeline progress doc is up to date as of the current commit/HEAD and reflects the live test and implementation status.*
