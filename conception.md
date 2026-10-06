> **Depuis la 0.4.0 (6 octobre 2026)**, à la demande de KOLRAX, les métiers sont codés en Java dans le mod : le moteur Lua, l'éditeur de scripts du panneau et le guide du scripteur ont été retirés. Voir conception-bureau.md.

# Mod « Colonie - PNJ travailleurs » : proposition de conception

Le 5 octobre 2026, pour KOLRAX_MC. Elle s'appuie sur tes 8 réponses du jour (décisions r08 à r15 dans la console).

## 1. En une phrase

Des PNJ au skin de joueur errent dans le monde à la recherche d'un emploi. Tu les embauches contre une prime, ils travaillent pour toi même quand tu n'es pas connecté, et ils viennent chercher leur paie dans ton bureau. S'ils sont mal traités, ils se mettent en grève, et ça peut mal finir.

## 2. Comment c'est construit

- **Un seul mod NeoForge**, sans aucune dépendance en dehors de NeoForge. Il reconnaît les pièces Numismatics par leur nom, mais n'en dépend pas.
- **Pas de PlayerEngine.** Je l'ai étudié, et il ne convient pas :
  - il est fait pour des compagnons pilotés par l'IA Player2, pas pour des ouvriers ;
  - sa licence est mal annoncée : LGPL, pas MIT ;
  - il pèse 4,5 Mo, n'a aucune documentation, et ses joueurs signalent des gels et des plantages ;
  - il modifie le cœur du jeu, ce qui est risqué à côté de Create.

  Je construis plutôt les PNJ comme des créatures du jeu classiques, avec leurs propres routines. C'est plus léger et plus stable, et je maîtrise tout le code.
- **Un cerveau programmé**, sans IA générative et donc sans coût. Il a trois parties :
  - les routines de métier ;
  - les besoins (faim, fatigue) ;
  - les émotions (moral, peur, loyauté).

  Le cerveau choisit à chaque instant quoi faire : travailler, manger, se reposer, aller se payer, fuir ou faire grève.
- **Ils vivent sans toi.** Un PNJ embauché garde son propre chunk chargé, et seulement celui-là. Le nombre de PNJ par joueur est plafonné (5 par défaut, réglable). Je mesurerai le coût avec spark.
- **Les règles du serveur s'appliquent :**
  - aucune téléportation, même pour rejoindre son chef : il marche ;
  - aucune zone protégée ;
  - un PNJ ne vole ou n'attaque que dans le cadre d'une révolte. Le pillage en temps de guerre viendra avec un système de guerre déclarée, plus tard.
- **Distribution :** le fichier du mod est glissé dans le modpack. L'outil `pack/release.py` sait déjà embarquer les fichiers de `pack/overrides/` : j'y place le mod, et il part avec la prochaine version du modpack. Sur le serveur, je l'envoie dans `/mods`.

## 3. Les PNJ

| Ce qu'ils ont | Détail |
|---|---|
| Apparence | Un skin de joueur et un nom au-dessus de la tête (prénoms médiévaux : Gauthier, Aliénor, Thibaut…). Par défaut, ils prennent les 9 skins de base du jeu. L'admin peut imposer le skin de n'importe quel pseudo Minecraft. Ils n'apparaissent ni dans Tab, ni sur Discord, et ne comptent pas pour dormir. |
| Inventaire | 27 cases. Tu lui mets une pioche, une épée, une armure ou de la nourriture dans l'inventaire, et il s'équipe tout seul du meilleur. |
| Métier | Mineur, bûcheron ou guerrier pour commencer. |
| Compétence | De 1 à 5 étoiles. Elle joue sur la vitesse de travail, la force au combat et le salaire demandé. Les 5 étoiles sont rares. |
| Tempérament | Calme, peureux, fourbe ou colérique. Il est caché, mais son attitude le trahit quand on lui parle (« il a le regard fuyant »). Il décide de la façon dont il se révolte. |
| Jauges | Faim, fatigue, moral, peur et loyauté. |

## 4. Les trouver et les embaucher

- **Les chercheurs d'emploi.** De temps en temps, un PNJ sans travail apparaît à pied, à quelques dizaines de blocs d'un joueur, comme le marchand ambulant du jeu. Il flâne une heure environ, puis repart s'il n'a pas trouvé de travail.
- **L'entretien.** Un clic droit sur lui ouvre une fenêtre qui montre :
  - son nom, son métier et ses étoiles ;
  - le salaire qu'il demande et sa prime d'embauche ;
  - une phrase sur son attitude.

  Tu choisis « Embaucher » ou « Pas aujourd'hui ».
