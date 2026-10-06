# Le bureau, poste de pilotage du travailleur : plan

Le 6 octobre 2026, avec KOLRAX. Cette amélioration fait partie du jalon 1 (feuille de route de l'Atelier).

## Le principe

On ne dit plus au PNJ **où** travailler, on lui dit **quoi produire**. Il se débrouille seul dans sa zone, comme un joueur : il cherche, il va chercher, il rapporte au dépôt, et il vient demander ce qui lui manque. Un PNJ sans bureau ne travaille pas. Le bouton « Travailler ici » disparaît.

## Décisions de KOLRAX (6 octobre)

- **Un PNJ par bureau.** Les équipes de plusieurs PNJ viendront plus tard (idée du jalon 4).
- **Deux sortes de commandes**, au choix pour chacune :
  - **stock** : « garde 64 charbons au dépôt », il reprend dès que le stock baisse ;
  - **quantité** : « mine 64 charbons », puis il attend une nouvelle commande.
- **Zone de travail** réglée dans le bureau : un centre (par défaut le bureau, ou des coordonnées) et un rayon (96 par défaut). Il y est complètement autonome et choisit seul où creuser ou couper. Il ne creuse jamais dans une construction : il part du sol naturel, à l'écart des blocs posés par les joueurs.
- **Horaires** en heures du jeu (par exemple de 6 h à 18 h sur la journée Minecraft de 20 minutes).

## L'écran du bureau (clic droit dessus, réservé au propriétaire)

| Partie | Contenu |
|---|---|
| L'employé | Son nom, son métier, ses étoiles, ce qu'il fait, l'avancement de sa commande (« charbon 41/64 »). Bouton « Rattacher un PNJ » (tes PNJ sans bureau) et « Libérer ». |
| La commande | Les commandes de son métier, puis : stock ou quantité, et le nombre. |
| La zone | Le centre (bouton « Ici » = ta position, ou les coordonnées) et le rayon (96 par défaut). |
| Les horaires | Début et fin de journée, en heures du jeu (6 h à 18 h par défaut). |
| La paie | Tu fixes son salaire. Par défaut, c'est ce qu'il demande. Moins payé, il travaille moins vite ; mieux payé, il travaille un peu plus vite. Les effets sur le moral et la loyauté viendront au jalon 2. |
| Caisse et dépôt | Comme aujourd'hui, plus les coffres collés au bureau, qui agrandissent le dépôt. |

Tout le monde peut ouvrir la caisse et le dépôt : c'est la règle « aucune protection », et le vol en RP est permis. Seul le propriétaire gère le PNJ.

## Les commandes, dans les scripts

Chaque métier déclare son catalogue de commandes dans son fichier Lua. Une nouvelle commande ne demande donc pas de nouvelle version du modpack. Par exemple, pour le mineur :

```lua
M.commandes = {
  { id = "charbon", nom = "Charbon", objet = "minecraft:coal", blocs = "#c:ores/coal", profondeur = 40 },
  { id = "diamant", nom = "Diamant", objet = "minecraft:diamond", blocs = "#c:ores/diamond", profondeur = -58, outil = "fer" },
  { id = "zinc", nom = "Zinc (Create)", objet = "create:raw_zinc", blocs = "#c:ores/zinc", profondeur = 30 },
  ...
}
```

Le PNJ lit sa commande avec `pnj.commande()` et son avancement avec `pnj.avancement()`.

## L'autonomie, métier par métier

**Mineur**
1. Il cherche les filons visibles dans les grottes de sa zone.
2. Sinon, il creuse un escalier depuis un point de sol naturel de sa zone jusqu'à la profondeur du minerai.
3. Il mine en galeries parallèles, espacées de 3 blocs. Il garde ce qu'il trouve en chemin.
4. S'il lui faut un meilleur outil (une pioche en fer pour le diamant), il vient le demander.

**Bûcheron**
- Il abat les arbres du type demandé, ou de n'importe quel type, dans sa zone, et replante.
- S'il n'y en a plus à portée, il explore sa zone par étapes.

**Guerrier**
- Il garde le bureau ou fait une ronde dans sa zone.
- Ou il escorte son chef.

Limite technique : un PNJ ne voit que les chunks chargés autour de lui. Il explore donc sa zone comme un joueur, il n'a pas de vision à 96 blocs à la ronde.

## Horaires, repos et fatigue

- Pendant ses heures, il travaille. En dehors, il rentre se reposer : dans un lit à moins de 8 blocs du bureau s'il y en a un, sinon au bureau.
- **La fatigue** est une nouvelle jauge, visible dans le bureau. Elle monte au travail et descend au repos. Fatigué, il travaille plus lentement.
- Au-delà de 12 heures de travail par jour, il est en surmenage : la fatigue s'accumule d'un jour à l'autre.
- Les conséquences sur le moral, la grève et la révolte arrivent au jalon 2.

## Les étapes (chacune testable en jeu)

| Étape | Contenu |
|---|---|
| **A. Le bureau** | L'écran du bureau, le rattachement d'un PNJ, la zone, les horaires et le repos, la paie par PNJ, la fatigue, les coffres collés au dépôt. |
| **B. Les commandes** | Les catalogues de commandes, les modes stock et quantité, le bûcheron autonome par type de bois, le guerrier gardien ou en ronde. |
| **C. Le mineur autonome** | Les filons visibles, l'escalier jusqu'à la profondeur du minerai, les galeries parallèles, le choix d'un sol naturel. |

Chaque étape demande une nouvelle version du modpack, car il y a de nouveaux écrans chez les joueurs.

Le guide des scripts sera mis à jour : nouvelles fonctions `pnj.commande()`, `pnj.avancement()`, `pnj.zone()`, `pnj.horaires()`, `pnj.fatigue()`…
