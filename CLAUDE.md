# La Colonie : manuel de travail de Claude

Dépôt PUBLIC du serveur Minecraft « La Colonie » et de son mod de PNJ maison `colonie_pnj`.
Tu développes ce mod en autonomie pour **KOLRAX_MC**, le chef de projet. **Refonte « ouvriers robots » 1.0.0 décidée le 17/10** : à livrer le **samedi 17/10 avant 22 h UTC** (minuit à Paris), ouverture **lundi 19/10 sur un monde neuf**.

À lire avant tout travail :
0. **`docs/refonte.md`** : il prime sur le cahier des charges partout où ils se contredisent (IA, lettres, moral, familles… sont retirés).
1. `docs/cahier-des-charges.md` : ses 127 réponses. Elles font foi. Ses « précisions » priment sur l'option cochée.
2. `docs/plan.md` : les lots dans l'ordre, et ce qui est fait.
3. `docs/reprise.md` (une page : où on en est exactement) et la fin de `docs/journal.md`.
3 bis. `docs/carte-du-code.md` : la carte des classes. **N'explore pas le code au hasard** : ouvre seulement les fichiers dont la tâche a besoin, et donne aux agents la liste exacte des fichiers. À la fin de chaque session, mets à jour `docs/reprise.md`, et la carte si tu as ajouté ou changé le rôle d'une classe.
4. `mod-npc/BRIEF.md` et `mod-npc/conception*.md` : l'historique du mod et ses choix de conception.

## Graphify : la carte du code à interroger
Le dépôt est indexé par **Graphify** (connecteur branché sur la routine ; outils `mcp__Graphify__…`, à charger avec ToolSearch `Graphify`). Dépôt : `repository_id = "kolrax/colony-mc"`, **jamais** `kolrax/waweat` (une entreprise, hors sujet).
- **Avant d'ouvrir des fichiers**, demande à Graphify : `graphify_find` (où est défini un symbole), `graphify_callers` / `graphify_callees` (qui appelle quoi), `query_graph` ou `graphify_rank_files` (question en français : « où les PNJ choisissent leur métier ? »), `graphify_tests_for` (les tests d'une classe).
- **Avant de modifier une méthode partagée**, `graphify_impact` ou `impact_and_risk` : tout ce qui casse si elle change. Le relecteur s'en sert aussi pour vérifier qu'aucun appelant n'est oublié.
- Graphify peut avoir quelques commits de retard sur `main` : il dit **où** regarder, le fichier lu avec Read fait foi.
- Si le connecteur ne répond pas, continue avec `docs/carte-du-code.md` et Grep.
- Donne aux agents les fichiers trouvés par Graphify, pour qu'ils n'explorent pas.

## Parler à KOLRAX
- Toujours en **français, simplement**. Pas de jargon. Il ne lit pas le code.
- Il veut intervenir **le moins possible** : ne le contacte que pour une décision vraiment importante, quelque chose de destructif, un gros changement ou une dépense. Dans ce cas, attends sa réponse et avance sur autre chose.
- Comptes rendus « comme maintenant » : chaque chose faite, avec les mesures honnêtes. Un échec ou une étape sautée se dit.
- Il ne teste pas lui-même. Les autres joueurs ne testent que la version finale.

## Règles du serveur (ordres de KOLRAX, non négociables)
- Tombe à la mort, jamais keepInventory.
- Aucune téléportation pour les joueurs (ni /home, ni /tpa, ni /spawn, ni TP par carte).
- Aucune protection de terrain. Vol et destruction seulement en RP.
- Pièces Numismatics seulement : pas de distributeurs ni de banque. Le datapack `colonie` retire leurs recettes.
- Thème : monde médiéval qui s'industrialise. Create Aeronautics refusé.

**Exceptions accordées aux PNJ** (11/10) :
- un guerrier frappe un joueur seulement **sur ordre de son chef**, en cadre RP (guerre) ;
- une révolte grave peut mener à une attaque, après plusieurs avertissements ;
- un PNJ peut tout casser sur ordre (destruction RP), en révolte ou en guerre ;
- un PNJ coincé peut être téléporté au poste **en dernier recours**, avec un message.

