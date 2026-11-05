# HESBA – Audit technique (phase 1)

Date : 2026-11-04 · Branche de travail : `hesba-complete`

## 1. Branches inspectées

| Branche | Dernier commit | Contenu | Statut vis-à-vis de `hesba-complete` |
|---|---|---|---|
| `main` (origin) | f76ee18 – merge PR #2 ayahesba | Room de base d'Aya + 1er écrans | ancêtre, non modifié |
| `eyatallah-calcul` | e9c01a7 – Finitions de mes pages | CostCalculator, conseiller de marge, recalcul, nav du bas, profil | ancêtre direct d'`eyatallah-integration` |
| `eyatallah-integration` | 00268a6 – Modifier/supprimer ingrédients et recettes | travail le plus récent d'Eya (photo, édition, suppression) | **point de départ** de `hesba-complete` |
| `Emna-ben-harbi` (origin) | a08e5cb – connexion, inscription, hachage | `auth/` (PBKDF2), Login/Register | fusionné (fast-forward, sans conflit) |
| `ayalachaalhesba` (origin) | 9d1678a – Sauvegarde du travail Aya | données par utilisateur, marge éditable, temps de préparation, loyer/employés dans le profil, changement de mot de passe | fusionné (fast-forward, sans conflit), **schéma corrigé ensuite** |
| `ayahesba` (origin) | af7b8cf | déjà contenu dans `main` | rien à reprendre |
| `sauvegarde-avant-fusion` (local) | 4907bfb | ancienne version avec écran Bilan | référence seulement, rien à reprendre (Bilan hors périmètre) |
| stash@{0} | « VenteDao non commité » | VenteDao (ventes) | **non appliqué** : ventes hors périmètre |

Aucune branche n'a été modifiée, poussée ni supprimée.

## 2. Pile technique constatée

- Kotlin + vues XML (pas de Compose), AGP 9.4.1 (Kotlin intégré), Gradle 9.6.0, KSP 2.3.12, JDK 21 (toolchain).
- compileSdk/targetSdk 37, minSdk 24.
- Room 2.7.2 (runtime, ktx, compiler via KSP), schémas exportés dans `app/schemas`.
- Material 1.14.0, AppCompat 1.8.0, ConstraintLayout 2.2.2. RecyclerView utilisé par `NouvelleRecetteActivity` mais **non déclaré** (arrive en transitif).
- Tests : JUnit 4 (`CostCalculatorTest`, `RecipeCostingTest`), 1 test instrumenté (`BaseDonneesTest`).

## 3. Base de données (Room, version 1)

Tables : `utilisateur`, `ingredient`, `recette`, `ligne_recette`, `vente`. Aucune migration n'existe.

| Table | Champs (version d'Eya, `1.json` d'origine, hash `f553715c…`) |
|---|---|
| utilisateur | id, prenom, email (unique), motDePasseHash, activite, politiqueConfidentialiteAcceptee, dateCreation |
| ingredient | id, nom, unite (g/ml/piece), quantiteEmballage REAL, **prixPaye REAL**, dateMiseAJour |
| recette | id, utilisateurId, nom, nombreParts, margeVoulue REAL, **coutParPart REAL**, **prixVente REAL?**, dateCreation |
| ligne_recette | id, recetteId (CASCADE), ingredientId (RESTRICT), quantite REAL, unite ; index unique (recetteId, ingredientId) |
| vente | id, recetteId (SET NULL), nomRecette, quantite, prixVenteUnitaire REAL, coutUnitaire REAL, date |

### Problème bloquant trouvé sur la branche d'Aya
`9d1678a` modifie les entités **sans changer la version** (toujours 1) et réécrit `1.json` (nouveau hash `772bfe57…`) :
ajout `utilisateur.nombreEmployes/salaireMoyenEmploye/loyerMensuel/volumeMensuel`, suppression de
`politiqueConfidentialiteAcceptee`, ajout `ingredient.utilisateurId`, `recette.tempsPreparationMinutes`.
Sur un téléphone qui a déjà la base v1 d'Eya, Room plante au démarrage (« Room cannot verify the data integrity »).
→ Correction : version 2 avec une migration 1→2 qui accepte **les deux variantes de v1** (Eya et Aya).

