/*
 * Copyright 2010-2021 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package kotlin.jdk7.test

import java.io.IOException
import java.nio.file.LinkOption
import java.nio.file.Path
import kotlin.io.path.*
import kotlin.test.*

class PathTreeWalkTest : AbstractPathTest() {

    companion object {
        val referenceFilenames = listOf("1", "1/2", "1/3", "1/3/4.txt", "1/3/5.txt", "6", "7.txt", "8", "8/9.txt")

        fun createTestFiles(): Path {
            val basedir = createTempDirectory()
            for (name in referenceFilenames) {
                val file = basedir.resolve(name)
                if (file.extension.isEmpty())
                    file.createDirectories()
                else
                    file.createFile()
            }
            return basedir
        }
    }

    @Test
    fun visitOnce() {
        val basedir = createTestFiles().cleanupRecursively()
        val referenceNames = setOf("") + referenceFilenames

        val namesTopDown = HashSet<String>()
        for (file in basedir.walkTopDown()) {
            val name = file.relativeToOrSelf(basedir).invariantSeparatorsPathString
            assertFalse(namesTopDown.contains(name), "$name is visited twice")
            namesTopDown.add(name)
        }
        assertEquals(referenceNames, namesTopDown)

        val namesBottomUp = HashSet<String>()
        for (file in basedir.walkBottomUp()) {
            val name = file.relativeToOrSelf(basedir).invariantSeparatorsPathString
            assertFalse(namesBottomUp.contains(name), "$name is visited twice")
            namesBottomUp.add(name)
        }
        assertEquals(referenceNames, namesBottomUp)
    }

    @Test
    fun singleFile() {
        val testFile = createTempFile().cleanup()
        val nonExistentFile = testFile.resolve("foo")

        assertEquals(testFile, testFile.walkTopDown().single(), "walk sequence of an existing file should contain only that file")
        assertEquals(testFile, testFile.walkTopDown().onEnter { false }.single(), "enter should not be called for single file")
        assertTrue(nonExistentFile.walkTopDown().none(), "should not walk a non-existent file")

        assertEquals(testFile, testFile.walkBottomUp().single(), "walk sequence of an existing file should contain only that file")
        assertEquals(testFile, testFile.walkBottomUp().onEnter { false }.single(), "enter should not be called for single file")
        assertTrue(nonExistentFile.walkBottomUp().none(), "should not walk a non-existent file")
    }

    @Test
    fun singleDirectory() {
        val testDir = createTempDirectory().cleanup()

        assertEquals(testDir, testDir.walkTopDown().single(), "walk sequence of an empty directory should contain only that directory")
        assertEquals(testDir, testDir.walkTopDown().onEnter { true }.single(), "onEnter should be called for an empty directory")
        assertTrue(testDir.walkTopDown().onEnter { false }.none(), "onEnter should be called for an empty directory")

        assertEquals(testDir, testDir.walkBottomUp().single(), "walk sequence of an empty directory should contain only that directory")
        assertEquals(testDir, testDir.walkBottomUp().onEnter { true }.single(), "onEnter should be called for an empty directory")
        assertTrue(testDir.walkBottomUp().onEnter { false }.none(), "onEnter should be called for an empty directory")
    }

    @Test
    fun enterLeave() {
        val basedir = createTestFiles().cleanupRecursively()

        val referenceNames = setOf("", "1", "1/2", "6", "8")
        val namesWalkEnter = HashSet<String>()
        val namesWalkLeave = HashSet<String>()
        val namesWalkVisit = HashSet<String>()
        var direction = FileWalkDirection.TOP_DOWN

        fun enter(file: Path): Boolean {
            val name = file.relativeToOrSelf(basedir).invariantSeparatorsPathString
            assertTrue(file.isDirectory(), "$name is not directory, only directories should be entered")
            assertFalse(namesWalkEnter.contains(name), "$name is entered twice")
            assertFalse(namesWalkLeave.contains(name), "$name is left before entrance")
            if (file.name == "3") return false // filter out 3
            namesWalkEnter.add(name)
            return true
        }

        fun leave(file: Path) {
            val name = file.relativeToOrSelf(basedir).invariantSeparatorsPathString
            assertTrue(file.isDirectory(), "$name is not directory, only directories should be left")
            assertFalse(namesWalkLeave.contains(name), "$name is left twice")
            namesWalkLeave.add(name)
            assertTrue(namesWalkEnter.contains(name), "$name is left before entrance")
        }

        fun visit(file: Path) {
            val name = file.relativeToOrSelf(basedir).invariantSeparatorsPathString
            assertFalse(namesWalkVisit.contains(name), "$name is visited twice")
            namesWalkVisit.add(name)
            if (file.isDirectory()) {
                assertTrue(namesWalkEnter.contains(name), "$name is visited before entrance")
                assertFalse(namesWalkLeave.contains(name), "$name is visited after leaving")
            }

            if (file == basedir)
                return
            val parent = file.parent
            if (parent != null) {
                val parentName = parent.relativeToOrSelf(basedir).invariantSeparatorsPathString
                assertTrue(namesWalkEnter.contains(parentName), "$name is visited before entering its parent $parentName")
                assertFalse(namesWalkLeave.contains(parentName), "$name is visited after leaving its parent $parentName")

                when (direction) {
                    FileWalkDirection.TOP_DOWN ->
                        assertTrue(namesWalkVisit.contains(parentName), "$direction: $name is visited before its parent $parentName")
                    FileWalkDirection.BOTTOM_UP ->
                        assertFalse(namesWalkVisit.contains(parentName), "$direction: $parentName is visited before its child $name")
                }
            }
        }

        for (file in basedir.walkTopDown().onEnter(::enter).onLeave(::leave)) {
            visit(file)
        }
        assertEquals(referenceNames, namesWalkEnter)
        assertEquals(referenceNames, namesWalkLeave)
        assertTrue(namesWalkVisit.containsAll(referenceNames), "all reference dirs $referenceNames must be visited $namesWalkVisit")

        namesWalkEnter.clear()
        namesWalkLeave.clear()
        namesWalkVisit.clear()
        direction = FileWalkDirection.BOTTOM_UP
        for (file in basedir.walkBottomUp().onEnter(::enter).onLeave(::leave)) {
            visit(file)
        }
        assertEquals(referenceNames, namesWalkEnter)
        assertEquals(referenceNames, namesWalkLeave)
        assertTrue(namesWalkVisit.containsAll(referenceNames), "all reference dirs $referenceNames must be visited $namesWalkVisit")
    }

    @Test
    fun filterAndMap() {
        val basedir = createTestFiles().cleanupRecursively()
        val referenceNames = setOf("", "1", "1/2", "1/3", "6", "8")
        assertEquals(referenceNames, basedir.walkTopDown().filter { it.isDirectory() }.map {
            it.relativeToOrSelf(basedir).invariantSeparatorsPathString
        }.toHashSet())
    }

    @Test
    fun deleteTxtTopDown() {
        val basedir = createTestFiles().cleanupRecursively()
        val referenceNames = setOf("", "1", "1/2", "1/3", "6", "8")
        val namesTopDown = HashSet<String>()

        fun enter(file: Path) {
            assertTrue(file.isDirectory())
            for (child in file.listDirectoryEntries()) {
                if (child.name.endsWith("txt"))
                    child.deleteExisting()
            }
        }

        for (file in basedir.walkTopDown().onEnter { enter(it); true }) {
            val name = file.relativeToOrSelf(basedir).invariantSeparatorsPathString
            assertFalse(namesTopDown.contains(name), "$name is visited twice")
            namesTopDown.add(name)
        }
        assertEquals(referenceNames, namesTopDown)
    }

    @Test
    fun deleteTxtBottomUp() {
        val basedir = createTestFiles().cleanupRecursively()
        val referenceNames = setOf("", "1", "1/2", "1/3", "6", "8")
        val namesBottomUp = HashSet<String>()

        fun enter(file: Path) {
            assertTrue(file.isDirectory())
            for (child in file.listDirectoryEntries()) {
                if (child.name.endsWith("txt"))
                    child.deleteExisting()
            }
        }

        for (file in basedir.walkBottomUp().onEnter { enter(it); true }) {
            val name = file.relativeToOrSelf(basedir).invariantSeparatorsPathString
            assertFalse(namesBottomUp.contains(name), "$name is visited twice")
            namesBottomUp.add(name)
        }
        assertEquals(referenceNames, namesBottomUp)
    }

    private fun compareWalkResults(expected: Set<String>, basedir: Path, filter: (Path) -> Boolean) {
        val namesTopDown = HashSet<String>()
        for (file in basedir.walkTopDown().onEnter { filter(it) }) {
            val name = file.relativeToOrSelf(basedir).invariantSeparatorsPathString
            assertFalse(namesTopDown.contains(name), "$name is visited twice")
            namesTopDown.add(name)
        }
        assertEquals(expected, namesTopDown, "Top-down walk results differ")

        val namesBottomUp = HashSet<String>()
        for (file in basedir.walkBottomUp().onEnter { filter(it) }) {
            val name = file.relativeToOrSelf(basedir).invariantSeparatorsPathString
            assertFalse(namesBottomUp.contains(name), "$name is visited twice")
            namesBottomUp.add(name)
        }
        assertEquals(expected, namesBottomUp, "Bottom-up walk results differ")
    }

    @Test
    fun filterOutDirectoryOnEnter() {
        val basedir = createTestFiles().cleanupRecursively()

        val referenceNames = listOf("", "1", "1/2", "6", "7.txt", "8", "8/9.txt").toSet()
        compareWalkResults(referenceNames, basedir) { !it.name.endsWith("3") }

        compareWalkResults(emptySet(), basedir) { false }
    }

    @Test
    fun forEach() {
        val basedir = createTestFiles().cleanupRecursively()
        var i = 0
        basedir.walkTopDown().forEach { _ -> i++ }
        assertEquals(10, i);
        i = 0
        basedir.walkBottomUp().forEach { _ -> i++ }
        assertEquals(10, i);
    }

    @Test
    fun count() {
        val basedir = createTestFiles().cleanupRecursively()
        assertEquals(10, basedir.walkTopDown().count());
        assertEquals(10, basedir.walkBottomUp().count());
    }

    @Test
    fun reduce() {
        val basedir = createTestFiles().cleanupRecursively()
        val res = basedir.walkTopDown().reduce { a, b -> if (a.absolutePathString() > b.absolutePathString()) a else b }
        assertTrue(res.endsWith("9.txt"), "Expected end with 9.txt actual: ${res.name}")
    }

    @Test
    fun maxDepthAndOnFail() {
        val basedir = createTestFiles().cleanupRecursively()

        val files = HashSet<Path>()
        val dirs = HashSet<Path>()
        val failed = HashSet<String>()
        val stack = ArrayList<Path>()

        fun beforeVisitDirectory(dir: Path): Boolean {
            stack.add(dir)
            dirs.add(dir.relativeToOrSelf(basedir))
            return true
        }

        fun afterVisitDirectory(dir: Path) {
            assertEquals(stack.last(), dir)
            stack.removeAt(stack.lastIndex)
        }

        fun visitFile(file: Path) {
            assertTrue(stack.last().listDirectoryEntries().contains(file), file.toString())
            files.add(file.relativeToOrSelf(basedir))
        }

        fun visitDirectoryFailed(dir: Path, @Suppress("UNUSED_PARAMETER") e: IOException) {
            assertEquals(stack.last(), dir)
            //stack.removeAt(stack.lastIndex) - don't remove from stack, onLeave will be called
            failed.add(dir.name)
        }

        basedir.walkTopDown().onEnter(::beforeVisitDirectory).onLeave(::afterVisitDirectory)
            .onFail(::visitDirectoryFailed).forEach { if (!it.isDirectory()) visitFile(it) }
        assertTrue(stack.isEmpty())
        assertTrue(failed.isEmpty())
        for (fileName in arrayOf("", "1", "1/2", "1/3", "6", "8")) {
            assertTrue(dirs.contains(Path(fileName)), fileName)
        }
        for (fileName in arrayOf("1/3/4.txt", "7.txt", "8/9.txt")) {
            assertTrue(files.contains(Path(fileName)), fileName)
        }

        //limit maxDepth
        files.clear()
        dirs.clear()
        basedir.walkTopDown().onEnter(::beforeVisitDirectory).onLeave(::afterVisitDirectory)
            .maxDepth(1).forEach { if (it != basedir) visitFile(it) }
        assertTrue(stack.isEmpty())
        assertTrue(failed.isEmpty())
        assertEquals(setOf(Path("")), dirs)
        for (fileName in arrayOf("1", "6", "7.txt", "8")) {
            assertTrue(files.contains(Path(fileName)), fileName)
        }

        //restrict access
        val restricted = basedir.resolve("1").toFile()
        if (restricted.setReadable(false)) {
            try {
                files.clear()
                dirs.clear()
                basedir.walkTopDown().onEnter(::beforeVisitDirectory).onLeave(::afterVisitDirectory)
                    .onFail(::visitDirectoryFailed).forEach { if (!it.isDirectory()) visitFile(it) }
                assertTrue(stack.isEmpty())
                assertEquals(setOf("1"), failed)
                assertEquals(listOf("", "1", "6", "8").map { Path(it) }.toSet(), dirs)
                assertEquals(listOf("7.txt", "8/9.txt").map { Path(it) }.toSet(), files)
            } finally {
                restricted.setReadable(true)
            }
        } else {
            System.err.println("cannot restrict access")
        }
    }

    @Test
    fun backup() {
        var count = 0
        fun makeBackup(file: Path) {
            count++
            val bakFile = Path("$file.bak")
            file.copyTo(bakFile)
        }

        val basedir1 = createTestFiles().cleanupRecursively()
        basedir1.walkTopDown().forEach {
            if (it.isRegularFile()) {
                makeBackup(it)
            }
        }
        assertEquals(4, count)

        count = 0
        val basedir2 = createTestFiles().cleanupRecursively()
        basedir2.walkTopDown().forEach {
            if (it.isRegularFile()) {
                makeBackup(it)
            }
        }
        assertEquals(4, count)
    }

    @Test
    fun find() {
        val basedir = createTestFiles().cleanupRecursively()
        basedir.resolve("8/4.txt").createFile()
        val count = basedir.walkTopDown().count { it.name == "4.txt" }
        assertEquals(2, count)
    }

    @Test
    fun findGits() {
        val basedir = createTestFiles().cleanupRecursively()
        basedir.resolve("1/3/.git").createDirectory()
        basedir.resolve("1/2/.git").createDirectory()
        basedir.resolve("6/.git").createDirectory()
        val found = HashSet<String>()
        for (file in basedir.walkTopDown()) {
            if (file.name == ".git") {
                found.add(file.parent.relativeToOrSelf(basedir).invariantSeparatorsPathString)
            }
        }
        assertEquals(setOf("1/2", "1/3", "6"), found)
    }

    @Test
    fun hardLink() {
        val basedir = createTestFiles().cleanupRecursively()
        val original = basedir.resolve("8/9.txt")
        val link = try {
            basedir.resolve("1/3/link").createLinkPointingTo(original)
        } catch (e: Exception) {
            // the underlying OS may not support hard links or may require a privilege
            println("Creating a link failed with ${e.stackTraceToString()}")
            return
        }
        for (direction in PathWalkDirection.values()) {
            for (linkOption in listOf(emptyArray(), arrayOf(LinkOption.NOFOLLOW_LINKS))) {
                val walk = basedir.walk(direction, *linkOption)
                assertTrue(walk.contains(original))
                assertTrue(walk.contains(link))
            }
        }
    }

    private fun testVisitedFiles(expected: List<String>, walk: PathTreeWalk, basedir: Path) {
        val actual = walk.map { it.relativeToOrSelf(basedir).invariantSeparatorsPathString }
        assertEquals(expected.sorted(), actual.toList().sorted())
    }

    @Test
    fun links() {
        val basedir = createTestFiles().cleanupRecursively()
        val original = basedir.resolve("8/9.txt")
        try {
            basedir.resolve("1/3/link").createLinkPointingTo(original)
        } catch (e: Exception) {
            // the underlying OS may not support hard links or may require a privilege
            println("Creating a link failed with ${e.stackTraceToString()}")
            return
        }
        for (direction in PathWalkDirection.values()) {
            for (linkOption in listOf(emptyArray(), arrayOf(LinkOption.NOFOLLOW_LINKS))) {
                val walk = basedir.walk(direction, *linkOption)
                testVisitedFiles(referenceFilenames + listOf("", "1/3/link"), walk, basedir)
            }
        }
    }

    private fun Path.tryCreateSymbolicLinkTo(original: Path): Path? {
        return try {
            this.createSymbolicLinkPointingTo(original)
        } catch (e: Exception) {
            // the underlying OS may not support hard links or may require a privilege
            println("Creating a link failed with ${e.stackTraceToString()}")
            null
        }
    }

    @Test
    fun symlinkToFile() {
        val basedir = createTestFiles().cleanupRecursively()
        val original = basedir.resolve("8/9.txt")
        basedir.resolve("1/3/link").tryCreateSymbolicLinkTo(original) ?: return

        for (direction in PathWalkDirection.values()) {
            for (linkOption in listOf(emptyArray(), arrayOf(LinkOption.NOFOLLOW_LINKS))) {
                val walk = basedir.walk(direction, *linkOption)
                testVisitedFiles(referenceFilenames + listOf("", "1/3/link"), walk, basedir)
            }
        }

        original.deleteExisting()
        for (direction in PathWalkDirection.values()) {
            for (linkOption in listOf(emptyArray(), arrayOf(LinkOption.NOFOLLOW_LINKS))) {
                val walk = basedir.walk(direction, *linkOption)
                testVisitedFiles(referenceFilenames - listOf("8/9.txt") + listOf("", "1/3/link"), walk, basedir)
            }
        }
    }

    @Test
    fun symlinkToDirectory() {
        val basedir = createTestFiles().cleanupRecursively()
        val original = basedir.resolve("8")
        basedir.resolve("1/3/link").tryCreateSymbolicLinkTo(original) ?: return

        for (direction in PathWalkDirection.values()) {
            // follow links
            val walk = basedir.walk(direction)
            // directory "8" contains "9.txt" file
            testVisitedFiles(referenceFilenames + listOf("", "1/3/link", "1/3/link/9.txt"), walk, basedir)

            // don't follow links
            val nofollowWalk = basedir.walk(direction, LinkOption.NOFOLLOW_LINKS)
            testVisitedFiles(referenceFilenames + listOf("", "1/3/link"), nofollowWalk, basedir)
        }

        original.deleteRecursively()
        for (direction in PathWalkDirection.values()) {
            for (linkOption in listOf(emptyArray(), arrayOf(LinkOption.NOFOLLOW_LINKS))) {
                val walk = basedir.walk(direction, *linkOption)
                testVisitedFiles(referenceFilenames - listOf("8", "8/9.txt") + listOf("", "1/3/link"), walk, basedir)
            }
        }
    }

    @Test
    fun symlinkToDirectoryMaxDepth() {
        val basedir = createTestFiles().cleanupRecursively()
        val original = basedir.resolve("8")
        original.resolve("10").createDirectory().resolve("11.txt").createFile()
        basedir.resolve("1/3/link").tryCreateSymbolicLinkTo(original) ?: return

        for (direction in PathWalkDirection.values()) {
            val walk = basedir.walk(direction)

            val depth2ReferenceNames = listOf("", "1", "1/2", "1/3", "6", "7.txt", "8", "8/9.txt", "8/10") // link is not visited
            testVisitedFiles(depth2ReferenceNames, walk.maxDepth(2), basedir)

            val depth3ReferenceNames = depth2ReferenceNames +
                    listOf("1/3/4.txt", "1/3/5.txt", "8/10/11.txt", "1/3/link") // link is visited, but not followed
            testVisitedFiles(depth3ReferenceNames, walk.maxDepth(3), basedir)

            val depth4ReferenceNames = depth3ReferenceNames +
                    listOf("1/3/link/9.txt", "1/3/link/10") // visited once more through the symbolic link
            testVisitedFiles(depth4ReferenceNames, walk.maxDepth(4), basedir)

            val depth5ReferenceNames = depth4ReferenceNames +
                    listOf("1/3/link/10/11.txt")
            testVisitedFiles(depth5ReferenceNames, walk.maxDepth(5), basedir)
            testVisitedFiles(depth5ReferenceNames, walk.maxDepth(6), basedir)
        }
    }

    @Test
    fun symlinkToSymlink() {
        val basedir = createTestFiles().cleanupRecursively()
        val original = basedir.resolve("8")
        val link = basedir.resolve("1/3/link").tryCreateSymbolicLinkTo(original) ?: return
        basedir.resolve("1/linkToLink").tryCreateSymbolicLinkTo(link) ?: return

        for (direction in PathWalkDirection.values()) {
            val walk = basedir.walk(direction)

            val depth2ReferenceNames = listOf("", "1", "1/2", "1/3", "1/linkToLink", "6", "7.txt", "8", "8/9.txt") // linkToLink is visited
            testVisitedFiles(depth2ReferenceNames, walk.maxDepth(2), basedir)

            val depth3ReferenceNames = depth2ReferenceNames +
                    listOf("1/3/4.txt", "1/3/5.txt", "1/3/link", "1/linkToLink/9.txt") // "9.txt" is visited once more through linkToLink
            testVisitedFiles(depth3ReferenceNames, walk.maxDepth(3), basedir)

            val depth4ReferenceNames = depth3ReferenceNames +
                    listOf("1/3/link/9.txt") // "9.txt" is visited once more through link
            testVisitedFiles(depth4ReferenceNames, walk.maxDepth(4), basedir)
            testVisitedFiles(depth4ReferenceNames, walk.maxDepth(5), basedir)
        }
    }

    @Test
    fun symlinkBasedir() {
        val basedir = createTestFiles().cleanupRecursively()
        val link = createTempDirectory().cleanupRecursively().resolve("link").tryCreateSymbolicLinkTo(basedir) ?: return

        for (direction in PathWalkDirection.values()) {
            val walk = link.walk(direction)
            testVisitedFiles(referenceFilenames + listOf(""), walk, link)

            val nofollowWalk = link.walk(direction, LinkOption.NOFOLLOW_LINKS)
            assertEquals(link, nofollowWalk.single())
        }
    }

    @Test
    fun symlinkRecursive() {
        val basedir = createTestFiles().cleanupRecursively()
        val original = basedir.resolve("1")
        original.resolve("2/link").tryCreateSymbolicLinkTo(original) ?: return

        for (direction in PathWalkDirection.values()) {
            val walk = basedir.walk(direction)

            val depth3ReferenceNames = referenceFilenames + listOf("", "1/2/link")
            testVisitedFiles(depth3ReferenceNames, walk.maxDepth(3), basedir)

            val depth4ReferenceNames = depth3ReferenceNames + listOf("1/2/link/2", "1/2/link/3")
            testVisitedFiles(depth4ReferenceNames, walk.maxDepth(4), basedir)

            val depth5ReferenceNames = depth4ReferenceNames + listOf("1/2/link/2/link", "1/2/link/3/4.txt", "1/2/link/3/5.txt")
            testVisitedFiles(depth5ReferenceNames, walk.maxDepth(5), basedir)

            val depth6ReferenceNames = depth5ReferenceNames + listOf("1/2/link/2/link/2", "1/2/link/2/link/3")
            testVisitedFiles(depth6ReferenceNames, walk.maxDepth(6), basedir)
        }
    }
}