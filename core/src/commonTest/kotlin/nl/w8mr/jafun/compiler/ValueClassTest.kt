package nl.w8mr.jafun.nl.w8mr.jafun

import kotlin.test.Test

class ValueClassTest {

    @Test
    fun valueClass() {
        test {
            file {
                code = """
                    value class Address(street: String, number: Int, city: String)
                    
                    val test = Address("Test", 1, "Test")
                    println test.number
                """.trimIndent()
                expectedOutput = "1\n"
                jvmIr("Script") {
                    method {
                        name = "main"
                        signature = "([Ljava/lang/String;)V"
                        loadConstant("Test")
                        astore("test_street")
                        loadConstant(1)
                        istore("test_number")
                        loadConstant("Test")
                        astore("test_city")
                        iload("test_number")
                        invokeStatic("java/lang/Integer", "valueOf", "(I)Ljava/lang/Integer;")
                        invokeStatic("jafun/io/ConsoleKt", "println", "(Ljava/lang/Object;)V")
                        `return`()
                    }
                }
            }
        }
    }

    @Test
    fun valueClassFunctionParam() {
        test {
            file {
                code = """
                    value class Address(number: Int, street: String)
                    fun printNumber(input: Address) {
                        println input.number
                    }
                    val test = Address(1, "x")
                    printNumber(test)
                """.trimIndent()
                expectedOutput = "1\n"
                jvmIr("Script") {
                    method {
                        name = "main"
                        signature = "([Ljava/lang/String;)V"
                        loadConstant(1)
                        istore("test_number")
                        loadConstant("x")
                        astore("test_street")
                        iload("test_number")
                        aload("test_street")
                        invokeStatic("Script", "printNumber", "(ILjava/lang/String;)V")
                        `return`()
                    }
                    method {
                        name = "printNumber"
                        signature = "(ILjava/lang/String;)V"
                        parameter("input_number")
                        parameter("input_street")
                        iload("input_number")
                        invokeStatic("java/lang/Integer", "valueOf", "(I)Ljava/lang/Integer;")
                        invokeStatic("jafun/io/ConsoleKt", "println", "(Ljava/lang/Object;)V")
                        `return`()
                    }
                }
            }
        }
    }

    @Test
    fun functionParamReverseAccessOrder() {
        test {
            file {
                code = """
                    fun test() {
                        fun subtract(a: Int, b: Int): Int {
                            b - a
                        }
                        println subtract(3, 5)
                    }
                    test()
                """.trimIndent()
                expectedOutput = "2\n"
            }
        }
    }

    @Test
    fun functionParamForwardAccessOrder() {
        test {
            file {
                code = """
                    fun test() {
                        fun subtract(a: Int, b: Int): Int {
                            a - b
                        }
                        println subtract(3, 5)
                    }
                    test()
                """.trimIndent()
                expectedOutput = "-2\n"
            }
        }
    }

    @Test
    fun valueClassTwoParamsReverseFieldOrder() {
        test {
            file {
                code = """
                    value class A(x: Int, y: String)
                    value class B(p: String, q: Int)
                    fun test(a: A, b: B) {
                        println b.q
                        println a.y
                        println a.x
                        println b.p
                    }
                    val a = A(10, "hello")
                    val b = B("world", 20)
                    test(a, b)
                """.trimIndent()
                expectedOutput = "20\nhello\n10\nworld\n"
                jvmIr("Script") {
                    method {
                        name = "main"
                        signature = "([Ljava/lang/String;)V"
                        loadConstant(10)
                        istore("a_x")
                        loadConstant("hello")
                        astore("a_y")
                        loadConstant("world")
                        astore("b_p")
                        loadConstant(20)
                        istore("b_q")
                        iload("a_x")
                        aload("a_y")
                        aload("b_p")
                        iload("b_q")
                        invokeStatic("Script", "test", "(ILjava/lang/String;Ljava/lang/String;I)V")
                        `return`()
                    }
                    method {
                        name = "test"
                        signature = "(ILjava/lang/String;Ljava/lang/String;I)V"
                        parameter("a_x")
                        parameter("a_y")
                        parameter("b_p")
                        parameter("b_q")
                        iload("b_q")
                        invokeStatic("java/lang/Integer", "valueOf", "(I)Ljava/lang/Integer;")
                        invokeStatic("jafun/io/ConsoleKt", "println", "(Ljava/lang/Object;)V")
                        aload("a_y")
                        invokeStatic("jafun/io/ConsoleKt", "println", "(Ljava/lang/Object;)V")
                        iload("a_x")
                        invokeStatic("java/lang/Integer", "valueOf", "(I)Ljava/lang/Integer;")
                        invokeStatic("jafun/io/ConsoleKt", "println", "(Ljava/lang/Object;)V")
                        aload("b_p")
                        invokeStatic("jafun/io/ConsoleKt", "println", "(Ljava/lang/Object;)V")
                        `return`()
                    }
                }
            }
        }
    }

