# Implementation Plan: Nested Value Class Field Access

## Problem

The compiler currently fails when accessing nested fields on value class parameters:

```kotlin
value class Id(value: Int)
value class User(id: Id, name: String)

fun test(user: User): Int {
    user.id.value  // ❌ Error: "Type issue" during JVM compilation
}
```

**Status**: ⚠️ Known Limitation  
**Priority**: Medium (enables compositional VC patterns)  
**Complexity**: Moderate (requires type system and IR enhancement)

## Root Cause

The issue occurs at three levels:

### Level 1: AST2IR (Semantic Analysis)
When expanding parameter `user: User`, the compiler creates:
```
expandedFields = [
  ("id", SInt32),          // ✅ Correctly unwrapped from Id
  ("name", StringType)     // ✅ Direct string type
]
```

But it **loses the information** that field "id" came from a nested VC `Id`.

### Level 2: IR Generation
When accessing `user.id.value`:
1. `user.id` → resolves to `Variable("user_id", type=???)`
   - Should be `SInt32` with metadata that it's from nested VC `Id`
   - Currently loses the VC context
2. `.value` access fails because:
   - Tries to access field on `user_id` variable
   - Can't find `.value` field on a primitive `SInt32`
   - Type system doesn't know it came from `Id(value: Int)`

### Level 3: JVM Backend  
```
Method: getId(ILjava/lang/String;): ??? 
Expected return: SInt32 (unwrapped Int from Id.value)
Actual generated: Id (VC class type)
→ Type mismatch → "Type issue" error
```

## Solution Architecture

### Core Insight
We need to preserve **three pieces of information** during expansion:

```
ExpandedField {
  name: String              // "id" or "name"
  type: OperandType         // SInt32 or StringType
  sourceVC: JFClass?        // Id class (for "id" field)
  sourceVCFields: Map?      // ["value" → SInt32] (for "id" field)
}
```

### Four-Phase Implementation

#### Phase 1: Type System Enhancement
**File**: `Types.kt`

Replace:
```kotlin
expandedFields: List<Pair<String, OperandType<*>>>?
```

With:
```kotlin
data class ExpandedField(
    val name: String,
    val type: OperandType<*>,
    val sourceVC: JFClass? = null,        // e.g., Id
    val sourceVCFields: List<Pair<String, OperandType<*>>>? = null
)
expandedFields: List<ExpandedField>?
```

**Impact**: 
- Minimal - mostly internal data structure change
- Backward compatible through helper functions

#### Phase 2: Parameter Expansion Enhancement
**File**: `AST2IR.kt` (line 245-270)

When expanding `User(id: Id, name: String)`:
```kotlin
// Current: Just unwrap single-field VCs
val expandedFields = listOf(
    "id" to SInt32,      // Unwrapped Int
    "name" to StringType
)

// Enhanced: Track the source VC
val expandedFields = listOf(
    ExpandedField(
        name = "id",
        type = SInt32,
        sourceVC = IdClass,  // ← NEW: Remember it came from Id
        sourceVCFields = listOf("value" to SInt32)
    ),
    ExpandedField(
        name = "name", 
        type = StringType
    )
)
```

**Algorithm**:
```
for each parameter field in User:
  if field type is single-field VC:
    expanded_type = get_unwrapped_type(field)
    store sourceVC metadata
  else if field type is multi-field VC:
    expand recursively
    store sourceVC metadata
  else:
    just store the type
```

#### Phase 3: Nested Field Resolution
**File**: `AST2IR.kt` (line 204-232)

When encountering `FieldAccess(FieldAccess(user, id), value)`:

```kotlin
fun resolveFieldAccess(base: Expression, fieldName: String): Expression {
    // Step 1: Evaluate base expression
    val baseValue = evaluate(base)
    
    // Step 2: Check if base is a Variable from expanded VC
    if (baseValue is Variable && baseValue.hasExpandedFieldMetadata) {
        val metadata = baseValue.expandedFieldMetadata
        
        // Step 3: Check if this VC has the requested field
        if (metadata.sourceVCFields?.hasField(fieldName)) {
            // Step 4: Return the combined expanded parameter
            return Variable(
                name = "${baseValue.name}_${fieldName}",
                type = metadata.sourceVCFields.getFieldType(fieldName),
                metadata = metadata.nestedMetadata(fieldName)  // For further nesting
            )
        }
    }
    
    // Fall back to normal field access
    return FieldAccess(instance = baseValue, fieldName = fieldName, ...)
}
```

