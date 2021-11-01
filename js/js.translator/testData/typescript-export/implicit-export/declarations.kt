// CHECK_TYPESCRIPT_DECLARATIONS
// RUN_PLAIN_BOX_FUNCTION
// SKIP_MINIFICATION
// SKIP_NODE_JS
// INFER_MAIN_MODULE
// MODULE: JS_TESTS
// FILE: declarations.kt

package foo

data class NonExportedType(val value: Int)

@JsExport
fun producer(value: Int): NonExportedType {
    return NonExportedType(value)
}

@JsExport
fun consumer(value: NonExportedType): Int {
    return value.value
}

@JsExport
class ExportedType(var value: NonExportedType) {
    fun <T: NonExportedType> increment(t: T): NonExportedType {
       return t.copy(value = t.value + 1)
    }
}