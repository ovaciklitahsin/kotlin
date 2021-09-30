/*
 * Copyright 2010-2021 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.konan.blackboxtest

import com.intellij.util.containers.FactoryMap
import org.jetbrains.kotlin.konan.blackboxtest.TestModule.Companion.initializeModules
import org.jetbrains.kotlin.test.directives.model.Directive
import org.jetbrains.kotlin.test.services.JUnit5Assertions
import org.jetbrains.kotlin.test.services.JUnit5Assertions.assertEquals
import org.jetbrains.kotlin.test.services.JUnit5Assertions.assertFalse
import org.jetbrains.kotlin.test.services.JUnit5Assertions.assertNotEquals
import org.jetbrains.kotlin.test.services.JUnit5Assertions.assertTrue
import org.jetbrains.kotlin.test.services.JUnit5Assertions.fail
import org.jetbrains.kotlin.test.services.impl.RegisteredDirectivesParser
import java.io.File

internal class TestProvider(
    private val testDataFileToTestCaseMapping: Map<File, CompiledTestCase>
) {
    fun getTestByTestDataFile(testDataFile: File): NativeTest {
        val compiledTestCase = testDataFileToTestCaseMapping[testDataFile] ?: fail { "No test binary for test file $testDataFile" }

        val binary = compiledTestCase.binary // <-- Compilation happens here.
        val runParameters = when (val testCase = compiledTestCase.testCase) {
            is TestCase.Standalone.WithoutTestRunner -> listOfNotNull(
                testCase.inputData?.let(TestRunParameter::WithInputData),
                testCase.outputData?.let(TestRunParameter::WithExpectedOutputData)
            )
            is TestCase.Standalone.WithTestRunner -> listOfNotNull(
                TestRunParameter.WithGTestLogger,
                testCase.outputData?.let(TestRunParameter::WithExpectedOutputData)
            )
            is TestCase.Composite -> listOfNotNull(
                TestRunParameter.WithGTestLogger,
                TestRunParameter.WithPackageName(packageName = testCase.testDataFileToPackageNameMapping.getValue(testDataFile)),
                testCase.testDataFileToOutputDataMapping[testDataFile]?.let(TestRunParameter::WithExpectedOutputData)
            )
            is TestCase.Regular -> fail { "Normally unreachable code" }
        }

        return NativeTest(binary, runParameters)
    }
}

internal fun createBlackBoxTestProvider(environment: TestEnvironment): TestProvider {
    // Load shared modules on demand.
    val sharedModules = FactoryMap.create<String, TestModule.Shared?> { name ->
        environment.sharedModulesDir
            ?.resolve(name)
            ?.takeIf { it.isDirectory }
            ?.listFiles()
            ?.takeIf { it.isNotEmpty() }
            ?.let { files ->
                val module = TestModule.Shared(name)
                files.mapTo(module.files) { file -> TestFile(file, file.readText(Charsets.UTF_8), module) }
                module
            }
    }

    val testDataFileToTestCaseMapping: MutableMap<File, CompiledTestCase> = mutableMapOf()
    val groupedRegularTestCases: MutableMap<TestCompilerArgs, MutableList<TestCase.Regular>> = mutableMapOf()

    environment.testRoots.roots.forEach { testRoot ->
        testRoot.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .map { testDataFile -> createSimpleTestCase(testDataFile, environment, sharedModules::get) }
            .forEach { testCase ->
                when (testCase) {
                    is TestCase.Standalone -> {
                        // Add standalone test cases immediately to the mapping.
                        testDataFileToTestCaseMapping[testCase.testDataFile] = testCase.toCompiledTestCase(environment)
                    }
                    is TestCase.Regular -> {
                        // Group regular test cases by compiler arguments.
                        groupedRegularTestCases.getOrPut(testCase.freeCompilerArgs) { mutableListOf() } += testCase
                    }
                }
            }
    }

    // Convert regular test cases into composite test cases and add the latter ones to the mapping.
    groupedRegularTestCases.values.forEach { regularCases ->
        val compositeTestCase = TestCase.Composite(regularCases).toCompiledTestCase(environment)
        regularCases.forEach { regularCase ->
            testDataFileToTestCaseMapping[regularCase.testDataFile] = compositeTestCase
        }
    }

    return TestProvider(testDataFileToTestCaseMapping)
}

private fun createSimpleTestCase(
    testDataFile: File,
    environment: TestEnvironment,
    findSharedModule: (name: String) -> TestModule.Shared?
): TestCase.Simple {
    val testDataFileDir = testDataFile.parentFile
    val generatedSourcesDir = environment.testSourcesDir
        .resolve(testDataFileDir.relativeTo(environment.testRoots.baseDir))
        .resolve(testDataFile.nameWithoutExtension)

    val effectivePackageName = computePackageName(testDataDir = environment.testRoots.baseDir, testDataFile = testDataFile)

    val testModules = mutableMapOf<String, TestModule.Individual>()
    var currentTestModule: TestModule.Individual? = null

    var currentTestFileName: String? = null
    val currentTestFileText = StringBuilder()

    val directivesParser = RegisteredDirectivesParser(TestDirectives, JUnit5Assertions)
    var lastParsedDirective: Directive? = null

    fun switchTestModule(newTestModule: TestModule.Individual, location: Location): TestModule.Individual {
        // Don't register new test module if there is another one with the same name.
        val testModule = testModules.getOrPut(newTestModule.name) { newTestModule }

        if (testModule !== newTestModule) {
            assertEquals(testModule, newTestModule) {
                "$location: Two declarations of the same module with different dependencies found:\n$testModule\n$newTestModule"
            }
        }

        currentTestModule = testModule
        return testModule
    }

    fun beginTestFile(fileName: String) {
        assertEquals(null, currentTestFileName)
        currentTestFileName = fileName
    }

    fun finishTestFile(forceFinish: Boolean, location: Location) {
        val needToFinish = forceFinish
                || currentTestFileName != null
                || (currentTestFileName == null /*&& testFiles.isEmpty()*/ && currentTestFileText.hasAnythingButComments())

        if (needToFinish) {
            val fileName = currentTestFileName ?: DEFAULT_FILE_NAME
            val testModule = currentTestModule ?: switchTestModule(TestModule.newDefaultModule(), location)

            testModule.files += TestFile(
                location = generatedSourcesDir.resolve(fileName),
                text = currentTestFileText.toString(),
                module = testModule
            )

            currentTestFileText.clear()
            repeat(location.lineNumber ?: 0) { currentTestFileText.appendLine() }
            currentTestFileName = null
        }
    }

    testDataFile.readLines().forEachIndexed { lineNumber, line ->
        val location = Location(testDataFile, lineNumber)
        val expectFileDirectiveAfterModuleDirective =
            lastParsedDirective == TestDirectives.MODULE // Only FILE directive may follow MODULE directive.

        val rawDirective = RegisteredDirectivesParser.parseDirective(line)
        if (rawDirective != null) {
            val parsedDirective = try {
                directivesParser.convertToRegisteredDirective(rawDirective)
            } catch (e: AssertionError) {
                // Enhance error message with concrete test data file and line number where the error has happened.
                throw AssertionError("$location: Error while parsing directive in test data file.\nCause: ${e.message}", e)
            }

            if (parsedDirective != null) {
                when (val directive = parsedDirective.directive) {
                    TestDirectives.FILE -> {
                        val newFileName = parseFileName(parsedDirective, location)
                        finishTestFile(forceFinish = false, location)
                        beginTestFile(newFileName)
                    }
                    else -> {
                        assertFalse(expectFileDirectiveAfterModuleDirective) {
                            "$location: Directive $directive encountered after ${TestDirectives.MODULE} directive but was expecting ${TestDirectives.FILE}"
                        }

                        when (directive) {
                            TestDirectives.MODULE -> {
                                finishTestFile(forceFinish = false, location)
                                switchTestModule(parseModule(parsedDirective, location), location)
                            }
                            else -> {
                                assertNotEquals(TestDirectives.FILE, lastParsedDirective) {
                                    "$location: Global directive $directive encountered after ${TestDirectives.FILE} directive"
                                }
                                assertNotEquals(TestDirectives.MODULE, lastParsedDirective) {
                                    "$location: Global directive $directive encountered after ${TestDirectives.MODULE} directive"
                                }

                                directivesParser.addParsedDirective(parsedDirective)
                            }
                        }
                    }
                }

                currentTestFileText.appendLine()
                lastParsedDirective = parsedDirective.directive
                return@forEachIndexed
            }
        }

        if (expectFileDirectiveAfterModuleDirective) {
            // Was expecting a line with the FILE directive as this is the only possible continuation of a line with the MODULE directive, but failed.
            fail { "$location: ${TestDirectives.FILE} directive expected after ${TestDirectives.MODULE} directive" }
        }

        currentTestFileText.appendLine(line)
    }

    val location = Location(testDataFile)
    finishTestFile(forceFinish = true, location)

    val duplicatedTestFiles = testModules.values.flatMap { it.files }.groupingBy { it.location }.eachCount().filterValues { it > 1 }.keys
    assertTrue(duplicatedTestFiles.isEmpty()) { "$testDataFile: Duplicated test files encountered: $duplicatedTestFiles" }

    // Initialize module dependencies.
    testModules.values.initializeModules(findSharedModule)

    val registeredDirectives = directivesParser.build()

    val freeCompilerArgs = parseFreeCompilerArgs(registeredDirectives, location)
    val outputData = parseOutputData(baseDir = testDataFileDir, registeredDirectives, location)

    val testKind = parseTestKind(registeredDirectives, location)
    TestCase2(
        kind = testKind,
        modules = if (testKind == TestKind.REGULAR) {
            // Fix package declarations to avoid unintended conflicts between symbols with the same name in different test cases.
            testModules.values.onEach { testModule ->
                testModule.files.transformInPlace { testFile -> fixPackageDeclaration(testFile, effectivePackageName, testDataFile) }
            }
        } else
            testModules.values,
        freeCompilerArgs = freeCompilerArgs,
        testDataFile = testDataFile,
        nominalPackageName = effectivePackageName,
        outputData = outputData,
        extras = if (testKind == TestKind.STANDALONE_NO_TR) {
            TestCase2.StandaloneNoTestRunnerExtras(
                entryPoint = parseEntryPoint(registeredDirectives, location),
                inputData = parseInputData(baseDir = testDataFileDir, registeredDirectives, location)
            )
        } else
            null
    )

    return when (parseTestKind(registeredDirectives, location)) {
        TestKind.REGULAR -> TestCase.Regular(
            // Fix package declarations to avoid unintended conflicts between symbols with the same name in different test cases.
            modules = testModules.values.onEach { testModule ->
                testModule.files.transformInPlace { testFile -> fixPackageDeclaration(testFile, effectivePackageName, testDataFile) }
            },
            freeCompilerArgs = freeCompilerArgs,
            testDataFile = testDataFile,
            outputData = outputData,
            packageName = effectivePackageName
        )
        TestKind.STANDALONE -> TestCase.Standalone.WithTestRunner(
            modules = testModules.values,
            freeCompilerArgs = freeCompilerArgs,
            testDataFile = testDataFile,
            outputData = outputData,
            designatorPackageName = effectivePackageName
        )
        TestKind.STANDALONE_NO_TR -> TestCase.Standalone.WithoutTestRunner(
            modules = testModules.values,
            freeCompilerArgs = freeCompilerArgs,
            testDataFile = testDataFile,
            inputData = parseInputData(baseDir = testDataFileDir, registeredDirectives, location),
            outputData = outputData,
            designatorPackageName = effectivePackageName,
            entryPoint = parseEntryPoint(registeredDirectives, location)
        )
    }
}

