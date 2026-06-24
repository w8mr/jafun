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
