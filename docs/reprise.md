# Reprise : où on en est

Mise à jour : 18/10/2026, 00 h 15 UTC. Une page à lire en début de session, avec `docs/refonte.md`, `docs/plan.md` (lot 16) et la fin de `docs/journal.md`.

## Versions
- **Serveur : 1.0.2** (17/10, 23 h 54 UTC), un seul jar dans `/mods` (1.0.0 et 1.0.1 rangés dans `/mods-off`), sur le **monde neuf `essai-v2`**. Les mondes `colonie` (0.9.43, Nielscutt) et `test-andesite` restent sur le disque : ne jamais les supprimer.
- **Modpack : 1.0.0, obligatoire** (canal réseau 9). Autres mods inchangés depuis la 0.5.1 (`build.py` voulait monter JEI et lithostitched : `lock.json` remis comme avant, à demander à l'Admin si utile).
- `main` : 1.0.2 + tests stabilisés (creeper, lave : pas encore sur le serveur), **292/292 JUnit, 329/329 en jeu**. Modpack 1.0.0 toujours valable (1.0.1/1.0.2 serveur seul).
- Panneau : **« Refonte des ouvriers »** https://claude.ai/artifact/CfnApV4mrmi1EtmMVYThkZ (`plan/etapes`, `etat/courant`, `journal`, `essais` V01-V25). L'ancien panneau ne sert plus.

## La 1.0.0 en bref (refonte « ouvriers robots »)
- Mission (`Travailleur.Mission` : id du catalogue, quantité/stock, une fois/tous les jours, lieu, horaires), file, `Missions.donner`/`Missions.action` (écran et fantôme), statut unique `Statut` (entityData `STATUT`, recalculé toutes les 5 s), `t.bloquer(raison)`/`debloquer`.
- Fiche au clic droit (`Client.EcranOuvrier`, `EcranNouvelleMission`, `EcranConfirmer`), poste à 5 onglets (`Client.EcranPoste`), journal du poste (`Courrier.ecrire` → ligne, `Boite`), livret (`Livret`, `livret.txt`), barre de commandes (`BarreCommandes`, touche B).
- Lieux (`Lieux`, `LieuxRegles`, `Lieux.peutCasser`), métiers bloqués avec raison (`Statut` en fin de fichier), `t.lieuTravail()`, hors horaires (`Cerveau.enService`).
- Guerre (`Guerre`, `Armee` : declarer, paix, Rang, Repli ; missions defendre/escorter/attaquer du guerrier).
- Joueur fantôme : `Fantome.java`, `python3 tools/fantome.py mod-npc/fantome/trois-ouvriers.txt` (prend `/tmp/gametest.lock`, ~6 min).
- Retirés (étiquette `v1-finale`) : IA, voix, chat libre, lettres et corbeau, moral détaillé, caractères, familles, rumeurs, économie entre ouvriers, conversations.

## Prochaine étape (exacte)
1. Case « Bûcheron qui garde ses bûches… » : joueur fantôme 3 jours (`trois-ouvriers.txt`, allonger à 3 jours), regarder le sac et le dépôt du bûcheron et du mineur (`Cerveau`, `sacVide`), corriger si vrai, livrer en 1.0.3 avec les corrections creeper/lave déjà sur `main`.
2. Lundi 19/10 : monde d'ouverture neuf (procédure de `CLAUDE.md`) avec la dernière version ; puis cocher la case.
3. Restes (`docs/plus-tard.md`) : fermier sur champ à plusieurs niveaux, `abatLExcedent` groupé, `enRang`, pic de 110 ms à 100 ouvriers, troupe lente en bataille, chasseur hors colonie, carrière, écrans jamais vus avec un vrai client.
4. La branche `origin/en-cours` existe encore (suppression refusée, 403) mais ne contient rien qui ne soit sur `main` : la supprimer si possible, sinon l'ignorer.

## Méthode qui a marché (17/10)
- Un développeur par morceau, chacun sur ses fichiers (`isolation: worktree`), branche locale `integration` où le chef fusionne au fil de l'eau, `git push -f origin HEAD:en-cours` après chaque fusion, passe complète `build` + `runGameTestServer` (5 min) avant `main`.
- `flock /tmp/gametest.lock` pour tout serveur de test ou du fantôme ; un seul à la fois.
- Chaque agent fait `git merge integration` avant son dernier test : les fusions finales se font sans conflit.

## Envoi du jar depuis le cloud
`panel.py upload` échoue. Ce qui marche : `panel.py write /mods-off/colonie_pnj-X.jar <jar>`, vérifier la taille (`panel.py ls /mods-off`), puis `panel.py mv` vers `/mods`.

## Session en cours
- aucune (session de nuit 17/10 22 h 00 - 18/10 00 h 15 UTC, terminée).
