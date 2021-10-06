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
import org.jetbrains.kotlin.konan.blackboxtest.TestModule.Companion.allDependencies
import org.jetbrains.kotlin.konan.blackboxtest.TestModule.Companion.allFriends
import org.jetbrains.kotlin.test.services.JUnit5Assertions.assertEquals
import org.jetbrains.kotlin.test.services.JUnit5Assertions.assertFalse
import org.jetbrains.kotlin.test.services.JUnit5Assertions.fail
import org.jetbrains.kotlin.utils.addToStdlib.safeAs
import java.io.*

//internal class CompiledTestCase(
//    val testCase: TestCase,
//    lazyBinary: () -> TestBinary
//) {
//    private val binaryResult: Result<TestBinary> by lazy {
//        runCatching { /* Do compile on demand. */ lazyBinary() }
//    }
//
//    val binary: TestBinary get() = binaryResult.getOrThrow()
//}

//internal fun TestCase.toCompiledTestCase(environment: TestEnvironment): CompiledTestCase {
//    modules.forEach { testModule ->
//        testModule.files.forEach { testFile ->
//            testFile.location.parentFile.mkdirs()
//            testFile.location.writeText(testFile.text)
//        }
//    }
//
//    environment.testBinariesDir.mkdirs()
//
//    return CompiledTestCase(this) {
//        val executableFile = environment.testBinariesDir.resolve(inventStableExecutableFileName(this, environment))
//
//        when (environment.globalEnvironment.testMode) {
//            TestMode.ONE_STAGE -> {
//                produceProgram(
//                    sources = modules.withDependencies().flatMap { it.files },
//                    output = executableFile,
//                    environment = environment
//                )
//            }
//            TestMode.TWO_STAGE -> {
//                val klibFile = executableFile.resolveSibling(executableFile.nameWithoutExtension + ".klib")
//                produceKlib(
//                    sources = modules.withDependencies().flatMap { it.files },
//                    output = klibFile,
//                    environment = environment
//                )
//                produceProgramFromIncludedKlib(
//                    library = klibFile,
//                    output = executableFile,
//                    environment = environment
//                )
//            }
//            TestMode.WITH_MODULES -> {
////                val distinctModules: Set<TestModule> = files.map { it.module }.toSet()
////
////                val orderedModules = DFSEx.reverseTopologicalOrderWithoutCycles(
////                    nodes = distinctModules,
////                    getNeighbors = { module -> module.dependencies + module.friends },
////                    onCycleDetected = { modules -> error("Cycle detected in modules $modules") }
////                )
////
////                val libraries = mutableMapOf<String, File>()
////                with(orderedModules.iterator()) {
////                    while (hasNext()) {
////                        val module = next()
////                        if (hasNext()) {
////                            TODO("unimplemented yet")
////                        }
////                    }
////                }
//
//                TODO("unimplemented yet")
//            }
//        }
//
//        TestBinary(executableFile)
//    }
//}

//private fun inventStableExecutableFileName(testCase: TestCase, environment: TestEnvironment): String {
//    val filesCount: Int
//    val designatorPackageName: String
//    val baseName: String?
//    val hash: Int
//
//    when (testCase) {
//        is TestCase.Simple -> {
//            filesCount = 1
//            designatorPackageName = when (testCase) {
//                is TestCase.Regular -> testCase.packageName
//                is TestCase.Standalone -> testCase.designatorPackageName
//            }
//            baseName = testCase.testDataFile.nameWithoutExtension
//            hash = testCase.testDataFile.hash
//        }
//        is TestCase.Composite -> {
//            filesCount = testCase.testDataFileToPackageNameMapping.size
//            designatorPackageName = testCase.testDataFileToPackageNameMapping.values.toSet().findCommonPackageName()
//            baseName = null
//            hash = testCase.testDataFileToPackageNameMapping.keys.fold(0) { acc, testDataFile -> acc + testDataFile.hash }
//        }
//    }
//
//    return buildString {
//        val prefix = filesCount.toString()
//        repeat(3 - prefix.length) { append('0') }
//        append(prefix).append('_')
//        append(designatorPackageName.replace('.', '_')).append('_')
//        if (baseName != null) append(baseName).append('_')
//        append(hash.toUInt().toString(16).padStart(8, '0'))
//        append('.').append(environment.globalEnvironment.target.family.exeSuffix)
//    }
//}

//private val File.hash: Int
//    get() = path.hashCode()

private class ArgsBuilder {
    private val args = mutableListOf<String>()

