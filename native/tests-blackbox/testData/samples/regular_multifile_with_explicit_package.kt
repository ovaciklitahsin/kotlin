// FILE: addition.kt
package samples.regular_multifile_with_explicit_package

import kotlin.test.*

@Test
fun addition() {
    assertEquals(42, 40 + 2)
}

// FILE: multiplication.kt
package samples.regular_multifile_with_explicit_package.a

import kotlin.test.*

@Test
fun multiplication () {
    assertEquals(42, 21 * 2)
}

// FILE: subtraction.kt
package samples.regular_multifile_with_explicit_package.b

import kotlin.test.*

@Test
fun subtraction () {
    assertEquals(42, 50 - 8)
}

// FILE: division.kt
package samples.regular_multifile_with_explicit_package.a.b

import kotlin.test.*

@Test
fun division () {
    assertEquals(42, 126 / 3)
}
