# Textes du lot 6 : lettres au chef (pêcheur, chasseur, cuisinier et boulanger, transporteur)

Lettres prêtes à recopier dans `Messages.java` (bloc `static {}`), au même format que `houe`, `graines`,
`fourrage`, `cisailles`, `sans_champ`. Elles ne sont **pas** encore dans le Java : un développeur travaille en
parallèle sur le pêcheur. Chaque clé est une proposition ; le développeur peut la renommer.

Conventions reprises de `Messages.java` :
- `m(clé, objet de la lettre, variantes…)` ; `t(clé, tempérament, variantes…)` pour un ton propre à un tempérament
  (choisi une fois sur deux) ;
- `{0}`, `{1}` : trous remplis par le code (indiqué sous chaque lettre) ;
- tutoiement du chef, pas d'accord de genre sur celui qui écrit, « le dépôt » et « le poste » comme ailleurs.

Les répliques de travail dites en bulle sont dans `mod-npc/src/main/resources/vie.txt` (sections
`peche_*`, `chasse_*`, `cuisine_*`, `transport_*`).

## Pêcheur

```java
        m("canne_peche", "Il me faut une canne à pêche",
                "Je n'ai pas de canne à pêche : je regarde les poissons sans pouvoir les prendre. Laisse-m'en une au dépôt.",
                "Ma canne s'est cassée, ou je n'en ai jamais eu. Sans elle, pas de poisson à la table. Il m'en faudrait une au dépôt.",
                "Je peux attendre au bord de l'eau toute la journée, mais sans canne à pêche, rien ne mordra. Laisse-m'en une au dépôt.");
        t("canne_peche", "colerique", "Pêcher à mains nues ? Très peu pour moi ! Une canne à pêche au dépôt, et vite.");
        t("canne_peche", "peureux", "Je n'ai plus de canne… Je n'ose pas rentrer les mains vides. Laisse-m'en une au dépôt, s'il te plaît.");

        m("sans_eau_peche", "Où dois-je pêcher ?",
                "Je ne trouve pas d'eau assez grande pour pêcher autour du poste. Montre-moi un étang, une rivière ou la mer, et dis-moi « pêche ici ».",
                "Pas d'eau poissonneuse par ici : une flaque ne nourrit personne. Mène-moi au bord d'une vraie eau et dis « pêche ici ».",
                "Je tourne autour du poste sans trouver où lancer ma ligne. Il me faut une étendue d'eau digne de ce nom.");
        t("sans_eau_peche", "fourbe", "Je pourrais pêcher dans l'abreuvoir des vaches, mais l'éleveur n'aimerait pas. Montre-moi une vraie eau.");
```
- `sans_eau_peche` : la formule « pêche ici » est à ajuster selon l'ordre retenu dans `repertoire.txt`.

## Chasseur

```java
        m("arc", "Il me faut un arc",
                "Je n'ai pas d'arc : le gibier me voit venir et file avant que j'approche. Laisse-m'en un au dépôt.",
                "Sans arc, je cours après les bêtes sans jamais les attraper. Il m'en faudrait un au dépôt.",
                "Mon arc s'est brisé, ou je n'en ai jamais eu. Laisse-m'en un au dépôt et je reprends la chasse.");
        t("arc", "colerique", "Chasser sans arc, c'est courir pour rien ! Mets-m'en un au dépôt.");

        m("fleches", "Plus de flèches",
                "Mon carquois est vide : plus une flèche. Laisse-m'en au dépôt.",
                "J'ai tiré ma dernière flèche. Sans elles, mon arc ne sert plus à rien. Il m'en faudrait au dépôt.",
                "Plus de flèches, ni sur moi ni au dépôt. Le gibier peut dormir tranquille, pour l'instant.");
        t("fleches", "fourbe", "Plus de flèches. Certaines sont restées dans les arbres, je l'avoue. Il m'en faudrait d'autres au dépôt.");
        t("fleches", "peureux", "Je n'ai plus une seule flèche… Je n'ose plus m'enfoncer dans les bois comme ça. Laisse-m'en au dépôt.");

        m("sans_gibier", "Pas de gibier",
                "J'ai battu les bois autour du poste : pas une bête sauvage à chasser. Il faudra chercher plus loin, ou attendre que le gibier revienne.",
                "Le gibier a déserté les environs. Je rentre bredouille. Envoie-moi chasser ailleurs, si tu connais un meilleur coin.",
                "Pas une trace, pas un bruit dans les bois d'ici. Je ne chasserai pas les bêtes de nos enclos, rassure-toi.");
        t("sans_gibier", "calme", "Les bêtes se sont éloignées. Elles reviendront. En attendant, montre-moi un autre bois si tu veux de la viande.");
```

