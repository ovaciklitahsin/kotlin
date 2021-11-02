/*
 * Copyright 2010-2021 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

@file:JvmMultifileClass
@file:JvmName("PathsKt")

package kotlin.io.path

import java.io.IOException
import java.nio.file.LinkOption
import java.nio.file.Path
import java.util.ArrayDeque


/**
 * An enumeration to describe possible walk directions.
 * There are two of them: beginning from parents, ending with children,
 * and beginning from children, ending with parents. Both use depth-first search.
 */
public enum class PathWalkDirection {
    /** Depth-first search, directory is visited BEFORE its files */
    TOP_DOWN,
    /** Depth-first search, directory is visited AFTER its files */
    BOTTOM_UP
    // Do we want also breadth-first search?
}

/**
 * This class is intended to implement different file traversal methods.
 * It allows to iterate through all files inside a given directory.
 *
 * Use [Path.walk], [Path.walkTopDown] or [Path.walkBottomUp] extension functions to instantiate a `PathTreeWalk` instance.

 * If the file path given is just a file, walker iterates only it.
 * If the file path given does not exist, walker iterates nothing, i.e. it's equivalent to an empty sequence.
 */
public class PathTreeWalk private constructor(
    private val start: Path,
    private val direction: PathWalkDirection,
    private val linkOptions: Array<out LinkOption>,
    private val onEnter: ((Path) -> Boolean)?,
    private val onLeave: ((Path) -> Unit)?,
    private val onFail: ((f: Path, e: IOException) -> Unit)?,
    private val maxDepth: Int = Int.MAX_VALUE
) : Sequence<Path> {

    internal constructor(start: Path, direction: PathWalkDirection, options: Array<out LinkOption>) : this(
        start,
        direction,
        options,
        onEnter = null,
        onLeave = null,
        onFail = null
    )


    /** Returns an iterator walking through files. */
    override fun iterator(): Iterator<Path> = PathTreeWalkIterator()

    /** Abstract class that encapsulates file visiting in some order, beginning from a given [root] */
    private abstract class WalkState(val root: Path) {
        /** Call of this function proceeds to a next file for visiting and returns it */
        public abstract fun step(): Path?
    }

    /** Abstract class that encapsulates directory visiting in some order, beginning from a given [rootDir] */
    private abstract class DirectoryState(rootDir: Path, linkOptions: Array<out LinkOption>) : WalkState(rootDir) {
        init {
            assert(rootDir.isDirectory(*linkOptions)) { "rootDir must be verified to be directory beforehand." }
        }
    }

    private inner class PathTreeWalkIterator() : AbstractIterator<Path>() {

        // Stack of directory states, beginning from the start directory
        private val state = ArrayDeque<WalkState>()

        init {
            when {
                start.isDirectory(*linkOptions) -> state.push(directoryState(start))
                start.exists() -> state.push(SingleFileState(start))
                else -> done()
            }
        }

        override fun computeNext() {
            val nextFile = gotoNext()
            if (nextFile != null)
                setNext(nextFile)
            else
                done()
        }


        private fun directoryState(root: Path): DirectoryState {
            return when (direction) {
                PathWalkDirection.TOP_DOWN -> TopDownDirectoryState(root)
                PathWalkDirection.BOTTOM_UP -> BottomUpDirectoryState(root)
            }
        }

        private tailrec fun gotoNext(): Path? {
            // Take next file from the top of the stack or return if there's nothing left
            val topState = state.peek() ?: return null
            val path = topState.step()
            if (path == null) {
                // There is nothing more on the top of the stack, go back
                state.pop()
                return gotoNext()
            } else {
                // Check that file/directory matches the filter
                if (path == topState.root || !path.isDirectory(*linkOptions) || state.size >= maxDepth) {
                    // Proceed to a root directory or a simple file
                    return path
                } else {
                    // Proceed to a sub-directory
                    state.push(directoryState(path))
                    return gotoNext()
                }
            }
        }

        /** Visiting in bottom-up order */
        private inner class BottomUpDirectoryState(rootDir: Path) : DirectoryState(rootDir, linkOptions) {

            private var rootVisited = false

            private var fileList: List<Path>? = null

            private var fileIndex = 0

            /** First all children, then root directory */
            override fun step(): Path? {
                if (fileList == null) {
                    if (onEnter?.invoke(root) == false) {
                        return null
                    }

                    try {
                        fileList = root.listDirectoryEntries()
                    } catch (e: IOException) { // NotDirectoryException is also an IOException
                        onFail?.invoke(root, e)
                    }
                }
                if (fileList != null && fileIndex < fileList!!.size) {
                    // First visit all files
                    return fileList!![fileIndex++]
                } else if (!rootVisited) {
                    // Then visit root
                    rootVisited = true
                    return root
                } else {
                    // That's all
                    onLeave?.invoke(root)
                    return null
                }
            }
        }

        /** Visiting in top-down order */
        private inner class TopDownDirectoryState(rootDir: Path) : DirectoryState(rootDir, linkOptions) {

            private var rootVisited = false

            private var fileList: List<Path>? = null

            private var fileIndex = 0

            /** First root directory, then all children */
            override fun step(): Path? {
                if (!rootVisited) {
                    // First visit root
                    if (onEnter?.invoke(root) == false) {
                        return null
                    }

                    rootVisited = true
                    return root
                } else if (fileList == null || fileIndex < fileList!!.size) {
                    if (fileList == null) {
                        // Then read a list of entries
                        try {
                            fileList = root.listDirectoryEntries()
                        } catch (e: IOException) { // NotDirectoryException is also an IOException
                            onFail?.invoke(root, e)
                        }
                        if (fileList == null || fileList!!.size == 0) {
                            onLeave?.invoke(root)
                            return null
                        }
                    }
                    // Then visit all files
                    return fileList!![fileIndex++]
                } else {
                    // That's all
                    onLeave?.invoke(root)
                    return null
                }
            }
        }

        private inner class SingleFileState(rootFile: Path) : WalkState(rootFile) {
            private var visited: Boolean = false

            init {
                assert(rootFile.exists()) { "rootFile must exist." }
            }

            override fun step(): Path? {
                if (visited) return null
                visited = true
                return root
            }
        }

    }

    /**
     * Sets a predicate [function], that is called on any entered directory before its files are visited
     * and before it is visited itself.
     *
     * If the [function] returns `false` the directory is not entered and neither it nor its files are visited.
     */
    public fun onEnter(function: (Path) -> Boolean): PathTreeWalk {
        return PathTreeWalk(start, direction, linkOptions, onEnter = function, onLeave, onFail, maxDepth)
    }

    /**
     * Sets a callback [function], that is called on any left directory after its files are visited and after it is visited itself.
     */
    public fun onLeave(function: (Path) -> Unit): PathTreeWalk {
        return PathTreeWalk(start, direction, linkOptions, onEnter, onLeave = function, onFail, maxDepth)
    }

    /**
     * Set a callback [function], that is called on a directory when it's impossible to get its file list.
     *
     * [onEnter] and [onLeave] callback functions are called even in this case.
     */
    public fun onFail(function: (Path, IOException) -> Unit): PathTreeWalk {
        return PathTreeWalk(start, direction, linkOptions, onEnter, onLeave, onFail = function, maxDepth)
    }

    /**
     * Sets the maximum [depth] of a directory tree to traverse. By default there is no limit.
     *
     * The value must be positive and [Int.MAX_VALUE] is used to specify an unlimited depth.
     *
     * With a value of 1, walker visits only the origin directory and all its immediate children,
     * with a value of 2 also grandchildren, etc.
     */
    public fun maxDepth(depth: Int): PathTreeWalk {
        if (depth <= 0)
            throw IllegalArgumentException("depth must be positive, but was $depth.")
        return PathTreeWalk(start, direction, linkOptions, onEnter, onLeave, onFail, maxDepth = depth)
    }
}

/**
 * Gets a sequence for visiting this directory and all its content.
 *
 * @param direction walk direction, top-down (by default) or bottom-up.
 */
public fun Path.walk(direction: PathWalkDirection = PathWalkDirection.TOP_DOWN, vararg options: LinkOption): PathTreeWalk =
    PathTreeWalk(this, direction, options)

/**
 * Gets a sequence for visiting this directory and all its content in top-down order.
 * Depth-first search is used and directories are visited before all their files.
 */
public fun Path.walkTopDown(vararg options: LinkOption): PathTreeWalk = walk(PathWalkDirection.TOP_DOWN, *options)

/**
 * Gets a sequence for visiting this directory and all its content in bottom-up order.
 * Depth-first search is used and directories are visited after all their files.
 */
public fun Path.walkBottomUp(vararg options: LinkOption): PathTreeWalk = walk(PathWalkDirection.BOTTOM_UP, *options)