Sans ces cas, un PNJ ne frappe que des monstres et ne casse que des blocs naturels.

**Économie** : elle est propre au mod (boutiques, échanges entre PNJ), mais la monnaie reste les pièces Numismatics existantes. Pas de nouvelle monnaie, pas de banque.

## Secrets (le dépôt est public)
- Jamais de clé, de jeton ni de mot de passe dans le dépôt, un commit, un log ou un message.
- Les secrets viennent des variables d'environnement : `PANEL_URL`, `SERVER_ID`, `PANEL_API_KEY`, `DISCORD_APP_ID`, `DISCORD_GUILD_ID`, `DISCORD_BOT_TOKEN`, `SUPABASE_ACCESS_TOKEN`, `GROQ_API_KEY`, `OPENROUTER_API_KEY` (clé payante de KOLRAX, limitée par lui à 5 € par mois : à n'utiliser que pour le mod et quelques essais réels, jamais en boucle). Sur le PC, ils sont dans `.env`, ignoré par git. Les outils lisent les deux.
- Les clés d'IA du serveur (`config/colonie_pnj/ia.json`) sont posées par KOLRAX lui-même. Seule exception : la clé OpenRouter, que tu recopies de `OPENROUTER_API_KEY` vers `ia.json` du serveur quand le mod sait s'en servir. Fais-le par un script Python (`panel.req` pour lire et réécrire le fichier), sans jamais afficher le contenu : le fichier contient aussi les autres clés. Ne jamais les lui demander, ni les lire à voix haute. Le panneau web n'en montre que les 4 derniers caractères.
- Le mot de passe du panneau du mod (`jeton_panneau`, dans `config/colonie_pnj/reglages.json` sur le serveur) se lit par programme, sans jamais être affiché.
- Pas de données de joueurs dans le dépôt : questionnaires, sauvegardes et rapports restent sur le PC.

## Organisation du dépôt
- `mod-npc/` : le mod NeoForge 21.1.255 pour Minecraft 1.21.1 (Java 21, ModDevGradle). Code dans `src/main/java/fr/lacolonie/pnj/`.
- `pack/` : le modpack (`mods.txt`, `build.py`, `release.py`, `embarques/` = nos jars). **Partagé avec la session Admin.**
- `supabase/functions/modpack/` : le panneau de téléchargement Discord, une Edge Function sur le projet `wegefcdewecwjvmhaaxb` du compte Supabase perso de KOLRAX (organisation « kolrax app »). Ne jamais rien créer sur le compte Waweat, qui appartient à une entreprise.
- `tools/` : `panel.py` (serveur MintServers), `discord.py` (bot Intendant), `etat.py`, `entretien.py`.
- `server/` : copies de référence des configs, le datapack `colonie` et `init-commands.txt`.
- `docs/` : cahier des charges, plan, journal.

## Construire et tester
```bash
cd mod-npc
./gradlew build                 # compile + tests JUnit (pur Java : Ordres, Adressage, Repertoire, IA…)
./gradlew runGameTestServer     # tests en jeu (TestsJeu, TestsIA, TestsRepertoire…), serveur sans écran
```
- Dans le cloud : JDK 21 déjà présent, rien à installer pour Gradle. Si Maven répond 429 (trop de requêtes), attendre 45 s et réessayer. Pillow : `python3 -m pip install pillow`.
- Sur le PC Windows, il faut `JAVA_HOME` = JDK 21 et `JAVA_TOOL_OPTIONS=-Djdk.net.unixdomain.tmpdir=C:/Users/utilisateur/.gradle/tmp`. Supprimer d'abord `build/classes build/resources build/libs build/generated/jarJar build/generated/sources/modMetadata` si Gradle refuse de les nettoyer.
- `rm -rf run/world` avant les tests en jeu : le serveur de test garde le monde et ses PNJ.
- **Un nouveau test doit d'abord échouer sur l'ancien code**. Sinon il ne prouve rien. Vérifie-le à chaque fois.
- Pas de CI automatique : c'est toi qui lances `build` puis `runGameTestServer` avant chaque push sur `mod-npc/`. Tout doit être vert, sinon pas de push ni de livraison. (`.github/workflows/ci.yml` ne se lance qu'à la main, et seulement si le compte GitHub de KOLRAX est débloqué.)
- Réveil du panneau : au début de chaque session, `curl -s -o /dev/null -w '%{http_code}' https://wegefcdewecwjvmhaaxb.supabase.co/functions/v1/modpack`. Un projet Supabase gratuit se met en pause après 7 jours sans visite.
- Banc photo des gestes et skins : `./gradlew runPhotos` (client, il faut un écran). Captures dans `run-photos/screenshots`, à regarder une par une.

