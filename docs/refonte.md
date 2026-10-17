# Refonte « ouvriers robots » (version 1.0.0) — décidée par KOLRAX le 17/10/2026

Ce document **prime sur le cahier des charges** partout où ils se contredisent. Le cahier reste la référence pour tout ce que ce document ne touche pas (règles du serveur, skins, métiers, chiffres de l'économie).

**Pourquoi** : la version 0.9 avait trop de systèmes à la fois. Les ouvriers semblaient bugués et peu naturels, et l'on ne comprenait pas ce qu'ils faisaient. Le testeur de KOLRAX dit que le problème principal est la **compréhension**.

**Le principe** : les ouvriers sont des robots fiables qui accomplissent la mission qu'on leur donne. On garde leurs savoir-faire et on retire ce qui les rendait imprévisibles.

**Échéance** : livrée **le samedi 17/10 avant minuit, heure de Paris** (22 h UTC). L'ouverture se fait **lundi 19/10, sur un monde neuf**.

## 1. La mission (KOLRAX : « j'adore, tout me semble parfait »)
Tout tourne autour d'un seul objet, la **mission**. Elle a cinq parties.
- **Quoi** : une action du métier, par exemple miner, couper, cultiver, élever, garder, transporter ou construire.
- **Où** : par défaut, l'ouvrier choisit lui-même un **lieu intelligent** (partie 2). Le joueur peut aussi dire « Travaille ici », et la mission prend alors sa position.
- **Jusqu'à quand** : une quantité (« 64 fer »), une seule fois, tous les jours, ou « garder N au dépôt ».
- **Quand** : les **horaires**, qui sont jour, nuit ou toujours, ou une plage d'heures. Ils remplacent le sommeil. « Le matin tu mines, l'après-midi tu coupes » donne deux missions avec chacune ses horaires.
- **Qui** : un ouvrier, ou plusieurs qui travaillent au même endroit.

Chaque ouvrier a une **file de missions**. Il fait la première jusqu'au bout, puis la suivante. Seul un danger l'interrompt : un monstre, de la lave, une chute ou un éboulement.

Il y a **un seul statut**. Il s'affiche au-dessus de la tête et dans le poste :
- en cours, par exemple « ⛏ Mine · 32/64 fer » ;
- bloqué, avec la raison **et** la solution, par exemple « ⚠ Pas de pioche au dépôt », « ⚠ Sac plein, dépôt plein » ou « ⚠ Aucun lieu sûr pour miner ici » ;
- en attente ;
- hors horaires.

Il **obéit toujours**. Une mission impossible ou mortelle est refusée, avec sa raison. Il ne refuse jamais par caprice.

Les missions survivent à un redémarrage du serveur : la file, la progression et les horaires sont enregistrés.

## 2. Lieux de travail intelligents (pas de bâton de chantier)
KOLRAX a écarté le bâton : « c'est mieux si l'ouvrier se débrouille seul, mais il doit travailler à un endroit intelligent ».

**Règles communes** :
- La **colonie** est la zone autour du poste : 48 blocs par défaut, réglable. On y ajoute un tampon de 8 blocs autour de toute construction de joueur.
- **Personne ne casse jamais une construction**, ni pour travailler, ni pour se débloquer. Cela couvre les blocs posés par un joueur, les blocs absents de la liste des blocs naturels, et toute construction proche d'un autre poste. C'est l'ancien risque du lot 15. Le coffre, le lit, les torches et les cultures d'un joueur ne sont pas touchés non plus.
- **Seule exception** : une destruction sur ordre pendant une guerre déclarée (partie 6).

**Par métier** :
- **Mineur** : il travaille sous terre, au moins 10 blocs sous la surface. S'il est dans la colonie, il descend par un seul puits, à un endroit qu'il choisit loin des constructions. Les carrières à ciel ouvert sont seulement **hors de la colonie**. Il ne creuse jamais sous une construction (pas de trou à moins de 4 blocs d'une fondation) et pas dans une structure (village, donjon) ni près d'une autre base.
- **Bûcheron** : il coupe seulement les **arbres naturels**, c'est-à-dire ceux dont les feuilles ne sont pas « persistantes ». Les feuilles posées à la main le sont, donc un arbre de décoration n'est jamais coupé. Il travaille hors de la colonie, ou dans une forêt que le joueur désigne par « Travaille ici ». Il replante toujours.
- **Fermier** : il utilise les champs existants près du poste. S'il n'y en a pas, il crée un champ de 9 × 9 sur un terrain plat, libre et hors construction, près de l'eau.
- **Éleveur** : il garde les animaux de l'enclos le plus proche du poste. S'il n'y a pas d'enclos, la mission est bloquée avec « ⚠ Pas d'enclos ».
- **Pêcheur** : l'eau la plus proche, hors de la colonie si possible.
- **Chasseur** : hors de la colonie. Il ne tue jamais les animaux d'un enclos.
- **Garde** : la colonie, et son poste.
- **Les autres métiers** (cuisinier, transporteur, guérisseur, maçon, boutiquier) : le poste et ce qui lui est relié.

Le lieu choisi est **affiché** (« Je mine en 120, 40, -35 ») et s'enregistre dans la mission.

## 3. Ce qui reste, ce qui se simplifie, ce qui part
**On garde**, branché sur les missions :
- **les 12 métiers**, avec tous leurs savoir-faire actuels :
  - mineur : filon, salle, carrière, tunnel, tri ;
  - bûcheron : essence, replantation, défrichage ;
  - guerrier : ronde, escorte, garde, équipement ;
  - fermier, éleveur, pêcheur, chasseur, cuisinier ;
  - transporteur, avec Create ;
  - guérisseur ;
  - maçon, avec les schémas ;
  - boutiquier, avec la boutique et son écran ;
- les **déplacements optimisés**, le déblocage et les réflexes de survie ;
- le **poste et le dépôt**, la réserve, les outils pris au dépôt et le tri ;
- la **paie** en pièces Numismatics, le coût des ouvriers, le **recrutement** (chercheurs d'emploi, affiche), les **étoiles** et la formation ;
- la **défense** contre les monstres et l'abri la nuit ;
- les **blessures** (gravités seulement), le guérisseur, l'état à terre puis la tombe ;
- les **skins** et tenues par métier, les icônes et les gestes ;
- le **répertoire d'actions** (environ 350), qui sert maintenant à la barre de commandes ;
- le **panneau web d'admin**, `/rapport`, `/pnj diag`, le banc de charge et la livraison.

**On simplifie** :
- **besoins** : il ne reste que le salaire et la nourriture prise au dépôt ;
- **sommeil** : remplacé par les horaires de mission (choix de KOLRAX). Hors horaires, l'ouvrier rentre au poste, ou à l'abri la nuit ;
- **satisfaction** : une seule jauge, qui dépend de la paie et de la nourriture et règle la vitesse de travail. Il y a **grève** si la paie manque ;
- **ordres longs** : ils deviennent des réglages de la mission (répéter, garder un stock, horaires) ;
- **guerre** : voir la partie 6 ;
- **autres joueurs** : une liste de joueurs autorisés à donner des missions à mes ouvriers ;
- **messages au joueur** : des phrases courtes, écrites à l'avance (« Mission terminée », « Il me faut une pioche »).

**On retire**. Le code reste dans l'historique git, avec l'étiquette `v1-finale` :
- l'IA (Groq, OpenRouter, Gemini), les voix, le micro, le chat libre, la compréhension des phrases et le journal des incompris ;
- les lettres, le corbeau et les messagers : le **Journal du poste** les remplace ;
- le moral détaillé, les caractères, la révolte, le vol, le sabotage et le deuil ;
- les conversations entre ouvriers, la mémoire, les relations, les familles et les enfants ;
- les rumeurs, l'espionnage, la débauche et l'économie entre ouvriers ;
- les loisirs, les maladies, les types de blessure, le crieur, le cadeau du diamant et l'éclaireur.

Les tests des systèmes retirés partent avec eux.

## 4. Commander (compréhension à 100 %)
1. **La fiche de l'ouvrier**, au clic droit (validée). Elle montre :
   - la mission en cours et sa progression ;
   - la file ;
   - le salaire et la nourriture ;
   - l'équipement ;
   - les boutons **Nouvelle mission**, **Travaille ici**, **Suis-moi**, **Attends ici**, **Rentre au poste** et **Licencier**.
   « Nouvelle mission » ouvre un menu qui ne propose **que les missions de son métier**, avec leurs réglages (quantité, matériau, répétition, horaires).
2. **Le poste**, avec 5 onglets (validé) :
   - **Ouvriers** : la liste, avec l'état et la mission de chacun ;
   - **Missions** : toutes les files, à réordonner ou annuler ;
   - **Dépôt** : à **redessiner** pour qu'il ait le même style que les autres onglets. C'est une demande de KOLRAX : les mêmes cadres, la même grille, des catégories et une recherche ;
   - **Caisse** : la paie ;
   - **Journal** : une ligne par événement récent, à la place des lettres. On y lit par exemple « Raoul a fini : 64 fer », « Jeanne bloquée : pas de houe » ou « Attaque de zombies repoussée ».
3. **La barre de commandes** (validée), par une touche dédiée. On tape « gar… » et elle propose « Garde à vous », « Garder la colonie »… Elle s'appuie sur le répertoire d'actions. On ne tape jamais une phrase libre : on choisit une ligne de la liste.
4. **La confirmation** (validée), avant chaque nouvelle mission : « Compris : Raoul mine du fer jusqu'à 64 » ✔ / ✖.

## 5. Équilibre
Tous les réglages sont dans **un seul fichier** :
- un débutant peut payer 2 ou 3 ouvriers, un joueur riche une trentaine, avec un plafond de 30 ;
- un ouvrier produit à peu près autant qu'un joueur qui fait le même travail ;
- le salaire coûte environ 10 % de ce que gagne un joueur par jour, et la nourriture s'y ajoute ;
- chaque étoile rend l'ouvrier plus rapide et plus cher, et la formation se paie.

## 6. Guerre : simple, mais de grands combats (choix de KOLRAX)
- Au poste, un chef **déclare la guerre** à un autre joueur. Les deux le voient. La paix se fait de la même façon.
- Les gardes ont trois missions :
  - **Défendre la colonie** ;
  - **Escorter** un joueur ;
  - **Attaquer** : la cible est un poste ennemi pris dans une liste, ou la position du joueur avec « Travaille ici ».
- **Grands combats** : jusqu'à **30 contre 30** sans lag. Les gardes marchent en groupe, en formation simple (ligne ou colonne), et suivent leur chef. Ils chargent sur ordre, se replient sous 30 % de vie et se protègent avec un bouclier s'ils en ont un.
- Un garde frappe un joueur **seulement pendant une guerre déclarée**, et seulement sur ordre. Il casse des blocs **seulement** avec la mission « Attaquer », pendant la guerre.
- Les règles du serveur restent : pas de téléportation des joueurs, tombe à la mort.

## 7. Le joueur fantôme (demandé par KOLRAX pour que les agents vérifient en jeu)
Les agents doivent pouvoir jouer une **vraie partie** sans écran et voir ce qu'un joueur voit.
- **Commande `/fantome`** : elle n'est active qu'en développement, ou si `reglages.json` contient `"fantome": true`, et elle est réservée aux op. Elle fait apparaître un faux joueur, que l'on pilote ensuite. Il peut :
  - aller à une position, poser un bloc, donner un objet ;
  - faire un clic droit sur un ouvrier ;
  - choisir une mission, en passant par **la même fonction serveur que l'écran**, qui ne doit pas être court-circuitée ;
  - utiliser la barre de commandes ;
  - payer ;
  - accélérer le temps (`tick sprint`).
- **Le rapport `/fantome rapport`** écrit un JSON dans `run/fantome/`. Il contient ce qu'un joueur verrait :
  - le texte au-dessus de chaque ouvrier ;
  - le contenu des 5 onglets du poste ;
  - les messages reçus par le fantôme ;
  - l'inventaire ;
  - le dépôt ;
  - les blocs cassés par les ouvriers près de la colonie.
- **`tools/fantome.py <scenario>`** lance un serveur de développement (`./gradlew runServer`) sur un **monde généré normal**, joue un fichier de scénario (une commande par ligne, avec des `attendre N`) puis rend le rapport.
- **Les scénarios obligatoires**, dans `mod-npc/fantome/` :
  - poste près d'un village ou d'une forêt, avec 3 ouvriers : mineur, bûcheron et fermier ;
  - 2 jours de jeu ;
  - résultat attendu : **aucun bloc construit cassé**, aucun arbre de village coupé, une production non nulle et des statuts tous lisibles.
- **Les captures** : si `xvfb-run` existe dans le cloud, `runPhotos` sous xvfb. Sinon, le rapport texte suffit.

## 8. Ordre de travail pour ce soir
L'ordre suit la valeur. Ce qui est vert à 22 h UTC est livré :
1. retraits (partie 3) et étiquette `v1-finale` ;
2. joueur fantôme ;
3. mission, file, statut et persistance ;
4. lieux intelligents ;
5. fiche, poste à 5 onglets et dépôt redessiné ;
6. les 12 métiers sur les missions ;
7. barre de commandes et confirmation ;
8. horaires, satisfaction et grève ;
9. guerre ;
10. équilibre ;
11. feuille de tests v2 et charge ;
12. livraison 1.0.0.

**Livraison 1.0.0** :
- un **nouveau monde d'essai** (`essai-v2`) ; le monde `colonie` reste sur le disque, pas supprimé ;
- le **modpack obligatoire**, car le canal réseau change ;
- l'annonce Discord.

Avant de redémarrer, on fait les contrôles habituels : les joueurs connectés, et 5 minutes de préavis si quelqu'un joue.

Le monde d'ouverture de lundi sera **un monde neuf**, généré lundi matin avec le datapack `colonie`, la dernière version et la procédure de `CLAUDE.md`.
