# Reprise : où on en est

Mise à jour : 19/10/2026, ~17 h 45 UTC. Une page à lire en début de session, avec `docs/refonte.md`, `docs/corrections.md` (lot 17, prime pour ce qu'il touche), `docs/plan.md` et la fin de `docs/journal.md`.

## Versions
- **Serveur : 1.0.22** (19/10, serveur seul : E4 chutes ≤ 3 blocs et ramassage loin de la lave ; voir le journal pour l'heure), un seul jar dans `/mods` (les anciens dans `/mods-off`), avec Burnt + GeckoLib + Distant Horizons (Chunky dans `/mods-off` : ne jamais les remettre ensemble), sur le **monde d'ouverture `ouverture`**. Les mondes `essai-v2`, `colonie` (0.9.43, Nielscutt) et `test-andesite` restent sur le disque.
- **Modpack : 1.0.18** (obligatoire : paquet de carte `reperes2`). Le prochain doit avoir un numéro plus grand et passer `pack/verifier.py`.
- `main` = la 1.0.22 (e1d7902 + journal) : **308/308 JUnit, 420/420 en jeu** (1re passe du chef).
- La branche `en-cours` existe encore sur GitHub (suppression refusée, 403) : sans danger, elle ne contient rien qui ne soit sur `main`.
- Panneaux : « Refonte des ouvriers » https://claude.ai/artifact/CfnApV4mrmi1EtmMVYThkZ et « Corrections des ouvriers » https://claude.ai/artifact/PHChHnU2CX3tXnxanGD9nW (fiche `e4` repassée en cours puis livrée en 1.0.22).

## ARRÊT DEMANDÉ PAR KOLRAX (19/10, 16 h 45 UTC, consigne `stop-19-10`)
Quota de la semaine à 88 %, routine désactivée. Ne reprendre que sur décision de KOLRAX. Rien de nouveau n'est sur `main` ni sur le serveur (toujours 1.0.22, modpack 1.0.18). Deux branches prêtes à reprendre, **ni relues ni fusionnées** :
- **`origin/a3-creux42`** (14a1061) : mineur coincé au fond d'un creux (graine 42). Trois défauts du compte « coincé » (`Coince.relever` : toute l'attente compte après un échec vers le poste ; `surveillerCoince` : « demander » compte, aller vers son chef n'efface plus le compte ; `Demander` : arrivé près du chef il ne le suit plus, marcher vers le poste sans y arriver = échec). Fantôme : `embauche` pose l'ouvrier sur une place sûre (la « chute à l'embauche » venait de l'outil, pas du mod). Tests vus rouges sur 1.0.22 : `CoinceTest.apresUnEchecVersLePosteToutLAttenteCompte`, `demandeAuChefAuFondDuCreuxEstRamene`, `demandeAuFondDUnCreuxPresDuPosteEstRamene`. **309/309, 422/422.** Relecture lancée puis arrêtée : à refaire. Restes vus : chute de 10,8 blocs en allant au lit (chemin direct), 2 terres laissées une fois (-415,130,-366), mineur perdu dans les grottes.
- **`origin/a3-chutes2`** = **`origin/en-cours`** (3f5f575, contient `a3-trace-chutes`) : chutes des bûcherons aux rebords : tests de chute à la course en terrasses (rouges sur 1.0.22), ne courent plus et ralentissent au bord d'une descente, pas de diagonale ni de coin coupé au bord d'un à-pic. Dernier message du développeur : « 423 verts » ; fantôme de vérification **pas fait**, aucune mesure. Relecture à faire.
- Pour reprendre : fusionner les deux, passe complète, une relecture chacune, fantôme `montagne.txt` graines 1234567 et 42, puis livrer 1.0.23 (serveur seul).
- Nouveau sur `main` (session locale de KOLRAX) : plan F dans `docs/corrections.md` (saut en boucle, bûcheron qui erre, métiers activables, points Xaero, corne de chèvre), F3 déjà fait.

## Prochaine étape (exacte)
1. **Plan : 1 case restante, A** (`python3 tools/suivi.py reste` = 1).
2. **Chutes encore vues en vraie partie : cause trouvée.** Trace ajoutée au fantôme sur la branche **`origin/a3-trace-chutes`** (dfb86a6, seulement `Fantome.java` + `tools/fantome.py`, build vert, PAS encore sur `main` : passe complète à faire avant). Partie graine 1234567 sur la 1.0.22 : 11 chutes, 17 PV, **toutes des bûcherons pendant « aller »**, 10 sur 11 en suivant un trajet partiel (« étape 1/1 (suite à calculer) »), toujours aux mêmes rebords près du poste ((-34,73,-300) depuis y 76 ; (-52,64,-314) depuis y 68 ; (-31,63,-303) depuis y 67), souvent juste après un demi-tour. Hauteurs : **3,3** (marche de 3 blocs + saut de 0,3 → 1 PV) et **4,8** (marche de 4 + saut → 2 PV), jamais plus. Piste : le chemin de Minecraft accepte une marche de 3 (`getMaxFallDistance` = 3) mais le saut en quittant le bord dépasse le seuil ; et une marche de 4 passe encore (sans doute un chemin calculé ailleurs que par `Travailleur` ou un nœud mal évalué dans `Itineraire.Evaluateur`). Essayer `getMaxFallDistance` = 2 pour le chemin (chute réelle ≤ 3) et chercher qui produit la marche de 4. Rapport : scratchpad de la session 019Ya833 (perdu) ; rejouer `montagne.txt` graine 1234567 avec la branche. Vu aussi : action « aller » pendant le statut « Se repose » (2 fois). Dans cette même partie (1.0.22), **personne n'a atteint le sommet** (bûcheron 0/16, 63 demi-tours ; les deux autres « En attente ») alors que la partie précédente sur la même graine en avait fait 16 : résultats très variables d'une partie à l'autre, à mesurer sur plusieurs parties.
3. **Graine 42 : chute à l'embauche** (2 coups, 15 PV, ticks 4-167) quand les ouvriers rejoignent le fantôme au fond d'un creux 7 blocs sous le poste (`suivre`).
4. **Graine 42 : le mineur reste 2 jours au fond du creux** (« demander », puis « bras croisés », 1 terre posée sur 33) sur la 1.0.21 comme sur la 1.0.22 ; il en sortait sur la 1.0.20 → recul de la 1.0.21 ou des deux lignes de relecture de la 1.0.20 (`Demander`).
5. **Bûcheron au seau sans pioche qui retaille la pierre** (146 à 210 blocs par partie sur 1.0.21/1.0.22, graine 1234567 ; 16 sur la 1.0.19).
6. Reste A3 : errance au pied, seau peu utilisé. Restes : `docs/plus-tard.md`.
7. Rapport du soir à KOLRAX (après 18 h Paris = 16 h UTC) si aucune session ne l'a fait.

## Session en cours
- aucune (session 19/10 13 h 00 - 15 h 40 UTC, terminée : 1.0.22). Avant : session 19/10 09 h 00 - 12 h 20 UTC (1.0.21).

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
