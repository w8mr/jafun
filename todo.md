# Things still to do

## Operations
- method call precedence &#x2611;

## When expression
- else &#x2611;
- input values &#x2611;
- method on input values &#x2611;

## Parser
- 2 phase: Structure / code &#x2611
- Merge lexer and parser &#x2611;
- String interpolation &#x2611
- pattern matching
- imports / exports
- Check for invoke methods - Need to implement detection and handling of invoke methods
- Escape characters in strings - Implement proper escape character handling in string literals
- Multiline string support - Add support for multiline string literals
- Cache parsers - Implement caching for parsers to improve performance
- Remove duplication in parser code - Refactor to eliminate duplicated code
- Handle complex types - Implement proper handling for complex type expressions

## Debug
- Extract ast tree debug to a separate file  &#x2611;
- Create printable implementation for all element types - Ensure all element types can be properly printed for debugging

## Compiler Frontend
- Extract AST 2 IR code away from ASTNodes  &#x2611;
- Create separate package for AST2IR  &#x2611;
- Move String Interpolation handling toward frontend
- Get the right scope for LocalSymbolMap - Fix scope handling in AST2IR conversion for when subject variable

## Compiler backend
- Create separate package for IR2Jvm
- Handle OperandType.Generic - Implement proper handling for generic operand types
- Handle OperandType.Unit - Implement proper handling for unit operand types
- Evaluate if DoWhile should be an expression - Review and decide on the expression status of DoWhile
- Implement type conversions - Complete implementation of all type conversion cases


## Compiler
- Move towards one node type (with interfaces for levels) &#x2611;
- Move towards non stack based nodes &#x2611;
- Separate stack based ndoes in separate level
- Scope node (check if needed)
- Look into compiler initialization issues - Review and fix initialization process

## Testing
- Create better dsl for testing separate outputs (ast, ir, bytecode, output (print), output function) &#x2611;
- Merge AST tests &#x2611;
- Create more AoC tests
- Handle no expected case in tests - Implement proper handling for test cases without expected output
- Fix operator flag in tests - Ensure operator flag is correctly set in test cases

## Symbol table
- Restructure to use classes instead of strings for lookup &#x2611;
- Lookup runtime classes
- Add Packages
- Fix operator flag handling - Investigate why operator flag isn't set to true in some cases
- Remove full path in name - Refactor to avoid using full paths in symbol names
- Improve array type handling - Enhance the handling of array types in the type system
- Source type information from appropriate location - Refactor to get type information from the correct source


## Type system
- Int type &#x2611;
- String type &#x2611;
- Boolean type &#x2611;
- Char type &#x2611;
- Check common type when expression
- Smart type integer literals (BigInteger, Long, Int, Short, Byte)
- Unsigned type (ULong, UInt, UShort, UByte)
- Introduce float literals
- Generics
- Marker interface on system classes
- Union types
- Interfaces without extending
- Handle JFClass path formatting - Ensure proper formatting of class paths in JFClass types
- Implement proper handling for variable symbols - Fix variable symbol handling in type system

## Kasmine
- Remove hardcoded path &#x2611;
- Publish as library &#x2611;
- StackMapTable

## Prelude

## Classes

## Data classes

## Destructering

## Pattern matching

## Loops

## Recursion detection

## Inline functions

## IR

## IR function body
- IR functions from Kotlin
- IR functions in Jafun


## Bytecode class generation
- StackMapTable
- labels / jump resolution &#x2611;
- linenumbers
- Implement bytecode printing for all instruction types - Add support for printing all bytecode instruction types
- Implement printing for all object types - Ensure all object types can be properly printed in bytecode format

## Implicit conversions

## Extension functions

## Context recievers

## Error handling
- Handle method resolution errors - Implement proper error handling for method resolution failures
- Handle class/package resolution errors - Implement proper error handling for class and package resolution failures
- Handle field resolution errors - Implement proper error handling for field resolution failures
- Handle else cases in control structures - Implement proper handling for else cases in various control structures
