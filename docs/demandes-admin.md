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
- Statut : prêt, en attente de l'accord de KOLRAX pour l'installation sur le serveur (branche `admin-mods`, verrou du modpack retiré pour ne pas bloquer l'équipe du mod)
- Réponse : les trois existent pour NeoForge 1.21.1, aucune incompatibilité déclarée avec Sodium 0.6.13 ni Iris 1.8.12 ; 34 mods au total après ajout (on reste loin des 50 à 80 de Mr_EcliipsE).
  - **Burnt** (22 Mo, + GeckoLib) : sur le serveur et chez les joueurs. Va avec le thème (villages qui brûlent en guerre RP). **Attention** : sans protection de terrain, une torche ou un éclair peut brûler une base en bois entière, et les chantiers des ouvriers aussi. L'équipe du mod devrait vérifier que les PNJ fuient le feu.
  - **Do a Barrel Roll** (+ Forgified Fabric API, 6 Mo) : chez les joueurs seulement, rien sur le serveur. Ne change que la façon de piloter l'élytre.
  - **Distant Horizons** (28 Mo) : sur les deux côtés. Le serveur a de la marge (32 Go de mémoire, 4 cœurs) ; il enverra le relief lointain de la zone déjà générée par Chunky. Plus lourd sur les PC (carte graphique et mémoire) : à baisser dans ses réglages si un PC rame.
  - Pas encore testé en vraie partie côté joueur (lancement du jeu avec le pack complet) : à faire au premier lancement du modpack 1.0.8.
