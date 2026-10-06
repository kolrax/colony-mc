# Poste commun, lettres, corbeau, ordres écrits : plan (version 0.5.0)

Le 6 octobre 2026 au soir, d'après les idées de KOLRAX dans l'Atelier : « Un seul bureau par joueur », « Communication par lettres », « Mission écrite, et interprétée par le code », « Ordres donnés en leur parlant, pas via bouton », « Bouton arrêter de me suivre », et les retours « Ne travaille pas » (Philippa) et le test 24 (nage).

Cette version change les écrans et ajoute le corbeau : **nouveau modpack obligatoire** (canal réseau « 5 »).

## 1. Un seul poste de travail par joueur

- Le bureau devient le **poste de travail** commun à tous les ouvriers d'un joueur. On ne peut en poser qu'un : un deuxième est refusé (« Tu as déjà un poste de travail en x y z »).
- **Tous ses ouvriers y sont rattachés d'office**, sans bouton : l'espace de vie (50 blocs), la caisse des salaires, le dépôt (agrandi par les coffres collés) et les lits (16 blocs autour) sont communs.
- **Passage depuis plusieurs bureaux** : le premier bureau que le joueur ouvre après la mise à jour lui propose « En faire ton poste de travail ». Les autres ne sont plus que des coffres (caisse et dépôt), à vider puis casser.
- **L'écran du poste** a trois onglets :
  - **Ouvriers** : la liste de tous ses PNJ (statut en couleur). Pour celui qu'on choisit : sa fiche, ses missions avec leur avancement, une mission à écrire (envoyée par lettre), ses horaires, sa paie et la durée maximale de ses expéditions. Les morts restent affichés en rouge jusqu'à ce qu'on les retire.
  - **Lettres** : le courrier reçu et le courrier à écrire.
  - **Caisse et dépôt**.

## 2. Les lettres, plus de messages dans le chat

- **Plus aucun message de PNJ dans le chat.**
  - Face à face, le PNJ parle : une **bulle** s'affiche au-dessus de sa tête.
  - Tout ce qu'il a à dire à son chef absent (faim, outil, mission terminée, expédition, grève, paie, à terre…) devient une **lettre** déposée au poste. Le chef voit seulement une ligne discrète au-dessus de sa barre d'objets (« Une lettre de Philippa t'attend au poste ») et entend un son.
- **Le dépôt est montré** :
  - **Ouvrier proche** (dans l'espace de vie) : il marche jusqu'au poste, une lettre à la main, et la pose.
  - **Ouvrier loin** : il envoie un **corbeau voyageur**. Le corbeau s'envole, puis arrive au poste, s'y pose, laisse la lettre et repart. On le voit partir et arriver ; entre les deux, son vol est compté (15 blocs par seconde, comme un vrai corbeau) sans charger le monde sur tout le trajet. Sinon, un corbeau qui traverse 2 000 blocs ferait générer et charger tout le terrain en dessous de lui, ce qui ralentirait le serveur.
- **Écrire à un ouvrier**, depuis l'onglet Lettres du poste :
  - S'il est au loin, un corbeau part du poste et le rejoint. Il lit la lettre, exécute les ordres qu'elle contient (« Rentre », une nouvelle mission…) et répond par corbeau, avec de ses nouvelles : où il est, ce qu'il fait, comment il va.
  - S'il est dans l'espace de vie, il passe prendre la lettre au poste.
- **Des banques de messages** : chaque sorte de lettre a plusieurs formulations, et le ton change selon le tempérament (calme, peureux, fourbe, colérique). Les lettres sont datées en jours du jeu et signées.

## 3. Missions écrites, interprétées par le code

- Plus de liste à faire défiler. On écrit la mission en français, par exemple :
  - « Ramène 10 fers et 1 diamant »
  - « Garde toujours 32 bûches de bouleau en stock »
  - « Fais la ronde »
- Le mod comprend :
  - les quantités, en chiffres ou en lettres (dix, une douzaine, une pile = 64) ;
  - ce qu'il faut produire, sous ses différents noms (fer, minerai de fer, lingots de fer…) ;
  - le mode : « garde, stock, toujours » veut dire un stock à maintenir ; « ramène, mine, coupe » veut dire une quantité, puis arrêt ;
  - plusieurs missions à la suite (« et », « puis »).
- Chaque ouvrier a une **liste de missions**. Il remplit la première qui n'est pas faite ; une quantité terminée est retirée, et il passe à la suivante. Une nouvelle lettre remplace les missions en cours, sauf si elle dit « aussi » ou « en plus ».
- Il répond ce qu'il a compris (« Compris, chef : 1) 10 fers, 2) 1 diamant »). Il dit aussi ce qu'il ne sait pas faire (« Je suis mineur : je ne coupe pas de bois »).
- **Sans intelligence artificielle générative** (ta décision : elle coûterait de l'argent réel). C'est un interpréteur de règles, testé sur des dizaines de phrases.

## 4. Parler aux PNJ au lieu de boutons

- Clic droit sur un de ses ouvriers : une **fenêtre de conversation**, avec ce qu'il dit et une zone pour lui écrire.
- Les ordres qu'il comprend :
  - **déplacements** : « suis-moi », « arrête de me suivre », « attends ici », « au travail », « rentre au poste » ;
  - **sac et renvoi** : « montre ton sac », « tu es renvoyé » (il demande de confirmer) ;
  - **questions** : « comment ça va ? », « où en es-tu ? », « pourquoi tu ne travailles pas ? » ;
  - **réglages** : les missions, comme à l'écrit, et les horaires (« travaille de 8 h à 20 h »).
- Le bouton « Arrêter de me suivre » est remplacé par la phrase. Il n'y a plus de boutons d'ordres. L'entretien d'embauche garde ses deux boutons.

## 5. Corrections

- **Philippa « ne travaille pas »** : sa mission « Diamant 1/1 » était déjà remplie (un diamant restait dans le dépôt de l'ancien bureau de Clémence). Le statut disait « Au travail ». Il dit maintenant « Mission remplie », et elle t'écrit.
- **Test 24 (nage)** : un PNJ ne prenait jamais la pose de nage d'un joueur ; il flottait debout. Il nage maintenant allongé, comme un joueur qui nage en courant.

## Ordre de travail

1. Missions en liste et interpréteur des ordres, avec ses tests.
2. Courrier : la boîte du poste, les banques de messages, les bulles.
3. Poste commun : un par joueur, passage depuis plusieurs bureaux, écran à onglets.
4. Conversation.
5. Corbeau : modèle, texture, animations, vols ; lettres du chef vers ses ouvriers.
6. Nage allongée.
7. Tests automatiques, puis serveur et modpack 0.4.6.
