# Standard Library Inlining Plan

## Motivation

The runtime is currently implemented as Kotlin classes in `jafun/` (`Int.kt`, `Console.kt`, etc.). The Jafun compiler discovers these at compile time via JVM reflection and emits `invokestatic` calls to them. This works but has two limitations:

1. **No Jafun-defined logic** — runtime behaviour cannot be expressed in Jafun itself; every operator requires a Kotlin bridge.
2. **No inlining** — every `a + b` goes through an `invokestatic`/`invokevirtual` call, even for trivial operations that could be a single JVM bytecode.

The goal: write the standard library **in Jafun**, with IR bodies that the compiler inlines at call sites. The Kotlin runtime is replaced piece by piece.

## Target Architecture

```
User code:  val c = a * b
               │
               ▼
Parser produces:  MethodInvocation("*", args=[a, b])
               │
               ▼
Inliner (Phase3 plugin) resolves * → stdlib function with IR body
  Pushes args onto stack, inlines body
  Body: ir (this, other) { mul }     ◄── user-written; same syntax as ir { }
               │
               ▼
JVM backend: push a, push b → Mul → imul / lmul / dmul / fmul
```

Three new concepts are needed:
- **Type-polymorphic IR operation nodes** — Abstract operation nodes like `Mul`, `Add`, `Sub` that work on any numeric type. The JVM backend dispatches to the correct bytecode based on operand types.
- **`inline` functions with IR bodies** — `ExpressionNode.Function` gets an `inline` flag; `JFMethod` gets a matching flag. The body lives in `Function.block`. The inliner checks `JFMethod.inline` on the callee symbol, then looks up the body from the parsed stdlib `Function` nodes (kept around after stdlib compilation).
- **Stdlib source loading** — `.jf` resource files parsed at compiler startup, their `Function` nodes stored in an inlineable-function registry for the inliner to reference.

## Current Runtime Inventory

All functions currently discoverable by the Jafun compiler, categorized by how they map to JVM:

### Category A — Type-Polymorphic IR Operation Nodes

Once these IR nodes exist, these need **no runtime at all**. The nodes are type-polymorphic — one `Mul` node serves `Int`, `Long`, `Double`, `Float`, and future types like `Int8`, `UInt32`, `Int128`. The JVM backend checks operand types to select the correct bytecode (`imul` for Int, `lmul` for Long, `dmul` for Double, `fmul` for Float).

| Function | IR node | JVM bytecodes per type |
|---|---|---|
| `+(Int,Int):Int` | `Add` | `iadd` / `ladd` / `dadd` / `fadd` |
| `-(Int,Int):Int` | `Sub` | `isub` / `lsub` / `dsub` / `fsub` |
| `*(Int,Int):Int` | `Mul` | `imul` / `lmul` / `dmul` / `fmul` |
| `/(Int,Int):Int` | `Div` | `idiv` / `ldiv` / `ddiv` / `fdiv` |
| `++(Int):Int` | `Add(a, 1)` | (same as Add) |
| `--(Int):Int` | `Sub(a, 1)` | (same as Sub) |
| `==(Int,Int):Boolean` | `CmpEq` | type-specific comparison + push bool |
| `<` / `<=` / `>` / `>=` | `CmpLt` / `CmpLe` / `CmpGt` / `CmpGe` | per-type comparison + push bool |
| `==(Char,Char):Boolean` | `CmpEq` (reuse) | `if_icmpne` + push bool |

### Category B — JVM Library Calls

Will remain `invokestatic`/`invokevirtual` to JRE methods. These are never "inlined" in the IR sense — they produce real method calls to the JVM standard library.

| Function | JVM target |
|---|---|
| `charAt(String,Int):Char` | `invokevirtual String.charAt(I)C` |
| `length(String):Int` | `invokevirtual String.length()I` |
| `print(Any?):Unit` | `getstatic System.out` + `invokevirtual PrintStream.print` |
| `println(Any?):Unit` | `getstatic System.out` + `invokevirtual PrintStream.println` |
| `objectHash(Any?):Int` | `invokevirtual Object.hashCode()I` |
| `objectEquals(Any?,Any?):Boolean` | `if_acmpeq` or `invokestatic Objects.equals` |
| `first(Array<String>):String` | `aaload` |

### Category C — Defined in Terms of A and B

These become Jafun stdlib functions with IR bodies. Once the inliner exists, they are written in Jafun and inlined at call sites.

