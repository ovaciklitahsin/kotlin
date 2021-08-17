/*
 * Copyright 2010-2021 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.generators.tests

//import org.jetbrains.kotlin.konan.blackboxtest.AbstractExtNativeBlackBoxTest
//import org.jetbrains.kotlin.test.generators.generateTestGroupSuiteWithJUnit5

fun main() {
    System.setProperty("java.awt.headless", "true")

    generateExtNativeBlackboxTestData(
        testDataSource = "compiler/testData",
        testDataDestination = "native/tests-blackbox/ext-testData"
    ) {
        include("codegen/box")
        include("codegen/boxInline")

        exclude("codegen/box/compileKotlinAgainstKotlin/specialBridgesInDependencies.kt")             // KT-42723
        exclude("codegen/box/collections/kt41123.kt")                                                 // KT-42723
        exclude("codegen/box/multiplatform/multiModule/expectActualTypealiasLink.kt")                 // KT-40137
        exclude("codegen/box/multiplatform/multiModule/expectActualMemberLink.kt")                    // KT-33091
        exclude("codegen/box/multiplatform/multiModule/expectActualLink.kt")                          // KT-41901
        exclude("codegen/box/coroutines/multiModule/")                                                // KT-40121
        exclude("codegen/box/compileKotlinAgainstKotlin/clashingFakeOverrideSignatures.kt")           // KT-42020
        exclude("codegen/box/callableReference/genericConstructorReference.kt")                       // ???
        exclude("codegen/boxInline/multiplatform/defaultArguments/receiversAndParametersInLambda.kt") // KT-36880
    }

//    generateTestGroupSuiteWithJUnit5 {
//        cleanTestGroup(
//            testsRoot = "native/tests-blackbox/ext-tests-gen",
//            testDataRoot = "compiler/testData"
//        ) {
//            testClass<AbstractExtNativeBlackBoxTest> {
//                model("codegen/box")
//                model("codegen/boxInline")
//            }
//        }
//    }
}