    @Test
    fun vcReturnMultiField() {
        test {
            file {
                code = """
                    value class Address(number: Int, street: String)
                    fun makeAddress(): Address {
                        Address(1, "street")
                    }
                    println makeAddress().number
                """.trimIndent()
                expectedOutput = "1\n"
                jvmIr("Script") {
                    method {
                        name = "main"
                        signature = "([Ljava/lang/String;)V"
                        invokeStatic("Script", "makeAddress", "()LAddress;")
                        getField("Address", "number", "I")
                        invokeStatic("java/lang/Integer", "valueOf", "(I)Ljava/lang/Integer;")
                        invokeStatic("jafun/io/ConsoleKt", "println", "(Ljava/lang/Object;)V")
                        `return`()
                    }
                    method {
                        name = "makeAddress"
                        signature = "()LAddress;"
                        `new`("Address")
                        dup()
                        loadConstant(1)
                        loadConstant("street")
                        invokeSpecial("Address", "<init>", "(ILjava/lang/String;)V")
                        areturn()
                    }
                }
            }
        }
    }

    @Test
    fun vcReturnMultiFieldWithVal() {
        test {
            file {
                code = """
                    value class Address(number: Int, street: String)
                    fun makeAddress(): Address {
                        Address(1, "street")
                    }
                    val a = makeAddress()
                    println a.number
                """.trimIndent()
                expectedOutput = "1\n"
                jvmIr("Script") {
                    method {
                        name = "main"
                        signature = "([Ljava/lang/String;)V"
                        invokeStatic("Script", "makeAddress", "()LAddress;")
                        astore("a")
                        aload("a")
                        getField("Address", "number", "I")
                        invokeStatic("java/lang/Integer", "valueOf", "(I)Ljava/lang/Integer;")
                        invokeStatic("jafun/io/ConsoleKt", "println", "(Ljava/lang/Object;)V")
                        `return`()
                    }
                    method {
                        name = "makeAddress"
                        signature = "()LAddress;"
                        `new`("Address")
                        dup()
                        loadConstant(1)
                        loadConstant("street")
                        invokeSpecial("Address", "<init>", "(ILjava/lang/String;)V")
                        areturn()
                    }
                }
            }
        }
    }

    @Test
    fun vcReturnSingleField() {
        test {
            file {
                code = """
                    value class Id(value: Int)
                    fun makeId(): Id {
                        Id(42)
                    }
                    println makeId()
                """.trimIndent()
                expectedOutput = "42\n"
                jvmIr("Script") {
                    method {
                        name = "main"
                        signature = "([Ljava/lang/String;)V"
                        invokeStatic("Script", "makeId", "()I")
                        invokeStatic("java/lang/Integer", "valueOf", "(I)Ljava/lang/Integer;")
                        invokeStatic("jafun/io/ConsoleKt", "println", "(Ljava/lang/Object;)V")
                        `return`()
                    }
                    method {
                        name = "makeId"
                        signature = "()I"
                        loadConstant(42)
                        ireturn()
                    }
                }
            }
        }
    }