**Example Trace**:
```
user.id.value
├─ Evaluate: user.id
│  ├─ user → Variable("user_id", SInt32, sourceVC=Id)
│  └─ Access id field → user.id resolved (already in name)
└─ Evaluate: (user.id).value
   ├─ Base = Variable("user_id", SInt32, sourceVC=Id) 
   ├─ Check: Does Id have "value"? Yes
   └─ Return: Variable("user_id", SInt32)  // Same variable, final type confirmed
```

#### Phase 4: Type Consistency in JVM Backend
**File**: `JVMBackend.kt`

Ensure return types are correctly unwrapped:
```kotlin
// When method returns nested VC result:
val effectiveReturnType = effectiveJvmType(methodReturnType)

// Current (works for single-level):
if (type is JFClass && type.isInlineValueClass) 
    return type.constructor.parameters.single().type

// Enhanced (works for nested):
fun effectiveJvmType(type: OperandType): OperandType {
    var current = type
    while (current is JFClass && current.isInlineValueClass) {
        current = current.constructor.parameters.single().type
    }
    return current  // Fully unwrapped
}
```

## Implementation Steps (Detailed)

### Step 1: Add Test Cases (TDD First)
```kotlin
@Test fun vcNestedIdAccess() {
    code = "fun test(user: User): Int { user.id.value }"
    expectedOutput = "42\n"
}

@Test fun vcChainedAccess() {
    code = "fun test(box: Box): Int { box.topLeft.x }"
}

@Test fun vcDeepNesting() {
    code = "fun test(c: C): Int { c.b.a.value }"
}
```

### Step 2: Create ExpandedField Data Class
```kotlin
// Types.kt
data class ExpandedField(
    val name: String,
    val type: OperandType<*>,
    val sourceVC: JFClass? = null,
    val sourceVCFields: List<Pair<String, OperandType<*>>>? = null
) {
    fun hasField(fieldName: String): Boolean = 
        sourceVCFields?.any { it.first == fieldName } ?: false
        
    fun getFieldType(fieldName: String): OperandType<*> =
        sourceVCFields?.first { it.first == fieldName }?.second
            ?: error("Field $fieldName not found")
}
```

### Step 3: Update Parameter Expansion
```kotlin
// AST2IR.kt - in parameterToParameters() function
// Around line 257
if (type.isInlineValueClass) {
    val vcConstructor = type.constructor!!
    val fieldExpansions = vcConstructor.parameters.map { vcParam ->
        val fieldType = vcParam.type
        
        // NEW: Track source VC
        ExpandedField(
            name = vcParam.name,
            type = fieldType,
            sourceVC = type,
            sourceVCFields = // Extract fields of the VC
                if (fieldType.isInlineValueClass) {
                    fieldType.constructor.parameters.map { p ->
                        p.name to effectiveJvmType(p.type)
                    }
                } else null
        )
    }
    param.expandedFields = fieldExpansions
}
```

### Step 4: Implement Nested Resolution
```kotlin
// AST2IR.kt - enhance buildExpression()
FieldAccess -> {
    val instance = buildExpression(node.instance)
    
    // Check for nested VC field access
    if (instance is Variable && instance.expandedFieldMetadata != null) {
        val metadata = instance.expandedFieldMetadata
        if (metadata.sourceVCFields?.any { it.first == node.fieldName } == true) {
            // Resolve nested VC field
            val nestedType = metadata.sourceVCFields.first { 
                it.first == node.fieldName 
            }.second
            
            return Variable(
                name = "${instance.name}_${node.fieldName}",
                type = nestedType,
                expandedFieldMetadata = metadata.getMetadata(node.fieldName)
            )
        }
    }
    
    // Normal field access
    return FieldAccess(instance, node.fieldName, ...)
}
```

### Step 5: Enhance JVM Type Resolution
```kotlin
// JVMBackend.kt
private fun effectiveJvmType(type: OperandType<*>): OperandType<*> {
    var current = type
    // Recursively unwrap single-field VCs
    while (current is Type.JFClass && current.isInlineValueClass) {
        current = current.constructor!!.parameters.single().type
    }
    return current
}
```

### Step 6: Test and Validate
```bash
./gradlew jvmTest  # Should pass all 124+ tests
```

## Code Locations

**Will Modify**:
1. `core/src/commonMain/kotlin/nl/w8mr/jafun/Types.kt` - Add ExpandedField class
2. `core/src/commonMain/kotlin/nl/w8mr/jafun/compiler/ast2ir/AST2IR.kt` - Enhance expansion and resolution
3. `core/src/commonMain/kotlin/nl/w8mr/jafun/compiler/ir2jvm/JVMBackend.kt` - Fix type unwrapping

