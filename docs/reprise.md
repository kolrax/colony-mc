# Reprise : où on en est

Mise à jour : 11/10/2026, 21 h UTC. Une page à lire en début de session, avec `docs/plan.md` et la fin de `docs/journal.md`.

## Versions
- Mod `colonie_pnj` : **0.8.2** dans le dépôt (commité, testé, PAS ENCORE RELU ni livré). Sur le serveur : **0.8.1** (livrée le 11/10 à 19 h 21 UTC).
- Modpack : **0.4.8** (d'après `docs/journal.md`). Il n'a pas à changer tant que le client ne change pas (canal réseau toujours `"6"`).
- Serveur : MintServers, monde de test **`test-andesite`** (jetable, génération finale). Le vrai monde sera créé à l'ouverture (vendredi 16/10 au soir ou samedi).

## Ce que le mod sait faire aujourd'hui
- **Recrutement** : des chercheurs d'emploi arrivent à pied près des joueurs ; on les embauche par une fiche (prime prise dans l'inventaire), salaire en pièces Numismatics touché à la caisse du poste. Plafond : 5 PNJ par joueur (réglable).
- **Un poste de travail par joueur** (le bloc bureau) : caisse, dépôt agrandi par les coffres voisins, écran du chef (ouvriers, missions, horaires, paie, durée d'expédition, lettres).
- **Trois métiers codés** : mineur (filons, escalier en colimaçon, galeries), bûcheron (arbres naturels, replante, va au bon biome), guerrier (garde, ronde, escorte, ne frappe que les hostiles et les agresseurs).
- **Autonomie** : espace de vie de 50 blocs, sorties à la journée et expéditions avec camp la nuit, retour à l'heure, repos au lit, faim, fatigue, paie, grève après 2 paies manquées, « à terre » au lieu de mourir tout de suite, tombe à la mort.
- **Déplacements** : longues distances par étapes vérifiées, retour par la trace, sortie de sous terre, escalier creusé vers la surface, nage, bateau. Chaque PNJ garde 3 x 3 chunks actifs.
- **Courrier** : plus de chat ; le PNJ parle en bulle face à face, sinon il écrit une lettre, portée à pied ou par un corbeau. Le chef écrit aussi des missions et des ordres.
- **Ordres en français sans IA** (« suis-moi », « ramène 10 fers ») et un répertoire de nombreuses actions (gestes, postures, gardes, tâches, sons, formations).
- **IA en option** (Groq pour la conversation et l'écoute, Whisper pour le micro, Gemini pour la voix), avec quotas gratuits ; sans clé, tout marche sans IA. Micro et voix par Simple Voice Chat. « À qui parle le chef » : formule apprise croisée avec l'IA.
- **Admin** : commande `/pnj` (chercheur, liste, diag, ia, panneau, recharger) et panneau web intégré au serveur (port 25776).

## Tests (au 11/10, 21 h UTC)
- 60 tests JUnit verts (`./gradlew build`).
- 71 tests en jeu verts (`rm -rf run/world && ./gradlew runGameTestServer`) : 38 TestsJeu, 16 TestsIA, 14 TestsRepertoire, 3 TestsCharge. 5 passages verts de suite.
- Mesure (monde plat de test, 200 ticks) : part du mod ≈ 1,6 à 2,4 ms/tick en moyenne à 50 PNJ (pire 6 à 17 ms) ; à 100 PNJ 2,2 à 4,4 ms, mais 2 pics à ~100 ms sur 4 passages (non analysés).

## Prochaine étape (exacte)
1. **Relire puis livrer la 0.8.2** : faire relire par le `relecteur` le dernier commit (Corbeau.arrivee/envol, Travailleur.ecouter, Consignes Faire.message, ColoniePnj.banc/finBanc, Monnaie.paieEnRetard). Puis livrer sur le serveur (pas de modpack : le client ne change pas).
   - **Envoi du jar depuis le cloud** : `panel.py upload` échoue (connexion coupée vers le nœud). Ce qui marche : `panel.py write /mods-off/essai-colonie_pnj-X.jar <jar>`, vérifier la taille avec `panel.py ls /mods-off`, puis `panel.py mv` vers /mods.
2. **Vraie mesure sur test-andesite** : `python3 tools/panel.py cmd "pnj banc 50 tous X Y Z"` (près du spawn, sur une case d'air sans coffre voisin), attendre ~1 min, `pnj charge`, lire le journal, puis `pnj banc fin`. Personne ne doit être connecté.
3. Analyser les pics (~100 ms à 100 PNJ, pire du cerveau et des chemins) et optimiser (points chauds : `Actions.chercherArbre` rayon 24, `Monde.chercherBloc`, `ColoniePnj.tous()`, chunks forcés par PNJ).
4. Puis lot 1 suite : déblocage, morts dues à un bug.

## Fichiers à ouvrir
- Mesure : `Charge.java`, `TestsCharge.java`, `ColoniePnj.java` (`banc`, `finBanc`, `charge`).
- Points chauds : `Actions.java` (`chercherArbre`), `Monde.java`, `Cerveau.java`, `Itineraire.java`, `Travailleur.java` (`forcerChunk`).
- `docs/carte-du-code.md` pour le reste.

## Session en cours
- aucune.
