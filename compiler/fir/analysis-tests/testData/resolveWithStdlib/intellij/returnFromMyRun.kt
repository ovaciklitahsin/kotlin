inline fun <T> myRun(f: () -> T): T = f()

fun foo(): Int {
    myRun {
        return 24
    }
}

fun bar(arg: Boolean): Int {
    if (arg) {
        return 42
    } else {
        myRun {
            return 24
        }
    }
<!NO_RETURN_IN_FUNCTION_WITH_BLOCK_BODY!>}<!>
