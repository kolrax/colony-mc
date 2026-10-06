# Guide du scripteur : écrire les métiers des PNJ

Ce guide s'adresse à toi, KOLRAX, comme à une IA chargée d'écrire un métier. Tout ce qu'il faut est ici : le principe, la liste complète des commandes du PNJ, des exemples et les pièges à éviter. Si tu es une IA, lis le guide en entier, puis suis la check-list de la partie 10.

## 1. Le principe

- Un **métier** est un fichier Lua sur le serveur, dans `config/colonie_pnj/metiers/<identifiant>.lua`. Le panneau web permet de les écrire, de les vérifier et de les enregistrer sans redémarrer.
- Le fichier `commun.lua` est le **cerveau commun**. Toutes les demi-secondes, pour chaque PNJ employé, le serveur appelle `commun.cerveau(pnj, metier)`. Ce cerveau gère tout ce qui ne dépend pas du métier : manger, toucher sa paie, faire grève, fuir, suivre son chef, vider son sac. Il appelle ensuite `metier.travailler(pnj, commun)`.
- Le PNJ fait **une action à la fois** : marcher, casser un bloc, abattre un arbre, attaquer… Une action dure plusieurs secondes.
  - Quand le script lance une action (par exemple `pnj.casser(x, y, z)`), il rend la main tout de suite.
  - Tant que l'action dure, `pnj.occupe()` vaut `true` et `travailler` n'est pas appelé.
  - Quand elle est finie, `pnj.resultat()` dit comment ça s'est passé, et le cerveau recommence.
- **Une fonction `travailler` décide de la prochaine action, la lance et s'arrête.** Elle ne fait jamais de boucle d'attente. Pour attendre, elle lance `pnj.attendre(secondes)`.
- Le PNJ a une **mémoire**, `pnj.memoire`. C'est une table Lua, gardée même quand le serveur redémarre. On y note où on en est : l'avancée de la galerie, les souches à replanter…
- Les scripts tournent sur le serveur seulement. **Un nouveau métier ne demande pas de nouvelle version du modpack.**
- **Le bureau est le poste de pilotage.** Chaque PNJ est rattaché à un bureau (un PNJ par bureau). Sans bureau, il ne travaille pas. Dans l'écran du bureau, le chef choisit :
  - une **commande** dans le catalogue du métier ;
  - une **zone de travail** : un centre et un rayon, 96 par défaut ;
  - des **horaires**, en heures du jeu ;
  - une **paie**.

  Le PNJ est autonome dans sa zone : on ne lui dit pas où travailler, on lui dit quoi produire.
- **Deux sortes de commandes :**
  - « stock » : garder N objets au dépôt, en reprenant dès que le stock baisse ;
  - « quantité » : livrer N objets, puis s'arrêter.

  Le cerveau commun arrête le métier tout seul quand la commande est remplie.

## 2. Écrire un métier

Un fichier de métier renvoie une table :

```lua
local M = {
  nom = "Carrier",                                   -- nom affiché (au-dessus de sa tête, à l'embauche)
  description = "Extrait l'andésite autour de son lieu de travail.",
  -- facultatif :
  combattant = false,      -- true : riposte quand on l'attaque (sinon il fuit)
  garder = { "nourriture", "#minecraft:pickaxes" },  -- ce qu'il garde en vidant son sac (une pile de chaque)
}

-- Obligatoire. Appelée quand le PNJ est libre, en mode « Travailler ici ».
function M.travailler(pnj, commun)
  -- décider, lancer UNE action, et return
end

-- Facultatif. Appelée toutes les demi-secondes même s'il est occupé : réagir à une urgence.
function M.vigilance(pnj) end

-- Facultatif. En mode « Suivre » : renvoyer true s'il a trouvé autre chose à faire que suivre.
function M.escorter(pnj) return false end

-- Facultatif mais conseillé : le catalogue des commandes affiché dans l'écran du bureau.
M.commandes = {
  -- id et nom : obligatoires. objet : ce qui se compte au dépôt (stock) ou à la livraison (quantité) ;
  -- sans objet, la commande n'a ni stock ni quantité (ex. « Garder le bureau »).
  -- Les autres champs sont libres : le script les relit avec pnj.commande().
  { id = "andesite", nom = "Andésite", objet = "minecraft:andesite", blocs = "minecraft:andesite", profondeur = 40 },
}

return M
```

