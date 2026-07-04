package nl.w8mr.jafun.compiler

import nl.w8mr.jafun.OperandType
import nl.w8mr.jafun.Type
import nl.w8mr.jafun.compiler.ExpressionNode
import nl.w8mr.jafun.compiler.SymbolMap

/**
 * Value Class Flattening and Expansion
 * 
 * Handles recursive flattening of nested value class types to their primitive components.
 * This is used for variable expansion during Phase 3 compilation, where:
 * 
 * val a = Box(Point(1,2), Point(3,4))
 * 
 * Gets expanded to separate variables:
 * a_topLeft_x = 1
 * a_topLeft_y = 2
 * a_bottomRight_x = 3
 * a_bottomRight_y = 4
 */

/**
 * Represents a single flattened primitive component of a VC type.
 * 
 * @param path The dot-separated path from the original VC to this primitive
 *             Examples: "x", "topLeft_x", "box_topLeft_x"
 * @param type The primitive OperandType
 */
data class FlattenedField(val path: String, val type: OperandType<*>)

/**
 * Flattens a VC type recursively to a list of (path, primitiveType) pairs.
 * 
 * For non-VC types, returns a single entry with the type itself.
 * For VC types, recursively expands all fields.
 * 
 * Examples:
 * - flattenType(Int) -> [(path="", type=Int)]
 * - flattenType(Id(value: Int)) -> [(path="value", type=Int)]
 * - flattenType(Point(x: Int, y: Int)) -> [(path="x", type=Int), (path="y", type=Int)]
 * - flattenType(Box(topLeft: Point)) -> [(path="topLeft_x", type=Int), (path="topLeft_y", type=Int)]
 * - flattenType(Box(topLeft: Point, bottomRight: Point)) -> 
 *       [(path="topLeft_x", type=Int), (path="topLeft_y", type=Int), 
 *        (path="bottomRight_x", type=Int), (path="bottomRight_y", type=Int)]
 */
fun flattenType(type: OperandType<*>, pathPrefix: String = ""): List<FlattenedField> {
    // If it's not a VC, it's a primitive - return as-is
    if (type !is Type.JFClass || type.kind != Type.ClassKind.VALUE_CLASS) {
        // Remove trailing underscore from prefix before returning
        val finalPath = if (pathPrefix.endsWith("_")) pathPrefix.dropLast(1) else pathPrefix
        return listOf(FlattenedField(finalPath, type))
    }
    
    // It's a VC - recursively flatten its fields
    val cons = type.constructor ?: error("VC ${type.name} has no constructor")
    
    return cons.parameters.flatMap { param ->
        val fieldPath = if (pathPrefix.isEmpty()) param.name else "$pathPrefix${param.name}"
        flattenType(param.type, fieldPath + "_")
    }
}

/**
 * Expands a variable into its flattened components.
 * Each component becomes a separate JFVariableSymbol with the flattened path appended to the variable name.
 * 
 * Example:
 * Variable "b" of type Box(Point(x: Int, y: Int), Point(x: Int, y: Int)) expands to:
 * - b_topLeft_x: Int
 * - b_topLeft_y: Int
 * - b_bottomRight_x: Int
 * - b_bottomRight_y: Int
 */
fun expandVariable(variable: Type.JFVariableSymbol): List<Type.JFVariableSymbol> {
    val flattened = flattenType(variable.type)
    
    return flattened.map { field ->
        // Remove trailing underscore from path if present
        val fieldPath = if (field.path.endsWith("_")) {
            field.path.dropLast(1)
        } else {
            field.path
        }
        
        val expandedName = if (fieldPath.isEmpty()) variable.name else "${variable.name}_$fieldPath"
        Type.JFVariableSymbol(expandedName, field.type, symbolMap = variable.symbolMap, mutable = variable.mutable)
    }
}

/**
 * Checks if a type is a multi-field VC that needs expansion at the top level.
 * Also returns true for single-field VCs that contain multi-field VCs.
 */
fun isMultiFieldVC(type: OperandType<*>): Boolean {
    if (type !is Type.JFClass || type.kind != Type.ClassKind.VALUE_CLASS) {
        return false
    }
    val cons = type.constructor ?: return false
    
    // Direct case: VC with multiple fields
    if (cons.parameters.size > 1) return true
    
    // Recursive case: single-field VC containing a multi-field VC
    if (cons.parameters.size == 1) {
        val fieldType = cons.parameters.single().type
        return isMultiFieldVC(fieldType)
    }
    
    return false
}