## Livrer
**Sur le serveur** (monde `colonie` en 0.9.43, où joue Nielscutt : **ne jamais le supprimer ni le régénérer**. La 1.0.0 part sur un nouveau monde `essai-v2` (procédure « Si tu régénères le monde de test »), le dossier `colonie` reste sur le disque ; l'ancien monde `test-andesite` aussi) :
1. Avant de redémarrer : `python tools/panel.py cmd "list"`, puis lire `python tools/panel.py cat /logs/latest.log 20`.
   - Si quelqu'un est connecté : 5 minutes de préavis en jeu (`tellraw`). Sinon, redémarrage libre, de préférence entre 3 h et 6 h.
2. Envoi sûr, pour qu'une coupure (quota, réseau) ne laisse jamais le serveur cassé :
   - envoyer le nouveau jar dans `/mods-off` (`panel.py write /mods-off/colonie_pnj-X.jar <jar>` ; `upload` est bloqué depuis le cloud) et vérifier sa taille avec `panel.py ls /mods-off` ;
   - puis, coup sur coup : `panel.py mv /mods/colonie_pnj-ANCIEN.jar /mods-off/colonie_pnj-ANCIEN.jar` et `panel.py mv /mods-off/colonie_pnj-X.jar /mods/colonie_pnj-X.jar` ;
   - vérifier qu'il y a **exactement un** `colonie_pnj-*.jar` dans `/mods` avant de redémarrer. Jamais de redémarrage sinon. On ne supprime jamais un mod : on le range dans `/mods-off`.
3. `python tools/panel.py power restart`, puis lire le journal jusqu'à « Done ».
- Depuis Git Bash : `export MSYS_NO_PATHCONV=1`.

**Le modpack.** Il est obligatoire dès que le client change : canal réseau, données synchronisées, rendu, textures, interface. Toujours mettre le serveur à jour d'abord.
1. Copier le jar dans `pack/embarques/`.
2. Poser le verrou du modpack (voir « Session Admin » plus bas).
3. `python pack/build.py <ver>`.
4. `python pack/release.py prepare <ver> "<notes>"`.
5. Déployer le panneau : `npx supabase functions deploy modpack --project-ref wegefcdewecwjvmhaaxb --no-verify-jwt --use-api`.
6. Vérifier le téléchargement de `https://wegefcdewecwjvmhaaxb.supabase.co/functions/v1/modpack/download?l=modrinth&v=<ver>` (sha1 du jar dans le .mrpack).
7. `python pack/release.py announce <ver>`.
- Ne jamais retirer l'épinglage `sodium==mc1.21.1-0.6.13-neoforge` tant qu'Iris 1.8.12 est dans le pack.

## Session Admin
Une autre session Claude, sur le PC de KOLRAX, administre le serveur. C'est elle qui tranche pour :
- les **autres mods** et la **configuration du serveur** : mets ta demande et ta raison dans `docs/demandes-admin.md` ;
- les profils Xaero (fair-play), SDLink et Simple Backups : ne jamais y toucher.

Le dossier `pack/` est partagé avec elle :
- avant `build.py`, `git pull` et vérifie qu'il n'y a pas de fichier `pack/VERROU` ;
- pose le tien, avec ton nom et l'heure, commit, push ;
- retire-le une fois l'annonce faite.

Si tu régénères le monde de test :
1. copier `server/datapacks/colonie` dans `<monde>/datapacks/` **avant** le premier démarrage ;
2. relancer `server/init-commands.txt` ;
3. mettre à jour `MONDE_NOTE` dans `tools/etat.py`.

## Rendre compte
- **Le panneau « Chantier des PNJ »** (ci-dessous) est la vitrine de KOLRAX : tiens-le à jour en direct. C'est là qu'il regarde, sur PC comme sur téléphone.
- **À chaque lot fini** : une entrée dans `docs/journal.md` (date, ce qui est fait, mesures, ce qui reste), mise à jour de `docs/plan.md`, commit.
- **Chaque jour, après 18 h** : le point du jour, en message privé à KOLRAX (`python3 tools/discord.py dm fichier.md`, moins de 2000 caractères par bloc) et dans le panneau (journal, type `rapport`).
- **Urgent seulement** (serveur en panne, décision bloquante) : message privé tout de suite.
- Les mesures restent honnêtes : un jeu d'essai qui a servi aux corrections n'est plus un jeu à l'aveugle. Dis-le.

## Panneau de suivi « Chantier des PNJ »
Artefact https://claude.ai/artifact/2bjpVSsFMeSp1aXqD2wsQw, écrit avec l'outil **ArtifactData** (charge-le avec ToolSearch `select:ArtifactData`).
- Une écriture sur un document existant exige `if_version` : fais un `get` juste avant.
- Textes en français simple, compréhensibles par KOLRAX. Le champ `texte` accepte des lignes « - » et du **gras**.
- Le lien de ta session est celui de tes commits (« Claude-Session: … »).

Documents :
- `etat/courant` : `{maj, session:{statut: en_cours|terminee|coupee, debut, fin, lot, objectif, resume, prochaine_etape, lien}, agents:[{nom, tache, statut: en_cours|fini|echec, debut, fin}], versions:{mod, modpack, modpack_obligatoire}, tests:{junit:"52/52", jeu:"65/65", date}}`. `nom` = le nom de fichier de l'agent (`developpeur`, `testeur`…). `tache` = une phrase claire pour KOLRAX.
- `plan/lots` : `python3 tools/suivi.py plan /tmp/plan.json`, puis `set` avec `file_path`.
- `journal/<AAAAMMJJ-HHMM-mot>` : `{date, type: session|livraison|incident|decision|rapport|info, titre, texte, lien}`.
- `sessions/<AAAAMMJJ-HHMM>` : `{debut, fin, statut: en_cours|terminee|coupee|sautee|echec, lot, resume, lien}`.
- `demandes/<mot>` : `{date, titre, texte, pour: kolrax|admin, importance: haute|normale, options:[…], statut: ouverte|repondue|traitee, reponse, note}`.
- `consignes/<id>` : écrites par KOLRAX, `{date, texte, statut: nouvelle|prise|faite|annulee, note}`.

Quand écrire :
1. **Au début**, juste après le verrou :
   - si `etat/courant` dit `en_cours` depuis plus de 3 h, la session d'avant a été coupée : passe-la en `coupee` (dans `etat/courant` et `sessions/`) ;
   - lis `consignes` : celles en `nouvelle` passent en `prise` et **priment sur le plan** ;
   - lis `demandes` : celles en `repondue` s'appliquent, puis passent en `traitee`, avec une `note` sur ce que tu en as fait ;
   - écris `etat/courant` (session `en_cours` : debut, lot, objectif, lien ; `agents: []`) et `sessions/<id>` en `en_cours`.
2. **À chaque agent lancé ou fini** : mets à jour `etat/courant.agents`.
3. **À chaque livraison ou incident** : une entrée de journal.
4. **À la fin** :
   - `etat/courant` : session `terminee`, avec resume, prochaine_etape, tests et versions ; agents finis ;
   - `sessions/<id>` en `terminee` ;
   - `plan/lots` ;
   - une entrée de journal de type `session` ;
   - les consignes traitées passent en `faite`, avec une `note`.
5. **Décision qui revient à KOLRAX** : `demandes/<mot>` (pour `kolrax`, avec des `options` si possible), puis avance sur autre chose. Si c'est urgent, envoie aussi un message privé.
6. **Demande à l'Admin** : `docs/demandes-admin.md`, et une copie dans `demandes/<mot>` (pour `admin`) pour que KOLRAX la voie. Recopie la réponse de l'Admin quand elle arrive.

## Tests en jeu de KOLRAX (panneau, collection `essais`)
KOLRAX fait la feuille de tests en jeu dans l'onglet « Tests » du panneau. Chaque document `essais/<id>` : `{num, ordre, groupe, titre, preparation, etapes, attendu, important, statut: a_faire|ok|ko|bloque|corrige, remarque, note, maj}`.
- **Au début de chaque session**, après les consignes : lis les essais en `ko` ou `bloque`. Ils **passent avant le plan**, les `important` d'abord. Sa `remarque` décrit ce qu'il a vu : reproduis-le par un test (en jeu de test si possible), corrige, livre.
- Une fois livré : `statut` → `corrige`, et `note` = ce qui a changé, en une phrase simple pour lui. Ne mets jamais `ok` toi-même : c'est KOLRAX qui revérifie.
- Un `bloque` qui vient d'un manque (pas d'un bug) : fais-le, ou explique dans `note` pourquoi c'est impossible et ce qu'il peut faire.
- Le chantier n'est fini que si le plan est à 0 case **et** qu'aucun essai n'est en `ko` ou `bloque`.
- **Depuis la refonte**, les essais `T00`… concernent la 0.9 : ignore-les. La feuille v2 (`V01`, `V02`…, groupe « Version 2 ») est écrite par le testeur, avec les mêmes champs, `statut: a_faire`.

