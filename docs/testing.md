# Testing

Tests live in `core/src/commonTest/kotlin/nl/w8mr/jafun/CompilerTest.kt`. There is also `SimpleParserTest.kt` in the same directory for Phase1-level parsing.

## CompilerTest structure

The test class uses a custom DSL. Each test is a method on `CompilerTest` with `@Test`:

```kotlin
@Test
fun someTest() {
    test {
        file {
            code = """
                println "hello"
            """
            expectedOutput = "hello\n"
            jvmIr { ... }       // optional expected bytecode
            phase2 { ... }      // optional expected ParserJafun output
        }
    }
}
```

### DSL blocks

| Block | Purpose |
|-------|---------|
| `code` | Jafun source to compile and run |
| `expectedOutput` | Expected stdout at runtime (ends with `\n` for each `println`) |
| `jvmIr` | Expected bytecode via kasmine assembler DSL |
| `phase2` | Expected ParserJafun output via Phase2Builder DSL helpers |
| `params` | Optional CLI arguments as `Array<String>` passed to `main` |

Use `@Ignore` above `@Test` to skip a test.

### jvmIr block

Defines expected bytecode using kasmine's `ClassBuilder` DSL:

```kotlin
jvmIr {
    name = "Script"
    method {
        name = "main"
        signature = "([Ljava/lang/String;)V"
        loadConstant(42)
        invokeStatic("java/lang/Integer", "valueOf", "(I)Ljava/lang/Integer;")
        invokeStatic("jafun/io/ConsoleKt", "println", "(Ljava/lang/Object;)V")
        `return`()
    }
    method {
        name = "add"
        signature = "(I)I"
        iload("param1")
        loadConstant(1)
        invokeStatic("jafun/lang/IntKt", "+", "(II)I")
        ireturn()
    }
}
```

Available instructions: `loadConstant`, `iload`/`aload`/`istore`/`astore`, `invokeStatic`/`invokeSpecial`/`invokeVirtual`, `ireturn`/`areturn`/`return`, `ifequal`/`goto`, `pop`/`dup`, `getStatic`, `new`, etc.

**Bytecode comparison**: Uses `javap -v` to decompile both expected and actual bytecode and compares the decompiled text, rather than raw byte-level comparison. This means different symbolic variable names that map to the same JVM slot produce matching decompiled output.

**Variable naming**: JVMBackend produces variable names in the format `${symbolMapId}.${name}` (e.g., `"0.param1"`, `"0.x"`). The `JFVariableSymbol(name, type)` constructor defaults to `IdentifierCache` as the symbol map (with `symbolMapId = 0`).

### phase2 block

Defines expected Phase 2 (ParserJafun) output using helper methods:

```kotlin
phase2 {
    +function(method("add", OperandType.SInt32, OperandType.SInt32)) {
        +invocation(plus, variable(symbol("a", OperandType.SInt32)), i(2))
    }
    +invocation(println, invocation(
        method("add", OperandType.SInt32, symbol("a", OperandType.SInt32)), i(4)
    ))
}
```

Available helpers:
- `method(name, returnType, vararg params)` — creates `JFMethod`; auto-names params `param1`, `param2`, etc.
- `function(method) { ... }` — creates `ExpressionNode.Function` with optional body
- `invocation(method, vararg args)` — creates `ExpressionNode.MethodInvocation`
- `invocation(method, field, vararg args)` — overload for field-based invocations
- `variable(symbol)` — creates `ExpressionNode.Variable`
- `symbol(name, type)` — creates `JFVariableSymbol`
- `i(Int)` — integer literal
- `s(String)` — string literal
- `b(Boolean)` — boolean literal
- `when(...)` — `when` expression
- `valAssignment(variable, expression)` — val declaration

Pre-defined methods are available as top-level properties in `Phase2Builder`: `println`, `plus`, `times`, `equals`, `join`, `printStreamPrintln`, `systemOut`, `objectType`.

## Running tests

```bash
# Single test
./gradlew :core:jvmTest --tests "*CompilerTest.testName" --rerun

# All tests
./gradlew :core:jvmTest --rerun
```

## SimpleParserTest

Located in `core/src/commonTest/kotlin/nl/w8mr/jafun/SimpleParserTest.kt`. Covers Phase1 tokenization and basic structure. Uses a simpler test pattern focused on parsing tokens rather than full compilation.

## Multi-Class Bytecode Validation

When the compiler generates multiple classes (e.g., a main Script class plus auxiliary classes like value classes), you can validate all of them in a single test using named `jvmIr` blocks:

