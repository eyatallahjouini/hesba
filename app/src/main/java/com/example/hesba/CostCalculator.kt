package com.example.hesba

object CostCalculator {

    // ===== PARTIE 1 : les formules de la version 1 =====

    // Prix par unité = prix payé ÷ quantité de l'emballage
    // (Quantité en unité de base : ex. 1 kg = 1000.0 g)
    fun pricePerUnit(pricePaid: Double, packageQuantity: Double): Double {
        if (packageQuantity <= 0.0) return 0.0
        return pricePaid / packageQuantity
    }

    // Coût d'une ligne = quantité utilisée × prix par unité
    fun lineCost(quantityUsed: Double, pricePerUnit: Double): Double {
        return quantityUsed * pricePerUnit
    }

    // Coût de la recette = somme des lignes
    fun recipeCost(lineCosts: List<Double>): Double {
        return lineCosts.sum()
    }

    // Coût par pièce = coût de la recette ÷ rendement
    fun costPerPiece(recipeCost: Double, pieces: Double): Double {
        if (pieces <= 0.0) return 0.0
        return recipeCost / pieces
    }

    // Prix de vente HT = coût de revient ÷ (1 − marge voulue)
    // La marge s'écrit en décimal : 40 % = 0.40
    fun sellingPriceHT(cost: Double, desiredMargin: Double): Double {
        if (desiredMargin >= 1.0) return 0.0
        return cost / (1.0 - desiredMargin)
    }

    // ===== PARTIE 2 : pour les écrans (marge et bénéfice) =====

    // Bénéfice par pièce = prix de vente − coût par pièce
    fun profitPerPiece(sellingPrice: Double, costPerPiece: Double): Double {
        return sellingPrice - costPerPiece
    }

    // Marge réelle = (prix de vente − coût) ÷ prix de vente
    fun marginRate(sellingPrice: Double, cost: Double): Double {
        if (sellingPrice <= 0.0) return 0.0
        return (sellingPrice - cost) / sellingPrice
    }

    // Conseiller de marge : position de la ligne qui coûte le plus cher
    // Renvoie -1 si la liste est vide ou si toutes les lignes coûtent 0
    fun mostExpensiveIndex(lineCosts: List<Double>): Int {
        var best = -1
        for (i in lineCosts.indices) {
            if (lineCosts[i] > 0.0 && (best == -1 || lineCosts[i] > lineCosts[best])) best = i
        }
        return best
    }

    // ===== PARTIE 3 : évolutions (pertes, fluides, charges) =====

    // Avec pertes : coût réel = coût ÷ (1 − % de perte)
    fun costWithLoss(cost: Double, lossRate: Double): Double {
        if (lossRate >= 1.0) return 0.0
        return cost / (1.0 - lossRate)
    }

    // Énergie = puissance (kW) × heures × prix du kWh
    fun energyCost(powerKw: Double, hours: Double, pricePerKwh: Double): Double {
        return powerKw * hours * pricePerKwh
    }

    // Eau = litres × prix du m³ ÷ 1000
    fun waterCost(liters: Double, pricePerM3: Double): Double {
        return liters * pricePerM3 / 1000.0
    }

    // Main-d'œuvre = (salaire mensuel chargé ÷ heures par mois) × temps passé
    fun laborCost(monthlySalary: Double, hoursPerMonth: Double, hoursSpent: Double): Double {
        if (hoursPerMonth <= 0.0) return 0.0
        return (monthlySalary / hoursPerMonth) * hoursSpent
    }

    // Part des charges fixes par produit = charges fixes mensuelles ÷ unités vendues par mois
    fun fixedCostPerUnit(monthlyFixedCosts: Double, unitsSoldPerMonth: Double): Double {
        if (unitsSoldPerMonth <= 0.0) return 0.0
        return monthlyFixedCosts / unitsSoldPerMonth
    }

    // Seuil de rentabilité = charges fixes ÷ (prix de vente − coût variable par unité)
    // Le résultat est un nombre d'unités à vendre
    fun breakEvenUnits(fixedCosts: Double, sellingPrice: Double, variableCostPerUnit: Double): Double {
        val gainPerUnit = sellingPrice - variableCostPerUnit
        if (gainPerUnit <= 0.0) return 0.0
        return fixedCosts / gainPerUnit
    }
}