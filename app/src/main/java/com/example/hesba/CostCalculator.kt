package com.example.hesba

object CostCalculator {

    // ===== PARTIE 1 : les formules de la version 1 =====

    // prix par unité = prix payé ÷ quantité de l'emballage
    // (quantité en unité de base : 1 kg = 1000.0 g)
    fun pricePerUnit(pricePaid: Double, packageQuantity: Double): Double {
        if (packageQuantity <= 0.0) return 0.0
        return pricePaid / packageQuantity
    }

    // coût d'une ligne = quantité utilisée × prix par unité
    fun lineCost(quantityUsed: Double, pricePerUnit: Double): Double {
        return quantityUsed * pricePerUnit
    }

    // coût de la recette = somme des lignes
    fun recipeCost(lineCosts: List<Double>): Double {
        return lineCosts.sum()
    }

    // coût par pièce = coût de la recette ÷ rendement
    fun costPerPiece(recipeCost: Double, pieces: Double): Double {
        if (pieces <= 0.0) return 0.0
        return recipeCost / pieces
    }

    // prix de vente HT = coût de revient ÷ (1 − marge voulue)
    // la marge s'écrit en décimal : 40 % = 0.40
    fun sellingPriceHT(cost: Double, desiredMargin: Double): Double {
        if (desiredMargin >= 1.0) return 0.0
        return cost / (1.0 - desiredMargin)
    }

    // ===== PARTIE 2 : pour les écrans (marge et bénéfice) =====

    // bénéfice par pièce = prix de vente − coût par pièce
    fun profitPerPiece(sellingPrice: Double, costPerPiece: Double): Double {
        return sellingPrice - costPerPiece
    }

    // marge réelle = (prix de vente − coût) ÷ prix de vente
    fun marginRate(sellingPrice: Double, cost: Double): Double {
        if (sellingPrice <= 0.0) return 0.0
        return (sellingPrice - cost) / sellingPrice
    }

    // ===== PARTIE 3 : pour plus tard (version 3) =====

    // avec pertes : coût réel = coût ÷ (1 − % de perte)
    fun costWithLoss(cost: Double, lossRate: Double): Double {
        if (lossRate >= 1.0) return 0.0
        return cost / (1.0 - lossRate)
    }

    // énergie = puissance (kW) × heures × prix du kWh
    fun energyCost(powerKw: Double, hours: Double, pricePerKwh: Double): Double {
        return powerKw * hours * pricePerKwh
    }

    // eau = litres × prix du m³ ÷ 1000
    fun waterCost(liters: Double, pricePerM3: Double): Double {
        return liters * pricePerM3 / 1000.0
    }

    // main-d'œuvre = (salaire mensuel chargé ÷ heures par mois) × temps passé
    fun laborCost(monthlySalary: Double, hoursPerMonth: Double, hoursSpent: Double): Double {
        if (hoursPerMonth <= 0.0) return 0.0
        return (monthlySalary / hoursPerMonth) * hoursSpent
    }

    // part des charges fixes par produit = charges fixes mensuelles ÷ unités vendues par mois
    fun fixedCostPerUnit(monthlyFixedCosts: Double, unitsSoldPerMonth: Double): Double {
        if (unitsSoldPerMonth <= 0.0) return 0.0
        return monthlyFixedCosts / unitsSoldPerMonth
    }

    // seuil de rentabilité = charges fixes ÷ (prix de vente − coût variable par unité)
    // le résultat est un nombre d'unités à vendre
    fun breakEvenUnits(fixedCosts: Double, sellingPrice: Double, variableCostPerUnit: Double): Double {
        val gainPerUnit = sellingPrice - variableCostPerUnit
        if (gainPerUnit <= 0.0) return 0.0
        return fixedCosts / gainPerUnit
    }
}