    fun add(vararg args: String) {
        this.args += args
    }

    fun add(args: Iterable<String>) {
        this.args += args
    }

    inline fun <T> add(rawArgs: Iterable<T>, transform: (T) -> String) {
        rawArgs.mapTo(args) { transform(it) }
    }

    inline fun <T> addFlattened(rawArgs: Iterable<T>, transform: (T) -> Iterable<String>) {
        rawArgs.flatMapTo(args) { transform(it) }
    }

    inline fun <T, R> addFlattenedTwice(rawArgs: Iterable<T>, transform1: (T) -> Iterable<R>, transform2: (R) -> String) {
        rawArgs.forEach { add(transform1(it), transform2) }
    }

//    inline fun <reified T : TestCase> TestCase.ifTestCaseIs(builderAction: T.() -> Unit) {
//        if (this is T) builderAction()
//    }

//    inline fun <reified T : TestCase> TestCase.unlessTestCaseIs(builderAction: () -> Unit) {
//        if (this !is T) builderAction()
//    }

    fun build(): Array<String> = args.toTypedArray()
}

private inline fun buildArgs(builderAction: ArgsBuilder.() -> Unit): Array<String> {
    return ArgsBuilder().apply(builderAction).build()
}

//private fun TestCase.produceKlib(
//    sources: List<TestFile<*>>,
//    dependencyLibraries: List<File> = emptyList(),
//    friendLibraries: List<File> = emptyList(),
//    output: File,
//    environment: TestEnvironment
//) {
//    val args = buildArgs {
//        applyCommonArgs(this, dependencyLibraries, friendLibraries, output, environment)
//        add("-produce", "library")
//        add(sources) { testFile -> testFile.location.path }
//    }
//
//    runCompiler(args, environment.globalEnvironment.lazyKotlinNativeClassLoader)
//}

//private fun TestCase.produceProgramFromIncludedKlib(
//    library: File,
//    output: File,
//    environment: TestEnvironment
//) {
//    val args = buildArgs {
//        applyCommonArgs(this, dependencyLibraries = emptyList(), friendLibraries = emptyList(), output, environment)
//        applyProgramArgs(this)
//        add("-Xinclude=${library.path}")
//    }
//
//    runCompiler(args, environment.globalEnvironment.lazyKotlinNativeClassLoader)
//}

//private fun TestCase.produceProgram(
//    sources: List<TestFile<*>>,
//    dependencyLibraries: List<File> = emptyList(),
//    friendLibraries: List<File> = emptyList(),
//    output: File,
//    environment: TestEnvironment
//) {
//    val args = buildArgs {
//        applyCommonArgs(this, dependencyLibraries, friendLibraries, output, environment)
//        applyProgramArgs(this)
//        add(sources) { testFile -> testFile.location.path }
//    }
//
//    runCompiler(args, environment.globalEnvironment.lazyKotlinNativeClassLoader)
//}

//private fun TestCase.applyCommonArgs(
//    builder: ArgsBuilder,
//    dependencyLibraries: List<File>,
//    friendLibraries: List<File>,
//    output: File,
//    environment: TestEnvironment
//) = with(builder) {
//    add(
//        "-enable-assertions",
//        "-g",
//        "-target", environment.globalEnvironment.target.name,
//        "-repo", environment.globalEnvironment.kotlinNativeHome.resolve("klib").path,
//        "-output", output.path,
//        "-Xskip-prerelease-check",
//        "-Xverify-ir"
//    )
//    addFlattened(dependencyLibraries) { library -> listOf("-l", library.path) }
//    addFlattened(friendLibraries) { library -> listOf("-friend-modules", library.path) }
//    add(freeCompilerArgs.compilerArgs)
//}

//private fun TestCase.applyProgramArgs(builder: ArgsBuilder) = with(builder) {
//    add("-produce", "program")
//    ifTestCaseIs<TestCase.Standalone.WithoutTestRunner> {
//        add("-entry", entryPoint)
//    }
//    unlessTestCaseIs<TestCase.Standalone.WithoutTestRunner> {
//        add("-generate-test-runner")
//    }
//}

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





//////////////////////////////////////////////////////////////////////////////////////////////////



