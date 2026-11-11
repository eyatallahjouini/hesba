package com.example.hesba.domain

import com.example.hesba.data.Recette
import com.example.hesba.domain.HomeSummary.Alert
import java.math.BigDecimal
import java.math.RoundingMode

/**
 * Les chiffres des cartes de l'Accueil (grille Bento), calculés à partir des données réelles.
 * Seules les recettes dont la marge réelle est connue (prix de vente fixé, coût complet) comptent
 * dans la moyenne et dans la meilleure marge.
 */
data class HomeDashboard(
    /** Moyenne simple des marges réelles, à 0,1 près ; null si aucune recette n'a de marge connue. */
    val averageMargin: BigDecimal?,
    /** Moyenne des marges voulues des mêmes recettes, à 0,1 près. */
    val averageTarget: BigDecimal?,
    /** Nombre de recettes prises dans la moyenne. */
    val pricedCount: Int,
    /** Nombre de recettes sous leur marge voulue. */
    val belowTargetCount: Int,
    val best: Best?,
    /** Le changement de prix le plus marquant des 14 derniers jours. */
    val topPriceChange: Alert.PriceChanged?
) {
    data class Best(val recette: Recette, val margin: BigDecimal, val priceMillimes: Long, val costMillimes: Long)

    companion object {
        fun build(ws: Workspace, summary: HomeSummary): HomeDashboard {
            data class Priced(val r: Recette, val margin: BigDecimal, val price: Long, val cost: Long)

            val priced = ws.recipes.mapNotNull { r ->
                val c = ws.cost(r)
                val margin = c.realMarginPercent ?: return@mapNotNull null
                val price = r.prixVenteMillimes ?: return@mapNotNull null
                val cost = c.costPerPortionMillimes ?: return@mapNotNull null
                if (!c.isComplete) return@mapNotNull null
                Priced(r, margin, price, cost)
            }

            fun mean(values: List<BigDecimal>): BigDecimal? =
                if (values.isEmpty()) null
                else values.fold(BigDecimal.ZERO, BigDecimal::add).divide(BigDecimal(values.size), 1, RoundingMode.HALF_UP)

            val best = priced.maxByOrNull { it.margin }
            return HomeDashboard(
                averageMargin = mean(priced.map { it.margin }),
                averageTarget = mean(priced.map { BigDecimal(it.r.margeVoulue.toString()) }),
                pricedCount = priced.size,
                belowTargetCount = priced.count { it.margin < BigDecimal(it.r.margeVoulue.toString()) },
                best = best?.let { Best(it.r, it.margin, it.price, it.cost) },
                // HomeSummary trie déjà les changements de prix du plus fort au plus faible
                topPriceChange = summary.alerts.filterIsInstance<Alert.PriceChanged>().firstOrNull()
            )
        }
    }
}
