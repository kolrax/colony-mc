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
