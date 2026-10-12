# À faire plus tard

Restes mineurs, à reprendre quand tous les lots ont leur première passe. Le plus utile en haut.

- Voir en vrai l'écran du poste (banc photo, il faut un écran) ; message de la barre d'action non testé.
- `Boucher` ne se déplace pas ; à 1 PV par la faim, le moindre coup met à terre ; non testés : coup pendant une fuite grave, messager en péril, « à bout d'air », montée de 2 blocs refusée par `ligneSure`.
- Compteur « coincé » et retours non sauvegardés ; allers-retours de plus de 8 blocs jamais comptés coincés ; changement de dimension non testé ; sortie par des lianes (15 s) ; chemin direct raté ~10 ms ; `Biomes.chercher` sur les fils de génération.
- Instabilités vues une fois : `TestsIA.iaDevineLeDestinataire`, `panneauRepond`, `rentreATempsSansLit` (sous charge).
- Journal des phrases non comprises : les ordres par **lettre** ne sont pas notés (seulement la voix et la fenêtre). L'action `lune` du répertoire réagit à « la lune » n'importe où dans une phrase (« fais tourner la lune à l'envers ») : trop large.
- Ordres longs (lot 3, première passe) : pas de changement de métier dans la journée (« le matin tu mines, l'après-midi tu coupes » à un mineur : il ne garde que le matin) ; les réponses de l'IA ne créent pas de règles (seul l'interpréteur les lit) ; « si tu vois… » dit la distance au poste, pas le lieu (« près de la mine ») ; une règle `FINI` donnée par lettre part sans le chef connecté (la consigne « va aider Raoul » demande le chef dans le monde) ; règles par lettre non testées ; les avis « vu » se regroupent au poste (une lettre qui compte).
- Ordres longs, relecture du lot 3 (faibles, non corrigés) :
  - `depuis` et `ordreChef` ne sont pas sauvegardés (après un redémarrage, une règle qui finit peut défaire un ordre donné avant) ;
  - le jour d'une mission quotidienne vient de `getDayTime()/24000` : bloquée avec `/time set` en arrière ou le cycle jour/nuit arrêté ;
  - `isRaining()` est global (il « pleut » aussi au désert et sous la neige) ;
  - PLUIE et ORAGE ensemble : la fin de l'orage le renvoie au travail sous la pluie ;
  - « si tu vois un zombie, tue-le » n'utilise pas le monstre vu ;
  - « si tu vois un étranger, attaque-le » promet sans faire (il devrait dire qu'il ne frappe personne hors guerre) ;
  - `pourquoi()` dit « stock plein » pour une mission quotidienne faite ;
  - « À la mine, il y a du fer » est lu comme « au travail » ;
  - l'avis « vu » va au poste même quand le chef est à côté ;
  - `Ordres.lire` coûte deux fois plus (2,4 ms pour 208 caractères) ;
  - une consigne de règle qui a besoin du chef (autre que « va voir / aider quelqu'un ») est refusée en bulle s'il est loin (plus de 48 blocs) ou absent ; pas de lettre pour le dire ;
  - instabilité vue une fois : `creusesousdugravier` (sous la charge d'un passage avec l'ancien code).
- Ordres longs, 2e relecture (non corrigés) :
  - « quand tu as fini, rentre s'il pleut » perd le « fini » (deux déclencheurs à la suite : seul le second garde la conséquence) ;
  - négation avec un déclencheur long (« ne travaille pas pendant la nuit ») : devient une règle ;
  - mission envoyée par lettre pendant un retour au poste à cause de la pluie : il reste en attente ;
  - `bloque` est calculé une fois avant la boucle des règles (une règle qui change son mode ne bloque pas les suivantes du même passage) ;
  - « attends » du chef pendant une attente posée par une règle : la fin de la règle peut le remettre au travail ;
  - « rentre » à un gréviste : rien n'est fait, sans message de grève.
