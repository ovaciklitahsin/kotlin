/*
 * Copyright 2010-2021 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.konan.blackboxtest

import org.jetbrains.kotlin.test.services.JUnit5Assertions.assertTrue
import org.jetbrains.kotlin.test.services.JUnit5Assertions.fail
import java.io.File

internal typealias PackageName = String

internal data class TestFile<M : TestModule>(
    val location: File,
    val text: String,
    val module: M
) {
    init {
        @Suppress("UNCHECKED_CAST")
        when (module) {
            is TestModule.Regular -> module.files += this as TestFile<TestModule.Regular>
            is TestModule.Shared -> module.files += this as TestFile<TestModule.Shared>
        }
    }
}

internal sealed class TestModule {
    abstract val name: String
    abstract val files: List<TestFile<*>>

    data class Regular(
        override val name: String,
        val dependencySymbols: Set<String>,
        val friendSymbols: Set<String>
    ) : TestModule() {
        override val files: MutableList<TestFile<Regular>> = mutableListOf()

        lateinit var dependencies: Set<TestModule>
        lateinit var friends: Set<TestModule>
    }

    data class Shared(override val name: String) : TestModule() {
        override val files: MutableList<TestFile<Shared>> = mutableListOf()
    }

    companion object {
        fun newDefaultModule() = Regular(DEFAULT_MODULE_NAME, emptySet(), emptySet())

        fun Collection<Regular>.initializeModules(findSharedModule: (name: String) -> Shared?) {
            val mapping: Map</* regular module name */ String, Regular> = toIdentitySet()
                .groupingBy { module -> module.name }
                .aggregate { name, _: Regular?, module, isFirst ->
                    assertTrue(isFirst) { "Multiple test modules with the same name found: $name" }
                    module
                }

            fun findModule(name: String): TestModule = mapping[name]
                ?: findSharedModule(name)
                ?: fail { "Module $name not found" }

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
    val files: List<TestFile<*>>
    val freeCompilerArgs: TestCompilerArgs

    sealed class Simple(
        override val files: List<TestFile<*>>,
        override val freeCompilerArgs: TestCompilerArgs,
        val testDataFile: File, // The origin of the test case.
        val outputData: String?
    ) : TestCase

    class Regular(
        files: List<TestFile<*>>,
        freeCompilerArgs: TestCompilerArgs,
        testDataFile: File,
        outputData: String?,
        val packageName: PackageName
    ) : Simple(files, freeCompilerArgs, testDataFile, outputData)

    sealed class Standalone(
        files: List<TestFile<*>>,
        freeCompilerArgs: TestCompilerArgs,
        testDataFile: File,
        outputData: String?,
        val designatorPackageName: PackageName
    ) : Simple(files, freeCompilerArgs, testDataFile, outputData) {

        class WithTestRunner(
            files: List<TestFile<*>>,
            freeCompilerArgs: TestCompilerArgs,
            testDataFile: File,
            outputData: String?,
            designatorPackageName: PackageName
        ) : Standalone(files, freeCompilerArgs, testDataFile, outputData, designatorPackageName)

        class WithoutTestRunner(
            files: List<TestFile<*>>,
            freeCompilerArgs: TestCompilerArgs,
            testDataFile: File,
            val inputData: String?,
            outputData: String?,
            designatorPackageName: PackageName,
            val entryPoint: String
        ) : Standalone(files, freeCompilerArgs, testDataFile, outputData, designatorPackageName)
    }

    class Composite(regularTestCases: List<Regular>) : TestCase {
        override val files: List<TestFile<*>> = regularTestCases.flatMap { it.files }
        override val freeCompilerArgs: TestCompilerArgs = regularTestCases.firstOrNull()?.freeCompilerArgs
            ?: TestCompilerArgs.EMPTY // Assume all compiler args are the same.
        val testDataFileToPackageNameMapping: Map<File, PackageName> = regularTestCases.associate { it.testDataFile to it.packageName }
        val testDataFileToOutputDataMapping: Map<File, String?> = regularTestCases.associate { it.testDataFile to it.outputData }
    }
}
