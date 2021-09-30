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
        testFile.location.writeText(testFile.text)
    }

    environment.testBinariesDir.mkdirs()

    return CompiledTestCase(this) {
        val executableFile = environment.testBinariesDir.resolve(inventStableExecutableFileName(this, environment))

        when (environment.globalEnvironment.testMode) {
            TestMode.ONE_STAGE -> {
                produceProgram(
                    sources = files,
                    output = executableFile,
                    environment = environment
                )
            }
            TestMode.TWO_STAGE -> {
                val klibFile = executableFile.resolveSibling(executableFile.nameWithoutExtension + ".klib")
                produceKlib(
                    sources = files,
                    output = klibFile,
                    environment = environment
                )
                produceProgramFromIncludedKlib(
                    library = klibFile,
                    output = executableFile,
                    environment = environment
                )
            }
            TestMode.WITH_MODULES -> {
                val distinctModules: Set<TestModule> = files.map { it.module }.toSet()

//                val orderedModules = DFSEx.reverseTopologicalOrderWithoutCycles(
//                    nodes = distinctModules,
//                    getNeighbors = { module -> module.dependencies + module.friends },
//                    onCycleDetected = { modules -> error("Cycle detected in modules $modules") }
//                )

//                val libraries = mutableMapOf<String, File>()
//                with(orderedModules.iterator()) {
//                    while (hasNext()) {
//                        val module = next()
//                        if (hasNext()) {
//                            TODO("unimplemented yet")
//                        }
//                    }
//                }

                TODO("unimplemented yet: $distinctModules")
            }
        }

        TestBinary(executableFile)
    }
}

private fun inventStableExecutableFileName(testCase: TestCase, environment: TestEnvironment): String {
    val filesCount: Int
    val designatorPackageName: String
    val baseName: String?
    val hash: Int

    when (testCase) {
        is TestCase.Simple -> {
            filesCount = 1
            designatorPackageName = when (testCase) {
                is TestCase.Regular -> testCase.packageName
                is TestCase.Standalone -> testCase.designatorPackageName
            }
            baseName = testCase.testDataFile.nameWithoutExtension
            hash = testCase.testDataFile.hash
        }
        is TestCase.Composite -> {
            filesCount = testCase.testDataFileToPackageNameMapping.size
            designatorPackageName = testCase.testDataFileToPackageNameMapping.values.toSet().findCommonPackageName()
            baseName = null
            hash = testCase.testDataFileToPackageNameMapping.keys.fold(0) { acc, testDataFile -> acc + testDataFile.hash }
        }
    }

    return buildString {
        val prefix = filesCount.toString()
        repeat(3 - prefix.length) { append('0') }
        append(prefix).append('_')
        append(designatorPackageName.replace('.', '_')).append('_')
        if (baseName != null) append(baseName).append('_')
        append(hash.toUInt().toString(16).padStart(8, '0'))
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

    inline fun <T> addMultiple(rawArgs: List<T>, transform: (T) -> Collection<String>) {
        rawArgs.flatMapTo(args) { transform(it) }
    }

    inline fun <reified T : TestCase> TestCase.ifTestCaseIs(builderAction: T.() -> Unit) {
        if (this is T) builderAction()
    }

    inline fun <reified T : TestCase> TestCase.unlessTestCaseIs(builderAction: () -> Unit) {
        if (this !is T) builderAction()
    }

    fun build(): Array<String> = args.toTypedArray()
}

private inline fun buildArgs(builderAction: ArgsBuilder.() -> Unit): Array<String> {
    return ArgsBuilder().apply(builderAction).build()
}

private fun TestCase.produceKlib(
    sources: List<TestFile>,
    dependencyLibraries: List<File> = emptyList(),
    friendLibraries: List<File> = emptyList(),
    output: File,
    environment: TestEnvironment
) {
    val args = buildArgs {
        applyCommonArgs(this, dependencyLibraries, friendLibraries, output, environment)
        add("-produce", "library")
        add(sources) { testFile -> testFile.location.path }
    }

    runCompiler(args, environment.globalEnvironment.lazyKotlinNativeClassLoader)
}

private fun TestCase.produceProgramFromIncludedKlib(
    library: File,
    output: File,
    environment: TestEnvironment
) {
    val args = buildArgs {
        applyCommonArgs(this, dependencyLibraries = emptyList(), friendLibraries = emptyList(), output, environment)
        applyProgramArgs(this)
        add("-Xinclude=${library.path}")
    }

    runCompiler(args, environment.globalEnvironment.lazyKotlinNativeClassLoader)
}

private fun TestCase.produceProgram(
    sources: List<TestFile>,
    dependencyLibraries: List<File> = emptyList(),
    friendLibraries: List<File> = emptyList(),
    output: File,
    environment: TestEnvironment
) {
    val args = buildArgs {
        applyCommonArgs(this, dependencyLibraries, friendLibraries, output, environment)
        applyProgramArgs(this)
        add(sources) { testFile -> testFile.location.path }
    }

    runCompiler(args, environment.globalEnvironment.lazyKotlinNativeClassLoader)
}

private fun TestCase.applyCommonArgs(
    builder: ArgsBuilder,
    dependencyLibraries: List<File>,
    friendLibraries: List<File>,
    output: File,
    environment: TestEnvironment
) = with(builder) {
    add(
        "-enable-assertions",
        "-g",
        "-target", environment.globalEnvironment.target.name,
        "-repo", environment.globalEnvironment.kotlinNativeHome.resolve("klib").path,
        "-output", output.path,
        "-Xskip-prerelease-check",
        "-Xverify-ir"
    )
    addMultiple(dependencyLibraries) { library -> listOf("-l", library.path) }
    addMultiple(friendLibraries) { library -> listOf("-friend-modules", library.path) }
    add(freeCompilerArgs.compilerArgs)
}

private fun TestCase.applyProgramArgs(builder: ArgsBuilder) = with(builder) {
    add("-produce", "program")
    ifTestCaseIs<TestCase.Standalone.WithoutTestRunner> {
        add("-entry", entryPoint)
    }
    unlessTestCaseIs<TestCase.Standalone.WithoutTestRunner> {
        add("-generate-test-runner")
    }
}

private fun runCompiler(args: Array<String>, lazyKotlinNativeClassLoader: Lazy<ClassLoader>) {
    val kotlinNativeClassLoader by lazyKotlinNativeClassLoader

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