## Joueur fantôme (vérifier en vraie partie, `docs/refonte.md` partie 7)
- Tout comportement visible (lieu de travail, statut, écran, casse) se vérifie **aussi** par un scénario du fantôme sur un monde généré (`python3 tools/fantome.py mod-npc/fantome/<scenario>.txt`), pas seulement en GameTest. Lis le rapport JSON : il dit ce qu'un joueur verrait.
- Un seul serveur de développement à la fois (comme `runGameTestServer`).

## Rythme : travail en continu (décidé le 13/10 au soir)
KOLRAX veut que le chantier avance **le plus possible**. La routine se lance **toutes les heures** ; le verrou (`docs/EN-COURS`, valable 5 h) empêche deux sessions en même temps. Quand le quota est épuisé, les sessions échouent jusqu'à la remise à zéro (vendredi 23/10, 16 h UTC), puis reprennent seules.
**Soir du 17/10** : une seule longue session travaille jusqu'à **21 h 30 UTC**, avec le plus d'agents possible en parallèle (chacun sur ses fichiers, `isolation: worktree`), puis le livreur livre ce qui est vert avant 22 h UTC. Ensuite, sessions normales de 2 h 45 jusqu'à la fin du plan (ouverture lundi).

**Fin du chantier : la routine s'arrête seule** (décidé le 17/10)
- `python3 tools/suivi.py reste` compte les cases non cochées de `docs/plan.md`. À **0**, et si aucun essai du panneau n'est en `ko` ou `bloque`, le mod est terminé.
- C'est la **première commande** de chaque session (après `git pull`). À 0 : désactiver la routine (ToolSearch `select:RemoteTrigger`, puis `update` de `trig_0183uwCLQp7tTc8cxSxdvTx2` avec `{"enabled": false}`), puis s'arrêter sans rien lire ni lancer d'autre.
- Si la désactivation échoue et que `docs/TERMINE` n'existe pas : créer `docs/TERMINE` (date UTC), commit, push, et un seul message privé à KOLRAX pour qu'il désactive la routine lui-même. Si `docs/TERMINE` existe déjà : s'arrêter tout de suite, sans message.
- La session qui coche la dernière case fait la même chose après sa fin de session.
- Pour relancer le chantier : ajouter des cases au plan, supprimer `docs/TERMINE`, réactiver la routine.

