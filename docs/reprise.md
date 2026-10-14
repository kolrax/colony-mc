# Reprise : où on en est

Mise à jour : 14/10/2026, 10 h 20 UTC. Une page à lire en début de session, avec `docs/plan.md` et la fin de `docs/journal.md`.

## Versions
- **Serveur : 0.9.27** (livrée le 14/10 à 09 h 42 UTC, « Done » sans erreur du mod ; un seul jar dans `/mods`, 1 581 405 octets, sha1 `c5261e09fb5f…`). Le redémarrage par la routine est autorisé par KOLRAX (demande `redemarrage`, 05 h 07 UTC).
- **`main` : 0.9.27** (14/10, 09 h 40 UTC ; 235 JUnit, 302/302 en jeu). Installée sur le serveur.
  - 0.9.27 = **sons des PNJ** (`Sons`, `Cadence`, `TestsSons`), **défense de la colonie** (`Defense`, `Abri`, `defense.txt`, `TestsDefense`), **mineur qui ne creuse plus les structures** (`Itineraire.creusable`).
  - 0.9.26 = **diamant gardé en cachette et excuses** (`Humeur.trouve`, `questionDiamant`, `excuses`, `Ordres.diamantCache`/`excuses`, `TestsCaractere` coins 230-234).
  - 0.9.25 = **amitiés, inimitiés, couples** (`Liens`, `Relations`, `TestsRelations` coins 262-263).
  - 0.9.24 = **deuil et enterrement** (`Deuil`, `TestsDeuil` coins 246-251, `relations.txt`).
- Ancienne attente : jar `colonie_pnj-0.9.23.jar` (1 479 953 octets, sha1 `b327619976f3…`) déjà envoyé dans `/mods-off` du serveur (0.9.20 et 0.9.22 aussi, inutiles maintenant). Pour livrer dès que le redémarrage est permis : mv 0.9.19 → /mods-off, mv 0.9.23 → /mods, un seul jar, restart, journal jusqu'à « Done ». Client inchangé : pas de modpack.
  - 0.9.23 = **remarques spontanées, humour devant l'impossible, réactions selon le caractère** (lot 7) : `Remarques` (une remarque par ouvrier toutes les 10 à 20 min, `secondes_entre_remarques`, jamais deux voisins à 16 blocs la même minute), `Impossible` (blague seulement si `Ordres.lire` ne trouve rien de faisable, sauf quantité/durée/magie impossibles ; jamais sur une question), `Vie.tirer` + `caractere_*` (insulte, coup du chef, paie manquée, cadeau) ; `TestsRemarques` coins 220-222, `RemarquesTest`. `remarque_machines`, `caractere_diamant_*`, `caractere_excuses` pas encore branchés ; blague avec l'IA non testée en jeu.
  - 0.9.22 = **éleveur fiable** : `Action.dePres` (seulement la `Corvee` de l'éleveur et son `Attaquer`) refait le chemin vers une bête qui bouge, seulement sur un chemin direct réussi (`Itineraire.surCheminDirect`) ; bête serrée dans un coin de l'enclos comptée (`Eleveur.dans(Enclos, Entity)`, `enclos`). 90 essais sans échec.
  - 0.9.21 = **« va me chercher du fer »** (lot 6, cahier 14.1) : `Relais` (pur), `Actions.Prevenir`, `Travailleur.proposerRelais`/`prevenir`/`transmettre`, `Lecture.pourAutres` ; oui/non routé seulement si l'ouvrier est visé et que personne d'autre n'attend de réponse ; refus du collègue rapporté au chef ; tests `TestsOrdres` coins 210-215, `RelaisTest`.
  - 0.9.20 = **moral, 2e partie** (lot 5) : vol, sabotage (torche/lanterne), fuite (équipement rendu au dépôt, petit butin), révolte armée contre le chef seul après 2 lettres + 3 avertissements, arrêtée par la paie à la caisse, l'éloignement (48 blocs) ou le moral ; réglages `moral_mefaits`, `moral_vol_max`, `moral_vol_autres`, `moral_revolte` ; `TestsMoral` coins 204-207.
  - 0.9.19 = moral 1re partie ; 0.9.18 transporteur ; 0.9.17 cuisinier ; 0.9.16 pêcheur ; 0.9.15 chasseur.
- Modpack : **0.5.1** (13/10, obligatoire, canal 8). Inchangé : rien de client depuis.

## Branche à reprendre
- **`en-cours` = `frappe-chef` (`739b9c1`) : « réaction quand on frappe le chef » (lot 9), PAS VERT.** `GardeChef` (pur, lecture des ordres « attaque quiconque me frappe », « reste tranquille », « ne fais jamais rien à X »), `Defense.chefFrappe` (écouteur `LivingDamageEvent.Post`), action `Interposer`, `chef.txt`, `TestsChef`, `GardeChefTest`. 3 tests en jeu sur 4 verts ; `TestsChef.ordreAttaqueFrappe` rouge : `Actions.Attaquer` finit « inaccessible » à 0,8 bloc du faux joueur sans frapper (piste : `isWithinMeleeAttackRange && vue` jamais vrai contre le faux joueur, `coince()` abandonne). Reste : corriger ça, voir les 4 tests échouer sur l'ancien code, build + passe complète, relecture, livrer. Le serveur exige `pvp=true` pour qu'un joueur frappe le chef (à vérifier). Autres branches fusionnées restées sur GitHub : `sons`, `defense`, `mineur-fiable`, `diamant`, `deuil`, `relations`, `remarques`, `fer`, etc.

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
1. Reprendre `en-cours` (frappe du chef, voir ci-dessus) : `git checkout -B en-cours origin/en-cours`, rebase sur `main`, faire passer `ordreAttaqueFrappe`, puis relecture et livraison 0.9.28.
2. Puis lot 9 « Guerriers qui défendent le dépôt, sauf contre un joueur autorisé par le chef », puis « Guerre entre colonies » ; puis lot 10 (maçon).
3. Instables vus le 14/10 : `TestsChasseur.chasseSurOrdreLEspeceDite`, `TestsRepertoire.ouvreLaPorte` (nouveau), `TestsDeuil.compagnonsEnParlent`, `carriereAuDessusDunVide`, `bucheronPresDeLaLave`. `mineurNeCassePasUneStructure` est réglé.
4. Restes : `docs/plus-tard.md` (défense : pas de mur quand il ne peut pas creuser, trou perdu de vue ; sons jamais écoutés en jeu).
5. Méthode qui a marché : un worktree par case (`git worktree add /home/user/wt-<case> -b <case> main`), jusqu'à 3 développeurs en parallèle, **tests en jeu toujours par `flock /tmp/gametest.lock ./gradlew runGameTestServer`**. Fusionner deux branches dans un worktree de branche (pas dans `main` pendant qu'une passe tourne dessus). Conflits courants : `Reglages`, `carte-du-code.md`, `plus-tard.md` : garder les deux côtés.

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
