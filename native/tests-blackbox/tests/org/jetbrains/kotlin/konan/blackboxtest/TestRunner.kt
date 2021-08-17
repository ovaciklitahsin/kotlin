/*
 * Copyright 2010-2021 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.konan.blackboxtest

import org.jetbrains.kotlin.jvm.compiler.AbstractWriteSignatureTest.Companion.matchExact
import org.jetbrains.kotlin.test.services.JUnit5Assertions.assertEquals
import org.jetbrains.kotlin.test.services.JUnit5Assertions.assertFalse
import org.jetbrains.kotlin.test.services.JUnit5Assertions.assertTrue
import kotlin.properties.Delegates

internal fun NativeTest.runAndVerify() {
    val programArgs = mutableListOf<String>(binary.executableFile.path)
    runParameters.forEach { it.applyTo(programArgs) }

    val process = ProcessBuilder(programArgs).directory(binary.executableFile.parentFile).start()
    runParameters.get<TestRunParameter.WithInputData> {
        process.outputStream.write(inputData.toByteArray(Charsets.UTF_8))
        process.outputStream.flush()
    }

    TestOutput(runParameters, programArgs, process).verify()
}

private class TestOutput(
    private val runParameters: List<TestRunParameter>,
    private val programArgs: List<String>,
    private val process: Process
) {
    private var exitCode: Int by Delegates.notNull()
    private lateinit var stdOut: String
    private lateinit var stdErr: String

    fun verify() {
        waitUntilExecutionFinished()

        assertEquals(0, exitCode) { "Process exited with non-zero code.${details()}" }

        if (runParameters.has<TestRunParameter.WithGTestLogger>()) {
            verifyTestWithGTestRunner()
        } else {
            verifyPlainTest()
        }
    }

    private fun verifyTestWithGTestRunner() {
        val testStatuses = mutableMapOf<TestStatus, MutableSet<TestName>>()
        val cleanStdOut = StringBuilder()

        var expectStatusLine = false
        stdOut.lines().forEach { line ->
            when {
                expectStatusLine -> {
                    val matcher = GTEST_STATUS_LINE_REGEX.matchExact(line)
                    if (matcher != null) {
                        // Read the line with test status.
                        val testStatus = matcher.group(1)
                        val testName = matcher.group(2)
                        testStatuses.getOrPut(testStatus) { mutableSetOf() } += testName
                        expectStatusLine = false
                    } else {
                        assertFalse(GTEST_ANY_LINE_REGEX.matches(line)) {
                            // If current line is not a status line then it could be only the line with the process' output
                            // and not the line with the output produced by GTest.
                            "Malformed test output.${details()}"
                        }
                        cleanStdOut.appendLine(line)
                    }
                }
                line.startsWith(GTEST_RUN_LINE_PREFIX) -> {
                    expectStatusLine = true // Next line contains either  test status.
                }
                else -> Unit
            }
        }

        assertTrue(testStatuses.isNotEmpty()) { "No tests have been executed.${details()}" }

        val passedTests = testStatuses[GTEST_STATUS_OK]?.size ?: 0
        assertTrue(passedTests > 0) { "No passed tests.${details()}" }

        runParameters.get<TestRunParameter.WithPackageName> {
            val excessiveTests = testStatuses.getValue(GTEST_STATUS_OK).filter { testName -> !testName.startsWith(packageName) }
            assertTrue(excessiveTests.isEmpty()) { "Excessive tests have been executed: $excessiveTests.${details()}" }
        }

        val failedTests = (testStatuses - GTEST_STATUS_OK).values.sumOf { it.size }
        assertEquals(0, failedTests) { "There are failed tests.${details()}" }

        runParameters.get<TestRunParameter.WithExpectedOutputData> {
            val mergedOutput = cleanStdOut.toString() + stdErr
            assertEquals(expectedOutputData, mergedOutput) { "Process output mismatch.${details()}" }
        }
    }

    private fun verifyPlainTest() {
        runParameters.get<TestRunParameter.WithExpectedOutputData> {
            val mergedOutput = stdOut + stdErr
            assertEquals(expectedOutputData, mergedOutput) { "Process output mismatch.${details()}" }
        }
    }

    private fun waitUntilExecutionFinished() {
        exitCode = process.waitFor()
        stdOut = process.inputStream.bufferedReader(Charsets.UTF_8).readText()
        stdErr = process.errorStream.bufferedReader(Charsets.UTF_8).readText()
    }

    private fun details() = buildString {
        appendLine("\n\nProgram arguments: $programArgs")
        appendLine("Exit code: $exitCode")
        appendLine("\n== BEGIN [STDOUT] ==")
        if (stdOut.isNotEmpty()) appendLine(stdOut)
        appendLine("== END [STDOUT] ==")
        appendLine("\n== BEGIN [STDERR] ==")
        if (stdErr.isNotEmpty()) appendLine(stdErr)
        appendLine("== END [STDERR] ==")
    }

    companion object {
        private const val GTEST_RUN_LINE_PREFIX = "[ RUN      ]"
        private val GTEST_STATUS_LINE_REGEX = Regex("^\\[\\s+([A-Z]+)\\s+]\\s+(\\S+)\\s+.*")
        private const val GTEST_STATUS_OK = "OK"
        private val GTEST_ANY_LINE_REGEX = Regex("^\\[[^\\[]+].*")
    }
}

private typealias TestStatus = String
private typealias TestName = String