### Argent
Tous les montants sont en `Double`/REAL et calculés en `Double` ; l'affichage est à 2 décimales (`%.2f dt`)
alors que le dinar a 3 décimales (millimes). → migration vers des millimes entiers (INTEGER) + BigDecimal.

## 4. Écrans et état

| Écran | État | Remarques |
|---|---|---|
| LoginActivity | fonctionne (Emna) | mode invité (id −1) ; « Mot de passe oublié » = toast « bientôt disponible » (faux bouton) |
| RegisterActivity | fonctionne | `AuthRepository.inscrire` **ignore loyerMensuel et volumeMensuel** (non transmis à l'entité) |
| MainActivity | redirige vers Recettes | pas d'écran Accueil |
| RecettesActivity | liste, édition, suppression, photo | pas de recherche ni tri ; calcul de coût **dupliqué** avec valeurs inventées (1500 dt, 1200 dt, 160 h, 300/500 parts) |
| ComposeRecipeActivity | création/édition, coût en direct, marge éditable, temps de prépa | même calcul dupliqué ; arrondi d'affichage 2 décimales |
| IngredientsActivity | liste, édition, suppression avec avertissement | pas de recherche |
| NouvelIngredientActivity | ajout/édition, kg/L/pièce | pas de rendement (pertes), pas d'historique de prix |
| ProfilActivity (Aya) | profil, activité/loyer/employés, mot de passe, déconnexion | recalcule les recettes avec valeurs inventées ; pas de langue/thème |
| NouvelleRecetteActivity (Aya, ancienne) | **inaccessible** | remplacée par ComposeRecipeActivity ; marge fixe 40 %, utilisateur 1 en dur |
| MesRecettesActivity | **classe supprimée** par Aya mais toujours déclarée dans le Manifest | entrée morte |
| Bilan / Ventes | déjà retirés de l'interface | table `vente` conservée (décision à prendre) |

## 5. Ressources
- Palette (à conserver) : `safran #F28C28`, `brun #3E2723`, `creme #FFF8EE`, `vert #2E7D32`, `rouge #D32F2F`, `caramel #E9C9A0`
  + `brun_clair #6D4C41`, `brun_leger #5A413B`, `caramel_clair #F6E4C8`, `vert_clair`, `rouge_clair`, `safran_texte #9A5200`, `gris_hint #8D7B76`.
  `styles_recettes.xml` (Aya) redéfinit des quasi-doublons (`cream`, `brown #3B1F14`, `orange`).
- `strings.xml` ne contient que `app_name` : **tous les textes sont en dur** dans le code et les layouts.
- `values-night/themes.xml` vide : le mode sombre n'est pas géré (fonds `@color/creme` en dur).
- Pas d'arabe ni d'anglais, mais `supportsRtl="true"`.

## 6. Ce qui manque (à construire)
Accueil, recherche/tri des recettes, détail de recette, coûts cachés, rendement/pertes, simulateur « et si »,
liste de courses, duplication, PDF, historique des prix, scanner de factures (OCR FR + AR), table de synonymes,
langues FR/EN/AR, thème sombre, sauvegarde, tests de migration, tests financiers BigDecimal.

## 7. Fichier `hesba.db` à la racine du dépôt
Suivi par git (commit af7b8cf d'Aya), 8 200 octets, **inutilisable** : il commence par `FF FE` (UTF-16),
signe d'une copie via une redirection PowerShell. Ce n'est pas une base SQLite valide et l'appli ne l'utilise pas.
**Supprimé du dépôt** (décision d'Eya, 2026-11-05).

## 8. Build de départ
`./gradlew assembleDebug testDebugUnitTest` sur l'état fusionné : **OK** (1 min 36 s).
