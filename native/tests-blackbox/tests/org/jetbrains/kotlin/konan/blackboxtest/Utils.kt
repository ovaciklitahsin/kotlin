/*
 * Copyright 2010-2021 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.konan.blackboxtest

import org.jetbrains.kotlin.test.services.JUnit5Assertions.assertTrue
import org.jetbrains.kotlin.test.util.KtTestUtil
import java.io.File
import java.nio.charset.Charset

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

internal fun computePackageName(baseDir: File, file: File): PackageName {
    assertTrue(file.startsWith(baseDir)) { "The file is outside of the directory.\nFile: $file\nDirectory: $baseDir" }

    return file.parentFile.relativeTo(baseDir).resolve(file.nameWithoutExtension).toPath().joinToString(".")
}

internal fun getSanitizedFileName(fileName: String): String =
    fileName.map { if (it.isLetterOrDigit() || it == '_' || it == '.') it else '_' }.joinToString("")

internal val Class<*>.sanitizedName: String
    get() = name.map { if (it.isLetterOrDigit() || it == '_') it else '_' }.joinToString("")

internal const val DEFAULT_FILE_NAME = "main.kt"
internal const val DEFAULT_MODULE_NAME = "default"
internal const val SUPPORT_MODULE_NAME = "support"
