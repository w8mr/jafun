# Things still to do

## Operations
- method call precedence &#x2611;

## When expression
- else &#x2611;
- input values &#x2611;
- method on input values &#x2611;
- pattern matching

## Parser
- 2 phase: Structure / code
- Merge lexer and parser  &#x2611;
- imports / exports

## Debug
- Extract ast tree debug to a separate file  &#x2611;

## Compiler Frontend
- Extract AST 2 IR code away from ASTNodes  &#x2611;
- Create separate package for AST2IR  &#x2611;

## Compiler backend
- Create separate package for IR2Jvm

## Compiler
- Move towards one node type (with interfaces for levels)
- Move towards non stack based nodes
- Separate stack based ndoes in separate level
- Statement node
- Scope node

## Testing
- Create better dsl for testing separate outputs (ast, ir, bytecode, output (print), output function)
- Merge AST tests
- Create more AoC tests

## Symbol table
- Restructure to use classes instead of strings for lookup &#x2611;
- Lookup runtime classes
- Add Packages


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



## Kasmine
- Remove hardcoded path &#x2611;
- Publish as library &#x2611;

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
- 
## Implicit conversions

## Extension functions

## Context recievers


