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

## Plan D : les ouvriers sur la carte Xaero (rapport 18)
« Il faut qu'on voie la position de nos PNJ sur la minimap. » KOLRAX précise : **sur la carte Xaero, seulement eux : pas les monstres, pas les autres joueurs**.
- Chaque joueur voit **ses propres ouvriers** sur la mini-carte et la carte du monde Xaero, avec leur prénom, et rien d'autre en plus. Pas d'autres entités, pas les ouvriers des autres joueurs.
- Ne jamais modifier les profils Xaero fair-play du serveur ni leur configuration : c'est le domaine de l'Admin. Passer par le mod lui-même, par exemple des repères (waypoints) temporaires mis à jour par le client, avec Xaero en dépendance facultative : le jeu doit marcher sans Xaero.
- Si c'est impossible sans changer la configuration de Xaero, l'écrire dans `docs/demandes-admin.md` et prévenir KOLRAX (`demandes/` du panneau).
- Le client change, donc il faut un modpack obligatoire.

## Ordre
Plans B et D d'abord (courts, et ils se livrent avec le même modpack), puis le plan A. Une livraison par plan fini et vert.