### Syntax

```kotlin
@Test
fun vcChangeAddress() {
    test {
        file {
            code = """
                value class Address(number: Int, street: String)
                fun increaseHouseNumber(address: Address, increment: Int): Address {
                    Address(address.number + increment, address.street)
                }
                fun changeAddress(address: Address): Address {
                    increaseHouseNumber(address, 5)
                }
                val a = Address(4, "Privet Drive")
                val b = changeAddress(a)
                println b.number
            """.trimIndent()
            expectedOutput = "9\n"
            
            // Validate Script class (default)
            jvmIr {
                name = "Script"
                method { ... }
            }
            
            // Validate Address class (by name)
            jvmIr("Address") {
                name = "Address"
                field(access = 1u, "number", "I")
                field(access = 1u, "street", "Ljava/lang/String;")
                method { ... }
            }
            
            // Can validate additional classes
            jvmIr("OtherClass") {
                name = "OtherClass"
                method { ... }
            }
        }
    }
}
```

### API

| Pattern | Purpose |
|---------|---------|
| `jvmIr { ... }` | Validates "Script" class (backward compatible) |
| `jvmIr("ClassName") { ... }` | Validates class named "ClassName" |
| Multiple `jvmIr` blocks | Each validates a different generated class |

### Field Definition

Use kasmine's `field()` function to define expected class fields:

```kotlin
field(access = 1u, name: String, type: String)
```

Access flags (common values):
- `1u` — `ACC_PUBLIC`
- `2u` — `ACC_PRIVATE` (default)
- `9u` — `ACC_PUBLIC + ACC_STATIC`

Type descriptors:
- `"I"` — int
- `"Z"` — boolean
- `"Ljava/lang/String;"` — String
- `"LClassName;"` — custom class reference

### Constructor Methods

For constructors, use `name = "<init>"` with the appropriate signature and **set the access flag**:

```kotlin
method {
    name = "<init>"
    signature = "(ILjava/lang/String;)V"
    access = 1u  // ACC_PUBLIC (REQUIRED: default is 9u = ACC_PUBLIC|ACC_STATIC)
    parameter("this")
    parameter("number")
    parameter("street")
    // Emit bytecode using local var references
    aload("this")
    invokeSpecial("java/lang/Object", "<init>", "()V")
    aload("this")
    iload("number")
    putField("ClassName", "number", "I")
    `return`()
}
```

### Local Variable Handling in kasmine

A critical insight: kasmine's `parameter()` function registers local variable slots, not just method parameters. In a constructor:

1. Calling `parameter("this")` reserves slot 0
2. Calling `parameter("number")` reserves slot 1
3. Calling `parameter("street")` reserves slot 2

Then you reference these in instructions by their registered names:
- `aload("this")` — loads slot 0
- `iload("number")` — loads slot 1
- `aload("street")` — loads slot 2

**Slot assignment order matters**: The first `parameter()` call gets slot 0, second gets slot 1, etc. This must match the JVM method signature order for correct bytecode generation.

### Example: Generated Value Class Constructor

For a value class `Address(number: Int, street: String)`, the compiler generates:

```
public Address(int, java.lang.String);
  Code:
     0: aload         0
     2: invokespecial Object."<init>":()V
     5: aload         0
     7: iload         1
     9: putfield      Address.number:I
    12: aload         0
    14: aload         2
    16: putfield      Address.street:Ljava/lang/String;
    19: return
```

To validate this in a test:

```kotlin
jvmIr("Address") {
    name = "Address"
    field(access = 1u, "number", "I")
    field(access = 1u, "street", "Ljava/lang/String;")
    method {
        name = "<init>"
        signature = "(ILjava/lang/String;)V"
        access = 1u  // Non-static instance method
        parameter("this")      // slot 0
        parameter("number")    // slot 1
        parameter("street")    // slot 2
        aload("this")
        invokeSpecial("java/lang/Object", "<init>", "()V")
        aload("this")
        iload("number")
        putField("Address", "number", "I")
        aload("this")
        aload("street")
        putField("Address", "street", "Ljava/lang/String;")
        `return`()
    }
}
```

### Bytecode Comparison Details

- **Decompilation-based**: Both expected and actual bytecode are decompiled using `javap -v` and compared as text, not raw bytes
- **Name-tolerant**: Different local variable names that map to the same JVM slot produce identical decompiled output
- **Detailed error messages**: When bytecode doesn't match, both the expected and actual javap output are shown side-by-side


