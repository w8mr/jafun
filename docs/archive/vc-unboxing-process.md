# Value Class Unboxing Process

## Goal

Unbox value classes where possible to avoid unnecessary boxing/unboxing overhead while preserving type safety and correct semantics.

## Core Principle

Every value class exists in two forms:
- **Boxed**: The VC as a single object
- **Unboxed**: The individual scalar fields

We choose which form to use based on context.

## Tracking Mechanism

### Variable State

Each variable carries tracking information:

```
Variable: {
  value,                    // The actual value
  type,                     // Static type
  form: boxed | unboxed,    // Current form
  typeLinks: [...]          // Chain: VC type → field name → sub-type
}
```

### Type Links

Type links connect scalar variables to their VC type hierarchy:

```
relationship_p1_address_street: {
  type: String,
  form: unboxed,
  typeLinks: [
    { vc: Relationship, field: p1 },
    { vc: Person, field: address },
    { vc: Address, field: street }
  ]
}
```

These links allow us to:
- Determine which field a scalar belongs to
- Reconstruct the boxed form when needed
- Know the VC context when accessing nested fields

## Unboxing Rules

### Rule 1: Eager Unboxing (Constructor on RHS)

When the right-hand side is a Constructor Invocation, both boxed and unboxed forms are available immediately.

```
val adam = Person("Adam", Address("Smallstreet", 1), Id(12))

// Becomes:
Val(String, adam_name) = "Adam"
Val(String, adam_address_street) = "Smallstreet"
Val(Int, adam_address_number) = 1
Val(Int, adam_id) = 12
```

**Rationale**: The CI gives us all scalar values directly. No reason to box first.

### Rule 2: Lazy Unboxing (Only Boxed Available)

When only the boxed form is available (e.g., function return), keep boxed until field access.

```
val a1 = getPerson1Address(relationship)

// Becomes:
Val(Address, a1) = Call(getPerson1Address, relationship)

// NOT unboxed here - only boxed form available
```

**Rationale**: Cannot unbox what we don't have. Must wait until we access fields.

### Rule 3: Unbox on Field Access

When accessing a field on a boxed VC, lazily unbox only that field.

```
println a1.street.length

// a1 is boxed Address
// Access .street → lazily unbox:
// a1_street = fieldOf(a1, street) = "Smallstreet"
```

**Rationale**: We only need the specific field. No need to fully unbox.

### Rule 4: Reconstruct for Function Returns

When passing to a function expecting a VC, reconstruct the boxed form if only unboxed is available.

```
calculateDistance(getPerson1Address(r), r.p2.address)

// First arg: getPerson1Address returns boxed Address (already boxed)

// Second arg: r.p2.address on expanded r
// - r is expanded, r.p2_address_* scalars are available
// - But calculateDistance expects boxed Address
// - Must reconstruct:
Arg(Address, arg2) = Address(r_p2_address_street, r_p2_address_number)
```

**Rationale**: Function signatures require specific types. Must match.

### Rule 5: Single-Field VC Boundary Return

For single-field VCs, we can return the scalar directly at function boundaries.

```
fun getId(p: Person): Id { p.id }

// getId returns Id(value: Int)
// But Id is single-field → can return Int directly:

Function(getId, ..., Int) {  // Return Int, not Id
  Return(p_id)
}
```

**Rationale**: Single-field VCs have only one field. No need to box/unbox roundtrip. The caller receives the scalar.

## Handling Field Access on Unboxed Variables

When a variable is already unboxed and we access a field:

1. The field name directly maps to the scalar variable
2. No unboxing needed - already have scalar
3. Type links tell us which VC this scalar belongs to

```
r_p1_address_street is already a String scalar
// Accessing .street gives us r_p1_address_street directly
```

## Summary

| Scenario | Action |
|----------|--------|
| Constructor on RHS | Eager unbox - have all scalars |
| Function return | Keep boxed until needed |
| Field access on boxed | Lazily unbox that field |
| Need VC but have scalars | Reconstruct boxed form |
| Single-field VC at boundary | Return scalar directly |

## Examples from Analysis

### Example 1: Storing a VC with Constructor

```
val adam = Person("Adam", Address("Smallstreet", 1), Id(12))
```
→ Stored as scalars: `adam_name`, `adam_address_street`, `adam_address_number`, `adam_id`

### Example 2: Using Expanded Variable

```
val relationship = Relationship(adam, eve, 1)
```
→ Uses scalar values directly from `adam` and `eve`

### Example 3: Function Return

```
val a1 = getPerson1Address(relationship)
```
→ `a1` kept boxed (only boxed form available from return)

### Example 4: Field Access on Boxed

```
println a1.street.length
```
→ Lazily unbox `.street` from boxed `a1`

### Example 5: Reconstruct at Boundary

```
calculateDistance(getPerson1Address(r), r.p2.address)
```
→ Second arg: reconstruct boxed `Address` from `r_p2_address_*` scalars

### Example 6: Single-Field Return

```
fun getId(p: Person): Id { p.id }
```
→ Return `Int` directly, not `Id`

## Key Insight

The tracking mechanism allows us to know at any point:
1. What form a variable is in
2. How to obtain the other form if needed
3. The VC type hierarchy for reconstruction

This enables optimal decisions: unbox when we have scalars, keep boxed when we don't, reconstruct when context requires it.