**L'identifiant** est le nom du fichier : `carrier.lua` donne `carrier`. Il s'écrit en minuscules, chiffres et `_`.

Le métier apparaît chez les chercheurs d'emploi dès qu'il est enregistré et actif. Le panneau permet de l'activer ou de le désactiver.

## 3. Le cerveau commun (`commun.lua`)

Voici ce qu'il fait, dans l'ordre. Chaque ligne qui lance une action termine la décision.

1. Une action est en cours : il appelle `metier.vigilance(pnj)` si elle existe, puis s'arrête.
2. **En grève** : si la caisse couvre ce qu'on lui doit, il va se payer. Sinon il attend au bureau.
3. **Attaqué** dans les 10 dernières secondes : un `combattant` riposte, les autres fuient.
4. **Faim** :
   - faim ≤ 14 : il mange ce qu'il a ;
   - faim ≤ 8 sans rien sur lui : il se sert au dépôt, sinon il va demander à son chef.
5. **Paie due** : il va la chercher à la caisse du bureau.
6. **Ordres** :
   - « Suivre » : `metier.escorter`, sinon il suit son chef ;
   - « Attendre » : il attend.
7. **Pas de bureau** : il prévient son chef, puis attend.
8. **Hors de ses heures, ou épuisé** (fatigue à 100, jusqu'à ce qu'elle redescende à 40) : il vide son sac, puis va se reposer, dans un lit libre près du bureau s'il y en a un.
9. **Sac plein** : il va le vider au dépôt (le bureau et les coffres collés au bureau).
10. **Commande remplie** : il prévient son chef, puis attend.
11. Sinon : `metier.travailler(pnj, commun)`.

Le cerveau commun fournit aussi des outils aux métiers, à travers le deuxième argument `commun` :

| Outil | Rôle |
|---|---|
| `commun.parfois(pnj, cle, texte, auChef, delai)` | Dit `texte`, au plus une fois toutes les `delai` secondes (600 par défaut) pour une même `cle`. `auChef = true` envoie un message privé au chef, sinon il parle à voix haute. Il évite de répéter le même message toutes les demi-secondes. |
| `commun.garder` | La liste par défaut de ce qu'on garde en vidant son sac. |
| `commun.pointDansZone(pnj)` | Un point au hasard dans sa zone de travail (x, y, z), à la surface si elle est connue. Il sert à explorer. |

## 4. Les filtres

Beaucoup de commandes prennent un **filtre**, une chaîne qui désigne des blocs ou des objets :

| Filtre | Signification |
|---|---|
| `"minecraft:andesite"` | Ce bloc ou cet objet précis. Ça marche aussi pour les mods : `"create:andesite_alloy"`. |
| `"#minecraft:logs"` | Un **tag** : tous les blocs ou objets de la famille. |
| `"nourriture"` | Tout ce qui se mange. |
| `"tout"` | N'importe quoi. |
| `"#c:ores\|minecraft:ancient_debris"` | Plusieurs filtres séparés par `\|`. |

**Tags utiles :**

| Famille | Tags |
|---|---|
| Bois | `#minecraft:logs` (troncs), `#minecraft:planks`, `#minecraft:saplings` (pousses), `#minecraft:leaves` |
| Pierre et minerais | `#c:ores` (tous les minerais, y compris ceux des mods), `#c:cobblestones`, `#c:stones` |
| Sols | `#minecraft:dirt` (terre, herbe, podzol…), `#minecraft:sand` |
| Outils | `#minecraft:pickaxes`, `#minecraft:axes`, `#minecraft:shovels`, `#minecraft:hoes`, `#minecraft:swords` |
| Cultures | `#minecraft:crops` |

## 5. Les commandes du PNJ (`pnj.…`)