    @Test
    fun vcReturnSingleFieldWithVal() {
        test {
            file {
                code = """
                    value class Id(value: Int)
                    fun makeId(): Id {
                        Id(42)
                    }
                    val id = makeId()
                    println id
                """.trimIndent()
                expectedOutput = "42\n"
                jvmIr("Script") {
                    method {
                        name = "main"
                        signature = "([Ljava/lang/String;)V"
                        invokeStatic("Script", "makeId", "()I")
                        istore("id")
                        iload("id")
                        invokeStatic("java/lang/Integer", "valueOf", "(I)Ljava/lang/Integer;")
                        invokeStatic("jafun/io/ConsoleKt", "println", "(Ljava/lang/Object;)V")
                        `return`()
                    }
                    method {
                        name = "makeId"
                        signature = "()I"
                        loadConstant(42)
                        ireturn()
                    }
                }
            }
        }
    }

    @Test
    fun vcReturnSingleFieldFieldAccess() {
        test {
            file {
                code = """
                    value class Id(value: Int)
                    fun makeId(): Id {
                        Id(42)
                    }
                    println makeId().value
                """.trimIndent()
                expectedOutput = "42\n"
                jvmIr("Script") {
                    method {
                        name = "main"
                        signature = "([Ljava/lang/String;)V"
                        invokeStatic("Script", "makeId", "()I")
                        invokeStatic("java/lang/Integer", "valueOf", "(I)Ljava/lang/Integer;")
                        invokeStatic("jafun/io/ConsoleKt", "println", "(Ljava/lang/Object;)V")
                        `return`()
                    }
                    method {
                        name = "makeId"
                        signature = "()I"
                        loadConstant(42)
                        ireturn()
                    }
                }
            }
        }
    }