/**
 * Creates a sequence of FieldAccess expressions to extract a deeply nested field.
 * 
 * Given a path like "topLeft_x", recursively extracts: arg.topLeft.x
 * 
 * @param pathComponents The field names in order (e.g., ["topLeft", "x"])
 * @param arg The base expression to access fields from
 * @return Expression representing the nested field access
 */
fun createNestedFieldAccess(
    pathComponents: List<String>,
    arg: ExpressionNode.Phase2_3Expression,
): ExpressionNode.Phase2_3Expression {
    if (pathComponents.isEmpty()) return arg
    if (pathComponents.size == 1) {
        // Single field - this is a primitive type access
        val fieldName = pathComponents[0]
        // For simple field access to a primitive, we need the field type
        // This will be resolved during compilation
        val fieldType = arg.type().let { argType ->
            if (argType is Type.JFClass && argType.kind == Type.ClassKind.VALUE_CLASS) {
                argType.constructor?.parameters?.find { it.name == fieldName }?.type
                    ?: OperandType.SInt32 // fallback
            } else {
                OperandType.SInt32 // fallback
            }
        }
        return ExpressionNode.FieldAccess(
            instance = arg,
            fieldIndex = 0, // Will be resolved during compilation
            fieldName = fieldName,
            fieldType = fieldType,
            arguments = emptyList(),
        )
    }
    
    // Nested access - recurse
    val firstField = pathComponents[0]
    val fieldType = arg.type().let { argType ->
        if (argType is Type.JFClass && argType.kind == Type.ClassKind.VALUE_CLASS) {
            argType.constructor?.parameters?.find { it.name == firstField }?.type
                ?: OperandType.SInt32 // fallback
        } else {
            OperandType.SInt32 // fallback
        }
    }
    val firstAccess = ExpressionNode.FieldAccess(
        instance = arg,
        fieldIndex = 0, // Will be resolved during compilation
        fieldName = firstField,
        fieldType = fieldType,
        arguments = emptyList(),
    )
    return createNestedFieldAccess(pathComponents.drop(1), firstAccess)
}

/**
 * Recursively expand a parameter type if it's a multi-field VC.
 * For primitive types and single-field VCs, returns a single Parameter.
 * For multi-field VCs, expands to multiple Parameters for each field.
 */
fun expandParameterRecursively(type: OperandType<*>, baseName: String?): List<Parameter> {
    if (type !is Type.JFClass || type.kind != Type.ClassKind.VALUE_CLASS) {
        return listOf(Parameter(type, baseName))
    }

    val cons = type.constructor ?: return listOf(Parameter(type, baseName))

    if (cons.parameters.size == 1) {
        val fieldType = cons.parameters[0].type
        val newBaseName = if (baseName != null) "${baseName}_${cons.parameters[0].name}" else cons.parameters[0].name
        return expandParameterRecursively(fieldType, newBaseName)
    }

    return cons.parameters.flatMap { fieldParam ->
        val fieldName = fieldParam.name
        val newBaseName = if (baseName != null) "${baseName}_${fieldName}" else fieldName
        expandParameterRecursively(fieldParam.type, newBaseName)
    }
}

/**
 * Gets the innermost primitive type from a single-field VC.
 * Returns null if the type is not a single-field VC.
 * Recursively unwraps nested single-field VCs.
 */
fun unboxSingleFieldVCType(type: OperandType<*>): OperandType<*>? {
    if (type !is Type.JFClass || type.kind != Type.ClassKind.VALUE_CLASS) return null
    val cons = type.constructor ?: return null
    if (cons.parameters.size != 1) return null
    val innerType = cons.parameters[0].type
    return unboxSingleFieldVCType(innerType) ?: innerType
}

/**
 * Expand an Assignment of a multi-field VC into multiple Assignment nodes
 * of primitive types (val for val, var for var).
 *
 * Example: val b = Box(Point(1,2), Point(3,4)) with type Box(topLeft: Point, bottomRight: Point)
 * becomes:
 *   val b_topLeft_x = <expr>
 *   val b_topLeft_y = <expr>
 *   val b_bottomRight_x = <expr>
 *   val b_bottomRight_y = <expr>
 *
 * The original symbol is updated with expandedFields storing the hierarchical structure
 * so that b.topLeft resolves correctly and knows that topLeft has x and y.
 */
