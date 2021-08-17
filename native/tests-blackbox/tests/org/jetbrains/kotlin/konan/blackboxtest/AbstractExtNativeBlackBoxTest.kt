/*
 * Copyright 2010-2021 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.konan.blackboxtest

import com.intellij.testFramework.TestDataFile
import org.jetbrains.kotlin.test.services.JUnit5Assertions.fail

abstract class AbstractExtNativeBlackBoxTest {
    fun runTest(@TestDataFile testDataFilePath: String) {
        val testDataFile = getAbsoluteFile(testDataFilePath)
        fail { "Not implemented yet: $testDataFile" }
    }
}
