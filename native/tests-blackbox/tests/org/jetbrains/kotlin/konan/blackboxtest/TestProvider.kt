/*
 * Copyright 2010-2021 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.konan.blackboxtest

import org.jetbrains.kotlin.test.services.JUnit5Assertions
import org.jetbrains.kotlin.test.services.JUnit5Assertions.assertEquals
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
    val testDataFileToTestCaseMapping: MutableMap<File, CompiledTestCase> = mutableMapOf()
    val groupedRegularTestCases: MutableMap<TestCompilerArgs, MutableList<TestCase.Regular>> = mutableMapOf()

    environment.testRoots.roots.forEach { testRoot ->
        testRoot.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .map { testDataFile -> createSimpleTestCase(testDataFile, environment) }
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

private fun createSimpleTestCase(testDataFile: File, environment: TestEnvironment): TestCase.Simple {
    val testDataFileDir = testDataFile.parentFile

    val relativeTestDir = testDataFileDir.resolve(testDataFile.nameWithoutExtension).relativeTo(environment.testRoots.baseDir)
    val effectivePackageName = relativeTestDir.toPath().joinToString(".")

    val generatedSourcesDir = environment.testSourcesDir.resolve(relativeTestDir)

    val testFiles = mutableListOf<TestFile>()

    var currentTestFileName: String? = null
    val currentTestFileContents = StringBuilder()

    val directivesParser = RegisteredDirectivesParser(TestDirectives, JUnit5Assertions)

    fun finishTestFile(newFileName: String?, lineNumber: Int) {
        if (currentTestFileName != null || currentTestFileContents.isNotBlank()) {
            val fileName = currentTestFileName ?: DEFAULT_FILE_NAME
            testFiles += TestFile(
                name = fileName,
                location = generatedSourcesDir.resolve(fileName),
                contents = currentTestFileContents.toString()
            )
        }

        currentTestFileContents.clear()

        if (newFileName != null) {
            currentTestFileName = newFileName
            repeat(lineNumber) { currentTestFileContents.appendLine() } // Preserve line numbers as in the original test data file.
        }
    }

    testDataFile.readLines().forEachIndexed { lineNumber, line ->
        val rawDirective = RegisteredDirectivesParser.parseDirective(line)
        if (rawDirective != null) {
            val location = Location(testDataFile, lineNumber)

            val parsedDirective = try {
                directivesParser.convertToRegisteredDirective(rawDirective)
            } catch (e: AssertionError) {
                // Enhance error message with concrete test data file and line number where the error has happened.
                throw AssertionError("$location: Error while parsing directive in test data file.\nCause: ${e.message}", e)
            }

            if (parsedDirective != null) {
                when (parsedDirective.directive) {
                    TestDirectives.FILE -> {
                        val newFileName = parseFileName(parsedDirective, location)
                        finishTestFile(newFileName, lineNumber)
                    }
                    else -> directivesParser.addParsedDirective(parsedDirective)
                }
                currentTestFileContents.appendLine()
                return@forEachIndexed
            }
        }

        currentTestFileContents.appendLine(line)
    }

    finishTestFile(newFileName = null, lineNumber = 0)

    val registeredDirectives = directivesParser.build()
    val location = Location(testDataFile)

    val freeCompilerArgs = parseFreeCompilerArgs(registeredDirectives, location)
    val outputData = parseOutputData(baseDir = testDataFileDir, registeredDirectives, location)

    return when (parseTestMode(registeredDirectives, location)) {
        TestMode.REGULAR -> TestCase.Regular(
            files = testFiles.map { testFile -> fixPackageDeclaration(testFile, effectivePackageName, testDataFile) },
            freeCompilerArgs = freeCompilerArgs,
            testDataFile = testDataFile,
            outputData = outputData,
            packageName = effectivePackageName
        )
        TestMode.STANDALONE -> TestCase.Standalone.WithTestRunner(testFiles, freeCompilerArgs, testDataFile, outputData)
        TestMode.STANDALONE_NO_TR -> {
            TestCase.Standalone.WithoutTestRunner(
                files = testFiles,
                freeCompilerArgs = freeCompilerArgs,
                testDataFile = testDataFile,
                inputData = parseInputData(baseDir = testDataFileDir, registeredDirectives, location),
                outputData = outputData,
                entryPoint = parseEntryPoint(registeredDirectives, location)
            )
        }
    }
}

private fun fixPackageDeclaration(testFile: TestFile, packageName: PackageName, testDataFile: File): TestFile {
    var existingPackageDeclarationLine: String? = null
    var existingPackageDeclarationLineNumber: Int? = null

    var inMultilineComment = false

    val lines = testFile.contents.lines()
    for ((lineNumber, line) in lines.withIndex()) {
        val trimmedLine = line.trim()
        when {
            inMultilineComment -> inMultilineComment = !trimmedLine.endsWith("*/")
            trimmedLine.isBlank() -> Unit
            trimmedLine.startsWith("/*") -> inMultilineComment = true
            else -> {
                // First meaningful line.
                if (trimmedLine.startsWith("package ")) {
                    existingPackageDeclarationLine = trimmedLine
                    existingPackageDeclarationLineNumber = lineNumber
                }
                break
            }
        }
    }

    return if (existingPackageDeclarationLine != null) {
        val existingPackageName = existingPackageDeclarationLine.substringAfter("package ").trimStart()
        assertEquals(packageName, existingPackageName) { // TODO could it be just a subpackage?
            val location = Location(testDataFile, existingPackageDeclarationLineNumber)
            "$location: Invalid package name declaration found: $existingPackageDeclarationLine\nExpected: $packageName"
        }
        testFile
    } else
        testFile.copy(contents = "package $packageName ${testFile.contents}")
}
