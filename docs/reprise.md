# Reprise : où on en est

Mise à jour : 17/10/2026, 21 h 15 UTC. Une page à lire en début de session, avec `docs/refonte.md`, `docs/plan.md` (lot 16) et la fin de `docs/journal.md`.

## Versions
- **Serveur : 1.0.0** (17/10, 20 h 28 UTC), un seul jar dans `/mods`, sur le **monde neuf `essai-v2`** (datapack `colonie`, `init-commands.txt` relancé). Les mondes `colonie` (0.9.43, Nielscutt) et `test-andesite` restent sur le disque : ne jamais les supprimer.
- **Modpack : 1.0.0, obligatoire** (canal réseau 9). Autres mods inchangés depuis la 0.5.1 (`build.py` voulait monter JEI et lithostitched : `lock.json` remis comme avant, à demander à l'Admin si utile).
- `main` : 1.0.0, **292/292 JUnit, 320/320 en jeu**.
- Panneau : **« Refonte des ouvriers »** https://claude.ai/artifact/CfnApV4mrmi1EtmMVYThkZ (`plan/etapes`, `etat/courant`, `journal`, `essais` V01-V25). L'ancien panneau ne sert plus.

## La 1.0.0 en bref (refonte « ouvriers robots »)
- Mission (`Travailleur.Mission` : id du catalogue, quantité/stock, une fois/tous les jours, lieu, horaires), file, `Missions.donner`/`Missions.action` (écran et fantôme), statut unique `Statut` (entityData `STATUT`, recalculé toutes les 5 s), `t.bloquer(raison)`/`debloquer`.
- Fiche au clic droit (`Client.EcranOuvrier`, `EcranNouvelleMission`, `EcranConfirmer`), poste à 5 onglets (`Client.EcranPoste`), journal du poste (`Courrier.ecrire` → ligne, `Boite`), livret (`Livret`, `livret.txt`), barre de commandes (`BarreCommandes`, touche B).
- Lieux (`Lieux`, `LieuxRegles`, `Lieux.peutCasser`), métiers bloqués avec raison (`Statut` en fin de fichier), `t.lieuTravail()`, hors horaires (`Cerveau.enService`).
- Guerre (`Guerre`, `Armee` : declarer, paix, Rang, Repli ; missions defendre/escorter/attaquer du guerrier).
- Joueur fantôme : `Fantome.java`, `python3 tools/fantome.py mod-npc/fantome/trois-ouvriers.txt` (prend `/tmp/gametest.lock`, ~6 min).
- Retirés (étiquette `v1-finale`) : IA, voix, chat libre, lettres et corbeau, moral détaillé, caractères, familles, rumeurs, économie entre ouvriers, conversations.

## Prochaine étape (exacte)
1. **Ouvriers peu productifs au fantôme** (vu le 17/10 sur la 1.0.0, 2 jours) : mineur 0 à 18/64 charbon (pioche parfois restée au dépôt, action `attendre`), fermier sans champ visible avec un statut « Cultive 0/32 » qui ment, bûcheron qui garde ses bûches dans son sac loin du poste. Travail commencé sur la branche `v2-inactifs` (à reprendre, vérifier au fantôme, livrer en 1.0.1). Une course a vu les ouvriers perdre leur poste au bout de 2 jours (non reproduit deux fois depuis) : à surveiller dans les rapports du fantôme.
2. Case 12 du lot 16 : tests instables (`enRang`, `creeperAlerteLeGuerrier`, `sortDeLaLave`, `abatLExcedent`, `figeToiPuisAuTravail`, `fourbeEnvoieUnCollegue`) et charge à 50 ouvriers.
3. Restes de la 1.0.0 (`docs/plus-tard.md`) : troupe de guerre lente en bataille ; chasseur hors colonie ; carrière à ciel ouvert ; guérisseur sans statut bloqué ; vieux textes et champs sans effet (lettres, moral, `Nuit`, `Vie`) ; écrans jamais vus avec un vrai client.
4. Lundi 19/10 : monde d'ouverture neuf (procédure de `CLAUDE.md`), avec la dernière version.

## Méthode qui a marché (17/10)
- Un développeur par morceau, chacun sur ses fichiers (`isolation: worktree`), branche locale `integration` où le chef fusionne au fil de l'eau, `git push -f origin HEAD:en-cours` après chaque fusion, passe complète `build` + `runGameTestServer` (5 min) avant `main`.
- `flock /tmp/gametest.lock` pour tout serveur de test ou du fantôme ; un seul à la fois.
- Chaque agent fait `git merge integration` avant son dernier test : les fusions finales se font sans conflit.

## Envoi du jar depuis le cloud
`panel.py upload` échoue. Ce qui marche : `panel.py write /mods-off/colonie_pnj-X.jar <jar>`, vérifier la taille (`panel.py ls /mods-off`), puis `panel.py mv` vers `/mods`.

## Session en cours
- aucune (session du soir 17/10, 17 h 18 - 21 h 30 UTC, terminée).
