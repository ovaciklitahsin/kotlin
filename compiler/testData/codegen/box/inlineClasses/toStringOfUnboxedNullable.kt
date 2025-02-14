// WITH_RUNTIME
// IGNORE_BACKEND: JVM
// IGNORE_LIGHT_ANALYSIS
@Suppress("OPTIONAL_DECLARATION_USAGE_IN_NON_COMMON_SOURCE")
@kotlin.jvm.JvmInline
value class IC(val x: String)

fun IC?.foo() = toString() // `IC?` unboxed into `String?`
fun IC?.bar() = "$this"

fun assertEquals(a: String, b: String) {
    if (a != b) throw AssertionError("$a != $b")
}

fun box(): String {
    assertEquals((null as IC?).foo(), "null")
    assertEquals((null as IC?).foo(), (null as IC?).toString())
    assertEquals((null as IC?).foo(), (null as IC?).bar())
    assertEquals(IC("x").foo(), "IC(x=x)")
    assertEquals(IC("x").foo(), IC("x").toString())
    assertEquals(IC("x").foo(), IC("x").bar())
    return "OK"
}