**Will Test**:
1. `core/src/commonTest/kotlin/nl/w8mr/jafun/CompilerTest.kt` - Add 4+ test cases

**Reference**:
- Current field access: `AST2IR.kt:209-223`
- Current parameter expansion: `AST2IR.kt:257-270`
- Current type unwrapping: `JVMBackend.kt:358-361`

## Before & After

### Before (Fails)
```kotlin
// Input
fun test(user: User): Int { user.id.value }

// Phase3 IR
method test(Id, String): Int32 {
  user_id: Id                    // ❌ Wrong type annotation
}

// Error
Type issue: Method expects Int32 but parameter is Id
```

### After (Works)
```kotlin
// Input
fun test(user: User): Int { user.id.value }

// Phase3 IR
method test(Id, String): Int32 {
  user_id_value: Int32           // ✅ Correct nested resolution
}

// JVM
public static int test(int id, String name) {
  return id;  // ✅ Correct bytecode
}
```

## Effort Estimate

- **Analysis & Planning**: ✅ Complete (this document)
- **Type System Changes**: 1-2 hours
- **Parameter Expansion**: 1-2 hours  
- **Nested Resolution**: 2-3 hours
- **JVM Backend Fix**: 30-60 minutes
- **Testing & Validation**: 1-2 hours
- **Total**: ~6-10 hours of development

## Success Criteria

- [x] Test `vcNestedIdAccess` passes
- [x] Test `vcChainedNestedAccess` passes (was `vcChainedAccess`)
- [x] Test `vcDeepNesting` passes
- [x] Test `vcFieldAccessOnCallResult` passes (field access on function return)
- [x] All 200+ existing tests pass
- [x] No "Type issue" errors for valid nested access
- [ ] Clear error messages for invalid nested access
- [ ] Performance regression < 5%

## Implementation Progress

### Completed (Commits f112d34, f49fb45, 6cabd3d)

1. ✅ Created `ExpandedField` data class in Types.kt
   - Tracks field name, type, source VC, and source VC fields
   - Provides helper methods: `hasField()`, `getFieldType()`, `getFieldMetadata()`
   - Supports recursive metadata for nested nesting

2. ✅ Updated `buildFunctionParameters` in AST2IR.kt
   - Creates `ExpandedField` objects with nested VC tracking
   - Extracts sourceVC and sourceVCFields for single-field VC parameters
   - Properly unwraps nested VCs during parameter expansion

3. ✅ Implemented `tryResolveExpandedFieldAccess` one-level-at-a-time resolution
   - FieldAccess resolves one VC level at a time through `expandedFields` metadata
   - Synthetic `Variable` carries `expandedFields` for the next level in the chain
   - Chain resolves fully across `user.id.value`, `box.topLeft.x`, `c.b.a.value`

4. ✅ Phases 4a-4c: Extracted VC expansion from AST2IR into dedicated pipeline phases
   - `ParameterExpansionPhase` (4a): parameter expansion
   - `ValExpansionPhase` (4b): val assignment expansion
   - VarAssignment expansion (4c): var declaration and re-assignment with VCs

5. ✅ Added test cases for nested VC field access:
   - `vcNestedIdAccess`: Tests `user.id.value` access — **passes**
   - `vcChainedNestedAccess`: Tests `box.topLeft.x` access — **passes**
   - `vcDeepNesting`: Tests `c.b.a.value` three-level nesting — **passes**
   - `vcFieldAccessOnCallResult`: Tests `getTopLeft(b).x` — **passes**

### Key Implementation Detail: One-Level-at-a-Time Resolution

The resolution follows the "Revised Approach" from the plan. Each `FieldAccess` in the chain is processed independently by `compileExpressionNode`:

1. `FieldAccess(Variable(user), "id")` → `tryResolveExpandedFieldAccess` creates `Variable("user_id", Id)` with `expandedFields = [ExpandedField("value", Int, actualSymbol=user_id_value)]`
2. `FieldAccess(Variable("user_id"), "value")` → `tryResolveExpandedFieldAccess` finds `actualSymbol = user_id_value`, returns `Variable(user_id_value)`

The synthetic `Variable` at step 1 carries `expandedFields` metadata, and step 2 resolves through it via the same `tryResolveExpandedFieldAccess` function. This works for arbitrary nesting depth.

### Remaining Items

1. **Clear error messages for invalid nested access** — Currently the JVM backend throws `ClassCastException` or `Type issue` errors. Improve diagnostics.
2. **Performance regression check** — Verify that the one-level-at-a-time resolution doesn't introduce measurable overhead.

