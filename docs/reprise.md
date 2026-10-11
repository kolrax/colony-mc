# Reprise : où on en est

Mise à jour : 11/10/2026 soir. Une page à lire en début de session, avec `docs/plan.md` et la fin de `docs/journal.md`.

## Versions
- Mod `colonie_pnj` : **0.8.0** (`mod-npc/gradle.properties`, `mod_version`). C'est aussi la version installée sur le serveur.
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

## Tests (au 11/10)
- 52 tests JUnit verts (`./gradlew build`).
- 65 tests en jeu verts (`rm -rf run/world && ./gradlew runGameTestServer`) : 36 TestsJeu, 16 TestsIA, 13 TestsRepertoire.

## Prochaine étape (lot 1 de `docs/plan.md` : fiabilité et performance)
1. **Banc de charge** : une commande de test qui fait apparaître N ouvriers au travail et mesure le temps par tick (MSPT) et la part du mod.
2. **50 PNJ sans ralentissement** : objectif, la part du mod sous 10 ms par tick pour 50 PNJ.
3. **Déblocage** : il creuse ou pose des blocs pour sortir ; en dernier recours, téléportation au poste avec un message (exception accordée le 11/10).
4. Plus de morts dues à un bug (chutes, noyade, lave, faim).

## Fichiers à ouvrir pour cette étape
- `mod-npc/src/main/java/fr/lacolonie/pnj/Travailleur.java` : `customServerAiStep()` (la boucle de tick du PNJ), `tickCorps()`, `forcerChunk()`.
- `mod-npc/src/main/java/fr/lacolonie/pnj/Cerveau.java` : `penser()` et `decider()` (toutes les 10 ticks).
- `mod-npc/src/main/java/fr/lacolonie/pnj/ColoniePnj.java` : `commandes()` (la commande `/pnj`, où ajouter le banc), le `ServerTickEvent.Post`, `tous()`.
- `mod-npc/src/main/java/fr/lacolonie/pnj/TestsJeu.java` : `employe()` et `sol()` pour faire apparaître des ouvriers en test.
- `mod-npc/src/main/java/fr/lacolonie/pnj/Monde.java` et `Itineraire.java` : recherches de blocs et chemins, suspects pour la charge ; `Actions.java` (`Aller.coince()`) pour le déblocage.
- `docs/carte-du-code.md` pour le reste.

## Session en cours
- 11/10 19 h UTC : correction de l'erreur IA HTTP 400 (schéma Groq) en cours.
