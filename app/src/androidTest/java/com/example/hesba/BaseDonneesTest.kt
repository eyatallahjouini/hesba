package com.example.hesba

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.hesba.auth.AuthRepository
import com.example.hesba.auth.AuthResult
import com.example.hesba.auth.PasswordHasher
import com.example.hesba.data.HesbaDatabase
import com.example.hesba.data.HesbaRepository
import com.example.hesba.data.HesbaRepository.IngredientSave
import com.example.hesba.data.Ingredient
import com.example.hesba.data.LigneRecette
import com.example.hesba.data.PrixHistorique
import com.example.hesba.data.Recette
import com.example.hesba.domain.HomeSummary
import com.example.hesba.domain.WhatIf
import com.example.hesba.domain.invoice.IngredientMatcher
import com.example.hesba.domain.invoice.InvoiceParser
import com.example.hesba.domain.invoice.InvoiceReview
import com.example.hesba.domain.invoice.MatchCandidate
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Parcours complets sur une vraie base Room (en mémoire) : compte → ingrédient → recette → facture → alerte. */
@RunWith(AndroidJUnit4::class)
class BaseDonneesTest {

    private lateinit var db: HesbaDatabase
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Before
    fun creerBase() {
        db = Room.inMemoryDatabaseBuilder(context, HesbaDatabase::class.java).build()
    }

    @After
    fun fermerBase() = db.close()

    @Test
    fun motDePasseSaleEtHache() {
        val h1 = PasswordHasher.hash("secret-123")
        val h2 = PasswordHasher.hash("secret-123")
        assertNotEquals(h1, h2)                          // sel différent à chaque fois
        assertFalse(h1.contains("secret"))
        assertTrue(PasswordHasher.verify("secret-123", h1))
        assertFalse(PasswordHasher.verify("secret-124", h1))
        assertFalse(PasswordHasher.verify("x", "pas-un-hash"))
    }

