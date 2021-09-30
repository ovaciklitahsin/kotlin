/*
 * Copyright 2010-2021 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.konan.blackboxtest

import org.jetbrains.kotlin.renderer.KeywordStringsGenerated.KEYWORDS
import org.jetbrains.kotlin.test.services.JUnit5Assertions.assertTrue
import org.jetbrains.kotlin.test.util.KtTestUtil
import org.jetbrains.kotlin.utils.DFS
import java.io.File
import java.nio.charset.Charset
import java.nio.file.Path
import java.util.*
import kotlin.io.path.name

internal fun File.deleteRecursivelyWithLogging() {
    if (exists()) {
        walkBottomUp().forEach { entry ->
            val message = when {
                entry.isFile -> "File removed: $entry"
                entry.isDirectory && entry == this -> {
                    // Don't report directories except for the root directory.
                    "Directory removed (recursively): $entry"
                }
                else -> null
            }

            entry.delete()
            message?.let(::println)
        }
    }
}

internal fun File.mkdirsWithLogging() {
    mkdirs()
    println("Directory created: $this")
}

internal fun File.makeEmptyDirectory() {
    deleteRecursively()
    mkdirs()
}

internal fun File.writeFileWithLogging(text: String, charset: Charset) {
    parentFile.mkdirs()
    writeText(text, charset)
    println("File written: $this")
}

internal fun getAbsoluteFile(localPath: String): File = File(KtTestUtil.getHomeDirectory()).resolve(localPath).canonicalFile

internal fun computePackageName(testDataDir: File, testDataFile: File): PackageName {
    assertTrue(testDataFile.startsWith(testDataDir)) { "The file is outside of the directory.\nFile: $testDataFile\nDirectory: $testDataDir" }
    return testDataFile.parentFile
        .relativeTo(testDataDir)
        .resolve(testDataFile.nameWithoutExtension)
        .toPath()
        .map(Path::name)
        .joinToString(".") { packagePart -> if (packagePart in KEYWORDS) "_${packagePart}_" else packagePart }
}

internal fun getSanitizedFileName(fileName: String): String =
    fileName.map { if (it.isLetterOrDigit() || it == '_' || it == '.') it else '_' }.joinToString("")

internal val Class<*>.sanitizedName: String
    get() = name.map { if (it.isLetterOrDigit() || it == '_') it else '_' }.joinToString("")

internal const val DEFAULT_FILE_NAME = "main.kt"
internal const val DEFAULT_MODULE_NAME = "default"
internal const val SUPPORT_MODULE_NAME = "support"

internal fun Set<PackageName>.findCommonPackageName(): PackageName =
    map { packageName: PackageName ->
        packageName.split('.')
    }.reduce { commonPackageNameParts: List<String>, packageNameParts: List<String> ->
        mutableListOf<String>().apply {
            val i = commonPackageNameParts.iterator()
            val j = packageNameParts.iterator()

            while (i.hasNext() && j.hasNext()) {
                val packageNamePart = i.next()
                if (packageNamePart == j.next()) add(packageNamePart) else break
            }
        }
    }.joinToString(".")

internal object DFSEx {
    fun <N : Any> reverseTopologicalOrderWithoutCycles(
        nodes: Iterable<N>,
        getNeighbors: (N) -> Iterable<N>,
        onCycleDetected: (Iterable<N>) -> Nothing
    ): List<N> {
        val visitedNodes = linkedMapOf<N, State>()

        val visitHandler = DFS.Visited<N> { true }
        val nodeHandler = object : DFS.AbstractNodeHandler<N, Nothing>() {
            override fun beforeChildren(current: N) = when (visitedNodes[current]) {
                null -> {
                    visitedNodes[current] = State.VISITING
                    true
                }
                State.VISITED -> false
                State.VISITING -> onCycleDetected(visitedNodes.keys.dropWhile { it != current })
            }

            override fun afterChildren(current: N) = check(visitedNodes.put(current, State.VISITED) == State.VISITING)
            override fun result() = error("Not supposed to be called")
        }

        nodes.forEach { node -> DFS.doDfs(node, getNeighbors, visitHandler, nodeHandler) }

        return visitedNodes.keys.reversed()
    }

    private enum class State { VISITING, VISITED }
}

internal fun <T> Collection<T>.toIdentitySet(): Set<T> =
    Collections.newSetFromMap(IdentityHashMap<T, Boolean>()).apply { addAll(this@toIdentitySet) }
