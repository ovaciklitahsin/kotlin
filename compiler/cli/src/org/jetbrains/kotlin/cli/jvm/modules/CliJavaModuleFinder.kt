/*
 * Copyright 2010-2017 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.jetbrains.kotlin.cli.jvm.modules

import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.StandardFileSystems
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.psi.PsiJavaModule
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.util.io.URLUtil
import org.jetbrains.kotlin.resolve.jvm.KotlinCliJavaFileManager
import org.jetbrains.kotlin.resolve.jvm.modules.JavaModule
import org.jetbrains.kotlin.resolve.jvm.modules.JavaModuleFinder
import org.jetbrains.kotlin.resolve.jvm.modules.JavaModuleInfo

class CliJavaModuleFinder(
    jdkRootFile: VirtualFile?,
    jrtFileSystemRoot: VirtualFile?,
    private val javaFileManager: KotlinCliJavaFileManager,
    project: Project,
    private val releaseFlagValue: Int
) : JavaModuleFinder {
    private val modulesRoot = jrtFileSystemRoot?.findChild("modules")
    private val ctSymFile = jdkRootFile?.findChild("lib")?.findChild("ct.sym")
    private val userModules = linkedMapOf<String, JavaModule>()

    private val allScope = GlobalSearchScope.allScope(project)

    public val compilationJdkVersion by lazy {
        //TODO: add test with -jdk-home
        //JDK ct.sym contains folder with current JDK code which contains 'system-modules' file
        ctSymRootFolder()?.children?.maxOf {
            if (it.name == "META-INF") -1
            else it.name.substringBefore("-modules").toCharArray().maxOf { char ->

                when (char) {
                    in '0'..'9' -> char.toInt() - '0'.toInt()
                    in 'A'..'Z' -> char.toInt() - 'A'.toInt() + 10
                    else -> -1
                }
            }
        } ?: -1
    }

    private val isJDK12OrLater
        get() = compilationJdkVersion >= 12

    private val useLastJdkApi: Boolean
        get() = releaseFlagValue == 0 || releaseFlagValue == compilationJdkVersion

    fun addUserModule(module: JavaModule) {
        userModules.putIfAbsent(module.name, module)
    }

    val allObservableModules: Sequence<JavaModule>
        get() = systemModules + userModules.values

    val ctSymModules by lazy {
        collectModuleRoots(releaseFlagValue)
    }

    val systemModules: Sequence<JavaModule.Explicit>
        get() = if (useLastJdkApi || isJDK12OrLater) modulesRoot?.children.orEmpty().asSequence().mapNotNull(this::findSystemModule) else
            ctSymModules.values.asSequence().mapNotNull { findSystemModule(it.single(), true) }

    override fun findModule(name: String): JavaModule? =
        (if (useLastJdkApi || isJDK12OrLater)
            modulesRoot?.findChild(name)?.let(this::findSystemModule) else ctSymModules[name]?.let { findSystemModule(it.single(), true) })
            ?: userModules[name]

    private fun findSystemModule(moduleRoot: VirtualFile, useSig: Boolean = false): JavaModule.Explicit? {
        val file = moduleRoot.findChild(if (useSig) PsiJavaModule.MODULE_INFO_CLASS + ".sig" else PsiJavaModule.MODULE_INFO_CLS_FILE) ?: return null
        val moduleInfo = JavaModuleInfo.read(file, javaFileManager, allScope) ?: return null
        return JavaModule.Explicit(moduleInfo,
                                   if (useLastJdkApi) listOf(JavaModule.Root(moduleRoot, isBinary = true, isBinarySignature = useSig))
                                   //TODO: Don't clear how distinguish roots from different modules
                                   // so roots would be duplicated, how it's OK?
                                   else if (useSig) listFoldersForRelease(releaseFlagValue).map { JavaModule.Root(it, isBinary = true, isBinarySignature = true) }
                                   else ctSymModules[moduleInfo.moduleName]?.map { JavaModule.Root(it, isBinary = true, isBinarySignature = true) }
                                       ?: return null /*not module in old jdk*/,
                                   file)

    }

    private fun codeFor(release: Int): String = if (release < 10) release.toString() else ('A' + (release - 10)).toString()

    private fun matchesRelease(fileName: String, release: Int) =
        !fileName.contains("-") && fileName.contains(codeFor(release)) // skip `*-modules`

    private fun hasCtSymFile() = ctSymFile != null && ctSymFile.isValid

    fun listFoldersForRelease(release: Int): List<VirtualFile> {
        if (!hasCtSymFile()) return emptyList()
        val root = ctSymRootFolder() ?: return emptyList()
        return root.children.filter { matchesRelease(it.name, release) }.flatMap {
            if (isJDK12OrLater)
                it.children.toList()
            else {
                listOf(it)
            }
        }
    }

    private fun collectModuleRoots(release: Int): Map<String, List<VirtualFile>> {
        if (!hasCtSymFile()) return emptyMap()
        val result = mutableMapOf<String, MutableList<VirtualFile>>()
        val root = ctSymRootFolder() ?: return emptyMap()

        if (isJDK12OrLater) {
            root.children.filter { matchesRelease(it.name, release) }.forEach { virtualFile ->
                virtualFile.children.toList().forEach {
                    val modulePaths = result.getOrPut(it.name) { arrayListOf() }
                    modulePaths.add(it)
                }
            }
        } else {
            if (releaseFlagValue > 8) {
                val moduleSigs = root.findChild(codeFor(release) + "-modules")
                    ?: error("Can't find modules signatures in `ct.sym` file for `-release $release` in ${ctSymFile?.path}")
                moduleSigs.children.forEach {
                    result[it.name] = mutableListOf(it)
                }
            }
        }
        return result
    }

    private fun ctSymRootFolder() = VirtualFileManager.getInstance().getFileSystem(StandardFileSystems.JAR_PROTOCOL)
        ?.findFileByPath(ctSymFile!!.path + URLUtil.JAR_SEPARATOR)
}
