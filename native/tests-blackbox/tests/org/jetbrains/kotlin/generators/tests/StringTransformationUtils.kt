/*
 * Copyright 2010-2021 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.generators.tests

import org.jetbrains.kotlin.test.services.JUnit5Assertions.assertTrue

internal inline fun String.transformByLines(transform: (String) -> String?): String =
    lines().mapNotNull(transform).joinToString("\n")

internal fun String.insertAfterIndentation(insertion: String): String {
    val nonWhitespaceCharIndex = indexOfFirst { !it.isWhitespace() }
    return when {
        nonWhitespaceCharIndex > 0 -> insert(nonWhitespaceCharIndex, insertion)
        else -> insertion + this
    }
}

internal fun String.insert(insertionIndex: Int, insertion: String): String {
    assertTrue(insertionIndex in 0 until length) { "Bad insertion index $insertionIndex for string [$this]." }
    return substring(0, insertionIndex) + insertion + substring(insertionIndex)
}

internal fun String.computeIndentation(): String = buildString {
    for (ch in this@computeIndentation) {
        if (ch.isWhitespace()) append(ch) else break
    }
}
