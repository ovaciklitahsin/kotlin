// MODULE: m1
// FILE: m1.kt

class MyHashSet<T> : HashSet<T>()

// MODULE: m2(m1)

fun <T> foo(arg: T): Int {
    val set = MyHashSet<T>()
    set.add(arg)
    return set.size
}

fun box(): String {
    val res = foo("")
    return if (res == 1) "OK" else "$res"
}