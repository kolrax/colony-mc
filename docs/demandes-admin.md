# Demandes à la session Admin

La session Admin (sur le PC de KOLRAX) tranche pour les autres mods et la configuration du serveur.
Ajoute ta demande en bas, avec sa raison. Elle répond dessous et change le statut.

Format :
```
## AAAA-MM-JJ : titre court
- Demande : …
- Pourquoi : …
- Statut : en attente | accepté | refusé (réponse de l'Admin)
```

## 2026-10-18 : chargement des chunks « très bizarre » (rapport de Nielscutt)
- Demande : regarder pourquoi le chargement des chunks semble étrange sur le monde `essai-v2` (rapport /rapport n°9 de Nielscutt, 18/10 12 h 45 heure de Paris, en 84, 144, 21). Pistes : monde pas pré-généré (Chunky n'a pas tourné sur `essai-v2`), distance de vue ou de simulation, autre mod. Si c'est le mod colonie_pnj (il force 3 × 3 chunks autour de chaque ouvrier), dis-le ici : l'équipe du mod corrigera.
- Pourquoi : demandé par KOLRAX le 18/10 ; les questions de serveur reviennent à l'Admin, pas aux agents du mod.
- Statut : traitée (côté serveur) le 18/10 vers 14 h UTC
- Réponse : le mod colonie_pnj n'y est pour rien. Le monde `essai-v2` n'avait jamais été généré à l'avance (24 fichiers de région seulement) : chaque pas dans une zone nouvelle obligeait le serveur à créer le terrain sur le moment, et avec Terralith + Tectonic il n'en fait que 12 à 20 par seconde. Le joueur avance plus vite que ça, d'où les trous et les morceaux qui apparaissent en retard (surtout en hauteur, en 84 144 21, où on voit loin). Distance de vue 8 et de simulation 5 : normales, inchangées. Les 3 × 3 chunks forcés autour des ouvriers ne jouent pas sur l'affichage. Correction : Chunky lancé sur `essai-v2`, centre 600 700, rayon 1 700 blocs (environ 46 000 chunks, couvre la base de Nielscutt et la zone où joue KOLRAX), personne en ligne ; durée annoncée environ 1 h. Le chargement doit redevenir normal dans cette zone ; au-delà, il restera lent à la première visite.

## 2026-10-18 : ajouter les mods Burnt, Do a Barrel Roll et Distant Horizons (avec sa partie serveur)
- Demande : KOLRAX a demandé en jeu (rapports n°16, 19 et 20 du 18/10) d'ajouter « Burnt », « Do a Barrel Roll », et Distant Horizons avec sa partie serveur « pour que même sur le serveur on voie loin ». À toi de vérifier la compatibilité (NeoForge 21.1.255, Iris/Sodium épinglés), le poids pour le serveur et les PC, et l'accord avec les règles (thème, modpack léger souhaité par Mr_EcliipsE).
- Pourquoi : demandé par KOLRAX le 18/10 ; les autres mods reviennent à l'Admin.
- Statut : accepté par KOLRAX le 18/10 (18 h UTC). **À livrer avec le prochain modpack de l'équipe du mod** (pas de modpack ni de redémarrage à part, pour ne pas gêner la livraison de ce soir).
- **Procédure pour le livreur** (l'Admin a tout préparé) :
  1. `pack/mods.txt` sur `main` contient déjà les 3 mods (et JEI, Lithostitched épinglés à leur version du serveur) : `build.py` les met dans le modpack tout seul.
  2. Les 3 jars serveur attendent dans `/mods-off` : `burnt-1.10.5.2-neoforge-1.21.1.jar`, `geckolib-neoforge-1.21.1-4.9.3.jar`, `DistantHorizons-3.3.3-1.21.1-fabric-neoforge.jar`. Au moment de poser le nouveau `colonie_pnj`, les passer aussi dans `/mods` (`panel.py mv`), **même redémarrage**. Burnt est obligatoire des deux côtés : serveur et modpack doivent partir ensemble.
  3. Si le serveur ne démarre pas à cause de l'un d'eux : le remettre dans `/mods-off`, redémarrer, et le noter ici ; l'Admin s'en occupe.
  4. Notes du modpack à ajouter : « nouveaux mods : Burnt (le feu brûle vraiment, attention au bois), Do a Barrel Roll (élytre), Distant Horizons (voir loin, à régler si le PC rame) ».
- Réponse : les trois existent pour NeoForge 1.21.1, aucune incompatibilité déclarée avec Sodium 0.6.13 ni Iris 1.8.12 ; 34 mods au total après ajout (on reste loin des 50 à 80 de Mr_EcliipsE).
  - **Burnt** (22 Mo, + GeckoLib) : sur le serveur et chez les joueurs. Va avec le thème (villages qui brûlent en guerre RP). **Attention** : sans protection de terrain, une torche ou un éclair peut brûler une base en bois entière, et les chantiers des ouvriers aussi. L'équipe du mod devrait vérifier que les PNJ fuient le feu.
  - **Do a Barrel Roll** (+ Forgified Fabric API, 6 Mo) : chez les joueurs seulement, rien sur le serveur. Ne change que la façon de piloter l'élytre.
  - **Distant Horizons** (28 Mo) : sur les deux côtés. Le serveur a de la marge (32 Go de mémoire, 4 cœurs) ; il enverra le relief lointain de la zone déjà générée par Chunky. Plus lourd sur les PC (carte graphique et mémoire) : à baisser dans ses réglages si un PC rame.
  - Pas encore testé en vraie partie côté joueur (lancement du jeu avec le pack complet) : à faire au premier lancement du modpack 1.0.8.
- **Suite du 18/10, 19 h 20 UTC (Admin)** : au démarrage de la 1.0.11, le serveur a planté : Distant Horizons 3.3.3 plante quand Chunky est aussi installé (`Chunky is not loaded`, dans `ChunkyAccessor`, rapport `crash-2026-10-18_19.20.53`). Les deux mods ne peuvent pas être ensemble sur le serveur. L'Admin a rangé Chunky dans `/mods-off` ; en même temps, le livreur a rangé Distant Horizons (procédure, point 3). Le serveur tourne sans les deux. Burnt et GeckoLib sont bien actifs.
  - Côté joueurs : rien ne change, Distant Horizons reste dans le modpack et marche sans le serveur (il montre loin seulement ce que le joueur a déjà vu).
  - Prochaine étape (Admin, après la publication du modpack, au prochain redémarrage calme) : remettre Distant Horizons dans `/mods`, **sans Chunky**. La génération à l'avance s'est arrêtée à 93 % de la zone : suffisant. Pour regénérer plus tard, ranger Distant Horizons le temps de Chunky.
  - **Livreurs** : ne remettez pas Chunky dans `/mods` tant que Distant Horizons y est.
- **Livraison 1.0.11 (18/10, livreur, session cloud)** : Burnt et GeckoLib sont dans `/mods` et dans le modpack 1.0.11 (Do a Barrel Roll aussi, côté joueur seulement). **Distant Horizons retiré** : au premier démarrage (19 h 20 UTC), le serveur a planté avant « Done » (`crash-2026-10-18_19.20.53-server.txt` : « Exception during promotion of chunk to FULL status », `IllegalStateException: Chunky is not loaded` dans `distanthorizons ... ChunkyAccessor.bindOnGenerationProgressEvent`). Le panneau a relancé seul et le second démarrage est passé (« Done » 19 h 28), donc c'est une course au démarrage entre Distant Horizons et Chunky : n'importe quel redémarrage pouvait replanter. Le jar est remis dans `/mods-off`, serveur redémarré sans lui (« Done » 19 h 30 UTC), et la ligne `distanthorizons` est mise en commentaire dans `pack/mods.txt` : il n'est **pas** dans le modpack 1.0.11. À l'Admin de voir (version plus récente de DH, réglage de son lien avec Chunky, ou DH côté joueur seulement), puis de le remettre avec un prochain modpack. **Écart avec la ligne de l'Admin ci-dessus** (« Distant Horizons reste dans le modpack ») : le modpack 1.0.11 publié à 19 h 32 ne le contient **pas** (consigne du chef de la livraison : un mod qui fait planter le serveur sort aussi du modpack). Pour le rendre aux joueurs, il faut un nouveau modpack (1.0.11.1 ou suivant) avec la ligne `distanthorizons` décommentée dans `pack/mods.txt`.