## Cuisinier et boulanger

```java
        m("sans_combustible", "Plus de combustible",
                "Mon four est froid : je n'ai plus de charbon ni de bois pour le chauffer. Laisse-m'en au dépôt.",
                "Sans combustible, pas de cuisson. Un peu de charbon, ou du bois, au dépôt, et je rallume le four.",
                "Le feu s'est éteint, et il n'y a plus rien à brûler, ni sur moi ni au dépôt.");
        t("sans_combustible", "colerique", "Un four sans feu, c'est une boîte en pierre ! Du charbon au dépôt, tout de suite.");

        m("sans_ble", "Plus de blé",
                "Je n'ai plus de blé pour faire le pain. Laisse-m'en au dépôt, ou demande au fermier de m'en porter.",
                "La huche est vide : plus un grain de blé. Sans lui, pas de pain demain matin.",
                "Il me faut du blé pour pétrir. Il n'y en a plus, ni sur moi ni au dépôt.");
        t("sans_ble", "peureux", "Plus de blé… Tout le monde va me réclamer du pain, et je n'aurai rien à leur donner. Laisse-m'en au dépôt, je t'en prie.");

        m("rien_a_cuire", "Rien à cuire",
                "Je n'ai rien à mettre au four : ni {0}, ni rien d'autre de cru. Laisse-m'en au dépôt.",
                "Plus de {0} à cuire. Le four est chaud, mais la marmite est vide.",
                "Mon four attend, et moi aussi : il me faudrait du {0} cru au dépôt.");
        t("rien_a_cuire", "fourbe", "Plus rien à cuire. Je pourrais bien faire rôtir une poule de l'éleveur, mais il compte ses bêtes. Laisse-moi de quoi cuisiner au dépôt.");
```
- `rien_a_cuire` : `{0}` = ce qui manque, dit sans article (« poisson », « viande »). Pour le poisson cru :
  « ni poisson, ni rien d'autre de cru ». Si le code ne sait pas quoi, une version sans trou :
  `"Je n'ai plus rien de cru à cuire : ni poisson, ni viande. Laisse-m'en au dépôt."`
- Le boulanger peut partager ces clés avec le cuisinier (le texte parle de four et de pain dans les deux cas).

## Transporteur

```java
        m("coffre_source_vide", "Rien à porter",
                "Je reviens du coffre de {0} : il est vide. Je n'ai rien à porter pour l'instant.",
                "Le coffre de {0} ne contient rien. J'attends qu'il se remplisse avant de refaire le chemin.",
                "Rien à prendre au coffre de {0}. Je reste au poste et j'y retourne plus tard.");
        t("coffre_source_vide", "fourbe", "Le coffre de {0} est vide. Je n'y ai pas touché, je le précise : il était vide en arrivant.");

        m("coffre_plein", "Le coffre est plein",
                "Le coffre de {0} est plein à craquer : je ne peux plus rien y déposer. Il faudrait le vider, ou en poser un autre à côté.",
                "Plus une place dans le coffre de {0}. Je garde ma charge sur moi en attendant.",
                "Le coffre de {0} déborde. Je ne vais pas poser tout ça par terre : il me faut un autre coffre.");
        t("coffre_plein", "colerique", "Le coffre de {0} est plein ! Je porte cette charge pour rien. Vide-le, ou donne-m'en un autre !");
        t("coffre_plein", "peureux", "Le coffre de {0} est plein… Je ne sais pas où poser ce que je porte. Dis-moi quoi faire, s'il te plaît.");

        m("coffre_introuvable", "Je ne trouve pas le coffre",
                "Je ne trouve plus le coffre de {0}. Il a été cassé ou déplacé ? Montre-moi le nouveau.",
                "J'ai fait le chemin jusqu'à {0}, mais le coffre n'y est plus. Je ne peux ni prendre ni déposer.");
```
- `{0}` = le lieu ou le coffre, tel qu'on le dit (« la mine », « la cuisine », « 120 64 -38 »).
- `coffre_introuvable` est en plus de la demande (coffre cassé ou déplacé) ; la section de bulle
  `transport_coffre_introuvable` existe dans `vie.txt`.