private fun CharSequence.hasAnythingButComments(): Boolean {
    var result = false
    runForFirstMeaningfulStatement { _, _ -> result = true }
    return result
}

private fun fixPackageDeclaration(
    testFile: TestFile<TestModule.Individual>,
    packageName: PackageName,
    testDataFile: File
): TestFile<TestModule.Individual> {
    var existingPackageDeclarationLine: String? = null
    var existingPackageDeclarationLineNumber: Int? = null

    testFile.text.runForFirstMeaningfulStatement { lineNumber, line ->
        // First meaningful line.
        val trimmedLine = line.trim()
        if (trimmedLine.startsWith("package ")) {
            existingPackageDeclarationLine = trimmedLine
            existingPackageDeclarationLineNumber = lineNumber
        }
    }

    return if (existingPackageDeclarationLine != null) {
        val existingPackageName = existingPackageDeclarationLine!!.substringAfter("package ").trimStart()
        assertTrue(
            existingPackageName == packageName
                    || (existingPackageName.length > packageName.length
                    && existingPackageName.startsWith(packageName)
                    && existingPackageName[packageName.length] == '.')
        ) {
            val location = Location(testDataFile, existingPackageDeclarationLineNumber)
            "$location: Invalid package name declaration found: $existingPackageDeclarationLine\nExpected: package $packageName"

        }
        testFile
    } else
        testFile.copy(text = "package $packageName ${testFile.text}")
}

private inline fun CharSequence.runForFirstMeaningfulStatement(action: (lineNumber: Int, line: String) -> Unit) {
    var inMultilineComment = false

    for ((lineNumber, line) in lines().withIndex()) {
        val trimmedLine = line.trim()
        when {
            inMultilineComment -> inMultilineComment = !trimmedLine.endsWith("*/")
            trimmedLine.startsWith("/*") -> inMultilineComment = true
            trimmedLine.isEmpty() -> Unit
            trimmedLine.startsWith("//") -> Unit
            else -> {
                action(lineNumber, line)
                break
            }
        }
    }
}