Les coordonnées sont des nombres entiers de blocs. Une fonction qui « renvoie x, y, z » s'utilise ainsi : `local x, y, z = pnj.position()`.

### Qui il est, où il en est

| Commande | Renvoie |
|---|---|
| `pnj.nom()` | Son prénom. |
| `pnj.metier()` | L'identifiant de son métier. |
| `pnj.niveau()` | Sa compétence, de 1 à 5 étoiles. Elle rend déjà son travail plus rapide. |
| `pnj.temperament()` | `"calme"`, `"peureux"`, `"fourbe"` ou `"colerique"`. |
| `pnj.mode()` | `"travail"`, `"suivre"`, `"attendre"` ou `"greve"`. |
| `pnj.position()` | x, y, z de ses pieds. |
| `pnj.zone()` | x, y, z, rayon de sa zone de travail. Le centre est celui réglé au bureau ou par « Travailler ici », sinon le bureau. |
| `pnj.lieu()` | x, y, z du centre de sa zone (comme `pnj.zone()` sans le rayon), ou `nil`. |
| `pnj.dansZone(x, y, z)` | `true` si ce bloc est dans sa zone (distance horizontale au centre ≤ rayon). |
| `pnj.direction()` | dx, dz : la direction où regardait le chef quand il a dit « Travailler ici ». Chacun vaut -1, 0 ou 1. |
| `pnj.commande()` | Sa commande : la ligne du catalogue (`id`, `nom`, `objet`, et tes champs à toi), plus `mode` (`"stock"` ou `"quantite"`), `nombre` et `livre` (ce qu'il a déjà livré). Vaut `nil` s'il n'a pas de commande. |
| `pnj.avancement()` | Deux nombres : où il en est, et le nombre demandé. En mode stock, c'est ce qu'il y a au dépôt ; en mode quantité, ce qu'il a livré. Le premier vaut -1 si on ne sait pas. |
| `pnj.commandeRemplie()` | `true` quand la commande est remplie. |
| `pnj.enService()` | `true` pendant ses heures de travail. |
| `pnj.horaires()` | Début et fin de ses heures de travail, en heures du jeu. S'ils sont égaux, il travaille toute la journée. |
| `pnj.fatigue()` | De 0 à 100. Elle monte de 5 par heure du jeu travaillée et descend au repos (plus vite dans un lit). À partir de 70, il travaille plus lentement. |
| `pnj.vie()` | Sa vie et sa vie maximale. |
| `pnj.faim()` | De 0 à 20, comme un joueur. |
| `pnj.occupe()` | `true` si une action est en cours. |
| `pnj.action()` | Le nom de l'action en cours (`"casser"`, `"aller"`…), ou `nil`. |
| `pnj.resultat()` | Comment s'est finie la dernière action : `"ok"`, ou une raison d'échec (partie 6). Vaut `""` juste après le lancement d'une action. |
| `pnj.temps()` | Une horloge en secondes qui ne fait qu'avancer. Elle sert à espacer les choses. |
| `pnj.heure()` | L'heure du jour en jeu, de 0 à 23999. 0 est le matin, 13000 la tombée de la nuit. |
| `pnj.distance(x, y, z)` | Sa distance à ce bloc. |

### L'argent et le bureau

| Commande | Renvoie |
|---|---|
| `pnj.bureau()` | x, y, z de son bureau, ou `nil` s'il n'est rattaché à aucun. |
| `pnj.aBureau()` | `true` s'il est rattaché à un bureau. |
| `pnj.paieDue()` | `true` quand c'est l'heure d'aller chercher sa paie. |
| `pnj.du()` | Ce qu'on lui doit, en bronzes : les paies en retard, plus celle du jour si elle est due. C'est le salaire fixé au bureau, pas forcément celui qu'il demandait. Moins payé, il travaille moins vite (jusqu'à 75 % à mi-salaire) ; mieux payé, un peu plus vite (jusqu'à 115 %). |
| `pnj.caisse()` | Ce qu'il y a dans la caisse du bureau, en bronzes. Vaut -1 si le bureau est introuvable ou trop loin. |
| `pnj.depotContient(filtre)` | Combien d'objets de ce filtre il y a au dépôt, coffres collés au bureau compris. |
| `pnj.montant(n)` | Un montant en toutes lettres, par exemple « 1 rouage en laiton, 3 pièces de bronze ». |

### Regarder le monde

| Commande | Renvoie |
|---|---|
| `pnj.bloc(x, y, z)` | L'identifiant du bloc, par exemple `"minecraft:stone"`. |
| `pnj.est(x, y, z, filtre)` | `true` si le bloc correspond au filtre. |
| `pnj.solide(x, y, z)` | `true` si le bloc arrête le passage. |
| `pnj.libre(x, y, z)` | `true` si on peut passer : ni bloc solide, ni liquide. |
| `pnj.liquide(x, y, z)` | `"eau"`, `"lave"`, `"autre"` ou `nil`. |
| `pnj.lumiere(x, y, z)` | La lumière, de 0 à 15. En dessous de 8, les monstres peuvent apparaître. |
| `pnj.chercherBloc(filtre, rayon, options)` | x, y, z du bloc correspondant le plus proche du PNJ, ou `nil`. Le rayon va jusqu'à 12. `options` est facultatif : voir le tableau suivant. |
| `pnj.chercherArbre(rayon, options)` | x, y, z du pied de l'arbre naturel le plus proche (rayon jusqu'à 24), ou `nil`. Une maison en rondins n'est pas un arbre : il faut des feuilles naturelles. `options` accepte `{x=, y=, z=}` et `bois = filtre` (par exemple `"minecraft:birch_log"`). |
| `pnj.surface(x, z)` | La hauteur (Y) du sol à cet endroit, ou `nil` si le coin n'est pas chargé. |
| `pnj.solNaturel(rayon)` | x, y, z d'un endroit de sa zone où creuser sans abîmer de construction : sol naturel (terre, sable, pierre…), sans planches, vitres, portes, dalles ni coffres à 5 blocs autour. Vaut `nil` s'il n'en trouve pas. |
| `pnj.monstres(rayon)` | La liste des monstres proches (rayon jusqu'à 32). |
| `pnj.cibles(rayon)` | Les monstres proches, plus quiconque l'a attaqué, a attaqué son chef ou a été frappé par son chef dans les 10 dernières secondes. Triés du plus proche au plus loin. |
| `pnj.agresseur()` | Qui l'a attaqué dans les 10 dernières secondes, ou `nil`. |
| `pnj.chef()` | `{nom, connecte}`. S'il est connecté et dans la même dimension, aussi `x, y, z, distance`. Vaut `nil` pour un chômeur. |
| `pnj.objetsAuSol(rayon, filtre)` | Combien d'objets traînent par terre autour de lui. Le filtre est facultatif. |

**Les options de `chercherBloc` :**

| Option | Effet |
|---|---|
| `{x=, y=, z=}` | Le centre de la recherche. Par défaut, c'est le PNJ. |
| `expose = true` | Seulement les blocs qui touchent de l'air (visibles). |
| `recoltable = true` | Seulement ceux que son outil en main peut récolter. |

**Les entités** renvoyées par `monstres`, `cibles` et `agresseur` sont des tables `{id, nom, type, joueur, x, y, z, distance}`. On passe `id` à `pnj.attaquer(id)` ou `pnj.fuir(id)`. Exemple : `local c = pnj.cibles(10)[1]`.

### Son sac

Son sac compte 27 cases. Il porte aussi un objet en main et une armure.

| Commande | Effet |
|---|---|
| `pnj.compter(filtre)` | Combien il en a, objet en main compris. |
| `pnj.a(filtre, n)` | `true` s'il en a au moins `n` (1 par défaut). |
| `pnj.plein()` | `true` si son sac n'a plus de case vide. |
| `pnj.libres()` | Le nombre de cases vides. |
| `pnj.equiper(filtre)` | Prend en main le premier objet qui correspond. Renvoie `true` s'il l'a trouvé. |
| `pnj.enMain()` | L'identifiant de l'objet en main, ou `nil`. |
| `pnj.peutRecolter(x, y, z)` | `true` si l'outil en main récolte ce bloc. Sinon, il le casse pour rien, comme un joueur qui casse de la pierre à la main. |
| `pnj.manger()` | Mange tout de suite quelque chose de son sac. Renvoie `true` s'il a mangé. Ce n'est pas une action. |

Il enfile tout seul la meilleure armure de son sac. Ce qu'il casse va directement dans son sac, et il ramasse ce qui est à ses pieds.

### Les actions

Chaque action **rend la main tout de suite** et dure ensuite plusieurs secondes. Lancer une action **remplace** celle en cours.

| Commande | Ce qu'il fait |
|---|---|
| `pnj.aller(x, y, z, portee)` | Marche jusqu'à ce bloc, à `portee` près (1,5 par défaut). |
| `pnj.casser(x, y, z)` | S'approche à portée de main (4,5 blocs) et casse le bloc. Le temps dépend du bloc, de l'outil et de sa compétence, comme pour un joueur. Il refuse les blocs qui ont un contenu : coffres, fours… |
| `pnj.abattre(x, y, z)` | Abat tout l'arbre dont c'est le pied. Le bois va dans son sac. |
| `pnj.poser(x, y, z, filtre)` | Pose un bloc de son sac, comme un joueur : la torche va au mur, la pousse sur la terre. |
| `pnj.attaquer(id)` | Poursuit et frappe jusqu'à la mort de la cible, au plus 60 secondes. |
| `pnj.fuir(id)` | Court se mettre à l'abri, loin de cette entité. |
| `pnj.attendre(secondes)` | Ne fait rien pendant ce temps (jusqu'à 600 s). |
| `pnj.seReposer()` | Va se reposer : dans un lit libre à moins de 8 blocs de son bureau (il s'y couche), sinon à côté du bureau. Il refait le point toutes les 30 secondes. |
| `pnj.suivreChef()` | Rejoint son chef, à pied. Jamais de téléportation. |
| `pnj.deposer(garder)` | Va au bureau et range son sac dans le dépôt, puis dans les coffres, tonneaux ou réservoirs collés au bureau. Il garde **une pile** de chaque filtre de la liste `garder`. Ce qu'il dépose de l'objet de sa commande compte comme livré. |
| `pnj.prendre(filtre, nombre)` | Va au bureau et prend des objets au dépôt, ou dans les coffres collés au bureau. |
| `pnj.toucherPaie()` | Va à la caisse et prend ce qu'on lui doit. Si la caisse est vide, la paie compte comme impayée. Après plusieurs impayés, il fait grève. |
| `pnj.demander(texte, filtre, nombre)` | Va voir son chef et lui demande quelque chose. Si le chef n'est pas là, il l'attend au bureau et le lui dit à sa connexion. L'action réussit dès qu'il en a `nombre`. Elle abandonne au bout de 2 min 30, et le script peut redemander. |
| `pnj.ramasser(rayon, filtre)` | Va ramasser les objets qui traînent autour de lui (au plus 40 s). |
| `pnj.arreter()` | Interrompt l'action en cours. |

### Parler

| Commande | Effet |
|---|---|
| `pnj.dire(texte)` | Parle à voix haute : les joueurs à moins de 24 blocs l'entendent. |
| `pnj.direAuChef(texte)` | Message privé à son chef, où qu'il soit, s'il est connecté. |
| `pnj.log(texte)` | Écrit dans le journal du panneau, pour mettre au point un script. |
| `print(...)` | Écrit aussi dans le journal du panneau. |

## 6. Les résultats d'action (`pnj.resultat()`)

| Résultat | Signification |
|---|---|
| `"ok"` | Réussi. |
| `""` | Une action vient d'être lancée, ou aucune n'a encore eu lieu. |
| `"bloque"` | Il n'avance plus depuis 10 secondes : mur, trou, chemin impossible. |
| `"trop_loin"` | Il n'est pas arrivé au bout de 90 secondes de marche. |
| `"inaccessible"` | Il n'arrive pas à s'approcher assez du bloc ou de la cible. |
| `"incassable"` | Bloc indestructible, comme la bedrock. |
| `"contenant"` | Il refuse de casser un bloc qui a un contenu. |
| `"pas_un_arbre"` | Pas de feuilles naturelles autour : ce n'est pas un arbre. |
| `"occupe"` | Il y a déjà un bloc là où il devait poser. |
| `"pas_d_objet"` | Il n'a pas l'objet à poser. |
| `"rien_pour_tenir"` | Le bloc à poser n'a aucun appui. |
| `"impossible"` | La pose a été refusée. |
| `"pas_de_bureau"` | Il n'est rattaché à aucun bureau, ou son bureau a été détruit. |
| `"bureau_inaccessible"` | Il n'arrive pas à rejoindre le bureau. |
| `"caisse_vide"` | La caisse ne couvre pas sa paie. |
| `"depot_plein"` | Le dépôt du bureau est plein. |
| `"rien_au_depot"` | Ce qu'il voulait prendre n'est pas au dépôt. |
| `"pas_recu"` | Le chef ne lui a pas donné ce qu'il demandait à temps. |
| `"chef_absent"` | Son chef n'est pas connecté, ou pas dans la même dimension. |
| `"trop_long"` | Le combat a duré plus de 60 secondes. |
| `"arrete"`, `"ordre"`, `"greve"`, `"a_terre"`, `"licencie"` | L'action a été interrompue. |

**Bonne pratique** : quand une action échoue deux fois sur la même cible, passe à autre chose. Sinon le PNJ répète l'échec à l'infini. Le mineur de base montre comment faire (`m.cible` et `m.ignores`).

## 7. La mémoire (`pnj.memoire`)

- C'est une table ordinaire : `pnj.memoire.avance = 12`, `pnj.memoire.souches = { {x=1, y=64, z=3} }`.
- Elle est sauvegardée avec le PNJ. Elle accepte les nombres, les textes, les booléens et les tables (pas plus de 8 niveaux).
- Elle est **à lui seul**, et elle survit à un changement de métier. Préfixe tes clés si tu crains les mélanges.
- Garde-la petite : quelques dizaines d'entrées, pas des milliers.

## 8. Les règles d'or

1. **Une décision, une action, `return`.** Pas de `while` qui attend : le serveur coupe tout script qui dépasse environ 300 000 instructions (« script trop long »). Le PNJ reste alors 10 secondes sans réfléchir.
2. **Toujours faire quelque chose.** Si rien n'est à faire, lance `pnj.attendre(10)` ou plus. Sinon `travailler` est rappelé toutes les demi-secondes.
3. **Ne pas radoter.** Pour parler, passe par `commun.parfois(...)` ou note dans la mémoire ce qui a déjà été dit.
4. **Gérer l'échec.** Vérifie `pnj.resultat()` et évite de relancer indéfiniment la même action ratée.
5. **Rester raisonnable.** Les rayons sont plafonnés (12 pour les blocs, 24 pour les arbres, 32 pour les entités). Une grande recherche toutes les demi-secondes coûte cher au serveur. Fais-la quand le PNJ est libre, pas en boucle.
6. **Respecter les règles du serveur.**
   - Pas de téléportation : rien ne permet de déplacer un PNJ autrement qu'à pied.
   - Il ne vole et ne détruit qu'en RP : ne fais pas casser des constructions de joueurs, ni ouvrir les coffres des autres.
   - Il n'attaque jamais un joueur de lui-même : seulement celui qui l'a agressé, ou qui a agressé son chef.
7. **En cas d'erreur**, l'erreur s'affiche dans le journal du panneau avec la ligne fautive. Le PNJ attend 10 secondes, puis réessaie.

## 9. Exemples

### Carrier : extraire l'andésite autour de son lieu de travail

```lua
local M = {
  nom = "Carrier",
  description = "Extrait la pierre demandée en surface, dans sa zone, et la rapporte au bureau.",
  garder = { "nourriture", "#minecraft:pickaxes" },
}
M.commandes = {
  { id = "andesite", nom = "Andésite", objet = "minecraft:andesite", pierre = "minecraft:andesite" },
  { id = "granite",  nom = "Granite",  objet = "minecraft:granite",  pierre = "minecraft:granite" },
}

function M.travailler(pnj, commun)
  local c = pnj.commande()
  if not c then return pnj.attendre(20) end   -- le chef choisit la pierre dans le bureau
  if not pnj.equiper("#minecraft:pickaxes") then
    return pnj.demander("Il me faut une pioche pour extraire la pierre.", "#minecraft:pickaxes", 1)
  end
  local x, y, z = pnj.chercherBloc(c.pierre, 10, { expose = true })
  if x and pnj.dansZone(x, y, z) then return pnj.casser(x, y, z) end
  -- rien autour de lui : il va voir ailleurs dans sa zone
  local ex, ey, ez = commun.pointDansZone(pnj)
  return pnj.aller(ex, ey, ez, 3)
end

return M
```

### Garde de ville : faire la ronde entre deux points

```lua
local M = {
  nom = "Garde de ville",
  description = "Fait sa ronde autour de son poste et chasse les monstres.",
  combattant = true,
}

function M.vigilance(pnj)
  if pnj.action() == "attaquer" then return end
  local c = pnj.cibles(12)[1]
  if c then pnj.equiper("#minecraft:swords"); pnj.attaquer(c.id) end
end

function M.travailler(pnj, commun)
  local m = pnj.memoire
  local lx, ly, lz = pnj.lieu()
  local dx, dz = pnj.direction()
  pnj.equiper("#minecraft:swords")
  -- Deux bornes : le poste, et 20 blocs plus loin dans la direction donnée.
  m.borne = (m.borne == 1) and 2 or 1
  if m.borne == 1 then return pnj.aller(lx, ly, lz, 2) end
  return pnj.aller(lx + dx * 20, ly, lz + dz * 20, 2)
end

return M
```

## 10. Tester un métier

1. Dans le panneau, onglet **Métiers** : **Nouveau métier**, colle le script, puis **Vérifier**. La vérification signale les fautes de syntaxe, le `return M` oublié et la fonction `travailler` manquante.
2. **Enregistrer** : le métier est chargé tout de suite. L'ancienne version est gardée dans `config/colonie_pnj/historique/`.
3. En jeu, ou dans l'onglet **PNJ** du panneau, fais apparaître un chercheur d'emploi de ce métier près de toi. En jeu, la commande est `/pnj chercheur <identifiant> [niveau] [skin]`.
4. Embauche-le et donne-lui ses outils (accroupi + clic droit avec l'objet en main). Pose un bureau : dans son écran, rattache le PNJ, puis choisis sa commande, sa zone et ses horaires.
5. Regarde l'onglet **Journal** du panneau : erreurs de script, messages `pnj.log`. En jeu, `/pnj liste` montre ce que fait chaque PNJ.

**Check-list pour une IA qui écrit un métier**

- [ ] Le fichier finit par `return M`. `M.nom` et `M.description` sont en français.
- [ ] `M.travailler(pnj, commun)` lance une seule action, ou rien, puis `return`.
- [ ] Il y a toujours une issue : `pnj.attendre(...)` quand il n'y a rien à faire.
- [ ] Les échecs répétés sont gérés (`pnj.resultat()`, mémoire).
- [ ] Les messages passent par `commun.parfois` : pas de répétition toutes les demi-secondes.
- [ ] L'outil nécessaire est demandé au chef avec `pnj.demander` s'il manque.
- [ ] Le métier a un catalogue `M.commandes`. Il lit sa commande avec `pnj.commande()` et reste dans sa zone (`pnj.dansZone`, `commun.pointDansZone`).
- [ ] Rien n'enfreint les règles du serveur : pas de téléportation, pas de vol ni de destruction hors RP, pas d'attaque gratuite d'un joueur.
- [ ] Toutes les fonctions utilisées existent dans la partie 5. Il n'y a pas d'autres API : pas de `os`, `io` ni `require`.