    @Test
    fun parcoursCompletCompteIngredientRecetteFactureAlerte() = runBlocking {
        // 1. compte
        val auth = AuthRepository(db.utilisateurDao())
        assertTrue(auth.inscrire("Yasmine", "Pâtisserie Yasmine", "court", "12345678", "domicile", true) is AuthResult.Error)
        val r = auth.inscrire("Yasmine", "Pâtisserie Yasmine", "Yasmine@Test.tn", "12345678", "domicile", true) as AuthResult.Success
        assertTrue(auth.login("yasmine@test.tn", "12345678") is AuthResult.Success)
        assertTrue(auth.login("yasmine@test.tn", "mauvais!") is AuthResult.Error)
        val repo = HesbaRepository(db, r.userId)

        // 2. ingrédients (doublon refusé, insensible aux accents)
        val farine = (repo.saveIngredient(null, Ingredient(nom = "Farine", unite = "g", quantiteEmballage = 1000.0, prixMillimes = 1200)) as IngredientSave.Saved).id
        val beurre = (repo.saveIngredient(null, Ingredient(nom = "Beurre", unite = "g", quantiteEmballage = 250.0, prixMillimes = 3500)) as IngredientSave.Saved).id
        assertTrue(repo.saveIngredient(null, Ingredient(nom = "FARÏNE", unite = "g", quantiteEmballage = 1.0, prixMillimes = 1)) is IngredientSave.Duplicate)

        // 3. recette : prix pratiqué 1,500 → marge 52,5 %
        val gateau = repo.saveRecipe(
            null, Recette(nom = "Gâteau", nombreParts = 8, margeVoulue = 40.0, prixVenteMillimes = 1500),
            listOf(LigneRecette(recetteId = 0, ingredientId = farine, quantite = 0.5, unite = "kg"), LigneRecette(recetteId = 0, ingredientId = beurre, quantite = 250.0, unite = "g"))
        )
        assertEquals(513L, db.recetteDao().parId(gateau)!!.coutParPartMillimes)   // (600 + 3500) ÷ 8 = 512,5 → 513

        // 4. simulateur : calcul seulement, la base ne change pas
        val ws = repo.snapshot()
        val sim = WhatIf.simulate(ws, beurre, 6000)
        assertEquals(825L, sim[0].after.costPerPortionMillimes)
        assertEquals(3500L, db.ingredientDao().parId(beurre)!!.prixMillimes)

        // 5. facture : lecture → vérification → application en une transaction
        val invoice = InvoiceParser.parse(listOf("Beurre doux 250 g 4 5,200 20,800", "Farine 25 kg 1 32,500 32,500"))
        val matcher = IngredientMatcher(ws.ingredients.map { MatchCandidate(it.id, it.nom) }, emptyList())
        val options = InvoiceReview.Options(null, false, null, false)
        val lines = InvoiceReview.initial(invoice, matcher, ws.ingredientsById, options)
        val updates = lines.filter { it.action == InvoiceReview.Action.ACCEPT }.map {
            HesbaRepository.PriceUpdate(it.ingredientId!!, InvoiceReview.evaluate(it, ws.ingredientsById[it.ingredientId], options).newPriceMillimes!!)
        }
        assertEquals(2, updates.size)
        val result = repo.applyPrices(updates, PrixHistorique.SOURCE_FACTURE)
        assertEquals(2, result.updatedIngredients)
        assertEquals(5200L, db.ingredientDao().parId(beurre)!!.prixMillimes)
        assertEquals(2, db.prixHistoriqueDao().tout(r.userId).first().size)
        val impact = result.impacts.single()
        assertEquals(513L, impact.before.costPerPortionMillimes)
        assertEquals(1300L, db.ingredientDao().parId(farine)!!.prixMillimes)    // 32,500 ÷ 25 kg
        assertEquals(731L, impact.after.costPerPortionMillimes)        // (650 + 5200) ÷ 8 = 731,25
        assertFalse(impact.fallsBelowTarget)                           // marge (1500 − 731) ÷ 1500 = 51,3 % : reste au-dessus de 40 %
        assertEquals(731L, db.recetteDao().parId(gateau)!!.coutParPartMillimes)

        // 6. Accueil : la hausse de prix apparaît dans les alertes
        val summary = HomeSummary.build(repo.snapshot(), db.prixHistoriqueDao().tout(r.userId).first())
        assertTrue(summary.alerts.any { it is HomeSummary.Alert.PriceChanged && it.ingredient.id == beurre })

        // 7. un ingrédient utilisé ne peut pas être supprimé
        assertEquals(listOf("Gâteau"), repo.deleteIngredient(db.ingredientDao().parId(beurre)!!).map { it.nom })

        // 8. duplication : nouvelle recette avec les mêmes lignes
        val copy = repo.duplicateRecipe(gateau, "Gâteau (copie)")!!
        assertEquals(2, db.ligneRecetteDao().listePour(copy).size)
    }

    @Test
    fun margeSousLObjectifDeclencheUneAlerte() = runBlocking {
        val repo = HesbaRepository(db, -1)
        val pain = (repo.saveIngredient(null, Ingredient(nom = "Pain", unite = "piece", quantiteEmballage = 1.0, prixMillimes = 250)) as IngredientSave.Saved).id
        val id = repo.saveRecipe(null, Recette(nom = "Sandwich", nombreParts = 1, margeVoulue = 50.0, prixVenteMillimes = 600), listOf(LigneRecette(recetteId = 0, ingredientId = pain, quantite = 1.0, unite = "piece")))
        val r = repo.applyPrices(listOf(HesbaRepository.PriceUpdate(pain, 400)), PrixHistorique.SOURCE_FACTURE)
        val impact = r.impacts.single { it.recette.id == id }
        assertTrue(impact.fallsBelowTarget)                            // 58,3 % → 33,3 % sous l'objectif de 50 %
        val summary = HomeSummary.build(repo.snapshot(), emptyList())
        assertTrue(summary.alerts.first() is HomeSummary.Alert.BelowTarget)
    }

    @Test
    fun donneesDuModeEssaiRattacheesAuCompte() = runBlocking {
        val guest = HesbaRepository(db, -1)
        guest.saveIngredient(null, Ingredient(nom = "Sel", unite = "g", quantiteEmballage = 1000.0, prixMillimes = 500))
        val user = (AuthRepository(db.utilisateurDao()).inscrire("A", "", "a@b.tn", "12345678", "domicile", true) as AuthResult.Success).userId
        assertEquals(1, guest.transferGuestDataTo(user))
        assertEquals(1, db.ingredientDao().listePour(user).size)
        assertEquals(0, db.ingredientDao().listePour(-1).size)
    }
}
