# Plan de développement jusqu'au vendredi 16/10

Ordre décidé à partir du cahier des charges. Les trois priorités de KOLRAX : **« naturel, pas de lag, on s'amuse »**.
Un lot est fini quand :
- ses tests passent, dont au moins un test en jeu qui échouait avant ;
- `build` et `runGameTestServer` sont verts ;
- le serveur de test est à jour ;
- il a une entrée dans le journal.

On coche `[x]` quand c'est livré. Si un lot prend trop de temps, découpe-le : livre la partie qui marche, note le reste.

## 0. Infrastructure
- [x] Dépôt public, manuel `CLAUDE.md`, cahier des charges, agents
- [x] Tests lancés par la session cloud avant chaque push (pas de CI GitHub : compte bloqué, KOLRAX ne veut pas mettre de carte)
- [x] Session cloud capable de compiler et de lancer les tests en jeu (essai du 11/10 : 52 JUnit et 65 GameTests verts)
- [x] Déploiement du panneau Supabase par la ligne de commande (sans Chrome) : fait depuis le cloud pour le modpack 0.4.9 (12/10), téléchargement vérifié
- [x] Panneau de suivi en direct pour KOLRAX (artefact « Chantier des PNJ »), rempli par les sessions
- [x] Carte du code `docs/carte-du-code.md` (une ligne par classe : rôle, fichiers liés, pièges) et fiche de reprise `docs/reprise.md` (une page : état exact, prochaine étape, fichiers à ouvrir), pour que les sessions et les agents n'aient plus à explorer le code

## 1. Fiabilité et performance (indispensable)
- [x] Banc de charge : commande de test qui fait apparaître N ouvriers au travail, mesure du temps par tick (MSPT) et de la part du mod (`/pnj banc`, `/pnj charge`, tests à 10/50/100, compteurs de recherches de chemin ; livré en 0.8.2-0.8.4)
- [x] 50 PNJ et plus sans ralentissement perceptible (objectif : la part du mod < 10 ms par tick pour 50 PNJ) — atteint en 0.8.5 (12/10) : mesure réelle 2,6 à 5,1 ms en moyenne, 20 TPS ; pires ticks du mod encore 17-25 ms (échecs de chemin direct ~10 ms), à polir plus tard
- [x] Déblocage : il creuse ou pose des blocs pour sortir ; en dernier recours, téléportation au poste avec un message — livré en 0.8.7 (12/10) : pilier et pont de blocs, blocs repris ensuite, retour au poste après 5 min coincé avec un avis au chef, pas plus de 2 retours de suite
- [x] Plus de morts dues à un bug (chutes, noyade, lave, faim) — livré en 0.8.8 (12/10) : 14 essais de morts en jeu, 5 trouvées (mineur près de la lave ou sous du gravier/sable), corrigées ; il bouche la lave ; réflexe de survie (feu, lave, enseveli, noyade)

## 2. Interface du poste (indispensable, noté 2/5)
- [x] Beaucoup moins de messages, regroupés et triés ; gestion simple (lu, archivé, tout effacer) — livré en 0.8.9 (12/10) : lettres identiques regroupées (« ×N »), une seule lettre pour toute la colonie (dépôt plein, paie en retard, sans poste, poste détruit), importantes en tête en orange, boutons Tout lu / Archiver / Archives / Tout effacer ; modpack 0.4.9 (non obligatoire)

## 3. Ordres et IA
- [x] Journal des phrases non comprises (côté serveur), relu pour enrichir le répertoire (0.9.0, 12/10 ; à relire quand des joueurs auront parlé)
- [x] Ordres longs : chaque jour, garder un stock, « quand tu as fini », « si tu vois… », emploi du temps, conditions, ordres de groupe — 0.9.1 (12/10), première passe : règles gardées par ouvrier (pluie, orage, nuit, jour, matin, après-midi, soir, « quand tu as fini », « si tu vois… »), mission « tous les jours », « les mineurs, tous à la mine », « oublie tes ordres » ; pas de changement de métier dans la journée
- [x] Obéissance selon le moral et le caractère (refuser, négocier) ; refus d'un ordre mortel — 0.9.2 (13/10), première passe : moral de 0 à 100 (paie, faim, coups du chef, marchés), ouvrier qui râle, marchande une prime ou refuse un ordre pénible selon son moral et son caractère ; le chef peut insister ; ordres mortels toujours refusés
- [ ] OpenRouter : conversation payante (5 € par mois, limite posée par KOLRAX sur sa clé), clé propre à chaque serveur ; Groq et Gemini gratuits restent en premier
- [ ] Mémoire longue des conversations (ordres, promesses, insultes, cadeaux)

