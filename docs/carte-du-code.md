# Carte du code du mod `colonie_pnj`

Mise à jour : 11/10/2026, mod 0.8.0. 42 classes, environ 15 000 lignes.
But : ouvrir seulement les fichiers utiles. Chaque ligne donne le rôle, la taille (en lignes), les classes liées et les pièges.

## Où sont les choses
- Code : `mod-npc/src/main/java/fr/lacolonie/pnj/` (36 classes, tests en jeu compris). Tests JUnit : `mod-npc/src/test/java/fr/lacolonie/pnj/` (6 classes).
- Ressources : `mod-npc/src/main/resources/`
  - `assets/colonie_pnj/` : bloc bureau (blockstates, modèles, textures `bureau_cote/dessus.png`), texture du corbeau, langues `fr_fr.json` / `en_us.json` ;
  - `data/colonie_pnj/` : recette et butin du bureau, structures de test `structure/vide.nbt` et `grand.nbt` ; `data/minecraft/tags/block/mineable/axe.json` ;
  - `repertoire.txt` (les actions demandables, une par ligne), `paroles.txt` (phrases sans IA), `adressage.json` (poids appris : à qui le chef parle) ;
  - `web/index.html` : le panneau d'admin servi par le serveur Minecraft.
- `mod-npc/entrainement/` : exemples annotés et scripts Python de l'adressage. `mod-npc/outils/` : scripts qui dessinent les textures du bureau et du corbeau.
- Version : `mod-npc/gradle.properties` (`mod_version`). Tâches dans `mod-npc/build.gradle` (`runGameTestServer`, `runPhotos`, `entrainer`).

## Construire et tester
```bash
cd mod-npc
./gradlew build                                  # compile + 52 tests JUnit
rm -rf run/world && ./gradlew runGameTestServer  # 65 tests en jeu (36 TestsJeu, 16 TestsIA, 13 TestsRepertoire)
./gradlew entrainer --args="code"                # réentraîne l'adressage (adressage.json)
./gradlew runPhotos                              # banc photo des gestes (client, écran requis)
```

## Pour telle tâche, ouvrir tels fichiers
- **Banc de charge, performance** :
  - fichiers : `Travailleur` (`customServerAiStep`, `tickCorps`, `forcerChunk`), `Cerveau` (`penser`, `decider`), `ColoniePnj` (`ServerTickEvent.Post`, `tous()`, commande `/pnj`), `Monde` (recherches de blocs), `Courrier.tick`, `Voix.tick`, `TestsJeu` (`employe`, `sol`) et `TestsIA` (`equipe`, `etapes`).
  - **mesure** : `Charge` (fenêtre de 100 ticks, parties TOTAL/ENTITE/MOTEUR/CHUNKS/CORPS/ACTION/CERVEAU/CHEMINS/FILET/COURRIER/VOIX), `TestsCharge` (10/50/100 PNJ, lots séparés), commandes op `/pnj banc <N> [metier]`, `/pnj banc fin`, `/pnj charge`. `Travailleur.auTravail(...)` fait apparaître un ouvrier au travail (tests et banc).
