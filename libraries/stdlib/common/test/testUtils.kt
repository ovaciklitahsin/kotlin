/*
 * Copyright 2010-2020 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package test

public expect fun assertTypeEquals(expected: Any?, actual: Any?)

internal expect fun String.removeLeadingPlusOnJava6(): String

internal expect inline fun testOnNonJvm6And7(f: () -> Unit)

public expect fun testOnJvm(action: () -> Unit)
public expect fun testOnJs(action: () -> Unit)

public expect val isFloat32RangeEnforced: Boolean

public expect val supportsSuppressedExceptions: Boolean

public expect val supportsNamedCapturingGroup: Boolean