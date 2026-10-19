# Reprise : où on en est

Mise à jour : 18/10/2026, 23 h 30 UTC. Une page à lire en début de session, avec `docs/refonte.md`, `docs/corrections.md` (lot 17, prime pour ce qu'il touche), `docs/plan.md` et la fin de `docs/journal.md`.

## Versions
- **Serveur : 1.0.13** (18/10, 22 h 36 UTC ; serveur seul, le modpack 1.0.11.1 reste bon), un seul jar dans `/mods` (les anciens rangés dans `/mods-off`), avec Burnt + GeckoLib (Distant Horizons : voir l'Admin), sur le **monde neuf `essai-v2`**. Les mondes `colonie` (0.9.43, Nielscutt) et `test-andesite` restent sur le disque : ne jamais les supprimer.
- **Modpack : 1.0.11, obligatoire** (canal réseau **10** depuis la 1.0.11 : barre qui/quoi). `pack/release.py` ne garde plus que le jar maison de la dernière version (`GARDER_VERSIONS = 1` : deux jars dépassaient la limite de 5 Mo de la fonction Supabase). Autres mods inchangés depuis la 0.5.1 (`build.py` voulait monter JEI et lithostitched : `lock.json` remis comme avant, à demander à l'Admin si utile).
- `main` = la 1.0.13 : **306/306 JUnit, 391/391 en jeu**, identique au serveur. Modpack publié par l'Admin : 1.0.11.1 (le prochain doit avoir un numéro plus grand et passer `pack/verifier.py`). Distant Horizons est dans `/mods` du serveur, Chunky dans `/mods-off` : ne jamais les remettre ensemble.
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
0. **Lot 17** (`docs/corrections.md`, panneau « Corrections des ouvriers »). Installé : B1, B2, B4, D1, A2, A4, E1, E2, E3 (1.0.10-1.0.11) ; A3 blocs sans pioche, E4 lave (1.0.12) ; **A1 trajet calculé en entier, E6 compte des blessures et morts au fantôme + réglages de survie (1.0.13)**. **Reste, dans l'ordre** :
   - **E5 repère rouge Xaero** : FAIT et relu (aucun bloquant) sur la branche **`origin/e5-xaero`** (eb69ed3), PAS sur main. Le paquet est renommé `reperes2` sous `registrar("2").optional()` (même nom en deux versions = connexion refusée par NeoForge) : dès que le serveur l'a, les clients de l'ancien modpack se connectent mais **ne voient plus leurs ouvriers sur la carte** → serveur puis modpack (numéro > 1.0.11.1, `pack/verifier.py`) coup sur coup, **le 19/10 après 7 h UTC** seulement. Burnt, Do a Barrel Roll et DH : voir la consigne `admin-mods-modpack` et `docs/demandes-admin.md`. Mineur de relecture : ajouter un JUnit d'aller-retour de l'encodage `Repere` (StreamCodec écrit à la main).
   - **A3** : montagne au seau d'eau FAIT et vert (306/306, 392/392) sur **`origin/a3-seau`** (67db5da), **pas encore relu** : relire (eau jamais laissée : `eauOubliee`, `retirerEau` dans `remove` ; `eau` non sauvegardé, donc un arrêt du serveur en pleine montée laisse une source), puis livrer. Pas fait : reprendre les blocs du bas d'un pilier de plus de 3 blocs, falaise ouverte (eau qui coule de côté), scénarios fantôme montagne et forêt dense. `ouvrelaporte` vu instable une fois.
   - **E6** : mesure `mod-npc/fantome/morts-7j.txt` (10 ouvriers, 7 jours, missions longues) ; résultat dans le journal. Règle de décision : plus d'1 mort pour 70 journées-ouvrier (surtout « à terre sans secours ») → `releve_seul_minutes` 20 → 10, puis `vie_base` 20 → 24 ; morts par lave ou chute → réflexe à corriger, pas un chiffre.
   - Mineurs A1 non faits : `prolonger` ne consulte pas `etapesRatees`, `sousTerre` calculé à la place de l'ouvrier et pas au point de départ du prolongement, pas de nouvel essai quand la dernière étape s'arrête à côté du but.
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
- aucune (session 18/10 21 h 00 - 23 h 30 UTC, terminée : 1.0.13 livrée, serveur seul ; E5 prêt sur `e5-xaero`).
- Joueur fantôme : `python3 tools/fantome.py mod-npc/fantome/morts-7j.txt` (~15-20 min), `trois-ouvriers.txt`, `mine-lave.txt` (un seul mineur : « embauche mineur 2 » = niveau 2).

## Avant (18/10 matin)
- aucune (session 18/10 07 h 00 - 09 h 15 UTC, terminée : 1.0.5 et 1.0.6 livrées, serveur seul).
