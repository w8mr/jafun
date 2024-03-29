## Data class return

fun test(a: Int): (text: String, calc: double) {
    ("some string", 5.0)
}

implementation

data class testResult(val text: String, val calc: double)

fun test(a: Int): testResult {
    testResult("some string", 5.0)
}


## Destructuring

data class Person(firstname: String, lastname: String, age: Int)
val p = Person("John", "Smith", 30)
val Person(firstname, _) = p 

// val firstname = p._1

## Pattern matching

when (p) {
    Person(_, _, 1) -> "Person is one"
    Person(f, "Smith", _) -> "Person has common last name, but firstname is $f"
    Person(_, _, _ >= 10 && _ < 18) -> "Person is over 10 but below 18"
    Person(_, _, a >= 18) -> "Person is adult and $a years old"
}

implementation

when (p) {
    p is Person && p._3 == 1 -> "Person is one"
    p is Person && (val f = p._1; true) && p._2 == "Smith") -> "Person has common last name, but firstname is $f"
    p is Person && (p._3 >= 10 && p._3 < 18) -> "Person is over 10 but below 18"
    p is Person && (val a = p._3; a >= 18) -> "Person is adult and $a years old"
}
    