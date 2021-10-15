/*
 * Copyright 2010-2020 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.fir.java

import org.jetbrains.kotlin.builtins.jvm.JavaToKotlinClassMap
import org.jetbrains.kotlin.descriptors.Visibilities
import org.jetbrains.kotlin.descriptors.Visibility
import org.jetbrains.kotlin.descriptors.java.JavaVisibilities
import org.jetbrains.kotlin.fir.*
import org.jetbrains.kotlin.fir.declarations.FirDeclaration
import org.jetbrains.kotlin.fir.declarations.FirFile
import org.jetbrains.kotlin.fir.declarations.synthetic.FirSyntheticPropertyAccessor
import org.jetbrains.kotlin.fir.declarations.utils.visibility
import org.jetbrains.kotlin.fir.resolve.calls.FirSyntheticPropertySymbol
import org.jetbrains.kotlin.fir.resolve.calls.ReceiverValue
import org.jetbrains.kotlin.fir.symbols.FirBasedSymbol
import org.jetbrains.kotlin.fir.symbols.impl.ConeClassLikeLookupTagImpl
import org.jetbrains.kotlin.fir.symbols.impl.FirNamedFunctionSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirPropertyAccessorSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirVariableSymbol
import org.jetbrains.kotlin.name.Name

@NoMutableState
object FirJavaVisibilityChecker : FirVisibilityChecker() {
    override fun platformVisibilityCheck(
        declarationVisibility: Visibility,
        symbol: FirBasedSymbol<*>,
        useSiteFile: FirFile,
        containingDeclarations: List<FirDeclaration>,
        dispatchReceiver: ReceiverValue?,
        session: FirSession,
        isCallToPropertySetter: Boolean,
    ): Boolean {
        val isSyntheticProperty = symbol.fir is FirSyntheticPropertyAccessor
        return when (declarationVisibility) {
            JavaVisibilities.ProtectedAndPackage, JavaVisibilities.ProtectedStaticVisibility -> {
                if (symbol.packageFqName() == useSiteFile.packageFqName) {
                    true
                } else {
                    val ownerLookupTag = symbol.getOwnerLookupTag() ?: return false
                    val isVariableOrNamedFunction =
                        symbol is FirVariableSymbol || symbol is FirNamedFunctionSymbol || symbol is FirPropertyAccessorSymbol
                    if (canSeeProtectedMemberOf(
                            containingDeclarations, dispatchReceiver, ownerLookupTag, session,
                            isVariableOrNamedFunction, isSyntheticProperty
                        )
                    ) return true
                    val classId = ownerLookupTag.classId
                    if (classId.packageFqName.startsWith(JAVA_NAME)) {
                        val kotlinClassId = JavaToKotlinClassMap.mapJavaToKotlin(classId.asSingleFqName())
                        if (kotlinClassId != null && canSeeProtectedMemberOf(
                                containingDeclarations, dispatchReceiver, ConeClassLikeLookupTagImpl(kotlinClassId), session,
                                isVariableOrNamedFunction, isSyntheticProperty
                            )
                        ) return true
                    }

                    // FE1.0 allows calling public setters with property assignment syntax if the getter is protected.
                    if (!isCallToPropertySetter || symbol !is FirSyntheticPropertySymbol) return false
                    symbol.setterSymbol?.visibility == Visibilities.Public
                }
            }

            JavaVisibilities.PackageVisibility -> {
                if (symbol.packageFqName() == useSiteFile.packageFqName) {
                    true
                } else if (isSyntheticProperty) {
                    symbol.getOwnerLookupTag()?.classId?.packageFqName == useSiteFile.packageFqName
                } else {
                    false
                }
            }

            else -> true
        }
    }

    private val JAVA_NAME = Name.identifier("java")
}
