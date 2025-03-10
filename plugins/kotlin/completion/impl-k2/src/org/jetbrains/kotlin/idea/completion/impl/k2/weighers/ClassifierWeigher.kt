// Copyright 2000-2023 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package org.jetbrains.kotlin.idea.completion.weighers

import com.intellij.codeInsight.lookup.LookupElement
import com.intellij.codeInsight.lookup.LookupElementWeigher
import com.intellij.openapi.util.Key
import org.jetbrains.kotlin.analysis.api.KtAnalysisSession
import org.jetbrains.kotlin.analysis.api.components.KtScopeKind
import org.jetbrains.kotlin.analysis.api.symbols.KtClassLikeSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KtClassifierSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KtSymbol
import org.jetbrains.kotlin.analysis.api.symbols.markers.KtSymbolKind
import org.jetbrains.kotlin.psi.UserDataProperty

object ClassifierWeigher {
    const val WEIGHER_ID = "kotlin.classifierWeigher"
    const val LOW_PRIORITY = Int.MAX_VALUE

    private enum class Weight {
        LOCAL,
        NON_LOCAL
    }

    fun KtAnalysisSession.addWeight(lookupElement: LookupElement, symbol: KtSymbol, scopeKind: KtScopeKind?) {
        if (symbol !is KtClassifierSymbol) return

        val isLocal = (symbol as? KtClassLikeSymbol)?.symbolKind == KtSymbolKind.LOCAL
        val weight = if (isLocal) Weight.LOCAL else Weight.NON_LOCAL

        lookupElement.classifierWeight = CompoundWeight2(weight, scopeKind?.indexInTower ?: LOW_PRIORITY)
    }

    object Weigher : LookupElementWeigher(WEIGHER_ID) {
        override fun weigh(element: LookupElement): Comparable<*> =
            element.classifierWeight ?: CompoundWeight2(Weight.NON_LOCAL, LOW_PRIORITY)
    }

    private var LookupElement.classifierWeight: CompoundWeight2<Weight, Int>? by UserDataProperty(
        Key<CompoundWeight2<Weight, Int>>("KOTLIN_CLASSIFIER_WEIGHT")
    )
}