# Journal du développement

Une entrée par lot ou par session : date et heure, ce qui est fait, mesures, ce qui reste. La plus récente en bas.

## 2026-10-11 soir : mise en place de l'autonomie
- Questionnaire de 127 questions rempli par KOLRAX : `docs/cahier-des-charges.md`.
- Projet Supabase du panneau transféré sur son compte perso (organisation « kolrax app »), même adresse.
- Dépôt public `kolrax/colony-mc` : le mod, avec son historique, plus le modpack, le panneau, les outils et les configs de référence. Secrets et données de joueurs exclus.
- Outils : `panel.py` et `discord.py` lisent aussi les variables d'environnement (pour le cloud). Nouvelles commandes `panel.py mv` (ranger un jar dans /mods-off) et `discord.py dm` (message privé à KOLRAX).
- Manuel `CLAUDE.md`, plan `docs/plan.md`, agents `.claude/agents/`, CI GitHub.
- Serveur toujours en colonie_pnj 0.8.0, modpack 0.4.8.
- Reste : vérifier la CI et une première session cloud, puis attaquer le lot 1.

## 2026-10-11 : Essai de l'environnement cloud (lecture seule)
Aucun code du mod touché, aucune action sur le serveur.
- Java 21 : OK (OpenJDK 21.0.12, déjà installé, rien à faire). 0 s.
- `./gradlew build --no-daemon` : OK au 3ᵉ essai, 5 min 11 s pour le bon passage, 52 tests JUnit, 0 échec.
  - 1er essai : échec, `gradle.properties` ligne 42 impose un JDK Windows (`org.gradle.java.home=C:/Program Files/...`). Contourné sans modifier le fichier : `-Dorg.gradle.java.home=/usr/lib/jvm/java-21-openjdk-amd64`.
  - 2ᵉ essai : échec transitoire, Maven Central répond 429 (trop de requêtes). Le 3ᵉ essai, 45 s plus tard, passe.
- `rm -rf run/world && ./gradlew runGameTestServer --no-daemon` : OK, 65 tests en jeu sur 65, 0 échec, 2 min 13 s (tests : 54 s).
- Variables d'environnement : les 8 sont présentes (PANEL_URL, SERVER_ID, PANEL_API_KEY, DISCORD_APP_ID, DISCORD_GUILD_ID, DISCORD_BOT_TOKEN, SUPABASE_ACCESS_TOKEN, GROQ_API_KEY).
- `panel.py status` : OK, serveur en marche (RAM 1,8 Go, uptime 305 min), 2 s. `panel.py ls /mods` : OK, colonie_pnj-0.8.0.jar présent, 0 s.
- Discord (lecture) : OK, serveur « La Colonie », 1 s.
- Supabase : `npx supabase --version` OK (10 s) ; `functions list` OK, fonction `modpack` ACTIVE version 12 (3 s) ; le panneau répond HTTP 200.
- Pillow : `python3` (3.11, /usr/local/bin) n'avait pas PIL, alors que celui de /usr/bin (3.13) l'avait. Installé avec `python3 -m pip install pillow` (12.3.0), OK.
- Conclusion : le cloud peut faire tout le travail. Réserves : le JDK Windows du gradle.properties à surcharger, et les 429 de Maven à réessayer.

## 2026-10-11 20 h : routine de développement en place
- Le JDK Windows est retiré de `mod-npc/gradle.properties` (réglé dans `~/.gradle/gradle.properties` sur le PC) : plus besoin de `-Dorg.gradle.java.home` dans le cloud.
- Routine « Colonie - développement autonome du mod » : toutes les 3 heures (à hh:59 UTC), modèle Sonnet 5.5, environnement « La Colonie », sans connecteur. Verrou `docs/EN-COURS` contre deux sessions en même temps.
- Prochaine étape : lot 1 (banc de charge, 50 PNJ sans lag, déblocage).

## 2026-10-11 19 h - 21 h UTC : session routine (cloud)
- **Carte du code** (`docs/carte-du-code.md`) et **fiche de reprise** (`docs/reprise.md`) écrites.
- **Incident corrigé, version 0.8.1 livrée** : cet après-midi, une dizaine de conversations IA perdues (Groq HTTP 400, « le JSON ne suit pas le schéma » : le modèle mettait un id du répertoire dans `type`). Maintenant la réponse est rattrapée (`failed_generation`), un id du répertoire dans `type` devient `faire`, un type vraiment inconnu renvoie à l'interpréteur sans IA, et la consigne est précisée. Relu, corrigé après relecture. 55 JUnit, 65 en jeu.
  - Livrée à 19 h 21 UTC (personne connecté), « Done » sans erreur. `panel.py upload` est bloqué depuis le cloud (connexion coupée vers le nœud) : jar envoyé par `panel.py write` dans /mods-off, taille vérifiée (578 910 octets), puis `mv`. Modpack inchangé.
- **Lot 1, banc de charge fait** (commité, version 0.8.2, pas encore relu dans sa dernière forme ni livré) : classe `Charge` (mesure par fenêtre de 100 ticks, découpée par partie), `/pnj banc <N> [metier|tous] [x y z]`, `/pnj banc fin`, `/pnj charge`, tests en jeu à 10/50/100 PNJ. Première relecture : 5 corrections faites (fin sans envol de corbeaux, « sans paie » qui débordait et mettait les PNJ en grève, second banc refusé, emplacement vérifié, pas de ramassage).
  - Mesures (monde plat de test, 200 ticks, JIT froid, donc un minimum) : part du mod à 10 PNJ 0,3-0,4 ms ; à 50 PNJ 1,6-2,4 ms en moyenne, pire 6-17 ms ; à 100 PNJ 2,2-4,4 ms, avec 2 pics vers 100 ms sur 4 passages.
- **Deux tests en jeu instables, causes trouvées et corrigées** (pas des « flakes ») :
  - le corbeau apparaissait dans un tronçon inactif et restait figé : il part maintenant d'un point dont tout le trajet est actif, sinon la lettre est livrée tout de suite ;
  - un messager ne rattrapait jamais un ouvrier qui marche : l'ouvrier hélé s'arrête maintenant pour écouter. Stress 40 copies : 2 à 8 échecs sur 40 avant, 0 après.
  - 2 nouveaux tests, qui échouaient sur l'ancien code. Total : 60 JUnit, 71 en jeu, 5 passages verts de suite.
- Reste / prochaine étape exacte : voir `docs/reprise.md` (relire puis livrer 0.8.2, vraie mesure avec `/pnj banc 50` sur test-andesite, analyser les pics).
- Point du jour envoyé 2026-10-11