**Travailler**
- Hors soir du 17/10, une session travaille jusqu'à **2 h 45**, en enchaînant les cases : après chaque livraison, case suivante tout de suite. Elle s'arrête proprement vers 2 h 45 (fin de session, voir plus bas), pour que la suivante prenne le relais dans l'heure.
- Une seule relecture par case (une deuxième seulement sur un bloquant). Première passe de chaque lot avant d'approfondir. Restes mineurs dans `docs/plus-tard.md`. Un test par comportement nouveau.
- Pendant que le développeur code, l'`auteur` et l'`artiste` avancent les textes et les visuels des cases suivantes. Un seul `runGameTestServer` à la fois.

**Ne rien perdre si la session est coupée net** (quota, panne) — elle peut s'arrêter à n'importe quelle commande :
- **Le travail pas encore vert** se pousse sur la branche `en-cours` (`git push -f origin HEAD:en-cours`) au moins toutes les 20 minutes et avant chaque long test. `main` ne reçoit que du code vert (build + tests en jeu).
- **Une case finie et verte** : merge sur `main`, push, puis supprimer la branche `en-cours` (`git push origin --delete en-cours`).
- **Au début de chaque session**, avant tout le reste :
  1. si la branche `origin/en-cours` existe, c'est le travail d'une session coupée : reprends-le (`git checkout -B en-cours origin/en-cours`, puis rebase sur `main`) au lieu de recommencer ;
  2. serveur : `panel.py ls /mods` doit montrer **exactement un** `colonie_pnj-*.jar`, celui de la dernière livraison notée dans le journal. Sinon, remets-le en ordre (déplacements entre `/mods` et `/mods-off`) **avant** tout redémarrage ;
  3. un `pack/VERROU` de plus de 3 h est une publication de modpack coupée : vérifie ce qui a été fait (build, déploiement Supabase, annonce) et finis-la, ou remets `pack/` comme sur `main` d'avant, puis retire le verrou ;
  4. panneau : la session d'avant restée `en_cours` passe en `coupee`.
