/*
 * Copyright 2010-2021 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.konan.blackboxtest

import org.jetbrains.kotlin.renderer.KeywordStringsGenerated.KEYWORDS
import org.jetbrains.kotlin.storage.NotNullLazyValue
import org.jetbrains.kotlin.storage.StorageManager
import org.jetbrains.kotlin.test.services.JUnit5Assertions.assertTrue
import org.jetbrains.kotlin.test.util.KtTestUtil
import org.jetbrains.kotlin.utils.DFS
import java.io.File
import java.nio.charset.Charset
import java.nio.file.Path
import java.util.*
import kotlin.io.path.name
import kotlin.properties.ReadOnlyProperty
import kotlin.reflect.KProperty

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

internal fun <T> Collection<T>.toIdentitySet(): Set<T> =
    Collections.newSetFromMap(IdentityHashMap<T, Boolean>()).apply { addAll(this@toIdentitySet) }

internal class FailOnDuplicatesSet<E : Any> : Set<E> {
    private val uniqueElements: MutableSet<E> = hashSetOf()

    operator fun plusAssign(element: E) {
        assertTrue(uniqueElements.add(element)) { "An attempt to add already existing element: $element" }
    }

    override val size get() = uniqueElements.size
    override fun isEmpty() = uniqueElements.isEmpty()
    override fun contains(element: E) = element in uniqueElements
    override fun containsAll(elements: Collection<E>) = uniqueElements.containsAll(elements)
    override fun iterator(): Iterator<E> = uniqueElements.iterator()
    override fun equals(other: Any?) = (other as? FailOnDuplicatesSet<*>)?.uniqueElements == uniqueElements
    override fun hashCode() = uniqueElements.hashCode()
}

internal object DFSWithoutCycles {
    fun <N : Any> topologicalOrder(
        startingNodes: Iterable<N>,
        getNeighbors: (N) -> Iterable<N>,
        onCycleDetected: (Iterable<N>) -> Nothing = DEFAULT_CYCLE_HANDLER
    ): List<N> {
        val handler = TopologicalOrderNodeHandler(TransitiveClosureNodeHandler(emptyList(), getNeighbors, onCycleDetected))
        startingNodes.forEach { node -> DFS.doDfs(node, handler, handler, handler) }
        return handler.result()
    }

    fun <N : Any> transitiveClosure(
        prohibitedNodes: Iterable<N>,
        startingNodes: Iterable<N>,
        getNeighbors: (N) -> Iterable<N>,
        onCycleDetected: (Iterable<N>) -> Nothing = DEFAULT_CYCLE_HANDLER
    ): Set<N> {
        val handler = TransitiveClosureNodeHandler(prohibitedNodes, getNeighbors, onCycleDetected)
        startingNodes.forEach { node -> DFS.doDfs(node, handler, handler, handler) }
        return handler.result()
    }

    private class TransitiveClosureNodeHandler<N : Any>(
        prohibitedNodes: Iterable<N>, // Not included into transitive closure itself but are taken into account during cycle detection.
        private val getNeighbors: (N) -> Iterable<N>,
        private val onCycleDetected: (Iterable<N>) -> Nothing
    ) : DFS.Visited<N>, DFS.Neighbors<N>, DFS.NodeHandler<N, Set<N>> {
        private enum class State { PROHIBITED, VISITING, VISITED }

        private val visitedNodes: MutableMap<N, State> = prohibitedNodes.associateWithTo(linkedMapOf()) { State.PROHIBITED }

        override fun checkAndMarkVisited(current: N) = true
        override fun getNeighbors(current: N) = getNeighbors.invoke(current)

        override fun beforeChildren(current: N) = when (visitedNodes[current]) {
            null -> {
                visitedNodes[current] = State.VISITING
                true
            }
            State.VISITED -> false
            State.VISITING -> onCycleDetected(only(State.VISITING).dropWhile { it != current })
            State.PROHIBITED -> onCycleDetected(only(State.VISITING) + current)
        }

        override fun afterChildren(current: N) = check(visitedNodes.put(current, State.VISITED) == State.VISITING)

        override fun result() = only(State.VISITED)

        private fun only(state: State) = visitedNodes.filterValues { it == state }.keys
    }

    private class TopologicalOrderNodeHandler<N : Any>(
        private val transitiveClosureHandler: TransitiveClosureNodeHandler<N>
    ) : DFS.Visited<N> by transitiveClosureHandler, DFS.Neighbors<N> by transitiveClosureHandler, DFS.NodeHandler<N, List<N>> {
        private val orderedNodes = LinkedList<N>()

        override fun beforeChildren(current: N) = transitiveClosureHandler.beforeChildren(current)

        override fun afterChildren(current: N) {
            transitiveClosureHandler.afterChildren(current)
            orderedNodes.addFirst(current)
        }

        override fun result(): List<N> = orderedNodes
    }

    private val DEFAULT_CYCLE_HANDLER: (Iterable<*>) -> Nothing = { error("Cycle detected between nodes: $it") }
}

internal fun <N : Any> StorageManager.lazyNeighbors(
    directNeighbors: () -> Set<N>,
    computeNeighbors: (N) -> Set<N>,
    describe: (N) -> String = { it.toString() }
): ReadOnlyProperty<N, Set<N>> = object : ReadOnlyProperty<N, Set<N>> {
    private val lazyValue: NotNullLazyValue<Set<N>> = this@lazyNeighbors.createLazyValue(
        computable = {
            val neighbors = directNeighbors()
            if (neighbors.isEmpty())
                emptySet()
            else
                hashSetOf<N>().apply {
                    addAll(neighbors)
                    neighbors.forEach { neighbor -> addAll(computeNeighbors(neighbor)) }
                }
        },
        onRecursiveCall = { throw CyclicNeighborsException() })

    override fun getValue(thisRef: N, property: KProperty<*>): Set<N> = try {
        lazyValue.invoke()
    } catch (e: CyclicNeighborsException) {
        throw e.trace("Property ${property.name} of ${describe(thisRef)}")
    }
}

internal class CyclicNeighborsException : Exception() {
    private val backtrace = mutableListOf<String>()

    fun trace(element: String) = apply { backtrace += element }

    override val message
        get() = buildString {
            appendLine("Cyclic neighbors detected. Backtrace (${backtrace.size} elements):")
            backtrace.joinTo(this, separator = "\n")
        }
}