## 4. Métiers actuels plus malins (indispensable)
- [ ] Mineur (noté 3/5) : suivre un filon, branches, carrière, tri, « trouve-moi du diamant », creuser selon un plan ; ne jamais creuser sous la base d'un autre joueur ni casser une structure (il s'arrête et prévient)
- [ ] Bûcheron : feuilles, forêt en rangs, essence demandée, planches et charbon de bois, défricher
- [ ] Guerrier : ronde, escorte, défense des chantiers, chasse de nuit, mannequin, s'équiper et se soigner seul, arc et arbalète
- [ ] Outils fournis au dépôt, usure plus lente qu'un joueur

## 5. Vie, santé, moral (indispensable)
- [ ] Besoins : faim, fatigue, moral, lit à soi, santé, loisirs
- [ ] Blessures à plusieurs gravités ; à terre plus longtemps selon la gravité ; les autres se rassemblent autour du blessé
- [ ] Guérisseur (nouveau métier)
- [ ] Moral : causes (paie, nourriture variée, lit, dangers, ton du chef, deuil, météo, amitiés, réussite de la colonie) ; effets (lenteur, plaintes, grève, démission, fuite, vol, sabotage, révolte armée après avertissements)
- [ ] Réactions selon le caractère : insulte, coup, impayé, diamant gardé en cachette, cadeau
- [ ] Deuil, et enterrement possible pour adoucir le moral

## 6. Nouveaux métiers indispensables
- [ ] Fermier, éleveur, pêcheur, chasseur, cuisinier ou boulanger, transporteur
- [ ] « Va me chercher du fer », dit à un bûcheron : il propose d'aller prévenir le mineur

## 7. Conversations et relations (indispensable)
- [ ] Conversations fréquentes entre PNJ, SANS IA : une grande banque de textes, chacun son parler
- [ ] Remarques spontanées de temps en temps ; humour en réponse à l'impossible
- [ ] Amitiés et inimitiés, couples

## 8. Apparence (indispensable)
- [ ] Skins médiévaux selon le métier, tenues visibles
- [ ] Nom, métier et icône d'état (faim, colère, attente) au-dessus de la tête
- [ ] Plus de sons (pas, travail, réactions)

## 9. Défense et guerre (indispensable)
- [ ] Défense de la colonie contre les monstres ; les ouvriers s'éloignent d'un creeper et préviennent le guerrier ; la nuit dehors, ils campent et se cachent
- [ ] Guerre entre colonies : déclaration ; un chef PNJ lève une armée (les guerriers, et des fermiers en renfort s'il le propose) ; attaque : casser, brûler, voler, mener la mission
- [ ] Guerriers qui défendent le dépôt, sauf contre un joueur autorisé par le chef
- [ ] Réaction quand on frappe le chef, selon les ordres donnés avant

## 10. Construction (indispensable)
- [ ] Maçon ou bâtisseur : construit un schéma placé par le joueur, avec les matériaux du dépôt
- [ ] KOLRAX importe ses propres schémas

## 11. Économie (indispensable selon ses notes)
- [ ] Les PNJ dépensent leur salaire chez d'autres PNJ (nourriture, équipement) : une vraie économie entre eux
- [ ] Boutiques ; argent physique = pièces Numismatics existantes
- [ ] Coût : un débutant peut payer 2 ou 3 PNJ, un joueur très riche une trentaine
- [ ] Dépôt : les PNJ peuvent y ranger sans limite, mais pas le joueur

## 12. Create (indispensable)
- [ ] Les PNJ remplissent et vident les machines (dépôts, entonnoirs, tapis) ; ils sont la main-d'œuvre de l'industrie

## 13. Familles, recrutement, progression
- [ ] Recrutement : PNJ plus rares, avec des compétences propres ; arrivées depuis les villages, débauche d'un autre joueur, réputation, affiche ou crieur
- [ ] Étoiles qui montent avec l'expérience ou une formation payée
- [ ] Familles, enfants qui grandissent, vieillesse lente mais perceptible ; apprenti
- [ ] Travailleurs de nuit (fatigue, santé, moral)

## 14. Autres joueurs
- [ ] Autoriser un joueur : il peut parler aux ouvriers et leur donner des ordres ; un autre joueur peut tenter de les convaincre ou de les corrompre (paie plus élevée)
- [ ] Tout le monde voit le nom, le métier et le chef d'un ouvrier
- [ ] Rumeurs : un PNJ peut révéler ce qu'il a appris par espionnage ou bouche à oreille

## Bonus (après tout le reste)
Forgeron, marchand, opérateur de machines Create, cheminot, veilleur de nuit, scribe, éclaireur (important), quêtes et histoires, marchands ambulants et bandits, panneau web (admin seulement).
