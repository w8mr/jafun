package nl.w8mr.jafun.debug

import nl.w8mr.kasmine.ClassDef
import nl.w8mr.kasmine.ConstantPoolType
import nl.w8mr.kasmine.ConstantPoolType.ClassEntry
import nl.w8mr.kasmine.ConstantPoolType.ConstantInteger
import nl.w8mr.kasmine.Instruction
import nl.w8mr.kasmine.Instruction.NoArgument
import nl.w8mr.kasmine.Instruction.OneArgument
import nl.w8mr.kasmine.Instruction.OneArgumentByte
import nl.w8mr.kasmine.Instruction.OneArgumentShort
import nl.w8mr.kasmine.Instruction.OneArgumentUByte
import nl.w8mr.kasmine.Instruction.TwoArgument
import nl.w8mr.kasmine.ConstantPoolType.ConstantString
import nl.w8mr.kasmine.ConstantPoolType.FieldRef
import nl.w8mr.kasmine.ConstantPoolType.MethodRef
import nl.w8mr.kasmine.ConstantPoolType.UTF8String
import nl.w8mr.kasmine.InstructionBlock
import nl.w8mr.kasmine.MethodDef
import nl.w8mr.kasmine.Opcode

fun ClassDef.print() = Indenter().let { it.print(this) ; it.toString() }

fun Indenter.print(classDef: ClassDef) {
    -"class "
    -classDef.classRef.nameRef.value
    +"{"
    indent {
        classDef.methods.forEach { print(it) }
    }
    +"}"
}

fun Indenter.print(methodDef: MethodDef) {
    -"method "
    print(methodDef.methodName)
    +"{"
    indent {
        methodDef.instructions.forEach { print(it) }
    }
    +"}"
}

fun Indenter.print(instruction: Instruction) = when (instruction) {
    is NoArgument -> print(instruction)
    is OneArgument<*> -> print(instruction)
    is TwoArgument<*,*> -> print(instruction)
    else -> TODO("Implement for ${instruction.javaClass.simpleName}")
}

fun Indenter.print(instruction: NoArgument) {
    print(instruction.opcode)
    -"("
    +")"
}

fun <T: Any> Indenter.print(instruction: OneArgument<T>) {
    print(instruction.opcode)
    -" "
    print(instruction.value)
    +""
}

fun <T: Any, R: Any> Indenter.print(instruction: TwoArgument<T, R>) {
    print(instruction.opcode)
    -" "
    print(instruction.value1)
    -", "
    print(instruction.value2)
    +""
}


fun Indenter.print(method: MethodRef) {
    print(method.classRef)
    -"/"
    print(method.nameAndTypeRef.nameRef)
    print(method.nameAndTypeRef.typeRef)
}

fun Indenter.print(field: FieldRef) {
    print(field.classRef)
    -"/"
    print(field.nameAndTypeRef.nameRef)
    print(field.nameAndTypeRef.typeRef)
}

fun Indenter.print(string: ConstantString) {
    -"\""
    -string.nameRef.value
    -"\""
}

fun Indenter.print(integer: ConstantInteger) {
    -integer.value.toString()
}

fun Indenter.print(string: UTF8String) {
    -string.value
}

fun Indenter.print(opcode: Opcode) {
    -opcode.name
}

fun Indenter.print(classEntry: ClassEntry) {
    print(classEntry.nameRef)
}

fun Indenter.print(any: Any) = when (any) {
    is ClassEntry -> print(any)
    is Opcode -> print(any)
    is UTF8String -> print(any)
    is ConstantString -> print(any)
    is ConstantInteger -> print(any)
    is Byte, is UByte -> -any
    is Short, is UShort -> -any
    is MethodRef -> print(any)
    is FieldRef -> print(any)
    is InstructionBlock -> any.instructions.forEach { print(it) }
    else -> TODO("Implement for ${any.javaClass.simpleName}")
}