    @Test
    fun vcReturnSingleFieldFieldAccessWithVal() {
        test {
            file {
                code = """
                    value class Id(value: Int)
                    fun makeId(): Id {
                        Id(42)
                    }
                    val id = makeId()
                    println id.value
                """.trimIndent()
                expectedOutput = "42\n"
                jvmIr("Script") {
                    method {
                        name = "main"
                        signature = "([Ljava/lang/String;)V"
                        invokeStatic("Script", "makeId", "()I")
                        istore("id")
                        iload("id")
                        invokeStatic("java/lang/Integer", "valueOf", "(I)Ljava/lang/Integer;")
                        invokeStatic("jafun/io/ConsoleKt", "println", "(Ljava/lang/Object;)V")
                        `return`()
                    }
                    method {
                        name = "makeId"
                        signature = "()I"
                        loadConstant(42)
                        ireturn()
                    }
                }
            }
        }
    }

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
                jvmIr("Script") {
                    method {
                        name = "main"
                        signature = "([Ljava/lang/String;)V"
                        loadConstant(4)
                        istore("a_number")
                        loadConstant("Privet Drive")
                        astore("a_street")
                        iload("a_number")
                        aload("a_street")
                        invokeStatic("Script", "changeAddress", "(ILjava/lang/String;)LAddress;")
                        astore("b")
                        aload("b")
                        getField("Address", "number", "I")
                        invokeStatic("java/lang/Integer", "valueOf", "(I)Ljava/lang/Integer;")
                        invokeStatic("jafun/io/ConsoleKt", "println", "(Ljava/lang/Object;)V")
                        `return`()
                    }
                    method {
                        name = "increaseHouseNumber"
                        signature = "(ILjava/lang/String;I)LAddress;"
                        parameter("address_number")
                        parameter("address_street")
                        parameter("increment")
                        new("Address")
                        dup()
                        iload("address_number")
                        iload("increment")
                        iadd()
                        aload("address_street")
                        invokeSpecial("Address", "<init>", "(ILjava/lang/String;)V")
                        areturn()
                    }
                    method {
                        name = "changeAddress"
                        signature = "(ILjava/lang/String;)LAddress;"
                        parameter("address_number")
                        parameter("address_street")
                        iload("address_number")
                        aload("address_street")
                        loadConstant(5)
                        invokeStatic("Script", "increaseHouseNumber", "(ILjava/lang/String;I)LAddress;")
                        areturn()
                    }
                }
                jvmIr("Address") {
                    // Public fields for value class Address(number: Int, street: String)
                    field(access = 1u, "number", "I")
                    field(access = 1u, "street", "Ljava/lang/String;")
                    method {
                        name = "<init>"
                        signature = "(ILjava/lang/String;)V"
                        access = 1u  // ACC_PUBLIC (not static)
                        // Register local variables: this, number, street
                        // In kasmine, parameter() just creates local var slots
                        parameter("this")
                        parameter("number")
                        parameter("street")
                        // Emit constructor bytecode
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
                    method {
                        name = "toString"
                        signature = "()Ljava/lang/String;"
                        access = 1u
                        parameter("this")
                        new("java/lang/StringBuilder")
                        dup()
                        invokeSpecial("java/lang/StringBuilder", "<init>", "()V")
                        loadConstant("Address(")
                        invokeVirtual("java/lang/StringBuilder", "append", "(Ljava/lang/String;)Ljava/lang/StringBuilder;")
                        loadConstant("number=")
                        invokeVirtual("java/lang/StringBuilder", "append", "(Ljava/lang/String;)Ljava/lang/StringBuilder;")
                        aload("this")
                        getField("Address", "number", "I")
                        invokeVirtual("java/lang/StringBuilder", "append", "(I)Ljava/lang/StringBuilder;")
                        loadConstant(", ")
                        invokeVirtual("java/lang/StringBuilder", "append", "(Ljava/lang/String;)Ljava/lang/StringBuilder;")
                        loadConstant("street=")
                        invokeVirtual("java/lang/StringBuilder", "append", "(Ljava/lang/String;)Ljava/lang/StringBuilder;")
                        aload("this")
                        getField("Address", "street", "Ljava/lang/String;")
                        invokeVirtual("java/lang/StringBuilder", "append", "(Ljava/lang/String;)Ljava/lang/StringBuilder;")
                        loadConstant(")")
                        invokeVirtual("java/lang/StringBuilder", "append", "(Ljava/lang/String;)Ljava/lang/StringBuilder;")
                        invokeVirtual("java/lang/StringBuilder", "toString", "()Ljava/lang/String;")
                        areturn()
                    }
                    method {
                        name = "equals"
                        signature = "(Ljava/lang/Object;)Z"
                        access = 1u
                        parameter("thisRef")
                        parameter("other")
                        val returnFalse = label()
                        aload("other")
                        invokeStatic("java/util/Objects", "isNull", "(Ljava/lang/Object;)Z")
                        ifnotequal(returnFalse)
                        aload("thisRef")
                        invokeVirtual("java/lang/Object", "getClass", "()Ljava/lang/Class;")
                        invokeVirtual("java/lang/Class", "getName", "()Ljava/lang/String;")
                        aload("other")
                        invokeVirtual("java/lang/Object", "getClass", "()Ljava/lang/Class;")
                        invokeVirtual("java/lang/Class", "getName", "()Ljava/lang/String;")
                        invokeVirtual("java/lang/String", "equals", "(Ljava/lang/Object;)Z")
                        ifequal(returnFalse)
                        aload("thisRef")
                        invokeVirtual("java/lang/Object", "toString", "()Ljava/lang/String;")
                        aload("other")
                        invokeVirtual("java/lang/Object", "toString", "()Ljava/lang/String;")
                        invokeVirtual("java/lang/String", "equals", "(Ljava/lang/Object;)Z")
                        ifequal(returnFalse)
                        loadConstant(1)
                        ireturn()
                        returnFalse {
                            loadConstant(0)
                            ireturn()
                        }
                    }
                    method {
                        name = "hashCode"
                        signature = "()I"
                        access = 1u
                        parameter("thisRef")
                        new("java/lang/StringBuilder")
                        dup()
                        invokeSpecial("java/lang/StringBuilder", "<init>", "()V")
                        aload("thisRef")
                        getField("Address", "number", "I")
                        invokeVirtual("java/lang/StringBuilder", "append", "(I)Ljava/lang/StringBuilder;")
                        aload("thisRef")
                        getField("Address", "street", "Ljava/lang/String;")
                        invokeVirtual("java/lang/StringBuilder", "append", "(Ljava/lang/String;)Ljava/lang/StringBuilder;")
                        invokeVirtual("java/lang/StringBuilder", "toString", "()Ljava/lang/String;")
                        invokeVirtual("java/lang/String", "hashCode", "()I")
                        ireturn()
                    }
                }
            }
        }
    }

    @Test
    fun vcNestedIdInUser() {
        test {
            file {
                code = """
                    value class Id(value: Int)
                    value class User(id: Id, name: String)
                    fun getUserName(user: User): String {
                        user.name
                    }
                    val u = User(Id(42), "Alice")
                    println getUserName(u)
                """.trimIndent()
                expectedOutput = "Alice\n"
                jvmIr("Script") {
                    method {
                        name = "main"
                        signature = "([Ljava/lang/String;)V"
                        // Both User and Id are fully inlined at call site
                        // User(Id(42), "Alice") expands to just (42, "Alice")
                        loadConstant(42)           // id value (from Id)
                        istore("u_id_value")
                        loadConstant("Alice")      // name
                        astore("u_name")
                        iload("u_id_value")
                        aload("u_name")
                        invokeStatic("Script", "getUserName", "(ILjava/lang/String;)Ljava/lang/String;")
                        invokeStatic("jafun/io/ConsoleKt", "println", "(Ljava/lang/Object;)V")
                        `return`()
                    }
                    method {
                        name = "getUserName"
                        signature = "(ILjava/lang/String;)Ljava/lang/String;"
                        // Parameters are fully expanded: id (int) and name (String)
                        parameter("user_id")
                        parameter("user_name")
                         // Return user.name (second parameter)
                         aload("user_name")
                         areturn()
                     }
                 }
             }
         }
     }

    @Test
    fun vcNestedIdAccess() {
        test {
            file {
                code = """
                    value class Id(value: Int)
                    value class User(id: Id, name: String)
                    fun getId(user: User): Int {
                        user.id.value
                    }
                    val u = User(Id(42), "Alice")
                    println getId(u)
                """.trimIndent()
                expectedOutput = "42\n"
            }
        }
    }

     @Test
    fun testBoxSimple2() {
        test {
            file {
                code = """
                    value class Box(x: Int)
                    val b = Box(99)
                    println b.x
                """.trimIndent()
                expectedOutput = "99\n"
            }
        }
    }

    @Test
    fun testSimpleBoxAccess() {
        test {
            file {
                code = """
                    value class Point(x: Int, y: Int)
                    value class Box(topLeft: Point)
                    val p = Point(99, 10)
                    val b = Box(p)
                    println b.topLeft.x
                """.trimIndent()
                expectedOutput = "99\n"
            }
        }
    }

    @Test
    fun testBoxSingleField() {
        test {
            file {
                code = """
                    value class Point(x: Int, y: Int)
                    value class Box(topLeft: Point)
                    fun getPoint(box: Box): Point {
                        box.topLeft
                    }
                    val b = Box(Point(99, 10))
                    println getPoint(b).x
                """.trimIndent()
                expectedOutput = "99\n"
            }
        }
    }

    @Test
    fun testBoxSingleFieldWorking() {
        test {
            file {
                code = """
                    value class Point(x: Int, y: Int)
                    value class Box(topLeft: Point)
                    fun getPoint(box: Box): Point {
                        box.topLeft
                    }
                    println getPoint(Box(Point(99, 10))).x
                """.trimIndent()
                expectedOutput = "99\n"
            }
        }
    }


    @Test
    fun debugChainedAccess() {
        test {
            file {
                code = """
                    value class Point(x: Int, y: Int)
                    value class Box(topLeft: Point)
                    fun getX(box: Box): Int {
                        box.topLeft.x
                    }
                    val p = Point(99, 10)
                    val b = Box(p)
                    println getX(b)
                """.trimIndent()
                expectedOutput = "99\n"
            }
        }
    }

    @Test
    fun testMultiFieldVCFieldAccess() {
        test {
            file {
                code = """
                    value class Point(x: Int, y: Int)
                    fun getX(p: Point): Int {
                        p.x
                    }
                    val p = Point(42, 10)
                    println getX(p)
                """.trimIndent()
                expectedOutput = "42\n"
            }
        }
    }

    @Test
    fun vcAssignmentWithFunctionCallInConstructor() {
        test {
            file {
                code = """
                    value class Point(x: Int, y: Int)
                    fun getX(p: Point): Int {
                        p.x
                    }
                    val p = Point(getX(Point(5, 6)), 7)
                    println p.x
                """.trimIndent()
                expectedOutput = "5\n"
            }
        }
    }

    @Test
    fun vcChainedNestedAccess() {
        test {
            file {
                code = """
                    value class Point(x: Int, y: Int)
                    value class Box(topLeft: Point, bottomRight: Point)
                    fun getX(box: Box): Int {
                        box.topLeft.x
                    }
                    val b = Box(Point(99,10), Point(100,11))
                    println getX(b)
                """.trimIndent()
                expectedOutput = "99\n"
            }
        }
    }

    @Test
    fun testMultiArgReconstruction() {
        test {
            file {
                code = """
                    value class Point(x: Int, y: Int)
                    value class Box(topLeft: Point)
                    fun selectFirst(b: Box, p: Point): Point {
                        b.topLeft
                    }
                    val b = Box(Point(99, 10))
                    val p = Point(999, 1)
                    println selectFirst(b, p).x
                """.trimIndent()
                expectedOutput = "99\n"
            }
        }
    }

    @Test
    fun testVarWithInlineVC() {
        test {
            file {
                code = """
                    value class Box(x: Int)
                    var b = Box(99)
                    println b.x
                    b = Box(100)
                    println b.x
                """.trimIndent()
                expectedOutput = "99\n100\n"
            }
        }
    }

    @Test
    fun testVarWithMultiFieldVC() {
        test {
            file {
                code = """
                    value class Point(x: Int, y: Int)
                    var p = Point(1, 2)
                    println p.x
                    p = Point(3, 4)
                    println p.x
                """.trimIndent()
                expectedOutput = "1\n3\n"
            }
        }
    }

    @Test
    fun vcDeepNesting() {
        test {
            file {
                code = """
                    value class A(value: Int)
                    value class B(a: A)
                    value class C(b: B)
                    fun getValue(c: C): Int {
                        c.b.a.value
                    }
                    val c = C(B(A(42)))
                    println getValue(c)
                """.trimIndent()
                expectedOutput = "42\n"
            }
        }
    }

    @Test
    fun vcDeeperNesting() {
        test {
            file {
                code = """
                    value class A(value: Int)
                    value class B(a: A)
                    value class C(b: B)
                    value class D(c: C)
                    fun getValue(d: D): Int {
                        d.c.b.a.value
                    }
                    val d = D(C(B(A(42))))
                    println getValue(d)
                """.trimIndent()
                expectedOutput = "42\n"
            }
        }
    }

    @Test
    fun vcReconstructWithToString() {
        test {
            file {
                code = """
                    value class Address(street: String, number: Int)

                    fun show(a: Address) {
                        println a
                    }

                    val a = Address("Privet drive", 4)
                    show(a)
                """.trimIndent()
                expectedOutput = "Address(street=Privet drive, number=4)\n"
            }
        }
    }

    @Test
    fun vcFieldAccessOnCallResult() {
        test {
            file {
                code = """
                    value class Point(x: Int, y: Int)
                    value class Box(topLeft: Point)
                    fun getTopLeft(b: Box): Point {
                        b.topLeft
                    }
                    val b = Box(Point(99, 10))
                    println getTopLeft(b).x
                """.trimIndent()
                expectedOutput = "99\n"
            }
        }
    }

    @Test
    fun vcEqualsPositive() {
        test {
            file {
                code = """
                    value class Address(street: String, number: Int)
                    val a1 = Address("Main St", 42)
                    val a2 = Address("Main St", 42)
                    println objectEquals(a1, a2)
                """.trimIndent()
                expectedOutput = "true\n"
            }
        }
    }

    @Test
    fun vcEqualsNegative() {
        test {
            file {
                code = """
                    value class Address(street: String, number: Int)
                    val a1 = Address("Main St", 42)
                    val a2 = Address("Other St", 99)
                    println objectEquals(a1, a2)
                """.trimIndent()
                expectedOutput = "false\n"
            }
        }
    }

    @Test
    fun vcDeepNestingEquals() {
        test {
            file {
                code = """
                    value class Point(x: Int, y: Int)
                    value class Box(topLeft: Point)
                    val b1 = Box(Point(10, 20))
                    val b2 = Box(Point(10, 20))
                    println objectEquals(b1, b2)
                """.trimIndent()
                expectedOutput = "true\n"
            }
        }
    }

    @Test
    fun vcHashCodeEqualObjects() {
        test {
            file {
                code = """
                    value class Address(street: String, number: Int)
                    val a1 = Address("Main St", 42)
                    val a2 = Address("Main St", 42)
                    println objectHash(a1)
                    println objectHash(a2)
                """.trimIndent()
                expectedOutput = "1693788838\n1693788838\n"
            }
        }
    }

    @Test
    fun vcHashCodeDifferentObjects() {
        test {
            file {
                code = """
                    value class Address(street: String, number: Int)
                    val a1 = Address("Main St", 42)
                    val a2 = Address("Other St", 99)
                    println objectHash(a1)
                    println objectHash(a2)
                """.trimIndent()
                expectedOutput = "1693788838\n1752083601\n"
            }
        }
    }

    @Test
    fun vcVarNestedCtor() {
        test {
            file {
                code = """
                    value class Point(x: Int, y: Int)
                    value class Line(start: Point, end: Point)
                    fun mid(l: Line): Int { l.start.x + l.end.x }
                    val p = Point(1, 2)
                    val q = Point(3, 4)
                    println mid(Line(p, q))
                """.trimIndent()
                expectedOutput = "4\n"
            }
        }
    }

    @Test
    fun vcGreedyCapToFit() {
        test {
            file {
                code = """
                    value class Row(c1: Int, c2: Int, c3: Int, c4: Int, c5: Int, c6: Int, c7: Int, c8: Int)
                    value class Table(r1: Row, r2: Row, r3: Row, r4: Row, r5: Row, r6: Row, r7: Row, r8: Row)
                    
                    fun sum(a: Table, b: Table, c: Table, d: Table): Int {
                        a.r1.c1 + b.r1.c1 + c.r1.c1 + d.r1.c1
                    }
                    val r1 = Row(1,1,1,1,1,1,1,1)
                    val r2 = Row(2,2,2,2,2,2,2,2)
                    val r3 = Row(3,3,3,3,3,3,3,3)
                    val r4 = Row(4,4,4,4,4,4,4,4)
                    println sum(Table(r1,r1,r1,r1,r1,r1,r1,r1),Table(r2,r2,r2,r2,r2,r2,r2,r2),Table(r3,r3,r3,r3,r3,r3,r3,r3),Table(r4,r4,r4,r4,r4,r4,r4,r4))
                """.trimIndent()
                expectedOutput = "10\n"
            }
        }
    }
}
