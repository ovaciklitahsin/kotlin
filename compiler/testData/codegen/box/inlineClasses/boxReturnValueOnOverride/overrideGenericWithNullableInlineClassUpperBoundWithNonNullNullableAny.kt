// WITH_RUNTIME

@Suppress("OPTIONAL_DECLARATION_USAGE_IN_NON_COMMON_SOURCE")
@kotlin.jvm.JvmInline
value class X(val x: Any?)

interface IFoo<out T : X?> {
    fun foo(): T
}

class Test : IFoo<X> {
    override fun foo(): X = X("OK")
}

fun box(): String {
    val t1: IFoo<X?> = Test()
    val x1 = t1.foo()
    if (x1 != X("OK")) throw AssertionError("x1: $x1")

    val t2 = Test()
    val x2 = t2.foo()
    if (x2 != X("OK")) throw AssertionError("x2: $x2")

    return "OK"
}