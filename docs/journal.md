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