internal class TestCompilationFactory(
    private val environment: TestEnvironment
) {
    private val sharedModulesOutputDir = environment.testBinariesDir.resolve("__shared_modules__").apply { mkdirs() }
    private val compilations = hashMapOf<TestCompilationInput, TestCompilation>()

    fun oneModuleToKlib(module: TestModule, freeCompilerArgs: TestCompilerArgs): TestCompilation {
        val input = TestCompilationInput.Klib(freeCompilerArgs, sourceModule = module)

        return compilations.getOrPut(input) {
            TestCompilationImpl(
                environment = environment,
                input = input,
                expectedOutputFile = when (module) {
                    is TestModule.Exclusive -> artifactForModule(module, "klib")
                    is TestModule.Shared -> sharedModulesOutputDir.resolve("${module.name}.klib")
                },
                dependencies = TestCompilationDependencies(
                    dependencies = input.allDependencies.map { oneModuleToKlib(it, freeCompilerArgs) },
                    friends = input.allFriends.map { oneModuleToKlib(it, freeCompilerArgs) }
                ),
                specificCompilerArgs = { add("-produce", "library") }
            )
        }
    }

    fun oneTestCaseToExecutable(testCase: TestCase) = with(testCase) {
        val rootModule = topologicallyOrderedModules.first() as TestModule.Exclusive
        val nonRootModules = topologicallyOrderedModules.subList(1, topologicallyOrderedModules.size)

        val input = TestCompilationInput.Executable(
            freeCompilerArgs = freeCompilerArgs,
            sourceModules = setOf(rootModule),
            allDependencies = hashSetOf<TestModule>().apply {
                addAll(rootModule.allDependencies)
                addAll(nonRootModules)
            },
            allFriends = rootModule.allFriends,
            includedLibraries = emptySet(),
            entryPoint = extras?.entryPoint
        )

        compilations.getOrPut(input) {
            TestCompilationImpl(
                environment = environment,
                input = input,
                expectedOutputFile = artifactForModule(rootModule, environment.globalEnvironment.target.family.exeSuffix),
                dependencies = TestCompilationDependencies(
                    dependencies = input.allDependencies.map { oneModuleToKlib(it, freeCompilerArgs) },
                    friends = input.allFriends.map { oneModuleToKlib(it, freeCompilerArgs) },
                ),
                specificCompilerArgs = {
                    add("-produce", "program")
                    val entryPoint = extras?.entryPoint
                    if (entryPoint != null) add("-entry", entryPoint) else add("-generate-test-runner")
                }
            )
        }
    }

    fun manyTestCasesToExecutable(testCases: Collection<TestCase>): TestCompilation = TODO()


//    fun oneStageExecutable(
//        modules: Set<TestModule.Individual>,
//        freeCompilerArgs: TestCompilerArgs,
//        entryPoint: String?
//    ): TestCompilation {
//        val input = TestCompilationInput.Executable(freeCompilerArgs, modules, includedModules = emptySet(), entryPoint)
//
//        return compilations.getOrPut(input) {
//            val inputWithAllModules = input.copy(modules = modules.withDependencies())
//            val expectedOutputFile = File("???") // TODO
//
//            TestCompilationImpl(
//                environment = environment,
//                input = inputWithAllModules,
//                expectedOutputFile = expectedOutputFile,
//                dependencies = TestCompilationDependencies.EMPTY
//            ) {
//                add("-produce", "program")
//                if (entryPoint != null) add("-entry", entryPoint) else add("-generate-test-runner")
//            }
//        }
//    }

//    fun twoStageExecutable(
//        modules: Set<TestModule.Individual>,
//        freeCompilerArgs: TestCompilerArgs,
//        entryPoint: String?
//    ) {
//
//    }

    private fun outputDirForModule(module: TestModule.Exclusive): File =
        environment.testBinariesDir.resolve(module.testCase.nominalPackageName.replace('.', '_')).apply { mkdirs() }

    private fun artifactForModule(module: TestModule.Exclusive, extension: String): File =
        outputDirForModule(module).resolve("${module.testCase.testDataFile.nameWithoutExtension}.${module.name}.$extension")
}

internal interface TestCompilation {
    val output: TestCompilationOutput

    val resultingArtifact: File
        get() = output.safeAs<TestCompilationOutput.Success>()?.resultingArtifact
            ?: fail { "Compilation $this does not have resulting artifact. The output is $output." }
}

/**
 * The input of a particular compilation.
 *
 * [freeCompilerArgs] - specific arguments that shall be passed to the compiler.
 * [sourceModules] - the modules containing source files that needs to be passed to the compiler.
 */
private sealed interface TestCompilationInput {
    val freeCompilerArgs: TestCompilerArgs
    val sourceModules: Set<TestModule>
    val allDependencies: Set<TestModule>
    val allFriends: Set<TestModule>

