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
- [x] OpenRouter : conversation payante (5 € par mois, limite posée par KOLRAX sur sa clé), clé propre à chaque serveur ; Groq et Gemini gratuits restent en premier — 0.9.3 (13/10), première passe : secours de Groq pour la conversation seulement, budget du mois et du jour comptés au coût réel, clé posée sur le serveur, un appel réel vérifié (0,000012 $)
- [x] Mémoire longue des conversations (ordres, promesses, insultes, cadeaux) — 0.9.4 (13/10), première passe : 40 souvenirs datés par ouvrier, réactions selon le caractère, récit « tu te souviens ? », souvenirs donnés à l'IA

## 4. Métiers actuels plus malins (indispensable)
- [x] Mineur (noté 3/5, première passe 13/10, 0.9.5-0.9.6) : suivre un filon, branches, carrière, tri, « trouve-moi du diamant », creuser selon un plan ; ne jamais creuser sous la base d'un autre joueur ni casser une structure (il s'arrête et prévient)
- [x] Bûcheron (première passe 13/10, 0.9.7) : feuilles, forêt en rangs, essence demandée, planches et charbon de bois, défricher
- [x] Guerrier (première passe 13/10, 0.9.9) : ronde, escorte, défense des chantiers, chasse de nuit, mannequin, s'équiper et se soigner seul, arc et arbalète
- [x] Outils fournis au dépôt, usure plus lente qu'un joueur (première passe 13/10, 0.9.10 : pioche et hache prises au dépôt, la meilleure, usure 3 fois plus lente)