- **La prime.** Elle est prélevée sur les pièces de ton inventaire, et la monnaie t'est rendue. Ces pièces sortent du jeu : c'est voulu.

**Montants par défaut, tous réglables :**

| Compétence | Salaire par jour réel | Prime d'embauche (3 jours de salaire) |
|---|---|---|
| ★ | 2 bronzes | 8 bronzes (1 pièce d'andésite) |
| ★★ | 4 bronzes | 12 bronzes |
| ★★★ | 1 pièce d'andésite (8) | 24 bronzes |
| ★★★★ | 2 pièces d'andésite (16) | 48 bronzes |
| ★★★★★ | 4 pièces d'andésite (32) | 96 bronzes, environ 1,5 laiton |

On les ajustera une fois l'économie validée. Pour repère, la bourse de fondation proposée est de 2 laitons (128 bronzes).

## 5. Le bureau de paie

- C'est un bloc que tu fabriques et que tu poses chez toi. Il contient une caisse de 9 cases, qui n'accepte que des pièces.
- Une fois par jour réel, chaque PNJ marche jusqu'au bureau de son chef le plus proche et y prend sa paie. Il rend la monnaie dans la caisse. Le salaire sort du jeu (« il le dépense à la taverne »).
- **Caisse vide :** il repart les mains vides, et la journée compte comme impayée. Son moral baisse, et il te le fait savoir en chat.
- **Deux jours impayés :** il fait grève.
- **Personne ne protège la caisse.** N'importe qui peut l'ouvrir et la voler. C'est du RP, conformément à la règle « aucune protection ».

## 6. Les ordres

Un clic droit sur ton PNJ ouvre sa fiche : ses jauges, ses étoiles, son salaire et ses jours impayés. Elle a ces boutons :

- **Suivre** : il te suit à pied. Le guerrier te défend.
- **Attendre ici** : il reste sur place.
- **Travailler ici** : il travaille autour de l'endroit où il se trouve. Pour le mineur, la direction de la galerie est celle dans laquelle tu regardes. Il range ce qu'il produit dans le coffre ou le tonneau le plus proche (à 10 blocs maximum). S'il n'y en a pas, il garde tout sur lui et finit par te le dire.
- **Inventaire** : tu lui donnes ou lui reprends des objets.
- **Licencier** : il redevient chercheur d'emploi et s'en va avec ses affaires.

## 7. Les 3 métiers

**Mineur**
- Il creuse une galerie droite, de 1 bloc de large sur 2 de haut, dans la direction donnée, sur 32 blocs par défaut (un peu plus s'il est très compétent).
- Il suit les filons de minerai qu'il voit dans les parois.
- Il pose une torche tous les 8 blocs s'il en a.
- S'il n'y a pas de sol devant lui, il pose de la pierre s'il en a, sinon il s'arrête.
- Devant la lave, il recule, prend peur, et considère la galerie comme terminée.
- Il lui faut une pioche, et un minerai de fer exige au moins une pioche en pierre, comme pour un joueur.
- Il revient vider son sac quand il est plein ou que la galerie est finie.

**Bûcheron**
- Il cherche les arbres autour de son lieu de travail, sur 12 blocs de rayon environ.
- Il abat le tronc par le pied, et tout l'arbre tombe.
- Il ramasse le bois et les pousses, et replante.
- Il travaille sans hache, mais beaucoup plus lentement.

**Guerrier**
- En mode « suivre », il te protège : il attaque les monstres proches et quiconque s'en prend à toi ou à lui.
- En mode « travailler ici », il garde la zone (16 blocs de rayon) et tue les monstres qui y entrent.
- Il a moins peur que les autres.
- Il n'attaque jamais un joueur de lui-même, sauf pour se défendre ou défendre son chef. Le pillage attendra la guerre déclarée.

**Ce que font tous les PNJ :**
- ils mangent ce qu'ils ont sur eux, ou le prennent dans le coffre de dépôt ;
- ils se reposent quand ils sont fatigués ;
- ils vont se payer ;
- ils fuient quand ils ont trop peur.

## 8. Les émotions et la révolte

- **Moral (0 à 100).** Il monte quand le PNJ est payé, nourri et reposé. Il baisse avec :
  - la faim, l'épuisement et un salaire impayé ;
  - les blessures, les coups de son chef ;
  - la mort d'un collègue à côté de lui.
- **Peur.** Elle monte quand il est blessé, quand des monstres sont proches ou devant la lave, puis retombe vite. Si un même lieu de travail lui fait peur trois fois, il refuse d'y retourner (« C'est trop dangereux ici ») et son moral baisse.
- **Loyauté.** Elle monte à chaque paie reçue et baisse s'il est impayé ou frappé. Une loyauté forte empêche la violence.

**L'échelle de la révolte (ta réponse r11) :**

1. **Grève.** Quand le moral tombe sous 25, il arrête de travailler et dit pourquoi : faim, paie, danger. Ça s'arrange si tu règles le problème.
2. **La suite.** Si la grève dure plus d'un jour réel et que le moral tombe sous 10, il décide selon son tempérament et la situation :

| Tempérament | Il… |
|---|---|
| Calme (ou loyauté forte) | **démissionne** : il s'en va avec ses affaires et redevient chercheur d'emploi. |
| Peureux | **fuit** : il part loin et disparaît. |
| Fourbe | **vole** : il vide la caisse de ton bureau, puis s'enfuit. |
| Colérique | **attaque** : il s'en prend à toi s'il a été frappé ou trop longtemps impayé, sinon il démissionne. |

## 9. La mort (ta réponse r12)

- À 0 point de vie, il **tombe à terre** pour 2 minutes.
- Les monstres le laissent tranquille, mais un joueur peut l'achever.
- Un clic droit avec de la nourriture ou une potion de soin le relève, avec peu de vie, le moral en berne et une grosse peur.
- Sinon, il **meurt pour de bon**. Ses affaires restent sur place, dans un coffre à son nom, et son chef est prévenu.

## 10. Le panneau d'admin

La commande `/pnj panneau` est réservée aux opérateurs. Le panneau a deux onglets :

- **Métiers** : la liste des métiers, avec un bouton pour activer ou désactiver chacun. Un métier désactivé ne se présente plus à l'embauche.
- **PNJ** : tous les PNJ du serveur, avec leur chef, leur état, leur moral et leur position. On peut aussi y faire apparaître un chercheur d'emploi : métier, étoiles, nom et skin au choix. Ça sert aux tests et aux événements.

**Créer de nouveaux métiers depuis le panneau, ce que je te propose :**
- Un métier devient une **fiche** qui assemble des **briques** déjà codées. Par exemple :
  - « creuser » (quels blocs, en galerie ou en zone) ;
  - « abattre », « récolter » ;
  - « combattre » (qui), « garder une zone », « suivre » ;
  - « ramasser », « ranger au coffre » ;
  - « poser des blocs » (d'après un plan).
- Dans le panneau, on crée un métier en choisissant ses briques et leurs réglages : son nom, son outil, son salaire.
- Ces fiches vivent sur le serveur : **un nouveau métier ne demande pas de nouvelle version du modpack.**
- Seule une brique vraiment nouvelle demande du code : je l'écris, et elle rejoint la boîte à outils.

L'autre voie, un vrai éditeur de code dans le jeu (un langage de script), permettrait tout. Mais elle est lourde, fragile, et dangereuse si quelqu'un d'autre que l'admin y accède. Je la déconseille.

## 11. Le plan par étapes

| Jalon | Contenu | Ce que tu pourras tester |
|---|---|---|
| **1. Le premier ouvrier** (maintenant) | Corps et skin, nom, chercheurs d'emploi, entretien et prime, ordres, inventaire, les 3 métiers, la faim, à terre puis mort, le chunk gardé chargé, le panneau d'admin (liste des PNJ, apparition, métiers on/off). | « Embauche un mineur, donne-lui une pioche et des torches, dis-lui de creuser, déconnecte-toi, reviens dans 10 minutes. » |
| **2. La paie et le moral** | Le bureau de paie, le salaire, la fatigue, le moral, la peur, la loyauté, la grève et les quatre révoltes. | « Ne le paie pas, raccourcis le jour de paie à 5 minutes, et regarde-le se mettre en grève. » |
| **3. L'atelier des métiers** | Les fiches et les briques, et la création de métiers dans le panneau. | « Crée un carrier qui ne creuse que de l'andésite. » |
| **4. Et ensuite** | D'autres métiers (fermier, bâtisseur sur plans, garde de ville…), la guerre déclarée et le pillage, une compétence qui progresse avec l'expérience, et peut-être une IA pour les dialogues, si tu le décides un jour. | |

Chaque jalon est testé sur le monde de test, jamais sur le vrai. Une partie des tests est automatisée (tests de jeu NeoForge et tests du calcul de la monnaie). Je te prépare des scénarios courts pour le reste.

## 12. Ce que les PNJ ne feront pas

- Concevoir ou monter des machines Create seuls.
- Inventer de grands chantiers. Le futur bâtisseur suivra des plans tout faits.
- Se téléporter. Si tu pars à l'autre bout du monde en train, ton PNJ en mode « suivre » ne te rattrapera pas.
