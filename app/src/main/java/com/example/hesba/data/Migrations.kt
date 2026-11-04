package com.example.hesba.data

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

object Migrations {

    val ALL: Array<Migration> get() = arrayOf(MIGRATION_1_2)

    /**
     * v1 → v2. Accepte les DEUX variantes de v1 qui existent sur les téléphones de l'équipe :
     *  - v1 « Eya » (version publiée) : prixPaye/coutParPart/prixVente en REAL, pas d'utilisateurId sur ingredient ;
     *  - v1 « Aya » (sauvegarde non versionnée) : + utilisateurId, tempsPreparationMinutes, loyer/salaire en REAL,
     *    sans politiqueConfidentialiteAcceptee.
     * Les colonnes présentes sont lues avec PRAGMA table_info puis les tables sont reconstruites
     * (créer → copier → supprimer → renommer), comme le fait Room pour ses migrations.
     * Les montants passent en millimes : ROUND(valeur × 1000). Seul l'arrondi au millime change les valeurs.
     * Le rendement des ingrédients existants vaut 100 % : les coûts actuels ne changent pas.
     * Room exécute la migration avant d'activer les clés étrangères, puis vérifie foreign_key_check.
     */
    val MIGRATION_1_2 = object : Migration(1, 2) {
        override fun migrate(db: SupportSQLiteDatabase) {
            val userCols = columns(db, "utilisateur")
            val ingCols = columns(db, "ingredient")
            val recCols = columns(db, "recette")

            // --- utilisateur ---
            db.execSQL(CREATE_UTILISATEUR.replace("`utilisateur`", "`utilisateur_new`"))
            val politique = if ("politiqueConfidentialiteAcceptee" in userCols) "politiqueConfidentialiteAcceptee" else "1"
            val employes = if ("nombreEmployes" in userCols) "nombreEmployes" else "0"
            val salaire = if ("salaireMoyenEmploye" in userCols) millimes("salaireMoyenEmploye") else "0"
            val loyer = if ("loyerMensuel" in userCols) millimes("loyerMensuel") else "0"
            val volume = if ("volumeMensuel" in userCols) "volumeMensuel" else "0"
            db.execSQL(
                "INSERT INTO `utilisateur_new` (id, prenom, email, motDePasseHash, activite, politiqueConfidentialiteAcceptee, " +
                    "nomCommerce, ville, nombreEmployes, salaireMoyenMillimes, heuresTravailMois, loyerMensuelMillimes, volumeMensuel, dateCreation) " +
                    "SELECT id, prenom, email, motDePasseHash, activite, $politique, '', '', $employes, $salaire, 0, $loyer, $volume, dateCreation " +
                    "FROM `utilisateur`"
            )
            db.execSQL("DROP TABLE `utilisateur`")
            db.execSQL("ALTER TABLE `utilisateur_new` RENAME TO `utilisateur`")
            db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_utilisateur_email` ON `utilisateur` (`email`)")

            // Propriétaire des données sans compte valable : le premier compte, sinon le mode invité (-1)
            val owner = "COALESCE((SELECT MIN(id) FROM `utilisateur`), -1)"
            fun ownerOf(column: String) =
                "CASE WHEN $column = -1 OR $column IN (SELECT id FROM `utilisateur`) THEN $column ELSE $owner END"

            // --- ingredient ---
            db.execSQL(CREATE_INGREDIENT.replace("`ingredient`", "`ingredient_new`"))
            val ingOwner = if ("utilisateurId" in ingCols) ownerOf("utilisateurId") else owner
            db.execSQL(
                "INSERT INTO `ingredient_new` (id, utilisateurId, nom, unite, quantiteEmballage, prixMillimes, rendementPourcent, dateMiseAJour) " +
                    "SELECT id, $ingOwner, nom, unite, quantiteEmballage, ${millimes("prixPaye")}, 100.0, dateMiseAJour FROM `ingredient`"
            )
            db.execSQL("DROP TABLE `ingredient`")
            db.execSQL("ALTER TABLE `ingredient_new` RENAME TO `ingredient`")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_ingredient_utilisateurId` ON `ingredient` (`utilisateurId`)")

            // --- recette ---
            db.execSQL(CREATE_RECETTE.replace("`recette`", "`recette_new`"))
            val prep = if ("tempsPreparationMinutes" in recCols) "tempsPreparationMinutes" else "0"
            db.execSQL(
                "INSERT INTO `recette_new` (id, utilisateurId, nom, nombreParts, margeVoulue, coutParPartMillimes, prixVenteMillimes, " +
                    "tempsPreparationMinutes, emballageParPartMillimes, gazParLotMillimes, mainOeuvreParLotMillimes, dateCreation, dateMiseAJour) " +
                    "SELECT id, ${ownerOf("utilisateurId")}, nom, nombreParts, margeVoulue, ${millimes("coutParPart")}, " +
                    "CASE WHEN prixVente IS NULL THEN NULL ELSE ${millimes("prixVente")} END, " +
                    "$prep, 0, 0, 0, dateCreation, dateCreation FROM `recette`"
            )
            db.execSQL("DROP TABLE `recette`")
            db.execSQL("ALTER TABLE `recette_new` RENAME TO `recette`")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_recette_utilisateurId` ON `recette` (`utilisateurId`)")

            // --- nouvelles tables ---
            db.execSQL(CREATE_FACTURE)
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_facture_utilisateurId` ON `facture` (`utilisateurId`)")
            db.execSQL(CREATE_PRIX_HISTORIQUE)
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_prix_historique_ingredientId` ON `prix_historique` (`ingredientId`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_prix_historique_factureId` ON `prix_historique` (`factureId`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_prix_historique_date` ON `prix_historique` (`date`)")
            db.execSQL(CREATE_SYNONYME)
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_synonyme_ingredientId` ON `synonyme` (`ingredientId`)")
            db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_synonyme_utilisateurId_texteNormalise` ON `synonyme` (`utilisateurId`, `texteNormalise`)")
            // ligne_recette et vente ne changent pas
        }
    }

    private fun millimes(column: String) = "CAST(ROUND(COALESCE($column, 0) * 1000) AS INTEGER)"

    private fun columns(db: SupportSQLiteDatabase, table: String): Set<String> {
        val result = mutableSetOf<String>()
        db.query("PRAGMA table_info(`$table`)").use { c ->
            val nameIndex = c.getColumnIndex("name")
            while (c.moveToNext()) result += c.getString(nameIndex)
        }
        return result
    }

    // Copie exacte des createSql de schemas/…/2.json (vérifiée par MigrationTest)
    private const val CREATE_UTILISATEUR =
        "CREATE TABLE IF NOT EXISTS `utilisateur` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `prenom` TEXT NOT NULL, `email` TEXT NOT NULL, `motDePasseHash` TEXT NOT NULL, `activite` TEXT NOT NULL, `politiqueConfidentialiteAcceptee` INTEGER NOT NULL, `nomCommerce` TEXT NOT NULL, `ville` TEXT NOT NULL, `nombreEmployes` INTEGER NOT NULL, `salaireMoyenMillimes` INTEGER NOT NULL, `heuresTravailMois` INTEGER NOT NULL, `loyerMensuelMillimes` INTEGER NOT NULL, `volumeMensuel` INTEGER NOT NULL, `dateCreation` INTEGER NOT NULL)"
    private const val CREATE_INGREDIENT =
        "CREATE TABLE IF NOT EXISTS `ingredient` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `utilisateurId` INTEGER NOT NULL, `nom` TEXT NOT NULL, `unite` TEXT NOT NULL, `quantiteEmballage` REAL NOT NULL, `prixMillimes` INTEGER, `rendementPourcent` REAL NOT NULL, `dateMiseAJour` INTEGER NOT NULL)"
    private const val CREATE_RECETTE =
        "CREATE TABLE IF NOT EXISTS `recette` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `utilisateurId` INTEGER NOT NULL, `nom` TEXT NOT NULL, `nombreParts` INTEGER NOT NULL, `margeVoulue` REAL NOT NULL, `coutParPartMillimes` INTEGER NOT NULL, `prixVenteMillimes` INTEGER, `tempsPreparationMinutes` INTEGER NOT NULL, `emballageParPartMillimes` INTEGER NOT NULL, `gazParLotMillimes` INTEGER NOT NULL, `mainOeuvreParLotMillimes` INTEGER NOT NULL, `dateCreation` INTEGER NOT NULL, `dateMiseAJour` INTEGER NOT NULL)"
    private const val CREATE_FACTURE =
        "CREATE TABLE IF NOT EXISTS `facture` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `utilisateurId` INTEGER NOT NULL, `fournisseur` TEXT NOT NULL, `dateFacture` TEXT NOT NULL, `imagePath` TEXT, `langue` TEXT NOT NULL, `nombreLignes` INTEGER NOT NULL, `totalMillimes` INTEGER, `dateImport` INTEGER NOT NULL)"
    private const val CREATE_PRIX_HISTORIQUE =
        "CREATE TABLE IF NOT EXISTS `prix_historique` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `ingredientId` INTEGER NOT NULL, `ancienPrixMillimes` INTEGER, `nouveauPrixMillimes` INTEGER, `ancienneQuantite` REAL NOT NULL, `nouvelleQuantite` REAL NOT NULL, `source` TEXT NOT NULL, `factureId` INTEGER, `date` INTEGER NOT NULL, FOREIGN KEY(`ingredientId`) REFERENCES `ingredient`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE , FOREIGN KEY(`factureId`) REFERENCES `facture`(`id`) ON UPDATE NO ACTION ON DELETE SET NULL )"
    private const val CREATE_SYNONYME =
        "CREATE TABLE IF NOT EXISTS `synonyme` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `utilisateurId` INTEGER NOT NULL, `ingredientId` INTEGER NOT NULL, `texte` TEXT NOT NULL, `texteNormalise` TEXT NOT NULL, FOREIGN KEY(`ingredientId`) REFERENCES `ingredient`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )"
}
