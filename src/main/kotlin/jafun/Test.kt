package jafun

/*import kotlinx.coroutines.delay

data class Employee(val name: String, val age: Int, val salary: Int)

data class Manager(val name: String, val age: Int, val employees: List<Employee>)

fun test() {
    val managers =
        listOf(
            Manager(
                "Jan",
                22,
                listOf(
                    Employee("Piet", 50, 30000),
                    Employee("Bert", 39, 40000),
                ),
            ),
            Manager(
                "Klaas",
                45,
                listOf(
                    Employee("Ellen", 47, 45000),
                    Employee("Wim", 37, 35000),
                ),
            ),
        )

    println(managers.asSequence().filter { it.age > 40 }.flatMap { it.employees }.filter { it.age > 40 }.map { it.salary }.sum())

    val iterator = managers.iterator()
    val i =
        object : Iterator<Int> {
            val unknown = -1
            val empty = 0
            val stay = 1
            val next = 2

            var state = unknown
            var item: Int? = null

            var employeeIter: Iterator<Employee>? = null
            var employeeState = unknown

            private fun calc() {
                while (iterator.hasNext()) {
                    val manager = iterator.next()
                    if (!(manager.age<40)) continue
                    if (employeeState == unknown) employeeIter = manager.employees.iterator()
                }
                state = empty
            }

            override fun hasNext(): Boolean {
                if (state == unknown) calc()
                return state == next
            }

            override fun next(): Int {
                while (true) {
                    iterator.next()
                }
            }
        }

    fun next(iterator: Iterator<Manager>) {
    }

    println(boolean(true))
}

fun main(args: Array<String>) {
    test()
}

suspend fun sus() {
    println("test")
    delay(100L)
    println("test2")
}

fun testExpression(
    a: Int,
    b: Int,
): Int {
    a + b
    a + b
    a + b
    return a + b
}

fun boolean(a: Boolean): Boolean {
    val b = false
    return a && b
}

fun whenExample(a: Int) {
    println(
        when {
            a == 1 -> "One"
            a == 2 -> "Two"
            a == 3 -> "Three"
            a == 5 -> "Five"
            a == 6 -> "SixF"
            a == 10 -> "Ten"
            a == 100 -> "Hundred"
            a == 127 -> "127"
            a == 128 -> "128"
            a == 200 -> "Two Hundred"
            a == 32767 -> "32767"
            a == 32768 -> "32768"
            a == 0 -> "Zero"
            a == -1 -> "Miuns one"
            a == -2 -> "Minus two"
            a == -128 -> "-128"
            a == -129 -> "-129"
            a == -32768 -> "-32768"
            a == -32769 -> "-32769"

            a > 100000 -> "Large"
            a < -100000 -> "Small"
            else -> "More or less"
        },
    )
}
*/

fun main(args: Array<String>) {
    whenTest(2)
}

fun whenTest(a: Int) {
    println(
        when (a) {
            1 -> "true"
            else -> "false"
        },
    )
}
