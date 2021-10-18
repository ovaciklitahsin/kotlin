fun foo() = runBlocking {
    val f: suspend (String) -> Int = <!INITIALIZER_TYPE_MISMATCH!>{
        42
    }<!>
}

fun <T> runBlocking(block: suspend Any.() -> T): T = null!!