| Function | Definition (in Jafun) | Relies on |
|---|---|---|
| `**(Int,Int):Int` | recursive `when(b){0->1; else->a * **(a,b-1)}` | `*`, `-` (intrinsics), `when` |
| `euro(Int):Int` | `a + 100` | `+` (intrinsic) |
| `cent(Int):Int` | `a` (identity) | nothing |
| `<=>(Int):Int` | `a / 100 + a % 100` | `/`, `+`, `%` (intrinsics) |
| `reverse(String):String` | `StringBuilder(it).reverse().toString()` | StringBuilder JVM calls |
| `join(String,String):String` | `a + b` | `+` with string concat |
| `SimpleObject.fetchA():Int` | `this.a` | field access |
| `POJO.b(Int):Int` | `this.a + i` | `+` (intrinsic), field access |

### Category D — Java Interop (no change needed)

`System.out.println`, `Integer.valueOf`, `Boolean.valueOf`, etc. — these remain `invokestatic`/`invokevirtual` to JRE classes, handled by the existing MethodInvocation → `invokestatic` code path.

## Scalability: Long, Double, Float

Type-polymorphic IR nodes handle additional primitive types cleanly — **no new IR nodes needed**. Each new type only needs:

1. **New `OperandType` entries** — `SInt64`, `SDouble`, `SFloat`.
2. **JVM signature mappings** — `signature()` maps `SInt64` → `J`, `SDouble` → `D`, `SFloat` → `F`.
3. **JVM backend type dispatch** — add a `when` branch in each operation's handler for the new type.
4. **Stdlib function sets** — `jafun.lang.Long.jf`, `jafun.lang.Double.jf`, etc., each defining the same operators. The IR nodes (`Mul`, `Add`, etc.) are reused unchanged.

The inliner is type-agnostic — it substitutes arguments into IR bodies without inspecting types. The same inliner works for all types.

## Phased Implementation

### Phase 1 — `ir { }` Blocks with IR Operation Nodes (End-to-End)

**Goal**: Add type-polymorphic IR operation nodes (`Mul`, `Add`, `Sub`, `Div`, `CmpEq`, etc.), `ir { }` parser syntax, and JVM backend support — so `ir { mul a, b }` compiles and runs end-to-end.

**Principle**: One IR node per operation concept, not per type. `Mul(left, right)` handles Int, Long, Double, Float, and all future types. The return type is derived from the operand type (`left.type()`).

#### 1A — IR Nodes

Add to `ExpressionNode.kt`:

```kotlin
data class Mul(
    val left: Phase2_3Expression,
    val right: Phase2_3Expression,
) : Phase3Expression {
    override fun type() = left.type()
}

data class Add(
    val left: Phase2_3Expression,
    val right: Phase2_3Expression,
) : Phase3Expression {
    override fun type() = left.type()
}

data class Sub(
    val left: Phase2_3Expression,
    val right: Phase2_3Expression,
) : Phase3Expression {
    override fun type() = left.type()
}

data class Div(
    val left: Phase2_3Expression,
    val right: Phase2_3Expression,
) : Phase3Expression {
    override fun type() = left.type()
}

// Comparisons always return Boolean:
data class CmpEq(
    val left: Phase2_3Expression,
    val right: Phase2_3Expression,
) : Phase3Expression {
    override fun type() = OperandType.UInt1
}

data class CmpLt(
    val left: Phase2_3Expression,
    val right: Phase2_3Expression,
) : Phase3Expression {
    override fun type() = OperandType.UInt1
}

data class CmpLe(
    val left: Phase2_3Expression,
    val right: Phase2_3Expression,
) : Phase3Expression {
    override fun type() = OperandType.UInt1
}

data class CmpGt(
    val left: Phase2_3Expression,
    val right: Phase2_3Expression,
) : Phase3Expression {
    override fun type() = OperandType.UInt1
}

data class CmpGe(
    val left: Phase2_3Expression,
    val right: Phase2_3Expression,
) : Phase3Expression {
    override fun type() = OperandType.UInt1
}
```

Also add an `IRBlock` wrapper node for the `ir ( ) { }` syntax:

```kotlin
data class IRBlock(
    val parameters: List<Type.JFVariableSymbol>,
    val operation: Phase2_3Expression,
) : Phase2Expression {
    override fun type() = operation.type()
}
```

`parameters` are the variables passed into the block (pushed onto the stack). `operation` is the single IR operation that consumes them. (Multi-operation blocks can be added later.)

#### 1B — Parser: `ir (params) { }` Syntax