- **Déblocage, chemins** :
  - fichiers : `Itineraire` (étapes, sortie de sous terre, creusage, bateau), `Actions.Aller` (`coince()`), `Cerveau` (réaction au résultat `bloque`, lettre « perdu »), `Monde`, `Travailleur` (saut d'à-pic, fin du fichier).
  - tests : `TestsJeu` (`sortParLesGaleries`, `creuseVersLaSurface`, `sortDuPuits`, `traverseALaNage`…).
- **Ordres** :
  - fichiers : `Ordres` (lecture du français), `Travailleur.appliquer`, `Repertoire` + `repertoire.txt`, `Consignes` (exécution), `Dialogue`.
  - tests : `OrdresTest`, `RepertoireTest`, `TestsRepertoire`.
- **IA** :
  - fichiers : `IA` (appels), `ReponsesIA` (requêtes, schéma JSON, lecture des réponses), `ReglagesIA` (`ia.json`), `Adressage`, `Dialogue`, `Voix`.
  - tests : `IATest`, `AdressageTest`, `TestsIA` (faux Groq/Gemini).
- **Messages du poste (lot 2)** :
  - fichiers : `Courrier` (lettres, avis, corbeaux), `Messages` (textes), `Bureau` (le poste, données envoyées à l'écran), `Client.EcranPoste` (l'écran), `Reseau` (paquets `Poste`, `ActionBureau`).
- **Métiers** :
  - fichiers : `Metier` (base, catalogue, missions), `Mineur`, `Bucheron`, `Guerrier`, `Biomes`, `Actions`, `Cerveau`.
  - tests : `TestsJeu`.
- **Rendu, apparence** :
  - fichiers : `Client` (modèle, rendu, bulles, écrans), `Poses` (angles des gestes), `Photos` (banc photo), `Corbeau`, textures dans `assets/`. Tout changement ici oblige à publier un modpack.

## Cadence : ce qui tourne, et quand (pour le banc de charge)
Par PNJ, dans `Travailleur.customServerAiStep()` :
- à chaque tick : `super.customServerAiStep()` (navigation Minecraft), `tickCorps()`, l'action en cours (`Actions.Action.tick`) ;
- toutes les 10 ticks : `ramasserAutour()` (dans `tickCorps`) et `Cerveau.penser()` (décalé par `getId()` pour étaler la charge) ;
- toutes les 20 ticks : `forcerChunk()`, `tickCourrier()`, `Consignes.verifierVitesse()` ;
- toutes les 100 ticks : `equiperArmure()` ; toutes les 200 ticks : inscription à l'effectif (`Courrier.inscrire`), recherche du lit.
Pour tout le serveur, dans `ColoniePnj` (`ServerTickEvent.Post`) :
- à chaque tick : `Voix.tick()` ;
- toutes les 20 ticks : `Courrier.tick()` (corbeaux arrivés) ;
- toutes les 100 ticks : `tous()` puis `forcerChunk()` pour chaque PNJ (filet de sécurité) ;
- arrivée des chercheurs d'emploi (`arriveeChercheurs`).
En arrière-plan, hors du fil du serveur : tous les appels d'IA (`IA`).
Suspects pour la charge : `tous()` parcourt toutes les entités de tous les mondes ; `Monde.chercherBloc` et `solNaturel` balaient des volumes ; le calcul des chemins dans `Itineraire` ; 9 chunks forcés par PNJ.

## Pièges communs
- Un PNJ seul se fige au bord d'un chunk : il faut 3 x 3 chunks forcés (`forcerChunk`).
- `Level.getHeight` sur un chunk non chargé renvoie le fond du monde : charger le chunk d'abord.
- Ne pas multiplier la vitesse en sprint (l'attribut fait déjà +30 %).
- Règles du serveur codées dans `Consignes`, `Monde.construit` et `Guerrier` : ne frapper que les monstres, ne casser que le naturel, pas de téléportation (sauf exceptions accordées le 11/10, à coder).
- Un bloc `static {}` qui lit des champs doit venir après eux.

## Entité et cerveau
- **Travailleur** (1265) : le PNJ, un `PathfinderMob` à corps de joueur. Champs : métier, niveau, chef, bureau, mode (`CHOMEUR`, `SUIVRE`, `ATTENDRE`, `TRAVAIL`, `GREVE`), missions, inventaire de 27 cases, faim, impayés, action en cours.
  **Boucle de tick** : `customServerAiStep()` (ligne ~464) : chunks forcés toutes les 20 ticks, `tickCorps()` (ramassage, vitesse, armure…), `tickCourrier()` chaque seconde, l'action en cours (`Actions.Action.tick`) à chaque tick, puis `Cerveau.penser` toutes les 10 ticks (décalé par `getId()`). Aussi : à terre et relevé, mort et tombe, lettres reçues, `appliquer(Ordres.Lecture)`. Liés : `Cerveau`, `Actions`, `Courrier`, `Consignes`, `ColoniePnj.TICKETS`. Piège : `forcerChunk()` garde 3 x 3 chunks par PNJ (sinon il se fige au bord).
- **Cerveau** (292) : ce que fait tout PNJ employé, appelé toutes les demi-secondes sans action en cours : grève, fuite (frappé par un joueur, même un combattant ne riposte pas : il recule ; un joueur ne se frappe que sur consigne du chef), faim, paie, consignes, retour à temps, repos, sac plein, courrier, missions ; puis `Metier.travailler`. `Portee` : journée, demain, expédition, trop loin. Journal d'erreurs `JOURNAL` (vu au panneau). Une erreur met le cerveau en pause 200 ticks.
- **Actions** (659) : les gestes qui durent plusieurs ticks (`Aller`, `SuivreChef`, `Fuir`, `Attendre`, `Casser`, `Abattre`, `Poser`, `Attaquer`, `ToucherPaie`, `Deposer`, `Prendre`, `Demander`, `PorterLettres`, `LireLettres`, `Reposer`, `Ramasser`). Une seule à la fois. `Aller.coince()` : immobile 15 s ou sans progrès 2 min. Liés : `Itineraire`, `Monde`.
- **Itineraire** (545) : aller loin sans cul-de-sac : chemin Minecraft de près (64 blocs), étapes d'une trentaine de blocs vérifiées avant de partir, retour par la trace, sortie de sous terre, escalier creusé vers le ciel, nage, bateau. `diagnostic()` pour `/pnj diag`. Pièges : ne jamais suivre un chemin direct partiel ; `getHeight` sur chunk non chargé renvoie le fond du monde.
- **Monde** (169) : ce que le PNJ voit : surface, blocs libres, liquides, `chercherBloc`, `solNaturel`, `construit` (ne pas casser les constructions), cibles hostiles (`cibles` : jamais un joueur, même agresseur du PNJ ou du chef). Seulement les chunks chargés. Recherches de blocs : à surveiller pour la performance.
- **Consignes** (1563) : les actions du répertoire jouées pour de vrai (gestes, postures, gardes, tâches, combats, sons, particules, instruments, formations). `ordonner` (le chef demande), `executer` (appelé par le cerveau), `interrompre`, `faits` (ce que le PNJ sait dire : heure, météo, inventaire…). Règles du serveur appliquées ici (`cassable`, cibles). Liés : `Repertoire`, `Cerveau`, `Dialogue`.

## Métiers
- **Metier** (140) : classe de base. Catalogue `TOUS`, commandes par métier, missions (`avancement`, `remplie`), `travailler(t)` à définir. L'état de travail va dans `t.memoire` (sauvegardé).
- **Mineur** (282) : filons visibles, sinon escalier en colimaçon puis galeries tous les 3 blocs ; torches, comble les trous, fuit lave et eau, demande la bonne pioche.
- **Bucheron** (128) : abat seulement les arbres naturels (avec feuilles), replante, va droit au biome du bois demandé. Lié : `Biomes`.
- **Guerrier** (81) : garde, ronde, escorte ; tue les hostiles et les agresseurs, jamais un villageois, un animal ou un joueur innocent.
- **Biomes** (98) : quel biome fait pousser quel bois (mods compris), recherche du plus proche comme `/locate biome`.

## Poste, courrier, économie
- **Bureau** (394) : le poste de travail, un par joueur (`Bloc`, `Entite` avec caisse et dépôt agrandi par les coffres voisins, `Menu`). Données de l'écran du chef. Liés : `Courrier`, `Client.EcranPoste`, `Reseau`.
- **Courrier** (406) : `SavedData` du monde : lettres, postes de chaque joueur, effectif, vols de corbeau (15 blocs/s, comptés, pas simulés). `tick()` chaque seconde livre les corbeaux arrivés. `ecrire`, `avis`, `ecrireAuPnj`. Liés : `Messages`, `Corbeau`, `Bureau`.
- **Corbeau** (171) : l'entité visible au départ et à l'arrivée d'une lettre. Non sauvegardée.
- **Messages** (243) : textes des lettres et bulles, plusieurs variantes par situation, ton selon le tempérament ; trous `{0}`, `{1}`.
- **Monnaie** (74) : calcul pur des paiements en pièces Numismatics. Testé par `MonnaieTest`.

## Ordres et IA
- **Ordres** (589) : comprend le français sans IA : ordres, questions, missions (« ramène 10 fers », « garde 32 bûches »), nombres en lettres, horaires. `lire(texte, metier)` rend une `Lecture`. Pur Java.
- **Repertoire** (455) : les actions demandables, lues dans `repertoire.txt` et `paroles.txt` ; `chercher`/`lire` trouvent les actions dites, `candidats` en propose à l'IA. Pur Java. Piège : `Set.of` refuse les doublons.
- **Dialogue** (225) : le chef parle (fenêtre ou micro) : `parler()` trouve les destinataires (`Adressage`, puis l'IA si doute), applique et fait répondre. `autour()` : ouvriers visibles.
- **Adressage** (475) : probabilité que chaque ouvrier soit visé : formule apprise (`adressage.json`) croisée avec l'avis de l'IA. Pur Java.
- **IA** (527) : appels en arrière-plan : `converser` et `converserGroupe` (Groq), `ecouter`, `transcrire` (Whisper), `voix` (Gemini) ; quotas du jour et pauses ; `tester()` pour `/pnj ia test`. Tout échec retombe sur l'interpréteur sans IA.
- **ReponsesIA** (417) : le contenu des requêtes et la lecture des réponses : schéma JSON strict (`schema`, `schemaGroupe`, `action`, `response_format` type `json_schema`), Whisper, Gemini, erreurs et délais. Pur Java. Piège : c'est ici que se joue l'erreur HTTP 400 de Groq (schéma refusé).
- **ReglagesIA** (156) : `config/colonie_pnj/ia.json` (clés, modèles, quotas). Ne jamais afficher ce fichier.
- **Voix** (358) : parler (Gemini, timbre propre à chaque PNJ, répliques à tour de rôle) et écouter le micro jusqu'au silence. `tick()` à chaque tick serveur. Liés : `VoixSvc`, `Audio`, `Dialogue`.
- **VoixSvc** (125) : branchement à Simple Voice Chat, chargé seulement si le mod est là. Catégorie de volume « PNJ ».
- **Audio** (153) : WAV mono 16 bits, changement de fréquence (48/24/16 kHz), volume, phrases inventées par Whisper sur du silence. Pur Java.

## Interface client et réseau
- **Client** (781) : tout ce qui tourne chez le joueur : `ModeleOuvrier` et `Rendu` (corps de joueur et skin), `ModeleCorbeau`, bulles, écrans `EcranFiche` (embauche), `EcranConversation`, `EcranPoste` (ouvriers, missions, horaires, paie, lettres), `EcranBureau`. Piège : jamais de texte avec fond en `DisplayMode.NORMAL`.
- **Poses** (331) : angles de la tête, du corps, des bras et des jambes pour chaque geste ; `viser()` place une main sur un point. Pur Java.
- **Photos** (98) : banc photo des gestes (`./gradlew runPhotos`, propriété `colonie.photos`). Jamais chez un joueur.
- **Reseau** (110) : les paquets `Fiche`, `Commande`, `Conversation`, `Parler`, `Parole`, `Poste`, `ActionBureau`. Canal `registrar("6")` : le changer oblige à publier un modpack. Piège : vérifier `connection.hasChannel` avant d'envoyer (faux joueurs de test).

## Démarrage, réglages, admin
- **ColoniePnj** (326) : point d'entrée du mod : enregistrements (entités `TRAVAILLEUR`, `CORBEAU`, bloc `BUREAU`, menus, `TICKETS` pour forcer les chunks), événements, `tous(server)` (tous les PNJ vivants, parcourt toutes les entités), tick serveur (recale les chunks toutes les 100 ticks, `Courrier.tick`, `Voix.tick`), arrivée des chercheurs d'emploi, **commande `/pnj`** (op niveau 2) : `chercheur <metier> [niveau] [skin]`, `recharger`, `liste`, `diag <prenom>`, `ia [recharger|test]`, `panneau`, `banc <N> [metier]` / `banc fin`, `charge`.
- **Reglages** (169) : `config/colonie_pnj/reglages.json` : monnaie, paie, grève, `pnj_max_par_joueur` (5), chercheurs, métiers inactifs, `port_panneau`, `jeton_panneau` (ne jamais l'afficher).
- **PanneauWeb** (258) : serveur HTTP intégré (port 25776) : état, réglages, faire apparaître un PNJ, actions sur un PNJ. Protégé par `jeton_panneau`. Sert `web/index.html`.

## Tests JUnit (`src/test`, pur Java, 60 tests ; `ChargeTest` : la fenêtre de mesure ; `MonnaieTest.paieEnRetardSansDebordement`)
- **OrdresTest** (227, 17 tests) : lecture des ordres et missions.
- **IATest** (223, 14) : son et lecture des réponses d'IA, sans réseau.
- **RepertoireTest** (212, 11) : trouver les actions dans une phrase.
- **AdressageTest** (89, 5) : formule apprise sur des cas nets, consigne de l'IA.
- **MonnaieTest** (48, 5) : paiements en pièces.
- **Entrainement** (577, 0 test) : outil, pas un test : entraîne l'adressage (`./gradlew entrainer`). Écrit `adressage.json`.

## Tests en jeu (`src/main`, `./gradlew runGameTestServer`, 74 tests : 40 TestsJeu, 15 TestsRepertoire, 16 TestsIA, 3 TestsCharge)
- **TestsJeu** (~800, 40 tests) : cerveau et métiers pour de vrai (retour à temps, expédition, mineur, bûcheron, guerrier, dépôt, lit, à terre, grève, panneau, sorties de sous terre, nage…). Outils : `ouvrier(h, metier, x, y, z, outil)` et `employe(h, metier, bureau, pos, outil)` font apparaître un PNJ employé en service toute la journée, sans paie ; `sol(h, dx, dz)` : sol plat loin des autres tests.
- **TestsIA** (639, 16 tests) : IA contre un faux Groq et un faux Gemini (serveur local `avant`/`apres`). Outils : `equipe(h, chef, coin)` (3 ouvriers autour d'un faux chef), `etapes(...)` (attendre qu'ils soient visibles puis enchaîner), `attendre(h, ticks)`.
- **TestsRepertoire** (~300, 15 tests) : actions du répertoire jouées sans IA, chacune dans son coin de monde plat.
- Pièges : structure fermée par un plafond de barrières (sauf `skyAccess = true`) ; pas de `runAfterDelay` dans un déclencheur ; un PNJ juste apparu n'est visible qu'au tick suivant (`TestsIA.etapes`) ; `rm -rf run/world` avant de lancer.

## Ajouts du 11/10 soir (0.8.2)
- `Monnaie.SANS_PAIE` / `Monnaie.paieEnRetard` : calcul de retard de paie sans débordement (utilisé par `Travailleur.paieEnRetard`).
- `Travailleur.ecouter(qui, ticks)` : hélé par un messager, l'ouvrier s'arrête et le regarde sans perdre son action (`Consignes` `Faire.message` l'appelle à 16 blocs ou moins, seulement pour un ouvrier du même chef). Refus s'il vient d'être frappé (`vientDEtreFrappe()`), s'il se bat ou fuit (même dans une consigne), ou avec un monstre à 8 blocs ; frappé pendant l'écoute, il cesse d'écouter.
- `Corbeau.arrivee` / `envol` : le corbeau part d'un point dont tout le trajet est actif (sinon lettre livrée tout de suite) ; pas d'envol depuis un tronçon inactif.
- Piège : l'ordre des GameTests d'un lot dépend d'un HashSet ; ajouter un test déplace les autres sur la grille. Un test ne doit jamais dépendre de son voisin (faux joueur, tronçon chargé).
