# Reprise : où on en est

Mise à jour : 17/10/2026, 04 h 20 UTC. Une page à lire en début de session, avec `docs/plan.md` et la fin de `docs/journal.md`.

## Versions
- **Serveur : 0.9.40** (17/10, vers 04 h 15 UTC), un seul jar dans `/mods`.
- 0.9.40 = **familles** (`Ages` pur, `Famille`, `TestsFamille`, `AgesTest`, `famille.txt` ; âge, vieillesse, mort de vieillesse, naissances dans les couples, enfants, apprentis 12-16 ans). Rendu des enfants (`Attributes.SCALE`) jamais vu à l'écran.
- 0.9.39 = **travailleurs de nuit** (`Nuit` pur, `TestsNuit` coins 410-414, `NuitTest` ; « travaille de nuit », dort le jour, fatigue, santé, moral `NUIT`). `reglages.json` du serveur : `pnj_max_par_joueur` = 30, chercheurs 0,35 / 15 min (`plus_rares`).
- 0.9.38 = **étoiles** (`Etoiles` pur, `Apprentissage`, `TestsEtoiles`, `EtoilesTest` ; « va te former » : formation payée, arrêtée par tout ordre avec remboursement).
- 0.9.37 = **recrutement** (`Talents`, `Recrutement`, réputation dans `Courrier`, affiche, cloche de village ; `TestsRecrutement`, `RecrutementTest`). 0.9.36 = **machines de Create** par le transporteur (`Machines`, `TestsMachines` coins 390-398, `machines.txt`) ; jamais essayé avec le vrai Create (il plante le serveur de test : paquets aux faux joueurs).
- 0.9.35 = **boutiques** (`Boutique` pur, `Boutiquier`, `TestsBoutique` coins 380-387, lots `boutique` et `boutique_nuit`). 0.9.34 = **coût des ouvriers** (`Cout`, `TestsCout` lot `cout`). 0.9.33 = **économie entre PNJ** (`Economie`, `Achats`, `TestsEconomie` coins 370-374).
- 0.9.32 = **guerre entre colonies** 1re passe (`Guerre` pur, `Armee`, `Courrier.guerres`, `TestsGuerre` coins 320-323, `guerre.txt`), relue, 3 bloquants et 5 importants corrigés ; mineurs dans plus-tard.
- 0.9.31 = **réserve sans limite du dépôt** (`Reserve`, `Bureau.Entite`, `Courrier.reserves`, `TestsReserve` coins 340-346).
- 0.9.30 = **guerriers qui défendent le dépôt** (`GardeDepot`, `Defense.depotOuvert`, `depot.txt`, `TestsDepot` coins 238-242).
- 0.9.29 = **frappe du chef** (`GardeChef`, `TestsChef` 231-237), **maçon** (`Macon`, `Schemas`, coins 300-306), ouvrier épuisé sans lit.
- Modpack : **0.5.1** (13/10, canal 8). Rien de client depuis : pas de modpack.
- **Décision de KOLRAX (16/10, 17 h 12 UTC)** : le serveur n'ouvre que quand le mod est fini ; le chantier continue ; monde de test encore jetable (régénération permise).

## Branches à reprendre
- **`autres-joueurs`** (lot 14, cases « autoriser un joueur / débauche » et « nom, métier, chef visibles », 0.9.41) : 1re passe verte (bf89bcf : 345 JUnit, 387/387), puis relecture : **1 bloquant** (en jeu, un autre joueur ne peut ni ouvrir la fenêtre de conversation ni parler au micro : `Travailleur.mobInteract` 1549-1558, `Voix.viser` 296, `Voix.traiter` 343) et 4 importants (pièces encaissées avant la décision d'obéir ; une paie achète des trajets durables = vol du dépôt ; un joueur payeur/autorisé ordonne la retraite de l'armée ; la débauche emporte le sac) + mineurs. Corrections en cours à la fin de la session : voir le dernier commit de la branche et `docs/journal.md`. Ensuite : fusionner `main` (familles : un enfant ne se débauche pas), passe complète, livrer 0.9.41.
  - Liste complète à corriger (relecture 17/10) : 1) fenêtre et micro ouverts à tout joueur (si ça exige un changement client : modpack obligatoire, le dire) ; 2) `Commandement.reserver` et décision d'obéir avant d'encaisser (`Etranger` 56-59) ; 3) une paie n'achète qu'un ordre ponctuel (pas trajets, champs, construire, missions quotidiennes, règles : `Commandement.GESTES_AU_CHEF`) ; 4) refuser paie/ordres d'un joueur en guerre avec le chef, sauter `Guerre.repli`/`Armee.finir` si ce n'est pas le chef (`Travailleur` 1966, 1971) ; 5) débauche : il laisse au dépôt de l'ancien chef tout sauf ses pièces ; mineurs : OUI d'un autre ne confirme pas un renvoi (1928), un ordre d'un autre n'arrête pas la formation (1924), refus de débauche pendant la formation, sac plein = refus de paie (pas de pièces au sol), « viens me rejoindre au four » n'est pas une débauche, `Commandement.PERMIS` seulement pour des joueurs connus, `metiers_inactifs` vérifié, ordre d'étranger refusé pas gardé dans `enAttente`, enfant/apprenti ni débauché ni payé pour un ordre.
- `en-cours` = ancien `main` (suppression refusée depuis le cloud).

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
**Lire d'abord « Rythme : travail en continu » et « Graphify » dans CLAUDE.md.**
1. Finir `autres-joueurs` (ci-dessus), livrer 0.9.41, cocher les 2 cases du lot 14. Puis la dernière case : **rumeurs** (lot 14).
3. Avant l'ouverture : banc de charge avec 30 ouvriers par joueur ; essai réel des machines de Create sur le serveur (moulin, presse, bassin, tapis) ; rendre fiables les tests instables.
4. Restes : `docs/plus-tard.md` (crieur, débauche, achat d'équipement, guerre, boutiques, machines…).
5. Instables connus (passent à la relance) : `bucheronPresDeLaLave`, `compagnonsEnParlent`, `chasseSurOrdreLEspeceDite`, `coupsDArme`, `peureuxFuitDeLoin`, `fourbeSEclipse`, `fourbeEnvoieUnCollegue`, `creeperAlerteLeGuerrier`, `tondUnMouton`, `calmeFrappeSeDefend`, `figeToiPuisAuTravail`, `iavoix`, `sortDeLaLave`, `chasseDeNuit` (vu le 17/10), `neBlessePersonneDerriereLaProie`, `filonSousDuGravier` (vus le 17/10). `iaMicro` réparé le 17/10 (dépendait de la place des tests).
6. Méthode qui a marché : un worktree par case (outil Agent, isolation worktree), 2 développeurs en parallèle, `flock /tmp/gametest.lock`, relecteur dès qu'une case est verte ; avant de livrer, fusionner `main` dans la branche et refaire build + passe complète.

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
- aucune (session 02 h - 04 h 30 UTC du 17/10 terminée).
