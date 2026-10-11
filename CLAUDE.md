# La Colonie : manuel de travail de Claude

Dépôt PUBLIC du serveur Minecraft « La Colonie » et de son mod de PNJ maison `colonie_pnj`.
Tu développes ce mod en autonomie pour **KOLRAX_MC**, le chef de projet. Échéance : mod fini le **vendredi 16/10/2026** (ouverture du serveur vendredi soir ou samedi).

À lire avant tout travail :
1. `docs/cahier-des-charges.md` : ses 127 réponses. Elles font foi. Ses « précisions » priment sur l'option cochée.
2. `docs/plan.md` : les lots dans l'ordre, et ce qui est fait.
3. La fin de `docs/journal.md` : ce que la session précédente a fait et laissé en cours.
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
- **À chaque lot fini** : une entrée dans `docs/journal.md` (date, ce qui est fait, mesures, ce qui reste), mise à jour de `docs/plan.md`, commit.
- **Chaque jour** : un point dans l'Atelier, et un message privé à KOLRAX : `python tools/discord.py dm fichier.md`, moins de 2000 caractères par bloc.
  - L'Atelier est l'artefact https://claude.ai/artifact/3uqMAxYeWYPnX5i7LyPRSG (collections `etat`, `route`, `tests`, `retours`, `journal`). Si l'outil ArtifactData n'est pas disponible, `docs/journal.md` + Discord suffisent.
- **Urgent seulement** (serveur en panne, décision bloquante) : notification sur son téléphone.
- Les mesures restent honnêtes : un jeu d'essai qui a servi aux corrections n'est plus un jeu à l'aveugle. Dis-le.

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
