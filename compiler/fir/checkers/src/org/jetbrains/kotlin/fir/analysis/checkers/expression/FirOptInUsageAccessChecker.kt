/*
 * Copyright 2010-2021 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.fir.analysis.checkers.expression

import org.jetbrains.kotlin.fir.FirElement
import org.jetbrains.kotlin.fir.FirFakeSourceElementKind
import org.jetbrains.kotlin.fir.analysis.checkers.context.CheckerContext
import org.jetbrains.kotlin.fir.analysis.diagnostics.DiagnosticReporter
import org.jetbrains.kotlin.fir.expressions.FirQualifiedAccess
import org.jetbrains.kotlin.fir.expressions.FirVariableAssignment
import org.jetbrains.kotlin.fir.expressions.impl.FirNoReceiverExpression
import org.jetbrains.kotlin.fir.references.FirResolvedNamedReference
import org.jetbrains.kotlin.fir.resolve.fullyExpandedType
import org.jetbrains.kotlin.fir.symbols.impl.FirPropertySymbol
import org.jetbrains.kotlin.fir.types.coneType

object FirOptInUsageAccessChecker : FirQualifiedAccessChecker() {
    override fun check(expression: FirQualifiedAccess, context: CheckerContext, reporter: DiagnosticReporter) {
        val sourceKind = expression.source?.kind
        if (sourceKind is FirFakeSourceElementKind.DataClassGeneratedMembers ||
            sourceKind is FirFakeSourceElementKind.PropertyFromParameter
        ) return
        val reference = expression.calleeReference as? FirResolvedNamedReference ?: return
        val resolvedSymbol = reference.resolvedSymbol
        val dispatchReceiverType =
            expression.dispatchReceiver.takeIf { it !is FirNoReceiverExpression }?.typeRef?.coneType?.fullyExpandedType(context.session)
        with(FirOptInUsageBaseChecker) {
            val fromSetter: Boolean
            val element: FirElement

            if (expression is FirVariableAssignment && resolvedSymbol is FirPropertySymbol) {
                fromSetter = true
                element = expression.lValue
            } else {
                fromSetter = false
                element = expression
            }

            val experimentalities = resolvedSymbol.loadExperimentalities(context, fromSetter = fromSetter, dispatchReceiverType) +
                    loadExperimentalitiesFromTypeArguments(context, expression.typeArguments)
            reportNotAcceptedExperimentalities(experimentalities, element, context, reporter)
        }
    }
}