In `ParserJafun`, add parsing for `ir` keyword + `(params)` + `{ }`:

```
val c = ir (a, b) {
  mul
}
```

The block parameters `(a, b)` reference variables from the enclosing scope. The parser resolves them through its normal symbol-map lookup, producing `Variable` nodes. These are pushed onto the stack before the IR operation executes.

Inside the `{ }`:
- A single IR operation keyword (`mul`, `add`, `sub`, `div`, `cmpeq`, `cmplt`, etc.)
- The operation consumes values from the stack (which are the block parameters)
- The result of the operation is the result of the `ir` expression

The parser changes:
1. In the expression parser (`prattParser` or `methodLhs`), detect `ir` keyword token.
2. Parse `(params)` as a comma-separated list of identifiers (variable references).
3. Parse `{ }` block: read the operation name, match against known IR operations.
4. Create `IRBlock(parameters=[Variable(a), Variable(b)], operation=Mul)`.

The `IRBlock` node tells the backend:
1. Compile each parameter expression (pushes values onto the stack).
2. Compile the operation (consumes stack values, pushes result).

#### 1C — JVM Backend

Update `JVMBackend.Context.compile()` to handle each node with type dispatch:

```kotlin
is ExpressionNode.Mul -> {
    compile(instruction.left)
    compile(instruction.right)
    with(method) {
        when (instruction.left.type()) {
            is OperandType.SInt32 -> imul()
            is OperandType.SInt64 -> lmul()
            is OperandType.SDouble -> dmul()
            is OperandType.SFloat -> fmul()
            else -> error("Mul not supported for ${instruction.left.type()}")
        }
    }
}
is ExpressionNode.Add -> {
    compile(instruction.left)
    compile(instruction.right)
    with(method) {
        when (instruction.left.type()) {
            is OperandType.SInt32 -> iadd()
            is OperandType.SInt64 -> ladd()
            is OperandType.SDouble -> dadd()
            is OperandType.SFloat -> fadd()
            else -> error("Add not supported for ${instruction.left.type()}")
        }
    }
}
// Sub, Div: similar pattern with isub/lsub/dsub/fsub, idiv/ldiv/ddiv/fdiv

is ExpressionNode.CmpEq -> {
    compile(instruction.left)
    compile(instruction.right)
    with(method) {
        when (instruction.left.type()) {
            is OperandType.SInt32 -> {
                val trueLabel = label(); val endLabel = label()
                if_icmpeq(trueLabel); loadConstant(0); goto(endLabel)
                trueLabel { loadConstant(1) }; endLabel {}
            }
            is OperandType.SInt64 -> {
                lcmp()
                val trueLabel = label(); val endLabel = label()
                ifeq(trueLabel); loadConstant(0); goto(endLabel)
                trueLabel { loadConstant(1) }; endLabel {}
            }
            is OperandType.SDouble -> {
                dcmpg()
                val trueLabel = label(); val endLabel = label()
                ifeq(trueLabel); loadConstant(0); goto(endLabel)
                trueLabel { loadConstant(1) }; endLabel {}
            }
            else -> error("CmpEq not supported for ${instruction.left.type()}")
        }
    }
}
// CmpLt, CmpLe, CmpGt, CmpGe: similar with if_icmpXX / lcmp + ifXX / dcmpg + ifXX
```

Also handle `IRBlock` — compile parameters (pushes values), then the operation (consumes/pushes):
```kotlin
is ExpressionNode.IRBlock -> {
    instruction.parameters.forEach { compile(it) }
    compile(instruction.operation, asStatement)
}
```

#### 1D — AST2IR

`IRBlock` and its contained IR nodes are already `Phase3Expression` (or wrapped in `IRBlock` which is `Phase2Expression`). AST2IR needs to handle `IRBlock` by passing the inner operation through. No other changes needed since the inner nodes are already backend-ready.

**Testing**:
```jafun
val a = 5
val b = 6
val c = ir(a, b) { mul }
```
Compile and run. Assert bytecode contains `imul`, and `c` evaluates to `30`.

**Deliverable**: `ir { }` blocks work end-to-end. Can be used in user code today.

---

### Phase 2 — `inline` Functions and Inliner

**Goal**: `ExpressionNode.Function` and `JFMethod` get an `inline` flag. An inliner replaces calls to inline functions with their IR bodies.

**Changes**:

