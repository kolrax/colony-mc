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
  - **mesure** : `Charge` (fenêtre de 100 ticks, parties TOTAL/ENTITE/MOTEUR/CHUNKS/CORPS/ACTION/CERVEAU/CHEMINS/FILET/COURRIER/VOIX ; depuis la 0.8.4, les **recherches de chemin** comptées par origine `Charge.Origine` DIRECT/ETAPE/SORTIE/MONTEE/AUTRE, leurs échecs, le pire tick et le pire PNJ sur un tick : `recherche()`, `recherches()`, `echecs()`, `recherchesPireTick()`, `recherchesPirePnj()` ; les jetons d'étape refusés faute de budget et ceux accordés hors budget : `jeton(force)`, `jetonsRefuses()`, `jetonsForces()`), `TestsCharge` (10/50/100 PNJ, lots séparés ; `cheminsSobres` : arbre perché, enclos, prisonnier ; `TestsJeu.neMarchePasDansLaLave`), commandes op `/pnj banc <N> [metier]`, `/pnj banc fin`, `/pnj charge`. `Travailleur.auTravail(...)` fait apparaître un ouvrier au travail (tests et banc).
- **Déblocage, chemins** :
  - fichiers : `Itineraire` (étapes, sortie de sous terre, creusage, pilier et pont `commencerAPoser`/`poser`, bateau), `Actions.Aller` (`coince()`), `Actions.placer`, `Cerveau` (réaction au résultat `bloque`, lettre « perdu »), `Monde` (`placeSure`), `Coince` (temps coincé), `Travailleur` (`surveillerCoince`, `ramenerAuPoste`, saut d'à-pic, fin du fichier).
  - tests : `TestsJeu` (`sortParLesGaleries`, `creuseVersLaSurface`, `sortDuPuits`, `traverseALaNage`…), `TestsDeblocage` (pilier, pont, retour au poste, garde-fou ; lot `deblocage`), `CoinceTest` (JUnit).
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
- **Morts bêtes, réflexe de survie** :
  - fichiers : `Travailleur` (`survivre`, `enPeril`, `menace`), `Actions` (`Echapper`, `Boucher`, `Casser.boucher`/`seMettreACote`), `Monde` (`tombe`, `eboulement`, `coulee`, `refuge`, `ligneSure`, `chute`, `eauProche`, `mortel`), `Mineur.creuserCase`, `Itineraire.commencerCreusage`/`monter`.
  - tests : `TestsMorts` (lot `morts`).
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
Suspects pour la charge : `tous()` parcourt toutes les entités de tous les mondes ; `Monde.chercherBloc` et `solNaturel` balaient des volumes ; le calcul des chemins dans `Itineraire` (bridé depuis la 0.8.4 : 4 recherches d'étape par tick et par PNJ, 16 pour le serveur, 20 ticks de pause après un échec complet) ; 9 chunks forcés par PNJ.

## Pièges communs
- Un PNJ seul se fige au bord d'un chunk : il faut 3 x 3 chunks forcés (`forcerChunk`).
- `Level.getHeight` sur un chunk non chargé renvoie le fond du monde : charger le chunk d'abord.
- Ne pas multiplier la vitesse en sprint (l'attribut fait déjà +30 %).
- Règles du serveur codées dans `Consignes`, `Monde.construit` et `Guerrier` : ne frapper que les monstres, ne casser que le naturel, pas de téléportation, sauf le retour au poste en dernier recours (`Travailleur.ramenerAuPoste`, codé au lot 1).
- Un bloc `static {}` qui lit des champs doit venir après eux.

## Entité et cerveau
- **Travailleur** (1265) : le PNJ, un `PathfinderMob` à corps de joueur. Champs : métier, niveau, chef, bureau, mode (`CHOMEUR`, `SUIVRE`, `ATTENDRE`, `TRAVAIL`, `GREVE`), missions, inventaire de 27 cases, faim, impayés, action en cours. Depuis la 0.8.4 : `createNavigation` compte chaque vraie recherche (`PathFinder.findPath`) dans `recherches` / `recherchesPireTick` (lus par les tests) et `Charge` ; `origineChemin` (posé par `Itineraire.chercher`) ; `blocsInaccessibles` (position → fin, 600 ticks, non sauvegardé) alimenté par `Casser`/`Abattre` quand ils échouent « inaccessible », lu par `Actions.chercherArbre(..., pour)` (bûcheron, consigne « abattre »). Le mineur garde sa propre mémoire (`ignores`).
  **Boucle de tick** : `customServerAiStep()` (ligne ~464) : chunks forcés toutes les 20 ticks, `tickCorps()` (ramassage, vitesse, armure…), `tickCourrier()` chaque seconde, l'action en cours (`Actions.Action.tick`) à chaque tick, puis `Cerveau.penser` toutes les 10 ticks (décalé par `getId()`). Aussi : à terre et relevé, mort et tombe, lettres reçues, `appliquer(Ordres.Lecture)`. Depuis le lot 1 « déblocage » : `coince` (`Coince`), `surveillerCoince()` chaque seconde, `ramenerAuPoste()` (dernier recours, 5 min coincé, hors danger, avec un poste, mode travail ou grève, à plus de 6 blocs du poste ; même dimension, sinon celle du poste), `finirAction` nourrit le compteur. **Réflexe de survie** (lot 1 « morts ») : `survivre()` tous les 5 ticks (décalé par `getId()`, hors chômeurs ; quelques lectures de blocs) ; `enPeril()` = dans la lave, dans un bloc de feu, enseveli (`isInWall`), ou air < 100 sous l'eau ; ou en feu hors de l'eau. Il lâche lettre, écoute et action (`finirAction("danger")`) pour `Actions.Echapper` ; à bout d'air ou en feu sans refuge à portée (place au sec à 4 blocs, eau à 5), il ne lâche rien et ne recherche pas avant 1 s (`sansRefugeJusqua`). `menace()` compte aussi `Echapper` et `enPeril()` : ni écoute d'un messager, ni lecture de lettre ; un coup reçu n'interrompt pas `Echapper` (`Cerveau.decider`). Liés : `Cerveau`, `Actions`, `Courrier`, `Consignes`, `ColoniePnj.TICKETS`. Piège : `forcerChunk()` garde 3 x 3 chunks par PNJ (sinon il se fige au bord).
- **Cerveau** (292) : ce que fait tout PNJ employé, appelé toutes les demi-secondes sans action en cours (frappé en pleine action, il la lâche d'abord, une fois par coup : `Travailleur.coupVu` ; pas s'il fuit ou se bat déjà, ni frappé par son chef) : grève, fuite (frappé par un joueur, même un combattant ne riposte pas : il recule ; un joueur ne se frappe que sur consigne du chef), faim, paie, consignes, retour à temps, repos, sac plein, courrier, missions ; puis `Metier.travailler`. `Portee` : journée, demain, expédition, trop loin. Journal d'erreurs `JOURNAL` (vu au panneau). Une erreur met le cerveau en pause 200 ticks.
- **Actions** (760) : les gestes qui durent plusieurs ticks (`Aller`, `SuivreChef`, `Fuir`, `Attendre`, `Casser`, `Abattre`, `Poser`, `Attaquer`, `ToucherPaie`, `Deposer`, `Prendre`, `Demander`, `PorterLettres`, `LireLettres`, `Reposer`, `Ramasser`, `Echapper`). Une seule à la fois. `Aller.coince()` : immobile 15 s ou sans progrès 2 min ; l'attente « que le terrain soit prêt » (chunks pas encore prêts) ne compte plus « coincé » (`Coince`), sinon il pouvait être ramené en pleine marche (`TestsDeblocage.quiMarcheNEstJamaisRamene`, instable avant). Liés : `Itineraire`, `Monde`.
  - **`Casser`, sécurité** (lot 1 « morts ») : à portée, avant de frapper (puis toutes les 10 ticks), `Monde.coulee` : de la lave contre le bloc, ou de l'eau juste au-dessus s'il est dessous et au sec → `boucher` (pierre ou terre de son sac, jamais de planches : `Itineraire.caseDeChantier(t, true)`, posé dans le liquide par `placer`, 6 au plus) ; sans bloc ou hors de portée : échec « danger » (`blocInaccessible`). Gravier, sable (`Monde.tombe`, tout `FallingBlock`) au-dessus du bloc et lui dans la colonne dessous (`dessous`) → `seMettreACote` : une place debout à 2 blocs, hors de la colonne, d'où il touche le bloc (cherchée une fois), une recherche de chemin par seconde au plus, abandon si le chemin ne l'atteint pas ou au bout de 100 ticks : échec « danger ». Le mineur ignore ensuite ce filon (`ignores`).
  - **`Boucher`** : pose pierre ou terre de son sac dans un liquide à portée (galeries et escalier du mineur, `Mineur.creuserCase`).
  - **`Echapper`** : le réflexe de survie (`Travailleur.survivre`). Enseveli : il sort par le côté le plus sûr (`sortie` : debout, sinon la plus petite chute, 3 blocs au plus), sinon il dégage le bloc de sa tête s'il est naturel (`Monde.construit`). Sinon il va au refuge (`Monde.refuge` : d'abord à sa hauteur ou à un bloc près sur 4 blocs, puis à 2 blocs ; ou `Monde.eauProche` s'il est en feu), choisi de nouveau une fois par seconde tant qu'il est en péril (`partir`) : tout droit seulement si `Monde.ligneSure` (ni lave, ni feu, ni chute de plus de 3 ; 3 cases brûlantes tolérées s'il est déjà dans la lave ou le feu), sinon un chemin qui atteint le refuge (`canReach`), sinon un autre refuge (`exclus`) ; jamais de chemin partiel. Sans refuge dans un liquide, il nage vers le haut. Fini dès qu'il est hors de danger (éteint pour la version « eau »), échec « perdu » au bout de 10 s. `t.diag` : « échappe vers … ». Le guerrier ne l'interrompt pas pour attaquer (`Guerrier.vigilance`).
- **Itineraire** (631) : aller loin sans cul-de-sac : chemin Minecraft de près (64 blocs), étapes d'une trentaine de blocs vérifiées avant de partir, retour par la trace, sortie de sous terre, escalier creusé vers le ciel, nage, bateau. `diagnostic()` pour `/pnj diag`. `commencerCreusage` refuse un essai qui casse un bloc de sa propre colonne portant du gravier ou du sable, ou dont la marche va recevoir du gravier (`Monde.eboulement`) ; `monter` n'y monte pas tant que ça tombe. Sous une couche de gravier, il creuse de côté jusqu'à en sortir (`TestsMorts.creuseSousDuGravier`). Pièges : ne jamais suivre un chemin direct partiel ; `getHeight` sur chunk non chargé renvoie le fond du monde.
  - Depuis la 0.8.5 : `PAR_TICK` = 2, `BUDGET` = 4, un seul passe-droit par tick serveur (le plus ancien) ; étapes cherchées avec portée = distance + `DETOUR` (16) et 512 nœuds (`chercher(..., portee, noeuds)`, remis dans un finally ; `Travailleur.porteeChemin`) ; mémoire des étapes ratées par PNJ (`Travailleur.etapesRatees`, clé cellule+hauteur de départ et cellule visée, 30 s ; tout en mémoire → choix refait sans elle) ; délai croissant après un choix tout raté (`choixRates` : 20 → 200 ticks) ; `attend()` gèle le compteur d'immobilité d'`Actions.Aller` ; `Charge` mesure aussi la durée moyenne des recherches réussies/ratées par origine. Tests `TestsCharge.etapesSobres` et `etapeEnMontee`.
  - Coût des chemins (0.8.4, valeurs remplacées ci-dessus) : toute recherche passe par `chercher(t, pos, precision, origine)` (comptée dans `Charge`). `choisirEtape` est une petite machine à états (`phase` 0 but, 1 trace, 2 sortie, 3 candidats ; `curseur`, `candidats`, `rates`) : au plus `PAR_TICK` = 4 recherches par tick et par PNJ, `BUDGET` = 16 pour tout le serveur (`budget`, remis par `nouveauTick()` depuis `ColoniePnj`), le PNJ attend sans chemin partiel (après `ATTENTE_MAX` = 10 ticks d'attente, il passe hors budget, compté dans `Charge`) ; un choix laissé en route est oublié (`finChoix`) en passant au chemin direct, au bateau ou au retour d'une montée ; après un choix où rien n'est atteignable, `prochainChoix` = 20 ticks plus tard. Chemin direct et sortie : recalcul au plus toutes les `RECALCUL` = 10 ticks quand la navigation est finie ; au bout d'un chemin accepté (`auBout`), il fait les derniers pas tout droit **seulement si ce chemin mène au but** (`canReach`) ; un chemin qui s'arrête à côté (lave, trou, bloc à casser) le laisse au bord, avec un recalcul toutes les 20 ticks.
  - **Déblocage** (lot 1, relu) : quand rien n'est atteignable et qu'il ne peut pas creuser (construction, trop haut) ou qu'un pas de côté libre ne mène nulle part, `commencerAPoser` : un **pilier** (il saute et pose sous lui un bloc de chantier de son sac, `BLOCS_CHANTIER` + planches ; 8 blocs au plus, 3 si vie <= 10 ; refusé si la place de ses pieds n'est pas vide : dalle, chemin, terre labourée, torche…) ou un **pont** d'1-2 blocs au-dessus d'un vide ; `poser` (chaque tick, sans recherche de chemin), puis `montee` vers le bord, puis `reprendre` (il casse ses blocs à portée de main s'il sait les récolter, sauf ceux où se tient quelqu'un d'autre). Abandon au bout de 100 ticks sans pose : `echecs++`, délai croissant, endroit mémorisé (`posesRatees`) ; `POSES_MAX` 24 par trajet. Jamais dans un liquide, sur quelqu'un (`Actions.placer`), ni vers la lave, le feu ou le vide (`Monde.mortel`) ; jamais les blocs que sa mission demande (`caseDeChantier`) ; en mode ATTENDRE, ni creusage ni pose (`evasionPermise`). Tests `TestsDeblocage`.
- **Monde** (250) : ce que le PNJ voit : surface, blocs libres, liquides, `tombe` (bloc qui tombe), `eboulement` (du gravier va tomber dans une case), `coulee` (liquide à boucher), `refuge`, `ligneSure`, `chute`, `eauProche`, `chercherBloc`, `solNaturel`, `construit` (ne pas casser les constructions), cibles hostiles (`cibles` : jamais un joueur, même agresseur du PNJ ou du chef). Seulement les chunks chargés. Recherches de blocs : à surveiller pour la performance.
- **Consignes** (1563) : les actions du répertoire jouées pour de vrai (gestes, postures, gardes, tâches, combats, sons, particules, instruments, formations). `ordonner` (le chef demande), `executer` (appelé par le cerveau), `interrompre`, `faits` (ce que le PNJ sait dire : heure, météo, inventaire…). Règles du serveur appliquées ici (`cassable`, cibles). Liés : `Repertoire`, `Cerveau`, `Dialogue`.

## Métiers
- **Metier** (140) : classe de base. Catalogue `TOUS`, commandes par métier, missions (`avancement`, `remplie`), `travailler(t)` à définir. L'état de travail va dans `t.memoire` (sauvegardé).
- **Mineur** (290) : filons visibles, sinon escalier en colimaçon puis galeries tous les 3 blocs ; torches, comble les trous, fuit lave et eau, demande la bonne pioche. Filons : bouche la lave, jamais sous du gravier (voir `Actions.Casser`). `creuserCase` : un liquide autour de la case → `Actions.Boucher` (`BOUCHONS` = 10 par case, compteur `bouchons` en mémoire), sans pierre ni terre ou au-delà : `LIQUIDE` comme avant (l'escalier tourne, la galerie est abandonnée) ; du gravier ou du sable qui va tomber dans la case (`Monde.eboulement`) : il attend 0,5 s et le recasse depuis sa marche, il n'y entre qu'une fois la colonne tombée (`TestsMorts.escalierDansLeSable`).
- **Bucheron** (128) : abat seulement les arbres naturels (avec feuilles), replante, va droit au biome du bois demandé. Lié : `Biomes`.
- **Guerrier** (81) : garde, ronde, escorte ; tue les hostiles et les agresseurs, jamais un villageois, un animal ou un joueur innocent.
- **Biomes** (98) : quel biome fait pousser quel bois (mods compris), recherche du plus proche comme `/locate biome`.

## Poste, courrier, économie
- **Bureau** (394) : le poste de travail, un par joueur (`Bloc`, `Entite` avec caisse et dépôt agrandi par les coffres voisins, `Menu`). Données de l'écran du chef. Liés : `Courrier`, `Client.EcranPoste`, `Reseau`.
- **Courrier** (406) : `SavedData` du monde : lettres, postes de chaque joueur, effectif, vols de corbeau (15 blocs/s, comptés, pas simulés). `tick()` chaque seconde livre les corbeaux arrivés. `ecrire`, `avis`, `ecrireAuPnj`. Liés : `Messages`, `Corbeau`, `Bureau`.
- **Corbeau** (171) : l'entité visible au départ et à l'arrivée d'une lettre. Non sauvegardée.
- **Messages** (243) : textes des lettres et bulles, plusieurs variantes par situation, ton selon le tempérament ; trous `{0}`, `{1}`.
- **Monnaie** (74) : calcul pur des paiements en pièces Numismatics. Testé par `MonnaieTest`.
- **Coince** : calcul pur du temps passé coincé. Compte quand il veut marcher vers un endroit fixe sans creuser ni poser (`Action.mobile` : SuivreChef, Fuir, Attaquer, Demander vers le chef, `Aller.mobile(true)` des consignes de suivi ne comptent pas), ou, sans action ou en `Attendre`, dans la minute qui suit un échec « bloque »/« inaccessible » (`FENETRE_ECHEC`). Remis à zéro arrivé à son but (après avoir marché), à 8 blocs du départ du compte, à un ordre qui change son mode ou son action (`appliquer`), embauche, renvoi, et tant qu'il ne pourrait pas être ramené (`Travailleur.surveillerCoince` : sans poste, hors travail/grève, menace, bateau, repos, sommeil, suivi, à moins de 6 blocs du poste). `DELAI` 5 min (15 s dans les lots de tests « deblocage »), `ENTRE_DEUX` 10 min, `REESSAI` 1 min sans place sûre (lettre « sans_place » une fois), `RETOURS_MAX` 2 retours sans vrai travail (Casser, Abattre, Poser, Attaquer réussis, poste de ronde atteint `Action.travail` ; oubliés au bout d'1 h) : il reste au poste en ATTENDRE (`attenteAuto`, lettre « ramene_attend » ; il y touche sa paie ; un gréviste reste en grève) ; remis au travail par son chef, un seul retour de plus. `Travailleur.ramenerAuPoste` : la seule téléportation permise (`Monde.placeSure`, avis « Le poste » au chef ; autre dimension : `libererChunk` puis `changeDimension`). Testé par `CoinceTest` et `TestsDeblocage`.

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

## Tests JUnit (`src/test`, pur Java, 63 tests ; `ChargeTest` : la fenêtre de mesure ; `MonnaieTest.paieEnRetardSansDebordement`)
- **OrdresTest** (227, 17 tests) : lecture des ordres et missions.
- **IATest** (223, 14) : son et lecture des réponses d'IA, sans réseau.
- **RepertoireTest** (212, 11) : trouver les actions dans une phrase.
- **AdressageTest** (89, 5) : formule apprise sur des cas nets, consigne de l'IA.
- **MonnaieTest** (48, 5) : paiements en pièces.
- **Entrainement** (577, 0 test) : outil, pas un test : entraîne l'adressage (`./gradlew entrainer`). Écrit `adressage.json`.

## Tests en jeu (`src/main`, `./gradlew runGameTestServer`, 89 tests : 48 TestsJeu, 18 TestsRepertoire, 16 TestsIA, 7 TestsCharge)
- **TestsJeu** (~980, 48 tests) : cerveau et métiers pour de vrai (retour à temps, expédition, mineur, bûcheron, guerrier, dépôt, lit, à terre, grève, panneau, sorties de sous terre, nage…). Outils : `ouvrier(h, metier, x, y, z, outil)` et `employe(h, metier, bureau, pos, outil)` font apparaître un PNJ employé en service toute la journée, sans paie ; `sol(h, dx, dz)` : sol plat loin des autres tests.
- **TestsIA** (639, 16 tests) : IA contre un faux Groq et un faux Gemini (serveur local `avant`/`apres`). Outils : `equipe(h, chef, coin)` (3 ouvriers autour d'un faux chef), `etapes(...)` (attendre qu'ils soient visibles puis enchaîner), `attendre(h, ticks)`.
- **TestsRepertoire** (~370, 18 tests) : actions du répertoire jouées sans IA, chacune dans son coin de monde plat.
- **TestsMorts** (~520, 20 tests, lot `morts`) : « aucune mort due à un bug ». Chutes (falaise, deux à-pics, pilier), noyade (longue nage, galerie inondée, poche d'eau percée, mare), lave (filon sous la lave, devant un lac de lave, bûcheron près de la lave), faim, étouffement (filon sous du gravier ou du sable, escalier sous le gravier) réflexe de survie (`sortDuFeu`, `sortDuGravier`, `sortDeLaLave`, `feuSansEscaladerUnRebord`, `sortDuGravierDuBonCote` : il attend dans le danger, il doit en sortir) et `escalierDansLeSable` (le mineur descend dans 7 blocs de sable). `suivre(h, t)` relève à chaque tick la vie et l'air les plus bas, les dégâts subis et les actions jouées ; `finir` l'écrit dans le journal (« [morts] ») et vérifie.
- Pièges : structure fermée par un plafond de barrières (sauf `skyAccess = true`) ; pas de `runAfterDelay` dans un déclencheur ; un PNJ juste apparu n'est visible qu'au tick suivant (`TestsIA.etapes`) ; `rm -rf run/world` avant de lancer.

## Ajouts du 11/10 soir (0.8.2)
- `Monnaie.SANS_PAIE` / `Monnaie.paieEnRetard` : calcul de retard de paie sans débordement (utilisé par `Travailleur.paieEnRetard`).
- `Travailleur.ecouter(qui, ticks)` : hélé par un messager, l'ouvrier s'arrête et le regarde sans perdre son action (`Consignes` `Faire.message` l'appelle à 16 blocs ou moins, seulement pour un ouvrier du même chef). Refus s'il vient d'être frappé (`vientDEtreFrappe()`), s'il se bat ou fuit (même dans une consigne), ou avec un monstre à 8 blocs ; frappé pendant l'écoute, il cesse d'écouter.
- `Corbeau.arrivee` / `envol` : le corbeau part d'un point dont tout le trajet est actif (sinon lettre livrée tout de suite) ; pas d'envol depuis un tronçon inactif.
- Piège : l'ordre des GameTests d'un lot dépend d'un HashSet ; ajouter un test déplace les autres sur la grille. Un test ne doit jamais dépendre de son voisin (faux joueur, tronçon chargé).

## Restes de relecture du lot 1 (12/10, après la 0.8.5)
- `Monde.combattable(e)` : sans ordre, un PNJ ne riposte que contre un monstre (`hostile`) ou un autre PNJ ; frappé par une bête, un golem ou un joueur, il recule (`Cerveau.decider`, `Monde.cibles`, `Consignes.Faire.menace`). Le bloc « attaqué » passe avant la grève.
- `Monde.mortel(l, p)` : lave, feu ou vide (chute de plus de 20 blocs) : « va là » refusé (cahier 8.5, `Consignes.ordonner`, cas `aller`).
- `Travailleur.menace()` (à terre, frappé, combat, fuite) et `enDanger()` (menace, ou monstre à 8 blocs en vue ou qui le vise) ; `lettresAuCalme` : lettres reçues en danger, lues au calme (`tickCourrier`), ou après 60 s sans menace (`auCalmeDepuis`) ; sauvegardées avec `LettresALire`, comptées dans « à lire » au poste.
- `Consignes.MESSAGE_MAX` (2 min, puis il renonce, résultat `trop_long`) et `HELER_MAX` (7 s de hélage, l'autre figé 8 s au plus) ; la limite ne joue plus une fois le message commencé.
- `Corbeau.actifSur` : tous les tronçons traversés par le trajet d'arrivée sont vérifiés (avant : un point tous les 4 blocs).
- `Itineraire.suivreSortie` : pas tout droit seulement au bout d'un chemin complet (`canReach`), sinon le point suivant de la route ; pas de recalcul en l'air, et une recherche vide ne jette pas le chemin en cours ; `commencerCreusage` : pas de marche au-dessus d'une chute de plus de 3 blocs, ni de lave ou de magma. `jeton` n'est plus privé (test `TestsCharge.jetonPourLAffame`).