    data class Klib(
        override val freeCompilerArgs: TestCompilerArgs,
        override val sourceModules: Set<TestModule>,
        override val allDependencies: Set<TestModule>,
        override val allFriends: Set<TestModule>
    ) : TestCompilationInput {
        constructor(freeCompilerArgs: TestCompilerArgs, sourceModule: TestModule) : this(
            freeCompilerArgs,
            setOf(sourceModule),
            sourceModule.allDependencies,
            sourceModule.allFriends
        )

        constructor(freeCompilerArgs: TestCompilerArgs, sourceModules: Set<TestModule>) : this(
            freeCompilerArgs,
            sourceModules,
            sourceModules.flatMapTo(hashSetOf()) { it.allDependencies },
            sourceModules.flatMapTo(hashSetOf()) { it.allFriends },
        )
    }

    data class Executable(
        override val freeCompilerArgs: TestCompilerArgs,
        override val sourceModules: Set<TestModule>,
        override val allDependencies: Set<TestModule>,
        override val allFriends: Set<TestModule>,
        val includedLibraries: Set<TestModule>,
        val entryPoint: String?
    ) : TestCompilationInput {
        constructor(freeCompilerArgs: TestCompilerArgs, sourceModule: TestModule, entryPoint: String?) : this(
            freeCompilerArgs,
            setOf(sourceModule),
            sourceModule.allDependencies,
            sourceModule.allFriends,
            emptySet(),
            entryPoint
        )
    }
}

internal sealed class TestCompilationOutput {
    class Success(val resultingArtifact: File) : TestCompilationOutput()
    class Failure(val throwable: Throwable) : TestCompilationOutput()
    object DependencyFailure : TestCompilationOutput()
}

/**
 * The dependencies of a particular [TestCompilationImpl].
 *
 * [dependencies] - the [TestCompilation]s (modules) that should yield KLIBs to be consumed as dependency libraries in the current compilation.
 * [friends] - similarly but friend modules (-friend-modules).
 * [includedLibraries] - similarly but included modules (-Xinclude).
 */
private class TestCompilationDependencies(
    val dependencies: Collection<TestCompilation> = emptyList(),
    val friends: Collection<TestCompilation> = emptyList(),
    val includedLibraries: Collection<TestCompilation> = emptyList()
) {
    val allSuccessful: Boolean
        get() = dependencies.none { it.output !is TestCompilationOutput.Success }
                && friends.none { it.output !is TestCompilationOutput.Success }
                && includedLibraries.none { it.output !is TestCompilationOutput.Success }

    companion object {
        val EMPTY = TestCompilationDependencies(emptyList(), emptyList(), emptyList())
    }
}

private class TestCompilationImpl(
    private val environment: TestEnvironment,
    private val input: TestCompilationInput,
    private val expectedOutputFile: File,
    private val dependencies: TestCompilationDependencies,
    private val specificCompilerArgs: ArgsBuilder.() -> Unit
) : TestCompilation {
    // Runs the compiler and memoizes the result on property access.
    override val output: TestCompilationOutput by lazy {
        if (!dependencies.allSuccessful)
            TestCompilationOutput.DependencyFailure
        else try {
            doCompile()
            TestCompilationOutput.Success(expectedOutputFile)
        } catch (t: Throwable) {
            TestCompilationOutput.Failure(t)
        }
    }

    private fun ArgsBuilder.applyCommonArgs() {
        add(
            "-enable-assertions",
            "-g",
            "-target", environment.globalEnvironment.target.name,
            "-repo", environment.globalEnvironment.kotlinNativeHome.resolve("klib").path,
            "-output", expectedOutputFile.path,
            "-Xskip-prerelease-check",
            "-Xverify-ir"
        )
        addFlattened(dependencies.dependencies) { library -> listOf("-l", library.resultingArtifact.path) }
        addFlattened(dependencies.friends) { friend -> listOf("-friend-modules", friend.resultingArtifact.path) }
        add(dependencies.includedLibraries) { include -> "-Xinclude=$include" }
        add(input.freeCompilerArgs.compilerArgs)
    }

    private fun ArgsBuilder.applySources() {
        addFlattenedTwice(input.sourceModules, { it.files }) { it.location.path }
    }

    private fun doCompile() {
        val args = buildArgs {
            applyCommonArgs()
            specificCompilerArgs()
            applySources()
        }

        runCompiler(args, environment.globalEnvironment.lazyKotlinNativeClassLoader)
    }
}
