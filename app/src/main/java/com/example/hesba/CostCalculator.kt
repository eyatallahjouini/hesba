object CostCalculator {

    // prix par unité = prix payé ÷ quantité de l'emballage
    fun pricePerUnit(pricePaid: Double, packageQuantity: Double): Double {
        return pricePaid / packageQuantity
    }
}