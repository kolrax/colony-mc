# Économie entre PNJ (lot 11) : état de la branche `economie`

Première passe de la case « Les PNJ dépensent leur salaire chez d'autres PNJ (nourriture, équipement) » (cahier 9.4).
Rien côté client : pas de nouveau modpack. `mod_version` inchangée.

## Ce qui est fait
- **Bourse** : la paie touchée à la caisse arrive maintenant en pièces dans le sac de l'ouvrier (`Actions.ToucherPaie`,
  `Reglages.pieces`). Avant, les pièces disparaissaient. `Actions.Deposer` ne dépose jamais les pièces au dépôt.
- **Achat de nourriture** (`Achats`, appelé par `Cerveau` à la faim ≤ 8 quand le dépôt de son chef n'a rien à manger) :
  une recherche au plus toutes les 3 minutes d'un cuisinier, fermier, pêcheur ou éleveur à 32 blocs au plus
  (`Reglages.achat_distance`), de n'importe quel chef, qui a à manger dans son sac ou au dépôt de son poste. Il y va,
  demande (ou marchande), paie, reçoit une part, puis la mange. Pièces : du sac de l'acheteur au sac du vendeur ; si la
  part vient du dépôt du vendeur, elles vont à la caisse de ce poste. Rien ne se perd ni ne se crée (`Economie.payer`).
  Sans de quoi payer : réplique `achat_pas_assez`, puis il le demande à son chef comme avant.
- **Prix** : table `prix` dans `reglages.json` (pain 1, steak 3…), sinon 1 pièce par 3 points de nourriture.
  **Marchandage** selon le caractère de l'acheteur (fourbe 70 %, colérique 50 %, calme 25 %, peureux 5 %) : un quart de
  rabais, une pièce au moins, jamais sous une pièce. « 1 pièces » devient « 1 pièce » (`Economie.accorder`).
- **Questions du chef** : « qu'as-tu acheté ? » (`achats`, réplique `chef_achat_info`) et « combien as-tu de pièces ? »
  (`bourse`) dans `repertoire.txt`, réponses dans `Consignes`.
- `economie.txt` chargé dans `Vie.TOUTES`, `{prix}` ajouté à `Vie.VARIABLES`, compté dans `VieTest`.

## Tests
- JUnit : `EconomieTest` (5 : prix, marchandage, singulier, paiement exact sans perte, répliques). `./gradlew build` vert.
- En jeu : `TestsEconomie` (lot `economie`, coins 370 à 373) : achat même chef, sans pièces, entre deux chefs, paie dans
  le sac. Résultat : voir la fin de ce fichier.

## Ce qui manque (aussi dans `docs/plus-tard.md`)
- Achat d'**équipement** (outil cassé, rien au dépôt → l'acheter à un ouvrier ou guerrier qui en a en trop au dépôt de
  son chef) : pas fait. Chaque métier gère son outil à part (`Mineur` : `outilAuDepot`, puis `Demander("pioche")`) :
  il faudra un point d'appel par métier, ou un point commun avant `Demander`.
- Répliques écrites mais pas encore dites : boutiques, `vente_propose`, `vente_faite`, `paie_depensee`,
  `paie_economise`, `prix_monte`, `riche_fier`, `achat_refuse_cher`, `lettre_boutique_vide`.
