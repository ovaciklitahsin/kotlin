/*
 * Copyright 2010-2021 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.konan.blackboxtest

import org.jetbrains.kotlin.cli.common.ExitCode
import org.jetbrains.kotlin.cli.common.messages.*
import org.jetbrains.kotlin.compilerRunner.OutputItemsCollectorImpl
import org.jetbrains.kotlin.compilerRunner.processCompilerOutput
import org.jetbrains.kotlin.config.Services
import org.jetbrains.kotlin.test.services.JUnit5Assertions.assertEquals
import org.jetbrains.kotlin.test.services.JUnit5Assertions.assertFalse
import java.io.*

internal class CompiledTestCase(
    val testCase: TestCase,
    lazyBinary: () -> TestBinary
) {
    private val binaryResult: Result<TestBinary> by lazy {
        runCatching { /* Do compile on demand. */ lazyBinary() }
    }

    val binary: TestBinary get() = binaryResult.getOrThrow()
}

internal fun TestCase.toCompiledTestCase(environment: TestEnvironment): CompiledTestCase {
    files.forEach { testFile ->
        testFile.location.parentFile.mkdirs()
        testFile.location.writeText(testFile.contents)
    }

    environment.testBinariesDir.mkdirs()

    return CompiledTestCase(this) {
        val executableFile = environment.testBinariesDir.resolve(inventStableExecutableFileName(this, environment))

        val args = buildArgs {
            add("-produce", "program")
            ifTestCaseIs<TestCase.Standalone.WithoutTestRunner> {
                add("-entry", entryPoint)
            }
            ifTestCaseIsNot<TestCase.Standalone.WithoutTestRunner> {
                add("-generate-test-runner")
            }
            add(
                "-enable-assertions",
                "-g",
                "-target", environment.globalEnvironment.target.name,
                "-repo", environment.globalEnvironment.kotlinNativeHome.resolve("klib").path,
                "-output", executableFile.path,
                "-Xskip-prerelease-check"
            )
            add(freeCompilerArgs.compilerArgs)
            add(files) { testFile -> testFile.location.path }
        }

        val kotlinNativeClassLoader by environment.globalEnvironment.lazyKotlinNativeClassLoader
        compileTest(args, kotlinNativeClassLoader)

        TestBinary(executableFile)
    }
}

private fun inventStableExecutableFileName(testCase: TestCase, environment: TestEnvironment): String {
    val testDataFilesCount: Int
    val firstTestDataFiles: String
    val hash: Int

    when (testCase) {
        is TestCase.Simple -> {
            testDataFilesCount = 1
            firstTestDataFiles = testCase.testDataFile.nameWithoutExtension
            hash = testCase.testDataFile.hash
        }
        is TestCase.Composite -> {
            testDataFilesCount = testCase.testDataFileToPackageNameMapping.size
            firstTestDataFiles = testCase.testDataFileToPackageNameMapping.keys
                .sorted()
                .take(3)
                .joinToString("_") { it.nameWithoutExtension }
            hash = testCase.testDataFileToPackageNameMapping.keys.fold(0) { acc, testDataFile -> acc + testDataFile.hash }
        }
    }

    return buildString {
        val prefix = testDataFilesCount.toString()
        repeat(3 - prefix.length) { append('0') }
        append(prefix).append('-')
        append(firstTestDataFiles).append('-')
        append(hash.toString(16))
        append('.').append(environment.globalEnvironment.target.family.exeSuffix)
    }
}

private val File.hash: Int
    get() = path.hashCode()

private class ArgsBuilder {
    private val args = mutableListOf<String>()

    fun add(vararg args: String) {
        this.args += args
    }

    fun add(args: List<String>) {
        this.args += args
    }

    inline fun <T> add(rawArgs: List<T>, transform: (T) -> String) {
        rawArgs.mapTo(args) { transform(it) }
    }

    inline fun <reified T : TestCase> TestCase.ifTestCaseIs(builderAction: T.() -> Unit) {
        if (this is T) builderAction()
    }

    inline fun <reified T : TestCase> TestCase.ifTestCaseIsNot(builderAction: () -> Unit) {
        if (this !is T) builderAction()
    }

    fun build(): Array<String> = args.toTypedArray()
}

private inline fun buildArgs(builderAction: ArgsBuilder.() -> Unit): Array<String> {
    return ArgsBuilder().apply(builderAction).build()
}

private fun compileTest(args: Array<String>, kotlinNativeClassLoader: ClassLoader) {
    val servicesClass = Class.forName(Services::class.java.canonicalName, true, kotlinNativeClassLoader)
    val emptyServices = servicesClass.getField("EMPTY").get(servicesClass)

    val compilerClass = Class.forName("org.jetbrains.kotlin.cli.bc.K2Native", true, kotlinNativeClassLoader)
    val entryPoint = compilerClass.getMethod(
        "execAndOutputXml",
        PrintStream::class.java,
        servicesClass,
        Array<String>::class.java
    )

    val compilerXmlOutput = ByteArrayOutputStream()
    val exitCode = PrintStream(compilerXmlOutput).use { printStream ->
        val result = entryPoint.invoke(compilerClass.newInstance(), printStream, emptyServices, args)
        ExitCode.valueOf(result.toString())
    }

    val compilerPlainOutput = ByteArrayOutputStream()
    val messageCollector = PrintStream(compilerPlainOutput).use { printStream ->
        val messageCollector = GroupingMessageCollector(
            PrintingMessageCollector(printStream, MessageRenderer.SYSTEM_INDEPENDENT_RELATIVE_PATHS, true),
            false
        )
        processCompilerOutput(
            messageCollector,
            OutputItemsCollectorImpl(),
            compilerXmlOutput,
            exitCode
        )
        messageCollector.flush()
        messageCollector
    }

    fun details() = buildString {
        appendLine("\n\nExit code: $exitCode")
        appendLine("\n== BEGIN[COMPILER_OUTPUT] ==")
        val compilerOutputText = compilerPlainOutput.toString(Charsets.UTF_8.name()).trim()
        if (compilerOutputText.isNotEmpty()) appendLine(compilerOutputText)
        appendLine("== END[COMPILER_OUTPUT] ==")
    }

    assertEquals(ExitCode.OK, exitCode) { "Compilation finished with non-zero exit code. ${details()}" }
    assertFalse(messageCollector.hasErrors()) { "Compilation finished with errors. ${details()}" }
}
