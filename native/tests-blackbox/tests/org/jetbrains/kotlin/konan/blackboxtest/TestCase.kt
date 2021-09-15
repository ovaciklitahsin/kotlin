/*
 * Copyright 2010-2021 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.konan.blackboxtest

import org.jetbrains.kotlin.test.services.JUnit5Assertions.assertTrue
import org.jetbrains.kotlin.test.services.JUnit5Assertions.fail
import java.io.File

internal typealias PackageName = String

internal data class TestFile(
    val location: File,
    val contents: String,
    val module: TestModule
) {
    init {
        module.files += this
    }
}

internal class TestModule(
    val name: String,
    val dependencySymbols: Set<String>,
    val friendSymbols: Set<String>
) {
    lateinit var dependencies: Set<TestModule>
    lateinit var friends: Set<TestModule>

    val files = mutableListOf<TestFile>()

    companion object {
        fun Collection<TestModule>.initializeModules() {
            val mapping: Map</* module name */ String, TestModule> = toSet()
                .groupingBy { module -> module.name }
                .aggregate { name, _: TestModule?, module, isFirst ->
                    assertTrue(isFirst) { "Multiple test modules with the same name found: $name" }
                    module
                }

            fun findModule(name: String): TestModule = mapping[name] ?: fail { "Module $name not found" }
            fun Set<String>.findModulesForSymbols(): Set<TestModule> = mapTo(mutableSetOf(), ::findModule)

            mapping.values.forEach { module ->
                with(module) {
                    if (dependencySymbols.isEmpty() && friendSymbols.isEmpty()) {
                        dependencies = emptySet()
                        friends = emptySet()
                    } else {
                        dependencies = dependencySymbols.findModulesForSymbols()
                        friends = friendSymbols.findModulesForSymbols()
                    }
                }
            }
        }
    }
}

/**
 *         TestCase
 *         /      \
 * Composite      Simple
 *    |           /    \
 *    +---> Regular    Standalone
 *                     /        \
 *        WithTestRunner        WithoutTestRunner
 */
internal sealed interface TestCase {
    val files: List<TestFile>
    val freeCompilerArgs: TestCompilerArgs

    sealed class Simple(
        override val files: List<TestFile>,
        override val freeCompilerArgs: TestCompilerArgs,
        val testDataFile: File, // The origin of the test case.
        val outputData: String?
    ) : TestCase

    class Regular(
        files: List<TestFile>,
        freeCompilerArgs: TestCompilerArgs,
        testDataFile: File,
        outputData: String?,
        val packageName: PackageName
    ) : Simple(files, freeCompilerArgs, testDataFile, outputData)

    sealed class Standalone(files: List<TestFile>, freeCompilerArgs: TestCompilerArgs, testDataFile: File, outputData: String?) :
        Simple(files, freeCompilerArgs, testDataFile, outputData) {

        class WithTestRunner(files: List<TestFile>, freeCompilerArgs: TestCompilerArgs, testDataFile: File, outputData: String?) :
            Standalone(files, freeCompilerArgs, testDataFile, outputData)

        class WithoutTestRunner(
            files: List<TestFile>,
            freeCompilerArgs: TestCompilerArgs,
            testDataFile: File,
            val inputData: String?,
            outputData: String?,
            val entryPoint: String
        ) : Standalone(files, freeCompilerArgs, testDataFile, outputData)
    }

    class Composite(regularTestCases: List<Regular>) : TestCase {
        override val files: List<TestFile> = regularTestCases.flatMap { it.files }
        override val freeCompilerArgs: TestCompilerArgs = regularTestCases.firstOrNull()?.freeCompilerArgs
            ?: TestCompilerArgs.EMPTY // Assume all compiler args are the same.
        val testDataFileToPackageNameMapping: Map<File, PackageName> = regularTestCases.associate { it.testDataFile to it.packageName }
        val testDataFileToOutputDataMapping: Map<File, String?> = regularTestCases.associate { it.testDataFile to it.outputData }
    }
}
