# Reprise : où on en est

Mise à jour : 19/10/2026, ~12 h 20 UTC. Une page à lire en début de session, avec `docs/refonte.md`, `docs/corrections.md` (lot 17, prime pour ce qu'il touche), `docs/plan.md` et la fin de `docs/journal.md`.

## Versions
- **Serveur : 1.0.21** (19/10, 12 h 10 UTC, serveur seul : mineuse figée près du poste ; 1.0.20 à 08 h 14, 1.0.18 à 07 h 01 avec le modpack), un seul jar dans `/mods` (les anciens dans `/mods-off`), avec Burnt + GeckoLib + Distant Horizons (Chunky dans `/mods-off` : ne jamais les remettre ensemble), sur le **monde d'ouverture `ouverture`** (pas pré-généré : demande à l'Admin, toujours ouverte). Les mondes `essai-v2`, `colonie` (0.9.43, Nielscutt) et `test-andesite` restent sur le disque.
- **Modpack : 1.0.18** (19/10, 07 h 03 UTC, obligatoire : paquet de carte `reperes2`), avec Distant Horizons. Le prochain doit avoir un numéro plus grand et passer `pack/verifier.py`.
- `main` = la 1.0.21 (3ef8f62 + journal) : **308/308 JUnit, 417/417 en jeu** (4e passe du chef ; les 3 premières : `tondunmouton` ; `descenddelafalaise` ; `chassedenuit` + `bucheronpresdelalave`). **`bucheronpresdelalave` a échoué 2 fois sur 6 passes aujourd'hui** (à terre, 1 PV, lave pendant `ramasser`) : à regarder (test de sécurité).
- La branche `en-cours` existe encore sur GitHub (égale à `main`) : sa suppression est refusée (403) depuis le cloud. Sans danger.
- Panneaux : « Refonte des ouvriers » https://claude.ai/artifact/CfnApV4mrmi1EtmMVYThkZ et « Corrections des ouvriers » https://claude.ai/artifact/PHChHnU2CX3tXnxanGD9nW.

## Prochaine étape (exacte)
1. **Plan : 1 case restante, A** (`python3 tools/suivi.py reste` = 1). E est cochée (1.0.18).
2. **Mineuse figée en « deposer »** : FAIT en 1.0.21. Cause : au fond de sa galerie à 3,7 blocs du poste derrière la roche, `Itineraire` disait « inaccessible », `Deposer` échouait (`bureau_inaccessible`) et le Cerveau relançait sans fin. Corrections : `Itineraire.percerVers`/`roche` (vers son propre poste seulement, pierre naturelle, même dans le tampon), `Coince.posteHorsDAtteinte`, pause 5 s du Cerveau après `bureau_inaccessible`, filet `sansAvancer` (10 min sans avancer de 2 blocs ni s'éloigner de 8). Pas de GameTest dédié au filet `sansAvancer`.
3. **A3, restes** : errance au pied ; le seau ne sert jamais en vraie montagne (0 eau versée en 7 parties ce matin) ; `getMaxFallDistance` = 6 (chutes 7 à 22 coups par partie) : décision « jamais plus de 3 blocs » à prendre ; pilier contre le mur d'une maison possible (relecture 1.0.20, mineur : il reprend ses blocs, rien de cassé).
4. **A1/A3 fantôme** : rejouer `montagne.txt` sur la 1.0.20 avec les graines 1234567 et 20261019 (seule la 42 a été jouée : mineur sorti du creux, 109 pierres, mission finie, 1 ramené au poste, 0 mort).
5. Restes : `docs/plus-tard.md`.

## Session en cours
- aucune (session 19/10 09 h 00 - 12 h 20 UTC, terminée : 1.0.21). Avant : (session 19/10 06 h 00 - 08 h 30 UTC, terminée : 1.0.18 + modpack 1.0.18 (E5), 1.0.19 (A3 mains nues), 1.0.20 (A3 creux)).

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

## Sessions d'avant
- (session 19/10 00 h 00 - 02 h 45 UTC, terminée : monde `ouverture`, 1.0.14 et 1.0.15 livrées, fantôme montagne et forêt dense ; A3 montagne en cours sur `a3-montagne`).
- Joueur fantôme : `python3 tools/fantome.py mod-npc/fantome/morts-7j.txt` (~15-20 min), `trois-ouvriers.txt`, `mine-lave.txt` (un seul mineur : « embauche mineur 2 » = niveau 2). Nouveaux : `montagne.txt`, `foret-dense.txt` (sur `main`).

## Avant (18/10 matin)
- aucune (session 18/10 07 h 00 - 09 h 15 UTC, terminée : 1.0.5 et 1.0.6 livrées, serveur seul).
