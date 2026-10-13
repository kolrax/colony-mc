# Reprise : où on en est

Mise à jour : 13/10/2026, 23 h 10 UTC. Une page à lire en début de session, avec `docs/plan.md` et la fin de `docs/journal.md`.

## Versions
- Mod `colonie_pnj` : **0.9.14** (13/10, 23 h 08 UTC ; 170 JUnit et 228 en jeu verts). Session 21 h - 23 h 30 UTC : 5 versions livrées.
  - 0.9.13 = **éleveur** (lot 6) : `Elevage` (pur), `Eleveur` (enclos `clore`, `Corvee`, action `Passer` pour le portillon, crochet `Metier.sortir` appelé tôt dans `Cerveau.decider`), réglage `betes_par_enclos` (12), `Monde.construit` compte les portillons ; tests `EleveurTest`, `TestsEleveur` (coins 140-145).
  - 0.9.12 = **fermier** (lot 6 : `Fermier`, `Cultures`, `Parcelle`, `Fermier.aLui`, ordres `champ`/`recolter`/`pain`/`oublier_champ`, `TestsFermier` coins 120-127) et **blessures** (lot 5 : `Gravite`, `Blessures` ; à terre 3/5/8 min puis mort, comme le cahier 5.12 ; compagnons qui accourent ; `TestsBlessures` coins 130-134).
  - 0.9.11 = **besoins** (lot 5 : `Vie` lit `vie.txt` (45 sections, 457 répliques), `Besoins` : répliques, lit à soi, pause `Flaner`, soin en mangeant sans aliment néfaste ; `TestsVie` coins 110-114).
  - 0.9.10 = **outils au dépôt** (lot 4 : `Outils`, `Metier.outilAuDepot`/`PrendreOutil`, `Reglages.usure_outils` = 3 ; `TestsOutils` coins 80-86) et **nom, métier, chef et icône d'état au-dessus des têtes** (lot 8 : `Insignes`, `Travailleur.ROLE`/`ETAT`, `Client.Rendu.renderNameTag` ; **canal réseau 8**).
