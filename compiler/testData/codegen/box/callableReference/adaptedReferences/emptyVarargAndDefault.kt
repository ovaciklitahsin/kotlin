// DONT_TARGET_EXACT_BACKEND: WASM
// WASM_MUTE_REASON: IGNORED_IN_JS

fun foo(x: String = "O", vararg y: String): String =
        if (y.size == 0) x + "K" else "Fail"

fun call(f: () -> String): String = f()

fun box(): String {
    return call(::foo)
}
