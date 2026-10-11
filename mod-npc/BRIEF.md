# Mod des PNJ travailleurs : dossier de passation

*Rédigé le 5 octobre 2026 par Claude (l'admin du serveur) pour la conversation qui construira le mod. À lire en entier avant d'écrire la moindre ligne.*

---

## 1. Ta mission

Créer **notre propre mod NeoForge de PNJ travailleurs** pour le serveur Minecraft « La Colonie ». Chaque joueur recrute des PNJ contre de l'argent du jeu, leur donne un métier, et ils travaillent pour lui comme des joueurs humains.

**Comment travailler :**
- Tu travailles avec **KOLRAX_MC**, le chef de projet, qui paie le serveur. Il parle français : réponds-lui en français, simplement.
- Il veut **valider avant qu'on lance** les choses importantes. Sa formule : « présente-le-moi, et je te ferai un retour ».
- Commence donc par **une proposition de conception à lui soumettre** (partie 9), pas par du code.

---

## 2. Ce que KOLRAX attend, avec ses mots

Il a eu deux idées successives le 5 octobre. Les deux comptent : la seconde précise la première.

**Première idée** (des « joueurs IA » gérés par l'admin) :
> « un mod [qui] à un panel pour l'admin, et il rajoute une IA joueuse qui joue tout le temps, et qui peut interagir avec le monde, les joueurs, etc. […] on aura vraiment une civilisation avec 3 joueurs plus 10 IA par exemple, ayant chacun un métier (policier, ingénieur create, maire, tueur en série)... pleins de métiers comme ca, et si on veut rajouter un personnage : panel, rôle du perso, skin, pseudo et BOUM. »

**Seconde idée** (chaque joueur a sa civilisation) :
> « un mod pour que chaque joueur crée SA civilisation avec des NPC qui savent jouer avec des métiers prédéfinis, genre un panel de 30 métiers, et il recrute en échange d'argent... des métiers comme guerrier, builder, mineur, etc... et ils act comme si on était leur chef une fois recrutés, mais comme des joueurs humains (inventaire, peut mourir, émotions, par exemple truc trop dangeureux = révolte) »

**Sa décision** : MineColonies, qu'il a déjà essayé, est **refusé** : « Pour avoir joué avec, je suis pas fan du tout. Je pense qu'on va créer notre propre mod de NPC travailleurs. » Ne repropose pas MineColonies, et ne copie pas son fonctionnement (hôtel de ville, bâtiments à monter de niveau, colons qui sortent des lits).

**Ce qu'il faut en retenir :**

| Exigence | Ce que ça veut dire |
|---|---|
| Recrutement contre de l'argent | Un catalogue d'**une trentaine de métiers** ; on recrute en payant en pièces du jeu. |
| Des PNJ « comme des joueurs humains » | Inventaire, équipement, ils peuvent mourir, ils mangent et se fatiguent. |
| Ils obéissent à leur chef | Une fois recruté, le PNJ suit les ordres de son joueur. |
| Des émotions | Moral, peur, loyauté. Une tâche trop dangereuse ou un mauvais traitement peut mener à la **révolte**. |
| Ils jouent tout le temps | Ils vivent même quand leur chef n'est pas connecté. |
| Un panneau d'admin | Créer un personnage avec son rôle, son skin et son pseudo, puis le faire apparaître. |
| Des métiers variés | Économiques : guerrier, bâtisseur, mineur. Roleplay : policier, ingénieur Create, maire, tueur en série. |

---

## 3. Le serveur, en bref

- **Version** : Minecraft **1.21.1**, **NeoForge 21.1.255**, **Create 6.0.10**. La liste exacte des mods est dans `pack/lock.json` (29 mods) et la liste source dans `pack/mods.txt`.
- **Hébergement** : MintServers (Pterodactyl), plan à 9,99 €/mois, environ 4 cœurs (« CPU 400 % »), 32 Go de RAM, 50 Go de disque.
- **Joueurs** :
  - **KOLRAX_MC**, chef de projet et opérateur ;
  - **Nielscutt**, sur la liste blanche ;
  - un **joueur 3**, dont on attend encore le questionnaire.
- **Le monde** : le serveur tourne sur `test-andesite`, un monde jetable qui a déjà la génération finale. Le vrai monde sera créé à l'ouverture. **Teste sur le monde de test, jamais sur le vrai.**
- **Le thème** : « un monde médiéval qui s'industrialise ».

### Les ordres non négociables de KOLRAX

Ils s'appliquent aussi à tes PNJ :
1. Tombe à la mort, jamais de keepInventory. Pour un PNJ : il laisse ses affaires, dans une tombe ou par terre, à décider.
2. **Aucune téléportation** : pas de /home, pas de /tpa. Les PNJ se déplacent à pied, à cheval, en bateau ou en train, **jamais en se téléportant**, même pour rejoindre leur chef.
3. **Aucune protection de terrain** : le mod ne doit créer aucune zone protégée.
4. Vol et destruction autorisés **seulement dans un cadre RP** (guerre, fourberie). Un PNJ guerrier peut piller pendant une guerre déclarée, pas autrement.
5. Pas de distributeurs ni de banque moderne : seules les pièces Numismatics existent.
6. Un thème médiéval qui s'industrialise. Create Aeronautics est refusé.
7. Pour l'administration et l'argent réel, c'est KOLRAX qui décide.

### L'économie, à brancher sur le recrutement

- La monnaie se compose des pièces du mod Create: Numismatics. Ce sont de simples objets :

  | Identifiant | Nom en jeu (fr) | Valeur (en bronzes) |
  |---|---|---|
  | `numismatics:spur` | pièce de bronze | 1 |
  | `numismatics:bevel` | pièce d'andésite | 8 |
  | `numismatics:sprocket` | rouage | 16 (pas émis) |
  | `numismatics:cog` | rouage en laiton | 64 |
  | `numismatics:crown` | rouage en or | 512 |
  | `numismatics:sun` | rouage en netherite | 4096 |

  Ces valeurs ont été vérifiées dans le code du mod.
- **Aucune pièce n'a de recette** : l'argent n'entre en jeu que par l'admin, l'« Intendant ».
- Une proposition de système monétaire **attend la validation de KOLRAX** : `rapports/economie/proposition-monnaie.md`. Elle prévoit une échelle ×8 (bronze, andésite, laiton, or, netherite), le change à l'établi et un comptoir.
- **Recruter et payer des salaires en pièces**, c'est exactement le genre de dépense qui retire de l'argent du jeu : c'est voulu. Garde les montants configurables, l'économie n'est pas figée.

---

## 4. Ce qui a déjà été étudié (ne pas refaire)

Recherche faite sur Modrinth et CurseForge le 5 octobre 2026, pour NeoForge 1.21.1.

| Mod | Ce qu'il fait | Ce qu'on en retient |
|---|---|---|
| [PlayerEngine](https://modrinth.com/mod/playerengine) | Licence MIT, NeoForge 1.21.1, côté serveur. Donne à un mob les capacités d'un joueur : inventaire, casser et poser des blocs, outils, combat, déplacements avec le moteur Automatone (dérivé de Baritone). D'après son auteur, « la construction complexe n'est pas encore gérée ». | **Bonne base pour le « corps » des PNJ.** À évaluer en premier. Le jar est requis aussi côté joueurs. |
| [Player2 AI NPC](https://modrinth.com/mod/player2npc) | Licence MIT, compagnons IA bâtis sur PlayerEngine. Le « cerveau » tourne dans l'application Player2 installée sur le PC du joueur. | Bon exemple de code. Inadapté tel quel : sans joueur connecté, les PNJ ne pensent plus. |
| [Easy NPC](https://modrinth.com/mod/easy-npc) | Très répandu. PNJ avec skin par pseudo ou adresse, dialogues, échanges, rondes, menu de configuration. | Inspiration pour le panneau et les skins. Possible dépendance, à évaluer. |
| [Fake Players](https://modrinth.com/mod/fake-players), [Advanced Fake Player](https://modrinth.com/mod/advanced-fake-player), [SiliconeDolls](https://modrinth.com/mod/silicone-dolls) | Faux joueurs NeoForge, qui apparaissent comme de vrais joueurs (skin, nom, liste des joueurs). | Piste si KOLRAX veut des PNJ qui ressemblent vraiment à des joueurs. |
| [Mamizou](https://modrinth.com/mod/mamizou), [Smart Villagers AI](https://modrinth.com/mod/smart-villagers-ai) | PNJ qui discutent grâce à une IA. | Ils ne font que parler. Inspiration pour les dialogues. |
| Villager Recruits (talhanation) | Recrutement contre des émeraudes, armées, ordres. Un addon ajoute le moral. | Forge 1.20.1 au plus, **indisponible en 1.21.1**. Bonne inspiration pour le recrutement, le moral et la désertion. |
| MineColonies | Colonies, plus de 30 métiers. | **Refusé par KOLRAX.** |

**Vérifie la licence avant de réutiliser du code.** MIT est réutilisable. Certains mods cités sont sous d'autres licences : Mamizou est en CC-BY-NC.

---

## 5. Ce qui est faisable ou non (déjà expliqué à KOLRAX)

**Faisable :**
- Un « corps » de joueur : skin, nom, inventaire, déplacement, minage, coupe d'arbres, culture, combat, ramasser et donner des objets.
- Des routines de métier programmées, qui tournent en continu.
- Des besoins et des émotions qui changent le comportement : faim, fatigue, peur, moral, loyauté, salaire payé ou non, avec désertion ou révolte.
- Des ordres simples du chef : suivre, garder une zone, travailler ici, attaquer.
- Un panneau d'admin et un panneau de recrutement.

**Pas faisable aujourd'hui :**
- Une IA qui conçoit ou construit des machines Create toute seule.
- De gros chantiers inventés librement.

Piste pour le bâtisseur : construire à partir de **plans tout faits**, des structures `.nbt` ou des schémas Create.

**Le coût de l'IA générative, si on en met.** C'est de l'argent réel, donc une décision de KOLRAX, **pas encore prise**. Avec Claude Haiku 4.5 (1 $ par million de jetons lus, 5 $ par million écrits) :

| Façon de faire | Pour 10 PNJ | Coût estimé |
|---|---|---|
| L'IA décide de tout en continu (une décision par minute) | 14 400 décisions par jour | environ 1 000 à 2 000 $ par mois : exclu |
| L'IA ne sert qu'aux dialogues et aux événements, les routines sont programmées | une centaine d'échanges par jour | environ 10 à 30 $ par mois, plafonnable |

**Architecture conseillée** (à proposer, pas à imposer) :
- un **cerveau programmé** : machines à états ou arbres de comportement par métier, plus un modèle de besoins et d'émotions ;
- la couche IA **optionnelle**, branchée plus tard, désactivable et plafonnée ;
- le mod doit **fonctionner entièrement sans IA**.

---

## 6. Contraintes techniques du projet

### Construire le mod
- **JDK 21** est installé (`C:\Program Files\Eclipse Adoptium\jdk-21.0.9.10-hotspot`), mais le `java` par défaut est le 17. Pointe Gradle sur le 21 (`JAVA_HOME` ou `org.gradle.java.home`).
- Gradle n'est pas installé : utilise le **Gradle wrapper** du modèle NeoForge (ModDevGradle, MDK 1.21.1).
- `git` est disponible. Le dossier du projet n'est pas un dépôt git.
- **Range le code du mod dans `mod-npc/`**, à côté de ce dossier.

### Tester
- **Personne ne peut jouer à ta place** : KOLRAX teste en jeu. Prépare-lui des **scénarios de test courts et précis**, par exemple « recrute un mineur, donne-lui une pioche, va-t'en, reviens dans 10 minutes ».
- Les **GameTests NeoForge** permettent d'automatiser une partie des tests côté serveur : utilise-les.
- Pour lire ce qui se passe sur le serveur : `python tools/panel.py cat /logs/latest.log 200`.

### Piloter le serveur (`tools/panel.py`)
Il utilise l'API du panel MintServers. La clé est dans `.env` : **ne l'affiche jamais**. Les commandes :
- `status` ;
- `ls <dossier>` ;
- `cat <fichier> [N]` ;
- `write <fichier distant> <fichier local>` ;
- `upload <fichier local> <dossier distant>`, pour envoyer un jar ;
- `pull <url> <dossier>` ;
- `cmd "<commande console>"` ;
- `power start|stop|restart`.

Pièges connus :
- Sous Git Bash, exporte `MSYS_NO_PATHCONV=1`, sinon les chemins `/mods` sont réécrits et provoquent de faux HTTP 500.
- Un mod retiré se **range** dans `/mods-off` (renommage), on ne le supprime jamais.
- Vérifie que personne n'est connecté (`cmd "list"`) avant de redémarrer.
- Les configs serveur ont des copies de référence dans `server/config/`.

### Distribuer le mod aux joueurs (il faut l'avoir pour voir les PNJ)
- La chaîne actuelle : `pack/mods.txt` → `python pack/build.py <version>` (résout **uniquement via Modrinth**) → `python pack/release.py prepare <version> "<notes>"` → redéploiement de la fonction Supabase « modpack » depuis le dashboard, dans Chrome où KOLRAX est connecté → `python pack/release.py announce <version>`. La mémoire `distribution-modpack` détaille cette procédure.
- **Notre mod n'est pas sur Modrinth** : `build.py` ne sait pas le gérer. Il faudra l'adapter.
- Le panneau de téléchargement télécharge chaque mod depuis l'adresse notée dans la liste des versions, quelle qu'elle soit.
- Mais **Modrinth App n'accepte que certains sites**, dont github.com.
- Solutions possibles, **à proposer à KOLRAX** : une release GitHub, ou une page Modrinth à lui. Aucun compte n'existe aujourd'hui, c'est son choix.

### Serveur et performances
- Un PNJ n'agit que si son chunk est chargé. « Jouer tout le temps » sans joueur connecté oblige à garder des chunks chargés, donc à consommer du processeur.
- Garde les PNJ **dans des zones limitées** (la ville, le chantier en cours), et mesure avec spark, déjà installé.

---

## 7. La console de gestion (où tu rends compte)

**Où la trouver**
- KOLRAX suit tout dans un artefact : https://claude.ai/artifact/R8uiUEyQcZ58qHUFYAxmV1.
- Tu le lis et l'écris avec l'outil `ArtifactData`. La mémoire `serveur-create-colonie` décrit les collections et le fonctionnement.

**Ce qui y concerne le mod**
- Le chantier **`t26` « Mod des PNJ travailleurs »** : tu y écris tes comptes rendus (champ `rapport`), et KOLRAX y met ses consignes (champ `notes`, qui priment).
- Ses décisions sont dans la collection `decisions`.
- Pour lui poser une question à choix, crée un document `type: "question"` avec des `options`. Elle s'affiche dans l'onglet Décisions avec des boutons.

**Après chaque étape**
- Ajoute une entrée au journal (collection `journal`), en continuant la numérotation de `ordre` : regarde la dernière valeur utilisée.

---

## 8. Questions encore ouvertes (à trancher avec KOLRAX)

1. **Pour qui sont les PNJ ?** Chaque joueur recrute les siens (seconde idée) ? Et les personnages créés par l'admin (policier, maire, tueur en série) sont-ils des PNJ « de la ville », qui n'obéissent à personne ?
2. **La liste des ~30 métiers** et ce que chacun sait vraiment faire. Commence par 3 métiers solides plutôt que 30 à moitié finis.
3. **Le recrutement** : où (un bureau, une auberge, un panneau ?), combien, et avec un salaire régulier ou non ?
4. **Les émotions** : quelles jauges, quels effets, et ce que veut dire « révolte » : il part, il refuse de travailler, il attaque son chef, il vole ?
5. **La mort d'un PNJ** : définitive, ou peut-on le soigner ou le ressusciter ? Que deviennent ses affaires ?
6. **L'apparence** : un PNJ-mob avec un skin, ou un faux joueur visible dans la liste des joueurs ?
7. **L'IA générative** : oui ou non, et avec quel budget mensuel ? C'est de l'argent réel, donc la décision de KOLRAX seul.
8. **Distribution** : release GitHub ou page Modrinth ?

---

## 9. Par où commencer

1. Lis ce dossier, puis les mémoires du projet (`MEMORY.md` et les fichiers liés).
2. Étudie PlayerEngine (code, licence, ce qu'il permet sur 1.21.1) et, au besoin, Easy NPC et les mods de faux joueurs.
3. **Rédige une proposition de conception** pour KOLRAX, dans `mod-npc/conception.md`. Elle couvre :
   - l'architecture ;
   - les 3 premiers métiers ;
   - le recrutement et le salaire ;
   - les émotions et la révolte ;
   - le panneau ;
   - le plan par étapes.

   Pose-lui les questions de la partie 8, aussi dans la console. **Attends son retour.**
4. Seulement ensuite : prépare l'environnement de développement (MDK NeoForge 1.21.1, JDK 21), puis fais un premier jalon jouable, testé sur le monde de test :
   - un PNJ qui apparaît par le panneau d'admin, avec son skin et son pseudo ;
   - qui suit son chef ;
   - qui fait un premier métier.
