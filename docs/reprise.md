# Reprise : où on en est

Mise à jour : 12/10/2026, 2 h 00 UTC. Une page à lire en début de session, avec `docs/plan.md` et la fin de `docs/journal.md`.

## Versions
- Mod `colonie_pnj` : **0.8.5** dans le dépôt et sur le serveur (livrée le 12/10 à 1 h 48 UTC, relue deux fois, 63 JUnit et 79 tests en jeu verts).
- Modpack : **0.4.8**. Il n'a pas à changer tant que le client ne change pas (canal réseau toujours `"6"`).
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
1. Restes de relecture ci-dessous (point 2), en un lot. Facultatif après : pires ticks (chemin direct raté à 9-12 ms : portée = distance + 16 et 512 nœuds aussi pour DIRECT quand dh ≤ 40 ?).
2. Restes de relecture (faibles, à faire en un lot) :
   - (0.8.5) quand tous les points d'étape sont en mémoire, il refait le choix sans elle : la mémoire n'économise presque rien pour un PNJ resté sur place ; variante : refaire sans mémoire seulement s'il a bougé. Marge du test `etapeEnMontee` étroite (48 blocs marchés pour une portée de 50,6).
   - (fait en 0.8.5 : un seul passe-droit par tick)
   - `Itineraire.java:437` sortie de sous terre : exiger `canReach()` avant les pas tout droit ; `:434` idem au dernier point ; `:464-472`/`526` vérifier le sol de la marche quand il creuse.
   - `Cerveau.java:87`, `Monde.java:157` : un guerrier attaque une bête qui le frappe (loup apprivoisé, chèvre, golem) → `Attaquer` seulement si `Monde.hostile` ou PNJ, sinon fuir.
   - `Cerveau.java:69-77` : un gréviste frappé ne fuit pas (bloc grève avant bloc attaque).
   - `Travailleur.recevoirLettre` (l.~577) : une lettre interrompt un combat ou une fuite et fige 3 s.
   - `Consignes.java:1519-1524` : message sans limite de temps (messager qui suit sans fin, ouvrier figé tant qu'il le voit).
   - `Corbeau.java:160-164` : trajet vérifié tous les 4 blocs, peut couper un coin inactif (lettre livrée par le secours au bout de 30 s).
   - `Consignes.java:405-407` : « va là » vers de la lave : le cahier (8.5) veut qu'il refuse.
   - Tests à compléter : logique de `Itineraire.jeton()` (affamé servi après 10 ticks), `neMarchePasDansLaLave` doit vérifier qu'il est arrivé au bord, guerrier frappé par un joueur en pleine action.
   - Cahier 14.8 (le chef frappe son ouvrier : selon son caractère) : aujourd'hui ignoré, à faire au lot 5.
3. Puis lot 1 suite : déblocage (creuser/poser pour sortir, téléportation au poste en dernier recours avec message), morts dues à un bug.

## Envoi du jar depuis le cloud
`panel.py upload` échoue (connexion coupée). Ce qui marche : `panel.py write /mods-off/essai-colonie_pnj-X.jar <jar>`, vérifier la taille avec `panel.py ls /mods-off`, puis `panel.py mv` vers /mods. Banc : `execute positioned 484 64 272 positioned over motion_blocking_no_leaves run pnj banc 50 tous ~ ~ ~` (personne connecté), puis `pnj charge`, `spark tps`, `pnj banc fin`.

## Fichiers à ouvrir
- Chemins : `Itineraire.java` (`avancer`, `choisirEtape`, `jeton`, `suivreSortie`), `Travailleur.java` (`createNavigation`, compteurs, `blocsInaccessibles`, `coupVu`), `Charge.java`, `TestsCharge.java`.
- Combat : `Cerveau.java` (`decider`), `Monde.java` (`cibles`), `Guerrier.java`.
- `docs/carte-du-code.md` pour le reste.

## Session en cours
- aucune.
