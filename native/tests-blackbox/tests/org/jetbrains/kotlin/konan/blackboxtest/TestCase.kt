/*
 * Copyright 2010-2021 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.konan.blackboxtest

import org.jetbrains.kotlin.test.services.JUnit5Assertions.assertTrue
import org.jetbrains.kotlin.test.services.JUnit5Assertions.fail
import java.io.File

internal typealias PackageName = String

/**
 * Represents a single file that will be supplied to the compiler.
 */
internal data class TestFile<M : TestModule>(
    val location: File,
    val text: String,
    val module: M
)

/**
 * One or more [TestFile]s that are always compiled together.
 *
 * Please note that [TestModule] is the minimal possible compilation unit, but not always the maximal possible compilation unit.
 * In certain test modes (ex: [TestMode.ONE_STAGE], [TestMode.TWO_STAGE]) modules represented by [TestModule] are ignored, and
 * all [TestFile]s are compiled together in one shot.
 *
 * [TestModule.Individual] represents a collection of [TestFile]s used exclusively for an individual [TestCase].
 * [TestModule.Shared] represents a "shared" module, i.e. the auxiliary module that can be used in multiple [TestCase]s.
 */
internal sealed class TestModule {
    abstract val name: String
    abstract val files: List<TestFile<*>>

    data class Individual(
        override val name: String,
        val dependencySymbols: Set<String>,
        val friendSymbols: Set<String>
    ) : TestModule() {
        override val files: MutableList<TestFile<Individual>> = mutableListOf()

        lateinit var dependencies: Set<TestModule>
        lateinit var friends: Set<TestModule>
    }

    data class Shared(override val name: String) : TestModule() {
        override val files: MutableList<TestFile<Shared>> = mutableListOf()
    }

    companion object {
        fun newDefaultModule() = Individual(DEFAULT_MODULE_NAME, emptySet(), emptySet())

        fun Collection<Individual>.initializeModules(findSharedModule: (name: String) -> Shared?) {
            val mapping: Map</* regular module name */ String, Individual> = toIdentitySet()
                .groupingBy { module -> module.name }
                .aggregate { name, _: Individual?, module, isFirst ->
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

        fun Collection<Individual>.allDependencyModules(): Set<TestModule> =
            DFSWithoutCycles.transitiveClosure<TestModule>(this, { module ->
                when (module) {
                    is Individual -> module.dependencies
                    is Shared -> emptyList()
                }
            })
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
    val modules: Collection<TestModule.Individual>
    val freeCompilerArgs: TestCompilerArgs

    sealed class Simple(
        override val modules: Collection<TestModule.Individual>,
        override val freeCompilerArgs: TestCompilerArgs,
        val testDataFile: File, // The origin of the test case.
        val outputData: String?
    ) : TestCase

    class Regular(
        modules: Collection<TestModule.Individual>,
        freeCompilerArgs: TestCompilerArgs,
        testDataFile: File,
        outputData: String?,
        val packageName: PackageName
    ) : Simple(modules, freeCompilerArgs, testDataFile, outputData)

    sealed class Standalone(
        modules: Collection<TestModule.Individual>,
        freeCompilerArgs: TestCompilerArgs,
        testDataFile: File,
        outputData: String?,
        val designatorPackageName: PackageName
    ) : Simple(modules, freeCompilerArgs, testDataFile, outputData) {

        class WithTestRunner(
            modules: Collection<TestModule.Individual>,
            freeCompilerArgs: TestCompilerArgs,
            testDataFile: File,
            outputData: String?,
            designatorPackageName: PackageName
        ) : Standalone(modules, freeCompilerArgs, testDataFile, outputData, designatorPackageName)

        class WithoutTestRunner(
            modules: Collection<TestModule.Individual>,
            freeCompilerArgs: TestCompilerArgs,
            testDataFile: File,
            val inputData: String?,
            outputData: String?,
            designatorPackageName: PackageName,
            val entryPoint: String
        ) : Standalone(modules, freeCompilerArgs, testDataFile, outputData, designatorPackageName)
    }

    class Composite(regularTestCases: List<Regular>) : TestCase {
        override val modules: Collection<TestModule.Individual> = regularTestCases.flatMap { it.modules }
        override val freeCompilerArgs: TestCompilerArgs = regularTestCases.firstOrNull()?.freeCompilerArgs
            ?: TestCompilerArgs.EMPTY // Assume all compiler args are the same.
        val testDataFileToPackageNameMapping: Map<File, PackageName> = regularTestCases.associate { it.testDataFile to it.packageName }
        val testDataFileToOutputDataMapping: Map<File, String?> = regularTestCases.associate { it.testDataFile to it.outputData }
    }
}