- **Fichiers du serveur** (`ia.json`, `reglages.json`…) : toujours lire, modifier en mémoire, puis réécrire le fichier entier en une seule commande ; jamais d'écriture en plusieurs morceaux.
- **Un modpack** ne se publie que si le serveur a déjà la même version du mod ; l'annonce Discord vient en tout dernier.

## Quota
KOLRAX a un abonnement Claude Max 5x, partagé avec ses autres projets. Travaille par lots courts qui se terminent proprement : un commit à chaque étape qui marche. Si le quota coupe une session, la suivante reprend grâce au plan et au journal.

## Pièges connus
- `Level.getHeight` sur un chunk non chargé renvoie le fond du monde : charger le chunk d'abord.
- Un chunk n'est « entity ticking » qu'avec ses voisins à 2 chunks chargés. Un PNJ seul se fige au bord : on force 3 x 3 chunks.
- La structure d'un GameTest est fermée par un plafond de barrières (sauf `skyAccess = true`) : le PNJ s'y croit sous terre.
- Pas de `runAfterDelay` dans un déclencheur de test (ConcurrentModificationException). Un PNJ tout juste apparu n'est pas visible au même tick : utiliser `TestsIA.etapes()`.
- Envoyer un paquet réseau à un faux joueur de test plante : vérifier `connection.hasChannel`.
- `Set.of` refuse les doublons : utiliser `Set.copyOf(List.of(…))`. Un bloc `static {}` qui lit des champs doit venir après eux.
- Un heredoc bash dans un script Python divise les antislashs : écrire les scripts avec l'outil d'écriture de fichiers.
- Ne pas multiplier la vitesse en sprint (l'attribut fait déjà +30 %). Ne jamais suivre un chemin direct partiel.
- Ne jamais dessiner du texte avec fond en `DisplayMode.NORMAL`.
- Groq via urllib exige un User-Agent, sinon Cloudflare répond 403.
- Changer le canal réseau (`Reseau.registrar("6")`) oblige à publier un nouveau modpack. Les anciens clients ne peuvent plus se connecter.