fun expandAssignmentIfNeeded(
    assignment: ExpressionNode.Assignment,
    expandedInfo: MutableMap<String, Type.ExpandedInfo> = mutableMapOf(),
): List<ExpressionNode.Phase2_3Expression> {
    val varType = assignment.variableSymbol.type

    // Only expand VC types
    if (varType !is Type.JFClass || varType.kind != Type.ClassKind.VALUE_CLASS) {
        return listOf(assignment as ExpressionNode.Phase2_3Expression)
    }
    // We can only expand when the RHS is a constructor invocation with known values
    if (assignment.expression !is ExpressionNode.ConstructorInvocation) {
        return listOf(assignment as ExpressionNode.Phase2_3Expression)
    }
    val ci = assignment.expression as ExpressionNode.ConstructorInvocation

    // Get the flattened fields for the entire structure
    val flattened = flattenType(varType)
    val expandedVars = expandVariable(assignment.variableSymbol)

    // Store the hierarchical structure on the original symbol
    val constructorParams = (varType as Type.JFClass).constructor!!.parameters

    // Build a map from flattened field paths to their expanded symbols
    val fieldPathToSymbol = mutableMapOf<String, Type.JFVariableSymbol>()
    expandedVars.forEachIndexed { index, expandedVar ->
        fieldPathToSymbol[flattened[index].path] = expandedVar
    }

    val fields = constructorParams.map { param ->
        // Find all flattened fields belonging to this parameter
        val paramFlattened = flattened.filter { field ->
            field.path.startsWith(param.name + "_") || field.path == param.name
        }

        // For nested VCs, extract the sub-structure
        val sourceVCFields = if (isMultiFieldVC(param.type) && paramFlattened.size > 1) {
            paramFlattened.map { field ->
                // Strip the parameter name prefix to get local path
                val localPath = if (field.path.startsWith(param.name + "_")) {
                    field.path.substring((param.name + "_").length)
                } else {
                    field.path
                }
                localPath to field.type
            }
        } else {
            null
        }

        // Find the actual symbol if this is a direct field (not nested)
        val actualSymbol = if (paramFlattened.size == 1 && sourceVCFields == null) {
            fieldPathToSymbol[paramFlattened[0].path]
        } else {
            null
        }

        Type.ExpandedField(
            name = param.name,
            type = param.type,
            sourceVC = if (isMultiFieldVC(param.type)) param.type as? Type.JFClass else null,
            sourceVCFields = sourceVCFields,
            actualSymbol = actualSymbol  // For top-level primitive fields
        )
    }

    expandedInfo[assignment.variableSymbol.name] = Type.ExpandedInfo(fields, fieldPathToSymbol)

    // For each flattened field, create a nested field access expression
    val expandedArgs = flattened.map { field ->
        val pathComponents = field.path.split("_")

        // Find which constructor argument this path belongs to
        val matchingArgIndex = constructorParams.indexOfFirst { param ->
            field.path.startsWith(param.name + "_") || field.path == param.name
        }

        if (matchingArgIndex >= 0 && matchingArgIndex < ci.arguments.size) {
            var arg = ci.arguments[matchingArgIndex]
            
            // Remove the first component if it matches the arg parameter name
            val param = constructorParams[matchingArgIndex]
            val remainingPath = if (field.path.startsWith(param.name + "_")) {
                field.path.substring((param.name + "_").length).split("_")
            } else if (field.path == param.name) {
                emptyList()
            } else {
                field.path.split("_")
            }

            if (remainingPath.isEmpty()) {
                arg
            } else {
                // Resolve through expanded field symbols when the arg is a variable
                val resolvedFromVariable = if (arg is ExpressionNode.Variable) {
                    val fieldPath = remainingPath.joinToString("_")
                    expandedInfo[arg.variableSymbol.name]?.fieldSymbols?.get(fieldPath)
                        ?.let { ExpressionNode.Variable(it) }
                } else {
                    null
                }
                // When arg is a ConstructorInvocation, extract the matching constructor argument
                val resolvedFromConstructor = if (arg is ExpressionNode.ConstructorInvocation && resolvedFromVariable == null) {
                    val fieldName = remainingPath[0]
                    val fieldIndex = arg.cons.parameters.indexOfFirst { it.name == fieldName }
                    if (fieldIndex >= 0 && fieldIndex < arg.arguments.size) {
                        val fieldArg = arg.arguments[fieldIndex]
                        if (remainingPath.size == 1) fieldArg
                        else createNestedFieldAccess(remainingPath.drop(1), fieldArg)
                    } else null
                } else null
                resolvedFromVariable ?: resolvedFromConstructor ?: createNestedFieldAccess(remainingPath, arg)
            }
        } else {
            error("Could not match field path ${field.path} to constructor arguments")
        }
    }

    // Create separate Assignment for each expanded variable (val for val, var for var)
    return expandedVars.mapIndexed { index, expandedVar ->
        if (assignment.variableSymbol.mutable) {
            ExpressionNode.VarAssignment(expandedVar, expandedArgs[index])
        } else {
            ExpressionNode.ValAssignment(expandedVar, expandedArgs[index])
        }
    }
}