- Modpack : **0.5.1** (13/10, **obligatoire** : canal 8). Il n'a pas à changer tant que le client ne change pas.
- 0.9.14 = guérisseur (lot 5 : `Guerisseur`, `Soins`, `Actions.Action.nouveauBut`, `TestsGuerisseur` coins 150-154)
- Mod `colonie_pnj` : **0.9.9** dans le dépôt et sur le serveur (13/10, 20 h 23 UTC ; 139 JUnit et 191 en jeu verts). 0.9.9 = guerrier (lot 4) : `Guerrier` (s'équiper au dépôt, se soigner, défendre un ouvrier, chasse de nuit, ronde sur parcours `ronde_point`/`ronde_effacer`, mannequin), `Actions.Attaquer` (arc et arbalète, recul, creeper de loin, `tenir`, `portee`), flèches marquées et `Guerrier.impact` (une flèche ne blesse jamais qu'un monstre ou la cible ordonnée), `Monde.ligneDeTir`, `garder_ici` (porte, pont, tireurs), ouvriers qui fuient un creeper (`Cerveau`) ; tests `TestsGuerrier`, `GuerrierTest`.
- 0.9.8 (20 h 00) = conversations entre PNJ sans IA (lot 7) : `Conversations` (lit `conversations.txt`, 237 blocs), `Causeries` (dans `Cerveau.decider`), `Reglages.secondes_entre_conversations` (180) ; tests `ConversationsTest`, `TestsConversations`.
- 0.9.7 (19 h 07) = bûcheron (lot 4 : feuilles, géants, `foret`, `planches`, `charbon_bois`, `defricher_zone`, `TestsBucheron`) et tenues par métier (lot 8 : `Tenues`, donnée synchronisée `Travailleur.TENUE`, 36 skins dans `textures/entity/ouvrier/`). **Canal réseau 7.**
- Modpack : **0.5.0** (13/10, **obligatoire** : canal 7, tenues). Il n'a pas à changer tant que le client ne change pas.
- Prêt mais pas encore utilisé : 24 icônes (`textures/gui/etat/*.png`, `textures/gui/metier/*.png`, script `outils/icones_tex.py`) pour « nom, métier et icône d'état au-dessus de la tête » (lot 8, 2e case) : **côté client, donc nouveau modpack**.
- Avant : 0.9.6 (122 JUnit et 164 en jeu). 0.9.6 = chantiers du mineur (lot 4) : classe pure `Fouille`, `Consignes.Faire.fouiller`, tri `Bureau.Entite.ranger` ; tests `FouilleTest`, `TestsMineur`. 0.9.5 = mineur prudent : `Monde.structure`, `Mineur.interdit`/`baseVoisine`.
- Avant : 0.9.4 (115 JUnit et 155 en jeu). 0.9.4 = mémoire longue (lot 3) : classe pure `Souvenirs` (genres COUP, INSULTE, PROMESSE, MARCHE, CADEAU, ORDRE, COMPLIMENT ; 40 au plus ; `lire`, `insulte` adressée seulement, `question`, `raconter`, `pourIA`, `bonus`), `Travailleur.souvenirs`/`seSouvenir`/`reaction` (appelé dans `appliquer`), objet donné accroupi, `IA.systeme`/`systemeGroupe` (« Tes souvenirs »), `reponseImposee` sur une question de souvenir ; tests `SouvenirsTest`, `TestsOrdres.insulteRappeleeDeuxJoursPlusTard`.
- Avant : 0.9.3 = OpenRouter (lot 3) : secours payant de Groq pour `converser`/`converserGroupe` seulement (`IA.secours`), classe pure `DepensesIA` (`config/colonie_pnj/depenses-ia.json`, 95 % du budget du mois, 2 × la moyenne du jour, appel coupé compté au prix prudent, fichier abîmé = bloqué jusqu'au mois suivant), réglages `openrouter_cle`, `budget_openrouter_mois` (5 $), `max_openrouter_jour` (300) dans `ia.json`. **Clé posée sur le serveur** (script, jamais affichée). Un appel réel : 0,000012 $.
- Avant : 0.9.2 (livrée le 13/10 à 1 h 43 UTC, 108 JUnit et 153 en jeu verts). 0.9.2 = obéissance (lot 3) : classe `Obeissance` (décision OBEIT/RALE/NEGOCIE/REFUSE, peine de l'ordre, `mortel`, `accepte`, `insiste`, `abandonne`, `sansOrdre`, `question`, `prime`), `Travailleur.moral`/`prime`/`enAttente`, point de décision `Travailleur.appliquer → obeir → appliquerOrdre` ; tests `ObeissanceTest`, `TestsOrdres` (3 de plus).
- Avant : 0.9.1 = ordres longs (lot 3) : classe `OrdresLongs` (règles pluie, orage, nuit, jour, matin, après-midi, soir, « quand tu as fini », « si tu vois… » ; « tous les jours » ; « oublie tes ordres »), `Travailleur.regles`/`tickRegles`/`declencher`/`reprendre`, `Mission.quotidienne`, « les mineurs, tous à la mine » ; tests `OrdresLongsTest`, `TestsOrdres`.
- Avant : 0.9.0 = journal des phrases non comprises : classe `Incompris` (regroupement par `Ordres.normaliser`, 500 entrées, noms des joueurs et des PNJ remplacés par `@`), noté dans `Dialogue.noterIncompris` (motifs `rien`, `ia_seule` hors gestes spontanés via `Decision.lectureDemandee`, `ia_parole`), fichier `config/colonie_pnj/incompris.json` sur le serveur, `/pnj incompris [vider]`, route `/api/incompris`, carte dans l'onglet Actions du panneau, `tools/incompris.py` (lecture à distance, jamais dans le dépôt).
- Avant : 0.8.9 = messages du poste (lot 2) : classe `Boite` (regroupement « ×N », lettre unique pour la colonie : dépôt plein, paie en retard, sans poste, poste détruit ; purge des lues d'abord ; tri importantes en tête), boutons Tout lu / Archiver / Archives / Tout effacer (`Bureau.Entite.action` : `tout_lu`, `archiver`, `desarchiver`, `tout_effacer`), tests `BoiteTest` et `TestsMessages`. Écran jamais vu en vrai.
- Avant : 0.8.8 (livrée le 12/10 à 12 h 13 UTC, relue deux fois, 74 JUnit et 124 tests en jeu verts). 0.8.8 = plus de morts bêtes : le mineur bouche la lave (et l'eau sous terre), ne se met jamais sous du gravier/sable qui tombe ; réflexe de survie pour tous (`Travailleur.survivre`/`enPeril`, `Actions.Echapper`, `Actions.Boucher`, `Monde.ligneSure`/`refuge`/`eboulement`/`coulee`/`tombe`/`eauProche`), tests `TestsMorts` (lot `morts`, 24 tests).
- Avant : 0.8.7 = déblocage : pilier et pont de blocs (repris ensuite), retour au poste après 5 min coincé avec un avis au chef (classe `Coince`, `Travailleur.surveillerCoince`/`ramenerAuPoste`, `Itineraire.commencerAPoser`, tests `TestsDeblocage`).
- Modpack : **0.4.9** (12/10, non obligatoire : nouveaux boutons du poste ; ModernFix 5.27.26 côté joueurs, 5.27.24 sur le serveur). Il n'a pas à changer tant que le client ne change pas (canal réseau toujours `"6"`).
- Serveur : MintServers, monde de test **`test-andesite`** (jetable). Le vrai monde sera créé à l'ouverture (vendredi 16/10 au soir ou samedi).

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
1. Lot 6 : pêcheur, chasseur, cuisinier ou boulanger, transporteur (sur le modèle du fermier/éleveur) ; « va me chercher du fer » dit à un bûcheron.
2. Lot 5 : moral (causes et effets : grève, démission, fuite, vol, sabotage, révolte après avertissements — répliques déjà dans `vie.txt`), réactions selon le caractère, deuil et enterrement.
3. Restes : `docs/plus-tard.md`. Les noms au-dessus des têtes n'ont jamais été vus à l'écran (banc photo, il faut un écran).
4. Instables : `TestsMineur.mineurNeCassePasUneStructure` (1 échec ce soir, vert à la relance), `TestsMineur.carriereAuDessusDUnVide` (1 échec dans une passe où deux serveurs de test tournaient peut-être en même temps), `TestsIA.iavoix`. **Ne jamais lancer deux `runGameTestServer` en même temps.**
5. Piège de cette session : un livreur qui travaille dans le dossier principal suit la branche extraite. **Rester sur `main` dans /home/user/colony-mc pendant une livraison** ; tester les branches dans un `git worktree` à part.
6. Les branches `en-cours`, `textes`, `skins`, `apparence`, `conversations`, `tetes`, `textes-vie`, `vie`, `fermier`, `blessures`, `livraison`, `eleveur` restent sur GitHub (suppression refusée depuis le cloud) : toutes fusionnées dans `main` sauf `guerisseur` (et `en-cours`, qui suit le guérisseur).

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