1. Add `inline: Boolean` to `ExpressionNode.Function`:
   ```kotlin
   data class Function(
       val symbol: Type.JFMethod,
       val block: List<Phase2_3Expression>,
       val inline: Boolean = false,
   ) : Phase2Expression
   ```

2. Add `inline: Boolean` to `Type.JFMethod`:
   ```kotlin
   data class JFMethod(
       val parameters: List<JFVariableSymbol>,
       override val parent: MethodParent,
       override val name: String,
       val rtn: OperandType<*>,
       val static: Boolean = false,
       val operator: Boolean = false,
       val associativity: Associativity = Associativity.PREFIX,
       val precedence: Int = 10,
       val inline: Boolean = false,
   ) : Type, HasParent<MethodParent>
   ```

3. Update the parser to recognize the `inline` keyword on function declarations. When `inline` is present, both `Function.inline` and `JFMethod.inline` are set to `true`.

4. Create `Inliner.kt` — a Phase3 plugin that walks `MethodInvocation` nodes:
   ```kotlin
   class Inliner(private val inlineFunctions: List<ExpressionNode.Function>) : Compiler.Phase3Plugin {
       override fun handle(context: IRBuilder.ClassContext): IRBuilder.ClassContext {
           return context.copy(
               methods = context.methods.map { method ->
                   method.copy(
                       instructions = method.instructions.map { inlineCall(it, method.name) }
                   )
               }
           )
       }

       private fun inlineCall(node: Phase2_3Expression, currentMethod: String): Phase2_3Expression = when (node) {
           is ExpressionNode.MethodInvocation -> {
               // Find the callee's symbol in the symbol map
               val callee = findCalleeSymbol(node) ?: return node
               if (!callee.inline) return node
               // Don't inline recursive calls
               if (callee.name == currentMethod) return node
               // Find the matching Function node
               val fn = inlineFunctions.find { it.symbol == callee } ?: return node
               substituteArguments(fn.block, fn.symbol.parameters, node.arguments)
                   .let { if (it.size == 1) it[0] else ExpressionNode.ExpressionList(it) }
           }
           is ExpressionNode.ExpressionList -> node.copy(
               expressions = node.expressions.map { inlineCall(it, currentMethod) }
           )
           else -> node
       }
   }
   ```

5. `substituteArguments(body, params, args)` — walks the body IR and replaces `Variable` references to parameter symbols with the argument expressions. Must deep-copy to avoid aliasing.

6. Register the inliner as a Phase3 plugin in the `Compiler`.

**Testing**: Write an inline function that uses `ir { }`, call it, verify the bytecode has no `invokestatic` to that function.

**Deliverable**: `inline` functions are expanded at call sites.

---

### Phase 3 — Stdlib Loading

**Goal**: Stdlib `.jf` source files are loaded at compiler startup. Their `inline` functions are registered and available for inlining.

**Changes**:

1. Create stdlib source directory: `core/src/commonMain/resources/jafun/lang/`.

2. Add a `loadStdlib()` method that:
   a. Reads `.jf` resource files from the stdlib path.
   b. Parses each file using `Phase1Parser` + `ParserJafun` (same pipeline as user code).
   c. Returns the parsed `Function` nodes — kept for the inliner.
   d. The `JFMethod` symbols are registered in the symbol map by the parser automatically.

3. Write first stdlib functions in `Int.jf`:
   ```jafun
   inline fun `*`(a: Int, b: Int): Int { ir(a, b) { mul } }
   inline fun `+`(a: Int, b: Int): Int { ir(a, b) { add } }
   inline fun `-`(a: Int, b: Int): Int { ir(a, b) { sub } }
   inline fun `/`(a: Int, b: Int): Int { ir(a, b) { div } }

   inline fun `++`(a: Int): Int = a + 1
   inline fun `--`(a: Int): Int = a - 1
   ```

   For `++` and `--`, the body calls `+` / `-` which are themselves inline. The inliner inlines both levels: `++(x)` → `+(x, 1)` → `Add(x, 1)`.

4. Load stdlib in `IdentifierCache.reset()`, pass the `Function` nodes to the `Inliner`.

**Testing**: Full end-to-end test: `val c = 5 * 6` → compile → no `invokestatic` in bytecode (only `imul`). Verify `c` evaluates to `30` at runtime.

**Deliverable**: `Int` arithmetic operators work without the Kotlin runtime.

---

### Phase 4 — Migrate Remaining Operators

Port comparison operators to stdlib `Int.jf`:

```jafun
inline fun `==`(a: Int, b: Int): Boolean { ir(a, b) { cmpeq } }
inline fun `<`(a: Int, b: Int): Boolean  { ir(a, b) { cmplt } }
inline fun `<=`(a: Int, b: Int): Boolean { ir(a, b) { cmple } }
inline fun `>`(a: Int, b: Int): Boolean  { ir(a, b) { cmpgt } }
inline fun `>=`(a: Int, b: Int): Boolean { ir(a, b) { cmpge } }
```

Delete the corresponding functions from `jafun/lang/Int.kt`. All existing tests pass.

---

### Phase 5 — Port Category C Functions

Port `euro`, `cent`, `<=>`, `**` to stdlib `Test.jf`:

```jafun
inline fun euro(a: Int): Int = a + 100
inline fun cent(a: Int): Int = a
inline fun `<=>`(a: Int): Int = a / 100 + a % 100
inline fun `**`(a: Int, b: Int): Int =
    when b {
        0 -> 1
        else -> a * `**`(a, b - 1)
    }
```

`**` is recursive — the inliner inlines the initial call site but leaves the recursive `MethodInvocation` inside the body untouched.

---

### Phase 6 — Char Operators

```jafun
// jafun/lang/Char.jf
inline fun `==`(a: Char, b: Char): Boolean { ir(a, b) { cmpeq } }
```

Delete `jafun/lang/Char.kt`.

---

### Phase 7 — String and IO (JVM Externs)

Deferred. These remain as reflection-discovered Kotlin functions until an extern mechanism is built. The Kotlin bridge for these is simple and stable.

---

### Phase 8 — Additional Primitive Types (Long, Double, Float)

1. Add `OperandType.SInt64`, `OperandType.SDouble`, `OperandType.SFloat`.
2. Add `when` branches in `JVMBackend` for each operation × type (add `ladd`/`dadd`/`fadd`, etc.).
3. Update `signature()`: `SInt64` → `J`, `SDouble` → `D`, `SFloat` → `F`.
4. Update variable load/store for the new types.
5. Create `jafun/lang/Long.jf`, `jafun/lang/Double.jf`, `jafun/lang/Float.jf`.

No new IR nodes needed — `Mul`, `Add`, etc. are reused.

---

### Phase 9 — Fully Delete Kotlin Runtime

Delete `jafun/lang/Int.kt`, `jafun/lang/Char.kt`, and ported functions from `jafun/test/Test.kt`.

---

## Key Design Decisions

### Q1: `ir { }` syntax

```jafun
val a = 5
val b = 6
val c = ir (a, b) {
  mul
}
```

`(a, b)` are the parameters — the compiler pushes them onto the stack. Inside `{ }`, `mul` pops two values and pushes one result. This mirrors inline function call semantics exactly: the inliner pushes arguments, then inlines the body.

Single operation per block for now. Multi-operation blocks (stack chaining) can be added later.

### Q2: Where stdlib source lives

**Options:**

1. **`commonMain/resources/jafun/lang/*.jf`** — Standard resource loading. Works on all targets.
2. **`stdlib/` at project root** — Separate from compiler module.
3. **Embedded in the compiler** — String constants in `IdentifierCache.kt`.

**Recommendation**: Option 1. Standard resource loading, no new build configuration needed.

### Q3: Recursive function inlining

The inliner checks `callee.name == currentMethod` before inlining. Recursive calls are left as `MethodInvocation`. Depth limit as additional safety net.

### Q4: Inliner phase placement

The inliner runs as a Phase3 plugin, after AST2IR and before VCBinder:
```
AST2IR → Phase3 plugins (including Inliner) → VCBinder → JVM backend
```

### Q5: `++` and `--` as `Add`/`Sub`

`++` and `--` are written as `a + 1` / `a - 1` in stdlib source. After inlining `+` → `Add`, the result is `Add(x, IntLiteral(1))`. No dedicated IR nodes needed.

## Testing Strategy

| Phase | Test approach |
|---|---|
| Phase 1 | `val c = ir { mul 5, 6 }` — bytecode asserts `imul`, runtime asserts `30` |
| Phase 2 | Call inline function wrapping `ir { }`, verify no `invokestatic` |
| Phase 3 | `val c = 5 * 6` — no `invokestatic`, runtime asserts `30` |
| Phase 4 | Existing test suite passes without Kotlin `Int.kt` operators |
| Phase 5 | Existing `**`, `euro` tests pass |
| Phase 6–9 | New type tests + existing tests continue passing |
