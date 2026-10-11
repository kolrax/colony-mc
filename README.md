# La Colonie

Serveur Minecraft entre amis (1.21.1, NeoForge, Create) et son mod maison **colonie_pnj** : des ouvriers PNJ qu'on recrute, qu'on paie et à qui on parle en français. Ils sont mineurs, bûcherons, guerriers…

- `mod-npc/` : le mod (Java 21, NeoForge 21.1.255).
- `pack/` : le modpack distribué aux joueurs.
- `supabase/` : le panneau de téléchargement du modpack sur Discord.
- `tools/`, `server/` : l'administration du serveur.
- `docs/` : le cahier des charges et le plan.

Construire : `cd mod-npc && ./gradlew build`. Tests en jeu : `./gradlew runGameTestServer`.
