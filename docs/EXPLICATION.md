# HESBA expliqué simplement

HESBA aide une petite pâtisserie, un fast-food ou un petit restaurant tunisien à connaître **le vrai coût
d'une part** de chaque recette, en dinars, et à fixer un prix de vente qui garde la marge voulue.
Tout est gratuit et reste **sur le téléphone** (pas de serveur, pas d'Internet).

---

## 1. Comment l'appli est construite (architecture)

On peut la voir comme trois étages :

```
┌──────────────── Écrans (Activities + XML) ────────────────┐
│ Accueil · Mes recettes · Fiche recette · Composer         │
│ Ingrédients · Profil · Simulateur · Courses · Scanner      │
└───────────────┬───────────────────────────────────────────┘
                │ demandent / affichent
┌───────────────▼──────── Logique (Kotlin pur) ─────────────┐
│ CostCalculator  : toutes les formules, en BigDecimal       │
│ domain/         : unités, recherche, simulateur, courses,  │
│                   alertes, parseur de factures, matching   │
└───────────────┬───────────────────────────────────────────┘
                │ lit / écrit
┌───────────────▼──────── Données (Room / SQLite) ──────────┐
│ HesbaRepository : toutes les écritures, transactions       │
│ HesbaDatabase v2 + Migrations (1 → 2)                      │
└────────────────────────────────────────────────────────────┘
```

* **Kotlin + vues XML** (pas de Compose), comme demandé.
* La **logique de calcul ne dépend pas d'Android** : on peut la tester sur l'ordinateur en une seconde
  (62 tests unitaires).
* Chaque écran est une `Activity`. Les 4 onglets du bas (Accueil, Mes recettes, Ingrédients, Profil)
  sont gérés par `BottomNav.kt`.
* `HesbaRepository` est le **seul endroit qui écrit** dans la base : c'est là qu'on garantit
  « prix + historique + recalcul » dans une seule transaction.

### Fichiers à connaître

| Fichier | Rôle |
|---|---|
| `CostCalculator.kt` | Les formules (coût de ligne, coût par part, prix conseillé, marge…) |
| `domain/Money.kt` | La règle d'arrondi au millime, la lecture des montants |
| `domain/Units.kt` | Conversions g/kg, ml/L, pièce/douzaine, emballage |
| `RecipeCosting.kt` | Passe des tables de la base aux formules |
| `data/Migrations.kt` | Passage de la base v1 à la v2 sans perte |
| `data/HesbaRepository.kt` | Toutes les écritures (transactions) |
| `domain/invoice/InvoiceParser.kt` | Lit le texte d'une facture (français et arabe) |
| `domain/invoice/IngredientMatcher.kt` | Trouve l'ingrédient qui correspond à une ligne de facture |
| `domain/invoice/InvoiceReview.kt` | Ancien prix / nouveau prix, doublons, prudence |
| `ocr/` | Les deux moteurs de lecture (ML Kit et Tesseract) |

---

## 2. L'argent : millimes et BigDecimal

Le dinar a **3 décimales** (1 DT = 1000 millimes).

* **Stockage** : tous les montants sont enregistrés en **millimes entiers**.
  1,250 DT est enregistré `1250`. Un entier ne fait jamais d'erreur d'arrondi.
* **Calcul** : on utilise `BigDecimal` (nombres décimaux exacts) et non `Double`.
  Avec `Double`, 0,1 + 0,2 donne 0,30000000000000004 ; avec BigDecimal, 0,3 exactement.
* **Arrondi unique** (fichier `Money.kt`) :
  1. aucun arrondi pendant le calcul (le prix au gramme peut valoir 3,8 millimes) ;
  2. on arrondit **au millime le plus proche** (règle HALF_UP : 0,0005 → 0,001) seulement les résultats
     affichés ou enregistrés : coût d'une ligne, coût du lot, coût par part, prix conseillé ;
  3. le prix conseillé est calculé à partir du coût par part **déjà arrondi**, pour que l'on puisse
     refaire le calcul à la main avec les chiffres de l'écran.

---

## 3. Les formules

Exemple qui sert dans tout ce document : **sandwich**, 10 parts, marge voulue 40 %.
Harissa : 1 kg acheté 6,000 DT. Pain : 0,250 DT la pièce.
Recette : 50 g de harissa, 10 pains.

| Étape | Formule | Exemple |
|---|---|---|
| Prix réel par unité | prix de l'emballage ÷ (quantité × rendement %) | 6000 ÷ 1000 g = 6 millimes/g |
| Coût d'une ligne | quantité utilisée × prix réel par unité | 50 g × 6 = 300 ; 10 × 250 = 2500 |
| Coût du lot | somme des lignes + gaz/électricité + main-d'œuvre | 2800 millimes |
| Coût par part | coût du lot ÷ parts + emballage par part + part du loyer | 2800 ÷ 10 = **0,280 DT** |
| Prix conseillé | coût par part ÷ (1 − marge) | 0,280 ÷ 0,60 = **0,467 DT** |
| Marge réelle | (prix de vente − coût) ÷ prix de vente | (0,500 − 0,280) ÷ 0,500 = 44 % |

### Le rendement (les pertes)

Quand on achète 1 kg de pommes de terre, on n'en utilise que 800 g après épluchage : le rendement est de
80 %. Les 200 g perdus ont quand même été payés. Donc :

> prix réel = prix payé ÷ (quantité × 80 %)

1 kg à 1,000 DT avec 80 % utilisable revient à 1,250 DT le kilo **utilisable**. Dans les recettes, on écrit
la quantité réellement utilisée. Les ingrédients qui existaient avant ont un rendement de **100 %**, donc
leurs coûts n'ont pas changé.

### Les coûts cachés (facultatifs, par recette)

* **Emballage par part** (boîte, sachet) : ajouté à chaque part.
* **Gaz et électricité du lot** et **main-d'œuvre du lot** : ajoutés au lot, donc divisés par les parts.
* **Part du loyer** (réglée dans le Profil) : loyer mensuel ÷ parts produites par mois.
* **Estimation de main-d'œuvre** (bouton dans Composer) : salaire mensuel ÷ heures par mois × temps de
  préparation. Ces valeurs viennent du travail d'Aya, mais **sans chiffres inventés** : si le profil ne
  les renseigne pas, rien n'est ajouté.

### Les cas difficiles (aucune erreur cachée)

| Cas | Ce que fait HESBA |
|---|---|
| 0 part | pas de coût par part (affiché « — ») et demande de saisir les parts |
| Quantité 0 | la ligne compte 0 et la saisie est refusée à l'enregistrement |
| Prix manquant | la recette est marquée « coût incomplet » (étiquette et alerte sur l'Accueil) |
| Unité incompatible (kg pour un ingrédient en pièces) | signalé « Unité ? », pas de faux calcul |
| Marge ≥ 100 % | refusée (division impossible) |

### Prix de vente « pratiqué » et alertes

Chaque recette garde **le prix auquel tu vends vraiment** (au départ, le prix conseillé). Quand le prix d'un
ingrédient monte, le coût monte mais le prix de vente ne change pas tout seul. La **marge réelle** baisse,
et si elle passe sous la marge voulue, l'Accueil affiche une alerte. C'est ce qui permet de réagir.

---

## 4. La base de données

Base SQLite gérée par **Room**, fichier `hesba.db` dans la mémoire privée de l'appli.

| Table | Contenu |
|---|---|
| `utilisateur` | compte local : prénom, e-mail, empreinte du mot de passe, commerce, charges |
| `ingredient` | nom, unité de base (g, ml, pièce), quantité et prix de l'emballage (millimes), rendement |
| `recette` | nom, parts, marge voulue, prix pratiqué, coûts cachés, dernier coût calculé |
| `ligne_recette` | recette + ingrédient + quantité + unité (**jamais le prix**) |
| `prix_historique` | chaque changement de prix : ancien, nouveau, source (manuel, facture, simulateur) |
| `facture` | factures scannées : fournisseur, date, chemin de l'image privée |
| `synonyme` | autres noms d'un ingrédient (ex. « فارينة » = Farine) appris par le scanner |
| `vente` | table d'origine conservée telle quelle (non utilisée, décision à prendre) |

**Pourquoi la ligne ne garde pas le prix ?** Pour qu'un changement de prix d'ingrédient se répercute
automatiquement dans toutes les recettes.

### Les mots de passe

On ne garde jamais le mot de passe. On garde `sel:empreinte` calculée avec **PBKDF2-HMAC-SHA256,
120 000 tours, sel aléatoire de 16 octets** (travail d'Emna). Deux comptes avec le même mot de passe ont
des empreintes différentes. Le mode « Essayer sans compte » enregistre les données sous l'identifiant −1 ;
quand on crée ensuite un compte, ces données lui sont rattachées.

### La migration (version 1 → 2)

La version 1 stockait les prix en `Double`. La version 2 les stocke en millimes. La migration :

1. lit les colonnes existantes (elle accepte la v1 d'Eya **et** la v1 non versionnée d'Aya) ;
2. crée les nouvelles tables, **copie** les données en convertissant `prix × 1000` arrondi ;
3. supprime les anciennes tables et renomme les nouvelles ;
4. ajoute les tables historique, facture et synonyme.

Seul l'arrondi au millime peut changer une valeur (ex. 0,46666… → 0,467). C'est vérifié par
`MigrationTest` sur l'émulateur avec de vraies données. Il n'y a **jamais** de
`fallbackToDestructiveMigration` (qui effacerait tout).

---

## 5. Le scanner de factures

```
Photo / galerie / PDF ─► OCR ─► reconstitution des lignes ─► parseur ─► correspondance
       ─► écran « Vérifier » (l'utilisateur corrige, accepte, ignore)
       ─► confirmation ─► UNE transaction : prix + historique + recalcul ─► résultat
```

1. **Entrée** : appareil photo (CameraX), image de la galerie, ou PDF (chaque page est transformée en
   image avec `PdfRenderer`). Les images restent dans `filesDir/factures` (privé, exclu des sauvegardes
   Google, supprimable dans « Factures enregistrées »).
2. **OCR (lecture du texte)**, sur le téléphone et gratuit, derrière une petite interface `OcrEngine` :
   * français/anglais : **ML Kit** de Google (modèle inclus dans l'appli, marche hors ligne) ;
   * arabe standard : **Tesseract** (bibliothèque Tesseract4Android, licence Apache 2.0) avec le modèle
     `ara` (1,4 Mo, licence Apache 2.0) ;
   * mode **Auto** : ML Kit d'abord ; si le texte est arabe ou si la facture n'est pas comprise, on essaie
     Tesseract et on garde la meilleure lecture.
3. **Reconstitution** : l'OCR découpe souvent le tableau en colonnes. `OcrLayout` remet sur la même ligne
   les morceaux qui sont à la même hauteur (de droite à gauche pour l'arabe).
4. **Parseur** (`InvoiceParser`) :
   * convertit les chiffres arabes ٠-٩ en 0-9, retire les marques invisibles de direction du texte ;
   * reconnaît fournisseur, date, remise, TVA, total, et les lignes produit ;
   * une ligne est **sûre** si quantité × prix unitaire = total ; sinon les champs devinés sont marqués
     « à vérifier » (pointillés) ;
   * un prix absent reste **vide** : on n'invente jamais un prix.
5. **Correspondance** (`IngredientMatcher`) : sans accents ni majuscules, lettres arabes unifiées (أ إ آ → ا,
   ة → ه, ى → ي), article « ال » ignoré, distance de Levenshtein pour les fautes d'OCR, table de synonymes.
   « Sûr », « Probable », « À vérifier » ou « Non trouvé » ; l'utilisateur peut toujours changer.
   Quand il confirme une association, elle est **retenue** comme synonyme pour la prochaine fois.
6. **Vérification** : pour chaque ligne, ancien prix → nouveau prix (ramené au même emballage que
   l'ingrédient), écart en dinars et en %. Actions : Accepter, Modifier, Ignorer, Tout accepter.
   Par prudence, seules les lignes sûres sont pré-acceptées. Deux lignes pour le même ingrédient : refusé.
7. **Confirmation** : une seule transaction met à jour les prix, écrit l'historique, recalcule les recettes.
   L'écran de résultat montre les recettes dont le coût et le prix conseillé ont changé, et celles qui
   **passent sous leur marge voulue**.
8. **Analyses** (« Évolution des prix ») : plus fortes hausses et recettes les plus touchées, calculées
   uniquement à partir de l'historique réel.

**Limites honnêtes** : factures imprimées seulement (pas d'écriture manuscrite fiable), arabe standard
(pas de dialecte tunisien). La lecture arabe n'a pas encore été testée sur de vraies factures : il faut
des exemples réels pour la valider.

---

## 6. Les écrans en bref

* **Accueil** : alertes d'abord (marge sous l'objectif, coût incomplet, prix de vente non fixé, prix d'un
  ingrédient changé), actions rapides, chiffres clés, outils. Premier usage : 3 étapes guidées.
* **Mes recettes** : recherche fixée en haut (sans accents, sans majuscules, arabe), tri par nom, coût,
  marge ; état « aucun résultat ».
* **Fiche recette** : coût par part en grand, prix pratiqué, marge réelle, **conseiller de marge** (curseur :
  marge visée → prix et bénéfice ; rien n'est enregistré sans confirmation), détail du coût, actions
  (liste de courses, dupliquer, PDF avec ou sans prix, modifier, supprimer).
* **Composer** : coût recalculé à chaque frappe, unité au choix par ligne, coûts cachés, marge et prix.
* **Ingrédients** : recherche, prix ramené au kg/L/pièce, rendement, autres noms, historique,
  suppression bloquée si l'ingrédient est utilisé.
* **Simulateur « et si »** : +x %, +x DT ou nouveau prix → recettes touchées avant/après. Rien n'est
  enregistré tant qu'on ne confirme pas.
* **Liste de courses** : recette + nombre de parts → quantités à acheter (pertes comprises), emballages
  entiers, coût estimé, partage en texte.
* **Profil** : commerce, charges, langue (FR/EN/AR), thème (clair/sombre/système), sauvegarde et
  restauration d'un fichier, factures, mot de passe, déconnexion.

---

## 7. Le design

* **Palette d'origine conservée** (safran, brun, crème, caramel, vert, rouge) ; le mode sombre utilise les
  mêmes teintes assombries ou éclaircies.
* Identité « carnet de comptes » : titres en serif, filets fins au lieu de cartes, montants en
  **chiffres tabulaires** (alignés colonne par colonne) et en grand.
* Une seule famille d'icônes au trait, dessinée pour l'appli, retournées automatiquement en arabe.
* Cibles tactiles de 48 dp minimum, descriptions pour TalkBack, messages d'erreur sous les champs.

---

## 8. Les tests

* **62 tests unitaires** (sur l'ordinateur) : formules, arrondis, unités, rendement, coûts cachés,
  lecture des nombres (chiffres arabes, virgules), parseur de factures français et arabe, correspondance,
  simulateur, liste de courses, analyses, recherche, alertes.
* **7 tests sur l'émulateur** : migration v1 → v2 (version d'Eya et version d'Aya, avec vraies données),
  hachage des mots de passe, parcours complet compte → ingrédient → recette → facture → recalcul →
  alerte, rattachement des données du mode essai.

Commandes : `gradlew testDebugUnitTest` et `gradlew connectedDebugAndroidTest` (émulateur allumé).
