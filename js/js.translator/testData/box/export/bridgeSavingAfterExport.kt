// MODULE: lib
// FILE: lib.kt
open class A {
    open fun foo(): Any? = "O"
}

class B: A() {
    override fun foo(): Any = "K"
}

// MODULE: main(lib)
// FILE: main.kt
fun box(): String {
    val a = A()
    val b = B()
    val o = a.foo()?.toString() ?: ""
    val k = b.foo()?.toString() ?: ""
    return o + k
}
