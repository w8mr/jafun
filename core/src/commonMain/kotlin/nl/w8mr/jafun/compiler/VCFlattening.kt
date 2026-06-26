package nl.w8mr.jafun.compiler

import nl.w8mr.jafun.OperandType
import nl.w8mr.jafun.Type
import nl.w8mr.jafun.compiler.ExpressionNode

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
 * Gets the effective JVM type for a variable.
 * For non-VC types, returns the type itself.
 * For single-field VC types, recursively unwraps to the innermost type.
 * For multi-field VC types, returns the VC type itself.
 */
fun effectiveVariableType(type: OperandType<*>): OperandType<*> {
    if (type !is Type.JFClass || type.kind != Type.ClassKind.VALUE_CLASS) {
        return type
    }
    
    val cons = type.constructor ?: return type
    
    // Multi-field VC - can't unwrap further
    if (cons.parameters.size > 1) {
        return type
    }
    
    // Single-field VC - unwrap one level
    return effectiveVariableType(cons.parameters[0].type)
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
 * Generate JVM signature character for a type
 */
fun signatureForType(type: OperandType<*>): String = when (type) {
    OperandType.SInt32 -> "I"
    OperandType.StringType -> "Ljava/lang/String;"
    is Type.JFClass -> "L${type.path.replace('.', '/')};"
    else -> "V"
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
 * Determines whether a value class parameter should be expanded into its fields.
 * Multi-field VCs always expand. Single-field VCs wrapping other VCs also expand.
 */
fun shouldExpandVC(vc: Type.JFClass): Boolean {
    val cons = vc.constructor ?: return false
    if (cons.parameters.size > 1) return true

    val fieldType = cons.parameters.singleOrNull()?.type ?: return false
    if (fieldType is Type.JFClass && fieldType.kind == Type.ClassKind.VALUE_CLASS) {
        return true
    }

    return true
}

/**
 * Reconstructs a VC object from its expanded primitive fields.
 * For multi-field VCs, creates a ConstructorInvocation with reconstructed args.
 * Returns null if reconstruction isn't possible (e.g., missing symbols).
 */
fun reconstructVCFromExpanded(
    vcType: Type.JFClass,
    expandedFieldSymbols: Map<String, Type.JFVariableSymbol>,
    pathPrefix: String = ""
): ExpressionNode.Phase2_3Expression? {
    val cons = vcType.constructor ?: return null
    val constructorArgs = mutableListOf<ExpressionNode.Phase2_3Expression>()
    for (fieldParam in cons.parameters) {
        val fieldPath = if (pathPrefix.isEmpty()) fieldParam.name else "${pathPrefix}_${fieldParam.name}"
        val fieldType = fieldParam.type

        val arg: ExpressionNode.Phase2_3Expression?
        if (fieldType is Type.JFClass && fieldType.kind == Type.ClassKind.VALUE_CLASS) {
            arg = reconstructVCFromExpanded(fieldType, expandedFieldSymbols, fieldPath)
        } else {
            if (fieldPath in expandedFieldSymbols) {
                arg = ExpressionNode.Variable(expandedFieldSymbols[fieldPath]!!)
            } else {
                arg = null
            }
        }
        if (arg == null) return null
        constructorArgs.add(arg)
    }
    return ExpressionNode.ConstructorInvocation(cons, constructorArgs)
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
        if (fieldType is Type.JFClass && fieldType.kind == Type.ClassKind.VALUE_CLASS) {
            val fieldCons = fieldType.constructor
            if (fieldCons != null && fieldCons.parameters.size > 1) {
                return listOf(Parameter(type, baseName))
            }
        }
        return listOf(Parameter(type, baseName))
    }

    return cons.parameters.flatMap { fieldParam ->
        val fieldName = fieldParam.name
        val newBaseName = if (baseName != null) "${baseName}_${fieldName}" else fieldName
        expandParameterRecursively(fieldParam.type, newBaseName)
    }
}

/**
 * Unwraps a type to its effective JVM representation.
 * Single-field inline VCs are unwrapped recursively to their underlying type.
 * Multi-field VCs and non-VC types are returned unchanged.
 */
fun effectiveJvmType(type: OperandType<*>): OperandType<*> {
    if (type is Type.JFClass && type.isInlineValueClass) {
        return effectiveJvmType(type.constructor!!.parameters.single().type)
    }
    return type
}
