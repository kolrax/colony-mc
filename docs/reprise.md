# Reprise : où on en est

Mise à jour : 14/10/2026, 02 h 20 UTC. Une page à lire en début de session, avec `docs/plan.md` et la fin de `docs/journal.md`.

## Versions
- Mod `colonie_pnj` : **0.9.17** (14/10, 01 h 54 UTC). Session 00 h - 02 h 30 UTC : 3 versions livrées (0.9.15 à 0.9.17), toutes sans erreur du mod.
  - 0.9.17 = **cuisinier** (lot 6 : `Cuisinier`, `Cuisine` (pur), fumoir/fourneau seulement (feu de camp retiré), combustible charbon de bois > charbon > planches, jamais de bûches ; `Fermier.Pain(int, boolean seul)` ; `Ordres.AUSSI` (pain = fermier aussi) ; `Metier.enCours` ; alias `boulanger` dans `Conversations` ; `TestsCuisinier` coins 180-185). Tests : 185 JUnit ; en jeu 3 passes de 249 à 248/247/248 (**aucune passe entièrement verte** : seuls des instables connus de l'éleveur et du mineur).
  - 0.9.16 = **pêcheur** (`Pecheur`, `Peche` (pur), vraie table de pêche, trésors seulement en eau libre 5 x 5 sur 2 de fond, rives écartées, deux pêcheurs deux rives, `Pecheur.oublier` au renvoi ; `TestsPecheur` coins 160-167). 181 JUnit, 243/243 en jeu.
  - 0.9.15 = **chasseur** (`Chasseur`, `Chasse` (pur), gibier sauvage seulement, `Chasseur.sauvage` (doute = pas tiré : clôture, chunk non chargé, pré clos > 1024 cases), tour borné, `Eleveur.butin` élargi ; `TestsChasseur` coins 170-176). 173 JUnit, 235/235 en jeu.
- Modpack : **0.5.1** (13/10, obligatoire, canal 8). Inchangé : aucun des nouveaux métiers ne touche le client.
- Avant : 0.9.14 (guérisseur), 0.9.13 (éleveur), 0.9.12 (fermier, blessures), 0.9.11 (besoins), 0.9.10 (outils, noms au-dessus des têtes, canal 8).

## Branches prêtes, pas encore sur main (À REPRENDRE EN PREMIER)
- **`transporteur`** : voir « Session en cours » ci-dessous pour son état exact à la fin de la session.
- **`moral`** (première partie du moral, lot 5 : `Moral` (pur), `Humeur` (jeu), bilan toutes les 5 min, lenteur, lettres `moral_bas`/`moral_avertit`, démission après 2 lettres lues par un chef connecté et 3 jours très bas, `Humeur.oublier` au renvoi, deuil plafonné, repas variés ; `TestsMoral` coins 200-203 ; réglages `moral_*`). Relue, corrigée : 190 JUnit, 247/247 en jeu **sur la base 0.9.16** (sans cuisinier ni transporteur). À faire : fusionner `origin/main`, build + tests en jeu, livrer. Reste noté : test de démission pas relancé sur l'ancien code ; pas de test « reste laissé à terre » ni « chef absent ».
- **`eleveur-fix`** (enquête sur les tests instables de l'éleveur) : correctif partiel (`Eleveur.Corvee.reprendre`, 3 reprises d'une bête manquée) + journal des actions dans les messages d'échec. **Cause pas trouvée** : l'éleveur ne rejoint parfois pas une bête dans un enclos 5 x 5 en 15 s (règle `coince()` d'`Actions.Attaquer`) ; et une vache sort parfois de l'enclos (`abatLExcedent`). À finir avant de fusionner.

## Ce que le mod sait faire aujourd'hui
- **Recrutement** : chercheurs d'emploi à pied près des joueurs, embauche par fiche, salaire en pièces Numismatics à la caisse du poste. Plafond 5 PNJ par joueur (réglable).
- **Un poste de travail par joueur** (bloc bureau) : caisse, dépôt agrandi par les coffres voisins, écran du chef.
- **Trois métiers** : mineur, bûcheron, guerrier.
- **Autonomie** : espace de vie, sorties et expéditions, repos, faim, paie, grève, « à terre », tombe.
- **Déplacements** par étapes vérifiées, sortie de sous terre, nage, bateau, 3 x 3 chunks par PNJ. Depuis la 0.8.5 : recherches d'étape courtes (portée = distance + 16, 512 nœuds), au plus 2 par PNJ et 4 par tick serveur, un passe-droit par tick, mémoire des étapes ratées 30 s, délai croissant (20 → 200 ticks) après un choix tout raté, jamais de pas tout droit au bout d'un chemin direct incomplet (lave, vide).
- **Combat** (0.8.3/0.8.4) : un PNJ frappé par un joueur ne riposte jamais sans ordre, il fuit ; un guerrier n'attaque pas un joueur qui frappe son chef sans ordre ; un PNJ frappé en pleine action réagit tout de suite (une fois par coup).
- **Courrier**, **ordres en français sans IA**, **répertoire d'actions**, **IA en option** (Groq, Whisper, Gemini), **admin** `/pnj` et panneau web (port 25776).

## Mesures réelles (test-andesite, 50 PNJ du banc + Raoul)
- 0.8.2 : part du mod 30-32 ms/tick (pire 59), dont chemins 27 ms ; tick serveur 33-36 ms.
- 0.8.4 (23 h 33 UTC, mêmes conditions) : 1re fenêtre (97 s) mod 14 ms (pire 85), chemins 12 ms, 150 recherches dont 101 échecs ; **2e fenêtre (128 s) mod 80 ms (pire 192), chemins 77 ms, 882 recherches dont 784 échecs (793 d'« étapes », 764 échecs), 19 au pire sur un tick ; 10-12 TPS**. Jetons refusés : 4. Donc le budget (16/tick) ne freine rien : c'est le **coût d'une recherche d'étape ratée (~9 ms !)** qui tue.

- **0.8.5 (12/10, 1 h 50 UTC, mêmes conditions) : objectif atteint.** Part du mod 2,6 / 3,9 / 5,1 / 4,0 ms en moyenne à 91 s / 130 s / 3 min / 5 min (pire 7 / 19 / 20 / 25 ms) ; chemins 0,5 à 2,8 ms ; 40 à 93 recherches par fenêtre (contre 882) ; **20 TPS**. Une recherche d'étape ratée coûte maintenant 1,5-5 ms ; un **chemin direct raté ~9-12 ms**, un échec « autres »/« montée » ~10-12 ms : ce sont eux qui font les pires ticks. Le tick serveur monte au fil du banc (8 → 13 ms) alors que le mod reste vers 4 ms : hors mod, non analysé. Réserve honnête : moins de recherches veut aussi dire des PNJ coincés qui attendent plus longtemps (délai jusqu'à 10 s) ; non mesuré en jeu réel.

## Prochaine étape (exacte)
**Lire d'abord « Rythme : travail en continu » dans CLAUDE.md.**
1. Branches ci-dessus : transporteur (si pas déjà livré), puis moral (fusion avec main, tests, livraison).
2. Tests instables de l'éleveur (`nourritEtFaitNaitreUnVeau`, `abatSurOrdreSansToucherAuDernierCouple`, `abatLExcedent`, ~1 passe sur 2) : finir l'enquête de `eleveur-fix`. Autres instables vus : `TestsMineur.mineurNeCassePasUneStructure`, `TestsIA.iavoix`, `TestsCharge.cheminsSobres`, `TestsJeu.litasoipasprisparunautre`, `sortdelalave`, `tondUnMouton` (une fois chacun).
3. Lot 6 : « va me chercher du fer », dit à un bûcheron. Lot 5 : moral 2e partie (vol, sabotage, fuite, révolte après avertissements : point d'entrée `Moral.effet`/`Moral.suite`), réactions selon le caractère, deuil et enterrement (crochet `Humeur.deuil`, `memoire.deuil_le`/`deuil_nom`).
4. Restes : `docs/plus-tard.md` (relectures du chasseur, du pêcheur, du cuisinier, du transporteur).
5. Méthode qui a marché : un worktree par case (`git worktree add /home/user/wt-<case> -b <case> main`), jusqu'à 3 développeurs en parallèle, et **tests en jeu toujours par `flock /tmp/gametest.lock ./gradlew runGameTestServer`** (un seul serveur de test à la fois). Le livreur reste sur `main` dans /home/user/colony-mc. Chaque fusion de deux métiers donne des conflits simples (Metier, Ordres.NOMS_METIERS, Adressage.CAPACITES, Consignes, Messages, RepertoireTest, carte) : garder les deux côtés.
6. Branches restées sur GitHub (suppression refusée depuis le cloud) : toutes fusionnées dans `main` sauf `moral`, `eleveur-fix` (et `transporteur` si non fusionnée).

## Envoi du jar depuis le cloud
`panel.py upload` échoue (connexion coupée). Ce qui marche : `panel.py write /mods-off/essai-colonie_pnj-X.jar <jar>`, vérifier la taille avec `panel.py ls /mods-off`, puis `panel.py mv` vers /mods. Banc : `execute positioned 484 64 272 positioned over motion_blocking_no_leaves run pnj banc 50 tous ~ ~ ~` (personne connecté), puis `pnj charge`, `spark tps`, `pnj banc fin`.

## Fichiers à ouvrir
- Mineur (lot 4) : `Mineur` (`interdit`, `baseVoisine`, `creuserCase`), `Monde.structure`, `Fouille`, `Consignes.Faire.fouiller`, `Bureau.Entite.ranger`, `TestsMineur`, `FouilleTest`. Bûcheron : `Bucheron`, `Actions.Abattre`, `Actions.chercherArbre`.
- Mémoire longue : `Souvenirs`, `Travailleur` (`seSouvenir`, `reaction`, objet donné), `IA.systeme`, `SouvenirsTest`, `TestsOrdres`. OpenRouter : `IA.secours`, `DepensesIA`, `ReglagesIA`, `TestsIA.iaOpenRouterPrendLeRelais`.
- Obéissance : `Obeissance`, `Travailleur` (`obeir`, `moral`, `prime`, `changerMoral`, `raisonDuRefus`), `Messages` (`rale`, `negocie`, `refus_*`, `mortel_*`), `ObeissanceTest`, `TestsOrdres`.
- Ordres longs : `OrdresLongs`, `Ordres.lire`, `Travailleur` (`regles`, `tickRegles`, `declencher`, `reprendre`, `appliquer`), `Dialogue.repondreAvec`, `TestsOrdres`, `OrdresLongsTest`.
- Messages du poste : `Boite`, `Courrier`, `Bureau` (`fiche`, `action`), `Client.EcranPoste`, `Messages` (`IMPORTANTES`, `SEULES`), `TestsMessages`, `BoiteTest`.
- Morts bêtes : `Travailleur.survivre`, `Actions` (`Casser`, `Echapper`, `Boucher`), `Mineur.creuserCase`, `Monde`, `TestsMorts.java`.
- Chemins : `Itineraire.java` (`avancer`, `choisirEtape`, `jeton`, `suivreSortie`), `Travailleur.java` (`createNavigation`, compteurs, `blocsInaccessibles`, `coupVu`), `Charge.java`, `TestsCharge.java`.
- Combat : `Cerveau.java` (`decider`), `Monde.java` (`cibles`), `Guerrier.java`.
- `docs/carte-du-code.md` pour le reste.

## Session en cours
- aucune.