## 5. Vie, santé, moral (indispensable)
- [x] Besoins : faim, fatigue, moral, lit à soi, santé, loisirs (première passe 13/10, 0.9.11 : ils disent quand ils ont faim, sommeil, mal ou le moral bas ; chacun son lit ; courtes pauses ; un blessé mange pour guérir)
- [x] Blessures à plusieurs gravités ; à terre plus longtemps selon la gravité ; les autres se rassemblent autour du blessé (première passe 13/10, 0.9.12 : légère, grave, très grave selon la vie perdue ; à terre 3, 5 ou 8 min ; les 3 compagnons les plus proches viennent)
- [x] Guérisseur (nouveau métier) (première passe 13/10, 0.9.14 : relève un ouvrier à terre avec un pain ou une potion de soin du dépôt, panse un blessé grave ou très grave, attend que le danger s'éloigne, ne quitte jamais un ordre du chef ; « soigne Jean », « va soigner les blessés »)
- [x] Moral : causes (paie, nourriture variée, lit, dangers, ton du chef, deuil, météo, amitiés, réussite de la colonie) ; effets (lenteur, plaintes, grève, démission, fuite, vol, sabotage, révolte armée après avertissements) (première partie 14/10, 0.9.19 : causes, lenteur, plaintes, démission ; 2e partie 14/10, 0.9.20 : vol, sabotage, fuite, révolte après 2 lettres et 3 avertissements ; pas encore installée sur le serveur)
- [x] Réactions selon le caractère : insulte, coup, impayé, diamant gardé en cachette, cadeau (14/10 : 0.9.23 insulte, coup, impayé, cadeau ; 0.9.26 diamant gardé en cachette selon le caractère, aveu ou démenti, excuses du chef ; pas encore installées sur le serveur)
- [x] Deuil, et enterrement possible pour adoucir le moral (14/10, 0.9.24 : nouvelle annoncée ou lettre au chef absent, ils en reparlent 2 jours, « on va enterrer X » : cérémonie à la tombe, moral perdu rendu de moitié ; proposition d'un ouvrier, « oui » suffit ; pas encore installée)

## 6. Nouveaux métiers indispensables
- [x] Fermier, éleveur, pêcheur, chasseur, cuisinier ou boulanger, transporteur (première passe 13-14/10 : 0.9.12 à 0.9.18)
- [x] « Va me chercher du fer », dit à un bûcheron : il propose d'aller prévenir le mineur (14/10, 0.9.21 : `Relais`, tout métier ; pas encore installée sur le serveur)

## 7. Conversations et relations (indispensable)
- [x] Conversations fréquentes entre PNJ, SANS IA : une grande banque de textes, chacun son parler (première passe 13/10, 0.9.8 : 237 conversations en bulles, `Conversations`/`Causeries`)
- [x] Remarques spontanées de temps en temps ; humour en réponse à l'impossible (14/10, 0.9.23 : `Remarques`, `Impossible`, `remarques.txt` ; pas encore installée sur le serveur)
- [x] Amitiés et inimitiés, couples (14/10, 0.9.25, première passe : `Liens`, `Relations` ; amis ≥ 50, ennemis ≤ −50, couple à 80 depuis 2 jours, lettre au chef, « qui sont tes amis ? » ; pas encore installée)

## 8. Apparence (indispensable)
- [x] Skins médiévaux selon le métier, tenues visibles (13/10, 0.9.7, modpack 0.5.0 ; jamais vus à l'écran)
- [x] Nom, métier et icône d'état (faim, colère, attente) au-dessus de la tête (13/10, 0.9.10, modpack 0.5.1 obligatoire, canal 8 ; jamais vu à l'écran)
- [x] Plus de sons (pas, travail, réactions) (14/10, 0.9.27 : `Sons`, `Cadence` ; sons du jeu joués par le serveur, pas de modpack ; jamais écoutés en jeu)

## 9. Défense et guerre (indispensable)
- [x] Défense de la colonie contre les monstres ; les ouvriers s'éloignent d'un creeper et préviennent le guerrier ; la nuit dehors, ils campent et se cachent (14/10, 0.9.27, première passe : `Defense`, `Abri`, `defense.txt` ; restes dans plus-tard.md)
- [x] Consigne de KOLRAX (14/10) : face aux monstres, chacun selon son caractère (colérique se bat, peureux fuit, fourbe s'éclipse ou envoie un collègue, calme se défend) (14/10, 0.9.28 : `Bravoure`, `Defense`)
- [x] Guerre entre colonies : déclaration ; un chef PNJ lève une armée (les guerriers, et des fermiers en renfort s'il le propose) ; attaque : casser, brûler, voler, mener la mission
- [x] Guerriers qui défendent le dépôt, sauf contre un joueur autorisé par le chef
- [x] Réaction quand on frappe le chef, selon les ordres donnés avant

## 10. Construction (indispensable)
- [x] Maçon ou bâtisseur : construit un schéma placé par le joueur, avec les matériaux du dépôt
- [x] KOLRAX importe ses propres schémas (déjà couvert par le maçon : `.nbt` dans `config/colonie_pnj/schemas/` ou envoi par la table à schémas de Create)

## 11. Économie (indispensable selon ses notes)
- [x] Les PNJ dépensent leur salaire chez d'autres PNJ (nourriture, équipement) : une vraie économie entre eux (0.9.33, 1re passe : nourriture ; équipement dans plus-tard)
- [x] Boutiques ; argent physique = pièces Numismatics existantes (0.9.35)
- [x] Coût : un débutant peut payer 2 ou 3 PNJ, un joueur très riche une trentaine (0.9.34)
- [x] Dépôt : les PNJ peuvent y ranger sans limite, mais pas le joueur

## 12. Create (indispensable)
- [x] Les PNJ remplissent et vident les machines (dépôts, entonnoirs, tapis) ; ils sont la main-d'œuvre de l'industrie (0.9.36, 1re passe : par le transporteur ; pas encore essayé avec le vrai Create)

## 13. Familles, recrutement, progression
- [x] Recrutement : PNJ plus rares, avec des compétences propres ; arrivées depuis les villages, débauche d'un autre joueur, réputation, affiche ou crieur (0.9.37, 1re passe : rareté, talents, réputation, affiche, villages ; débauche au lot 14, crieur dans plus-tard)
- [x] Étoiles qui montent avec l'expérience ou une formation payée (0.9.38, 1re passe)
- [x] Familles, enfants qui grandissent, vieillesse lente mais perceptible ; apprenti (0.9.40, 1re passe)
- [x] Travailleurs de nuit (fatigue, santé, moral) (0.9.39, 1re passe)

## 14. Autres joueurs
- [x] Autoriser un joueur : il peut parler aux ouvriers et leur donner des ordres ; un autre joueur peut tenter de les convaincre ou de les corrompre (paie plus élevée)
- [x] Tout le monde voit le nom, le métier et le chef d'un ouvrier
- [x] Rumeurs : un PNJ peut révéler ce qu'il a appris par espionnage ou bouche à oreille

## Bonus (après tout le reste)
Forgeron, marchand, opérateur de machines Create, cheminot, veilleur de nuit, scribe, éclaireur (important), quêtes et histoires, marchands ambulants et bandits, panneau web (admin seulement).

## 15. Manques relevés au contrôle du 17/10 (remplacé par la refonte du 17/10)
- Casse au déblocage, persistance, emploi du temps à deux métiers, équipes, guerre, tests instables, charge et reprise : repris dans le lot 16
- Retirés par la refonte : loisirs et maladies, types de blessure, crieur, diamant, éclaireur

## 16. Refonte « ouvriers robots » 1.0.0 (décidée le 17/10, à livrer le 17/10 avant 22 h UTC, voir `docs/refonte.md`)
- [x] Étiquette git `v1-finale` ; retraits de la partie 3 de `docs/refonte.md` (IA, lettres et corbeau, moral détaillé, révolte, deuil, conversations, familles, rumeurs, économie entre ouvriers…) avec leurs tests ; build vert
- [x] Joueur fantôme : commande `/fantome` (dev ou réglage), rapport JSON, `tools/fantome.py` et scénario « 3 ouvriers, 2 jours, aucun bloc construit cassé » sur un monde généré
- [x] La mission : quoi, où, jusqu'à quand, horaires, qui ; file par ouvrier ; statut unique au-dessus de la tête et au poste ; blocages avec raison et solution ; tout survit au redémarrage
- [x] Lieux de travail intelligents sans bâton (partie 2) : colonie et tampon, jamais casser une construction (déblocage compris), arbres naturels seulement, mineur sous terre ou carrière hors colonie ; bouton « Travaille ici »
- [x] Fiche de l'ouvrier au clic droit, avec le menu des missions de son métier et la confirmation « Compris : … ✔/✖ »
- [x] Poste à 5 onglets (Ouvriers, Missions, Dépôt, Caisse, Journal) ; dépôt redessiné dans le même style ; le Journal remplace les lettres
- [x] Les 12 métiers branchés sur les missions, savoir-faire actuels gardés, plusieurs ouvriers sur une même mission
- [x] Barre de commandes guidées (touche dédiée, autocomplétion sur le répertoire d'actions, jamais de phrase libre)
- [x] Horaires à la place du sommeil ; satisfaction simple (paie et nourriture) qui règle la vitesse ; grève si impayé
- [x] Guerre simple et grands combats : déclarer et faire la paix au poste ; défendre, escorter, attaquer ; formation, charge, repli ; 30 contre 30 sans lag
- [x] Équilibre dans un seul fichier de réglages (coûts, salaires, production, étoiles, formation, plafond 30)
- [x] Feuille de tests v2 dans le panneau (essais `V01`…) ; tests instables rendus stables ; charge mesurée à 50 ouvriers (18/10 : creeper corrigé, lave et fige-toi stabilisés, `enRang` jamais reproduit, `abatLExcedent` seulement en lancements groupés, voir `plus-tard.md` ; charge : 49 ouvriers sur monde généré, 11 à 14 ms par tick, pire 31 ms)
- [x] Livraison 1.0.0 : nouveau monde d'essai `essai-v2` (le monde `colonie` reste sur le disque), modpack obligatoire, annonce (livrée le 17/10 à 20 h 28 UTC)
- [x] Après la livraison 1.0.0 : `docs/reprise.md` et `docs/carte-du-code.md` à jour ; livret d'aide donné avec le premier poste (à vérifier)
- [x] Livraison 1.0.1 (serveur seul) : mineur qui ne se bloque plus avec ses torches, « Travaille ici » qui le débloque, objets des joueurs respectés près de la mine, fermier « Plus de graines au dépôt » (livrée le 17/10 à 22 h 50 UTC)
- [x] Livraison 1.0.2 (serveur seul) : fermier qui creuse une petite mare sans seau et espace ses graines (fantôme : 15 blés en 2 jours au lieu de 1) (livrée le 17/10 à 23 h 54 UTC)
- [x] Bûcheron qui garde ses bûches dans son sac loin du poste et mineur qui ne vide pas son sac : vérifier au joueur fantôme (3 jours), corriger si vrai
- [ ] Lundi 19/10 : monde d'ouverture neuf avec la dernière version (procédure « Si tu régénères le monde de test » de `CLAUDE.md`, le monde `colonie` et `essai-v2` restent sur le disque)

## 17. Corrections du 18/10 d'après les rapports en jeu de KOLRAX (voir `docs/corrections.md`)
- [x] Plan B : ~~compteur du mineur qui ne monte pas~~ (1.0.9) ; retrait d'« expéditions max » et des horaires généraux (seulement les horaires de chaque mission)
- [x] Plan B4 (ce soir, 1.0.11) : barre de commandes, deuxième liste « qui / quoi » au lieu d'une question sans réponse ; plus aucun ordre qui ne fait que poser une question
- [x] Plan D (1.0.10, visible avec le modpack 1.0.11) : chaque joueur voit ses propres ouvriers sur la carte Xaero (seulement eux), sans toucher aux profils Xaero ; modpack obligatoire
- [ ] Plan E : ouvriers moins fragiles sans être invincibles (FAIT en 1.0.11 : se relever seul, fuir et guérir, armure et torches, alerte au chef ; en 1.0.12 : s'écarte de la lave qui coule vers lui ; RESTE : repère rouge Xaero, dangers de la nature vérifiés 2 jours au fantôme, morts comptées puis chiffres réglés ; se relever seul une fois, fuir à mi-vie et guérir, armure et torches du dépôt, dangers de la nature, alerte à terre avec repère Xaero, morts comptées au fantôme puis chiffres réglés)
- [ ] Plan A : déplacements « GPS » (FAIT en 1.0.11 : nouveau chemin après 3 s immobile, chemin direct moins recalculé, mineur qui contourne sous terre ; en 1.0.12 : sans pioche, il monte sur ses blocs et les reprend ; RESTE : trajet calculé en entier, montagnes (seau, fantôme), scénarios fantôme ; trajet calculé en entier, nouveau chemin après 3 s immobile, montagnes contournées ou gravies avec blocs ou seau, mineur qui contourne sous terre), vérifiés au joueur fantôme
