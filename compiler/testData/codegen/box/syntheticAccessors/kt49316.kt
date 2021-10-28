// FILE: kt49316.kt
import foo.*

// This test should become irrelevant after KT-35565 is fixed.

fun test(foo: Foo): String {
    return foo.s

    // VAL_REASSIGNMENT not reported in unreachable code.
    // Make sure there's no BE internal error here.
    foo.s = "oops"
}

fun box() = test(Foo("OK"))

// FILE: Foo.kt
package foo

class Foo(val s: String)
