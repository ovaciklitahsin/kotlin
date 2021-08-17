/*
 * Copyright 2010-2021 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.konan.blackboxtest

import org.jetbrains.kotlin.test.TestMetadata
import org.jetbrains.kotlin.test.services.JUnit5Assertions.fail
import org.jetbrains.kotlin.test.services.JUnit5Assertions.assertEquals
import org.jetbrains.kotlin.utils.addToStdlib.cast
import org.junit.jupiter.api.extension.BeforeTestExecutionCallback
import org.junit.jupiter.api.extension.ExtensionContext
import java.io.File

class NativeBlackBoxTestSupport : BeforeTestExecutionCallback {
    override fun beforeTestExecution(extensionContext: ExtensionContext) = with(extensionContext) {
        enclosingTestInstance.blackBoxTestProvider = getOrCreateBlackBoxTestProvider()
    }

    companion object {
        private val NAMESPACE = ExtensionContext.Namespace.create(NativeBlackBoxTestSupport::class.java.simpleName)

        /** Creates a single instance of [TestProvider] per test class. */
        private fun ExtensionContext.getOrCreateBlackBoxTestProvider(): TestProvider =
            root.getStore(NAMESPACE).getOrComputeIfAbsent(enclosingTestClass.sanitizedName) { sanitizedName ->
                val globalEnvironment = GlobalTestEnvironment() // Create with the default settings.

                val testRoots = computeTestRoots()

                val testSourcesDir = globalEnvironment.baseBuildDir
                    .resolve("own-test-sources")
                    .resolve(sanitizedName).apply {
                        makeEmptyDirectory() // Clean-up the directory with all potentially stale generated sources.
                    }

                val testBinariesDir = globalEnvironment.baseBuildDir
                    .resolve("own-test-binaries")
                    .resolve(globalEnvironment.target.name)
                    .resolve(sanitizedName).apply {
                        makeEmptyDirectory() // Clean-up the directory with all potentially stale executables.
                    }

                createBlackBoxTestProvider(TestEnvironment(globalEnvironment, testRoots, testSourcesDir, testBinariesDir))
            }.cast()

        private val ExtensionContext.enclosingTestInstance: AbstractNativeBlackBoxTest
            get() = requiredTestInstances.enclosingInstances.first().cast()

        private val ExtensionContext.enclosingTestClass: Class<*>
            get() = generateSequence(requiredTestClass) { it.enclosingClass }.last()

        private fun ExtensionContext.computeTestRoots(): TestRoots {
            val enclosingTestClass = enclosingTestClass

            val testRoots: Set<File> = when (val outermostTestMetadata = enclosingTestClass.getAnnotation(TestMetadata::class.java)) {
                null -> {
                    enclosingTestClass.declaredClasses.mapNotNullTo(mutableSetOf()) { nestedClass ->
                        nestedClass.getAnnotation(TestMetadata::class.java)?.testRoot
                    }
                }
                else -> setOf(outermostTestMetadata.testRoot)
            }

            val baseDir: File = when (testRoots.size) {
                0 -> fail { "No test roots found for $enclosingTestClass test class." }
                1 -> testRoots.first().parentFile
                else -> {
                    val baseDirs = testRoots.mapTo(mutableSetOf()) { it.parentFile }
                    assertEquals(1, baseDirs.size) {
                        "Controversial base directories computed for test roots for $enclosingTestClass test class: $baseDirs"
                    }

                    baseDirs.first()
                }
            }

            return TestRoots(testRoots, baseDir)
        }

        private val TestMetadata.testRoot: File get() = getAbsoluteFile(localPath = value)
    }
}
