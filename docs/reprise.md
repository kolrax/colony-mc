# Reprise : où on en est

Mise à jour : 18/10/2026, 16 h 10 UTC. Une page à lire en début de session, avec `docs/refonte.md`, `docs/corrections.md` (lot 17, prime pour ce qu'il touche), `docs/plan.md` et la fin de `docs/journal.md`.

## Versions
- **Serveur : 1.0.9** (18/10, 16 h 06 UTC), un seul jar dans `/mods` (1.0.0 à 1.0.8 rangés dans `/mods-off`), sur le **monde neuf `essai-v2`**. Les mondes `colonie` (0.9.43, Nielscutt) et `test-andesite` restent sur le disque : ne jamais les supprimer.
- **Modpack : 1.0.7** (canal réseau 9 inchangé depuis 1.0.0 : un client 1.0.0 se connecte encore). `pack/release.py` ne garde plus que le jar maison de la dernière version (`GARDER_VERSIONS = 1` : deux jars dépassaient la limite de 5 Mo de la fonction Supabase). Autres mods inchangés depuis la 0.5.1 (`build.py` voulait monter JEI et lithostitched : `lock.json` remis comme avant, à demander à l'Admin si utile).
- `main` = la 1.0.9 : **298/298 JUnit, 362/362 en jeu**, identique au serveur.
- Banc des écrans avec un vrai client : `cd mod-npc && LIBGL_ALWAYS_SOFTWARE=1 xvfb-run -a -s "-screen 0 1920x1080x24" ./gradlew runEcrans --no-daemon` (~3 min, 56 captures dans `run-photos/screenshots`).
- Panneau : **« Refonte des ouvriers »** https://claude.ai/artifact/CfnApV4mrmi1EtmMVYThkZ (`plan/etapes`, `etat/courant`, `journal`, `essais` V01-V25). L'ancien panneau ne sert plus.

## La 1.0.0 en bref (refonte « ouvriers robots »)
- Mission (`Travailleur.Mission` : id du catalogue, quantité/stock, une fois/tous les jours, lieu, horaires), file, `Missions.donner`/`Missions.action` (écran et fantôme), statut unique `Statut` (entityData `STATUT`, recalculé toutes les 5 s), `t.bloquer(raison)`/`debloquer`.
- Fiche au clic droit (`Client.EcranOuvrier`, `EcranNouvelleMission`, `EcranConfirmer`), poste à 5 onglets (`Client.EcranPoste`), journal du poste (`Courrier.ecrire` → ligne, `Boite`), livret (`Livret`, `livret.txt`), barre de commandes (`BarreCommandes`, touche B).
- Lieux (`Lieux`, `LieuxRegles`, `Lieux.peutCasser`), métiers bloqués avec raison (`Statut` en fin de fichier), `t.lieuTravail()`, hors horaires (`Cerveau.enService`).
- Guerre (`Guerre`, `Armee` : declarer, paix, Rang, Repli ; missions defendre/escorter/attaquer du guerrier).
- Joueur fantôme : `Fantome.java`, `python3 tools/fantome.py mod-npc/fantome/trois-ouvriers.txt` (prend `/tmp/gametest.lock`, ~6 min).
- Retirés (étiquette `v1-finale`) : IA, voix, chat libre, lettres et corbeau, moral détaillé, caractères, familles, rumeurs, économie entre ouvriers, conversations.

## Prochaine étape (exacte)
0. **Lot 17** (`docs/corrections.md`, panneau « Corrections des ouvriers » https://claude.ai/artifact/PHChHnU2CX3tXnxanGD9nW, ordre B, D, E, A). Fait : B1 (compteur, 1.0.9), B3 (Oriane morte, pas un bug). **Reprendre** B2 sur `origin/v2-horaires-generaux` (fini et vert d'après l'agent : 298/298, 357/357 ; reste : une relecture, refaire la capture du poste, décider des ordres parlés « travaille de nuit » / « N jours max » qui changent encore les heures en mémoire ; attention : une mission « Toujours » se fait maintenant jour ET nuit) et D1 sur `origin/v2-carte-xaero` (fini et vert d'après l'agent : JUnit 302/302, 363/363 ; repères Xaero « third-party waypoints », paquet facultatif `colonie_pnj:reperes`, canal 9 inchangé ; reste : une relecture, revoir au banc `./gradlew runCarte` le drapeau à 2 lettres, dire à KOLRAX que le repère se voit aussi dans le monde à travers les murs, coupable dans les réglages de Xaero) (travail des agents de la session du 18/10 13 h, lire le dernier commit de chaque branche : vert ou non, ce qui reste). B2 et D1 changent le client : modpack obligatoire, à publier ensemble. Puis E, puis A.
1. (fait 18/10 : sacs vidés au dépôt, 1.0.3 ; mission juste après l'embauche et mares du fermier, 1.0.4 ; chasseur hors colonie, guérisseur bloqué sans remède, accueil 1.0 à la connexion, 1.0.5 ; gardes qui lâchent l'ennemi à terre en bataille, 1.0.6 ; écrans mis en page pour 1280x720 après le banc client, gardes qui se répartissent les ennemis, mare des vieux champs secs, textes, 1.0.7 avec modpack.)
1 bis. (fait : 1.0.8 à 13 h 17, 1.0.9 à 16 h 06.)
2. Lundi 19/10 : monde d'ouverture neuf (procédure de `CLAUDE.md`) avec la dernière version ; puis cocher la case.
3. Restes (`docs/plus-tard.md`), en attendant lundi : chasseur « chef déconnecté » à prouver au fantôme (il faudrait une commande fantôme qui part loin puis revient), `abatLExcedent` groupé, `enRang`, pic de 110 ms à 100 ouvriers, carrière, `sortDeLaLave` instable, écrans en 4:3 à l'échelle 4 (débordent sur les côtés).
4. La branche `origin/en-cours` existe encore (suppression refusée, 403) mais ne contient rien qui ne soit sur `main` : la supprimer si possible, sinon l'ignorer.

## Méthode qui a marché (17/10)
- Un développeur par morceau, chacun sur ses fichiers (`isolation: worktree`), branche locale `integration` où le chef fusionne au fil de l'eau, `git push -f origin HEAD:en-cours` après chaque fusion, passe complète `build` + `runGameTestServer` (5 min) avant `main`.
- `flock /tmp/gametest.lock` pour `runGameTestServer` ; **pas** devant `tools/fantome.py`, qui prend ce verrou lui-même (sinon il s'attend lui-même jusqu'à son délai).
- Chaque agent fait `git merge integration` avant son dernier test : les fusions finales se font sans conflit.

## Envoi du jar depuis le cloud
`panel.py upload` échoue. Ce qui marche : `panel.py write /mods-off/colonie_pnj-X.jar <jar>`, vérifier la taille (`panel.py ls /mods-off`), puis `panel.py mv` vers `/mods`.

## Session en cours
- aucune (session 18/10 13 h 00 - 16 h 10 UTC, terminée : 1.0.8 et 1.0.9 livrées, serveur seul ; B2 et D1 sur leurs branches).
- Joueur fantôme : `python3 tools/fantome.py mod-npc/fantome/mare-vieux-champ.txt` (champ sec + potager collé, sans pluie, loin d'un village).

## Avant (18/10 matin)
- aucune (session 18/10 07 h 00 - 09 h 15 UTC, terminée : 1.0.5 et 1.0.6 livrées, serveur seul).
