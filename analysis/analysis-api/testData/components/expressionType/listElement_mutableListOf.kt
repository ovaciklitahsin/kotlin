// WITH_STDLIB
fun foo() {
    val lst = mutableListOf<List<*>>()
    <expr>lst[0]</expr> = emptyList<Any>()
}
