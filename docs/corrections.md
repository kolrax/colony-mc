# Corrections du 18/10 (lot 17) : d'après les rapports en jeu de KOLRAX

Décidé par KOLRAX le 18/10. Ce cahier prime sur `docs/refonte.md` pour ce qu'il touche.
Les rapports viennent de `config/colonie_pnj/rapports.jsonl` sur le serveur. Ils sont tous faits sur la 1.0.6 ou la 1.0.7, dans le monde `essai-v2`. Les numéros sont ceux de la liste montrée à KOLRAX.

**Suivi en direct** : le panneau « Corrections des ouvriers » (https://claude.ai/artifact/PHChHnU2CX3tXnxanGD9nW, voir `CLAUDE.md`). Chaque correction y a une fiche, à tenir à jour à chaque étape.

**Hors du mod, transmis à la session Admin** (`docs/demandes-admin.md`) : le chargement des chunks (rapport 9), et les mods Burnt, Do a Barrel Roll et Distant Horizons (rapports 16, 19 et 20). Les agents du mod n'y touchent pas.

## Plan A : des déplacements « GPS » (rapports 10, 12, 13, 15 et 17)
Ce que KOLRAX a vu :
- « Déplacements indécis, il faut qu'ils soient plus concrets : pas d'allers-retours inutiles, pas de stop pour reprendre, pas foncer vers un mur ou une impasse. »
- « Il faut un système qui calcule le trajet comme un GPS. Le PNJ décide les coordonnées de destination, l'action ou la mission, puis un script calcule le trajet, pour éviter de tourner en rond, d'être bloqué, etc. »
- « Les ouvriers ont du mal à gravir les montagnes. Il faut que le GPS prenne en compte la montagne pour la contourner si aucun chemin n'est possible. S'il a un seau ou des blocs, qu'il utilise le seau pour monter, ou les blocs pour faire une tour. »
- « Un PNJ est resté bloqué contre une noix de coco. Il faut qu'il change de chemin s'il reste statique, au lieu d'attendre bêtement. »
- « Face à un obstacle sous terre (lave, eau, grotte), le mineur remonte tout au lieu de reminer un escalier. Il devrait miner pour rester au niveau où il est et contourner le problème. »

Ce qu'on veut :
1. **Trajet calculé en entier avant de partir** vers la destination de la mission, puis suivi. On ne recalcule pas à chaque pas : seulement si le trajet est coupé. On garde ce qui existe déjà (chemins optimisés, déblocage par pilier et pont), on ne repart pas de zéro.
2. **Immobile 3 secondes** sans être arrivé → nouveau trajet qui évite l'endroit bloquant. Jamais d'attente sans fin.
3. **Montagne** : la contourner si un chemin existe. Sinon, monter en posant des blocs de son sac et les reprendre ensuite, ou par une colonne d'eau avec un seau, en reprenant l'eau. Toujours sans casser une construction.
4. **Mineur sous terre** : devant de la lave, de l'eau ou une grotte, il contourne à son niveau (galerie de côté, lave bouchée) au lieu de remonter à la surface.
5. **Vérifier** avec le joueur fantôme sur un monde généré, un scénario par cas : montagne, forêt dense avec cocotiers et buissons, mine avec lave et grotte. Compter les allers-retours et les arrêts de plus de 3 s. Le banc de charge à 50 ouvriers ne doit pas se dégrader.

## Plan B : petites corrections (rapports 11 et 14, et le mineur disparu)
1. **Le compteur du mineur ne monte pas** : « J'ai vu un mineur miner du fer, mais le compteur de fer au-dessus de sa tête n'est pas monté. » Trouver pourquoi et corriger. Penser par exemple au minerai brut ou au minerai des profondeurs.
2. **Horaires et expéditions** : « Il y a toujours le système d'horaires et d'expéditions max, il ne fallait pas le retirer ? Maintenant, les horaires concernent une mission précise. » Retirer « expéditions max » et le réglage général des horaires (poste et fiche). Il ne reste que les horaires de chaque mission. L'écran change, donc il faut un modpack obligatoire.
3. ~~Le mineur de Nielscutt a disparu~~ : **réglé, ce n'est pas un bug**. KOLRAX confirme le 18/10 qu'il est mort en jeu. Ne pas chercher. Les morts trop fréquentes seront traitées à part, si KOLRAX valide les propositions.

4. **Barre de commandes : les ordres qui demandent une précision** (signalé par KOLRAX le 18/10, à faire **ce soir, en priorité après la livraison des horaires et de la carte Xaero**). Des ordres de la barre (« montre quelqu'un du doigt », « récupère au dépôt », « donne à… », « prends… ») ne font que poser une question (« Qui ça, chef ? », « Quoi donc, chef ? ») à laquelle on ne peut pas répondre : la barre envoie la première formule du répertoire, sans nom ni objet (`BarreCommandes.executer` → `Consignes.ordonner` ; `Consignes.personne` cherche un prénom dans la phrase, `filtre` un objet). Ce qu'on veut : **quand un ordre a besoin d'un complément, la barre ouvre une deuxième liste** au lieu de laisser l'ouvrier poser une question. **Qui** : ses ouvriers et les joueurs proches, par prénom. **Quoi** : ce qu'il y a dans le dépôt, avec une recherche, puis la quantité. **Où** : marche déjà (le bloc regardé). Le choix est transmis à l'ordre comme si le nom ou l'objet avait été dit. Un ordre dont la précision ne peut pas se choisir ainsi est retiré de la barre : **plus aucune question sans réponse**. Bonus si le temps le permet : le transporteur peut choisir l'objet à porter. Modpack obligatoire (écran).

## Plan D : les ouvriers sur la carte Xaero (rapport 18)
« Il faut qu'on voie la position de nos PNJ sur la minimap. » KOLRAX précise : **sur la carte Xaero, seulement eux : pas les monstres, pas les autres joueurs**.
- Chaque joueur voit **ses propres ouvriers** sur la mini-carte et la carte du monde Xaero, avec leur prénom, et rien d'autre en plus. Pas d'autres entités, pas les ouvriers des autres joueurs.
- Ne jamais modifier les profils Xaero fair-play du serveur ni leur configuration : c'est le domaine de l'Admin. Passer par le mod lui-même, par exemple des repères (waypoints) temporaires mis à jour par le client, avec Xaero en dépendance facultative : le jeu doit marcher sans Xaero.
- Si c'est impossible sans changer la configuration de Xaero, l'écrire dans `docs/demandes-admin.md` et prévenir KOLRAX (`demandes/` du panneau).
- Le client change, donc il faut un modpack obligatoire.

## Plan E : des ouvriers qui ne meurent plus tout le temps, sans être invincibles (validé par KOLRAX le 18/10)
Constat : Oriane, la mineuse de Nielscutt, s'est blessée seule dans sa mine. Personne n'a pu la relever pendant 5 minutes, et elle est morte. Aujourd'hui, un ouvrier seul ne peut pas être sauvé. Il a 20 points de vie (plus 2 par étoile), pas d'armure, et meurt après 3, 5 ou 8 minutes à terre sans aide.
1. **Se relever seul, une fois** : à la fin du temps à terre, il ne meurt plus. Il se relève avec peu de vie et une blessure grave (il est ralenti), abandonne sa mission, rentre au poste et se repose jusqu'à guérir, avec une ligne au Journal. **Il meurt** s'il retombe à terre pendant qu'il est encore blessé (dans les 20 minutes), s'il est achevé par un joueur (la guerre ne change pas), ou dans le vide ou un bain de lave.
2. **Fuir avant d'être à terre** : sous la moitié de sa vie, il arrête le travail, s'éloigne du danger et mange ce qu'il a. Hors combat et nourri, il regagne sa vie petit à petit, comme un joueur.
3. **S'équiper** : il prend au dépôt la meilleure armure qu'il y trouve, comme pour ses outils. Le mineur éclaire sa galerie avec les torches du dépôt, pour qu'il apparaisse moins de monstres.
4. **Dangers de la nature** : vérifier puis renforcer les réflexes (pas de chute de plus de 3 blocs, pas de lave à côté de lui, pas de noyade), avec un scénario du joueur fantôme de 2 jours dans une mine.
5. **Prévenir quand il est à terre** : un message au chef avec la position, une ligne rouge au Journal, un repère rouge sur la carte Xaero (avec le plan D). Les ouvriers proches apportent à manger, pas seulement le guérisseur.
6. **Compter, puis régler** : un scénario du fantôme de 2 jours avec 10 ouvriers note chaque blessure et chaque mort, avec leur cause (le journal du serveur dit déjà pourquoi un ouvrier tombe). Ensuite, régler la vie de base (20 ou 30 ?), la durée à terre et la vitesse de guérison. Objectif : **au plus une mort pour 10 ouvriers par semaine de jeu**, si le joueur s'en occupe un minimum. Tous les chiffres vont dans le fichier de réglages.
Ce qui empêche l'invincibilité : un deuxième K.O. pendant la blessure est mortel ; un joueur peut l'achever ; le vide et la lave tuent ; une mort coûte toujours (la tombe, la réputation, une nouvelle embauche).

## Ordre
Plans B et D d'abord (courts, et ils se livrent avec le même modpack), puis le plan E, puis le plan A. Une livraison par plan fini et vert.

## Plan F : rapports du 18 et du 19/10 (KOLRAX lance F3, F1 et F2 le 19/10 au soir, livraison ce soir ; F4 et F5 après vendredi)
Fiches `f1` à `f5` du panneau « Corrections des ouvriers ».
1. **Saut en boucle** (1.0.22, 19/10 vers 19 h 16 à Paris) : un ouvrier saute sur place sans fin. Trouver la cause (pilier ou marche reprise, montée ratée, `Coince`…) et la corriger.
2. **Sur place, agir intelligemment** (1.0.22) : « le bûcheron a du mal à trouver les arbres » ; « Orianne (bûcheronne) est proche du biome cerisiers, à quelques blocs des arbres, mais erre bizarrement ». Arrivé au lieu de sa mission, l'ouvrier va droit au travail le plus proche qu'il peut atteindre. Le cerisier (tronc penché, grande couronne) est à vérifier en particulier.
3. **Ouvrir avec seulement certains métiers** : un réglage serveur (`reglages.json`) donne la liste des métiers actifs. Un métier inactif reste dans le mod, mais aucun chercheur d'emploi de ce métier n'apparaît, on ne peut pas l'embaucher ni le former, et il ne figure ni sur l'affiche ni dans les menus. **KOLRAX veut pouvoir choisir les métiers activés dans la configuration** : une liste `metiers_actifs` dans `config/colonie_pnj/reglages.json`, prise en compte par `/pnj recharger` sans redémarrage (et modifiable dans le panneau web du mod si c'est simple). Absente ou vide = tous les métiers actifs. Un ouvrier déjà embauché d'un métier désactivé continue de travailler. La liste de départ est choisie par KOLRAX. Côté serveur seulement si possible (sans nouveau modpack).
4. **Carte Xaero** : un petit point par ouvrier au lieu des repères, et le détail de l'ouvrier au survol. Modpack obligatoire. Plutôt après la remise à zéro du quota (vendredi 23/10).
5. **Idée** : une corne de chèvre pour donner un ordre à tous ses ouvriers. Bonus, après vendredi.

**Métiers de l'ouverture (KOLRAX, 19/10)** : bûcheron, chasseur, guérisseur, mineur, pêcheur. Les autres (fermier, éleveur, cuisinier, transporteur, maçon, boutiquier, guerrier) restent dans le mod, désactivés.
**À concevoir plus tard (pas ce soir)** : KOLRAX constate que les métiers qui posent problème sont surtout ceux qu'on dirigeait en leur parlant (le « chat »). Il faudra repenser leur commande avec des missions et des menus. Ne pas commencer sans son accord.
