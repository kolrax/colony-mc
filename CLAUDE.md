# La Colonie : manuel de travail de Claude

Dépôt PUBLIC du serveur Minecraft « La Colonie » et de son mod de PNJ maison `colonie_pnj`.
Tu développes ce mod en autonomie pour **KOLRAX_MC**, le chef de projet. Échéance : mod fini le **vendredi 16/10/2026** (ouverture du serveur vendredi soir ou samedi).

À lire avant tout travail :
1. `docs/cahier-des-charges.md` : ses 127 réponses. Elles font foi. Ses « précisions » priment sur l'option cochée.
2. `docs/plan.md` : les lots dans l'ordre, et ce qui est fait.
3. `docs/reprise.md` (une page : où on en est exactement) et la fin de `docs/journal.md`.
3 bis. `docs/carte-du-code.md` : la carte des classes. **N'explore pas le code au hasard** : ouvre seulement les fichiers dont la tâche a besoin, et donne aux agents la liste exacte des fichiers. À la fin de chaque session, mets à jour `docs/reprise.md`, et la carte si tu as ajouté ou changé le rôle d'une classe.
4. `mod-npc/BRIEF.md` et `mod-npc/conception*.md` : l'historique du mod et ses choix de conception.

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
**Sur le serveur** (monde de test `test-andesite`, encore jetable) :
1. Avant de redémarrer : `python tools/panel.py cmd "list"`, puis lire `python tools/panel.py cat /logs/latest.log 20`.
   - Si quelqu'un est connecté : 5 minutes de préavis en jeu (`tellraw`). Sinon, redémarrage libre, de préférence entre 3 h et 6 h.
2. `python tools/panel.py upload mod-npc/build/libs/colonie_pnj-X.jar /mods`, puis `python tools/panel.py mv /mods/colonie_pnj-ANCIEN.jar /mods-off/colonie_pnj-ANCIEN.jar`. On ne supprime jamais un mod : on le range dans `/mods-off`.
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

## Rythme jusqu'à vendredi (décidé le 12/10)
Bilan du 12/10 : lots 0 à 2 faits en 8 sessions, avec une très bonne qualité, mais il reste 12 lots et le quota hebdomadaire ne suffira pas au même rythme. Donc :
- **Une seule relecture par lot.** Une deuxième relecture, uniquement si la première trouve un bloquant, et seulement sur les corrections.
- **Première passe d'abord** : chaque lot restant reçoit d'abord sa version simple, complète et testée (le comportement principal du cahier des charges). On approfondit seulement quand tous les lots ont leur première passe.
- **Les restes mineurs** (cas limites rares, finitions, « non testé » sans danger) vont dans `docs/plus-tard.md`, jamais dans une session dédiée. Exception : ce qui peut tuer un PNJ, perdre des objets, planter le serveur ou le faire ralentir.
- **Moins de tests garde-fous** : un test par comportement nouveau (qui échoue avant), pas une batterie de variantes.
- **Un seul serveur de test à la fois**, jamais deux agents qui lancent `runGameTestServer` en même temps.
- **Cadence** : une session toutes les 6 heures (0 h 59, 6 h 59, 12 h 59, 18 h 59 UTC). La limite hebdomadaire de KOLRAX ne se remet à zéro que le **vendredi 16/10 à 18 h** : il reste environ 16 sessions. Chaque session doit livrer quelque chose de visible en jeu.
- **Gel jeudi 15/10 à 18 h UTC** : après, plus de nouveautés. Seulement des corrections, la vérification complète et la version finale (serveur + modpack), prête le vendredi 16 à 12 h UTC au plus tard.

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
