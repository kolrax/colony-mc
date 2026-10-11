"""Génère les situations d'entraînement de l'adressage (à qui le chef parle) : entrainement/scenarios.json.

Chaque situation : ce que dit le chef (au micro ou écrit dans la fenêtre d'un ouvrier), les ouvriers autour
(prénom, métier, distance, regard du chef, échange récent), les autres joueurs proches, et les destinataires
attendus. Les gabarits de phrases sont regroupés en familles ; une famille sur quatre est réservée au test,
pour mesurer si la formule généralise à des phrases qu'elle n'a jamais vues.

    python entrainement/generer.py
"""
import json
import random
from pathlib import Path

R = random.Random(20261010)
ICI = Path(__file__).parent

PRENOMS = ["Gauthier", "Thibaut", "Isabeau", "Enguerrand", "Mahaut", "Baudouin", "Ysolde", "Godefroy", "Blanche", "Perceval",
           "Héloïse", "Arnaud", "Clémence", "Bertrand", "Agnès", "Renaud", "Jehanne", "Hugues", "Ermengarde", "Aymeric",
           "Béatrix", "Foulques", "Pétronille", "Raoul", "Sibylle", "Guillaume", "Mélisende", "Tancrède", "Adélaïde",
           "Lancelin", "Guenièvre", "Odon", "Oriane", "Thierry", "Bérengère", "Amaury"]
METIERS = ["mineur", "bucheron", "guerrier"]
JOUEURS = ["Nielscutt", "Orphan", "Kalia", "Bastien", "Mathis", "Louve"]
METIER_SING = {"mineur": "mineur", "bucheron": "bûcheron", "guerrier": "guerrier"}
METIER_PL = {"mineur": "mineurs", "bucheron": "bûcherons", "guerrier": "guerriers"}

# Des ordres au singulier : communs à tous, puis propres à chaque métier.
ORDRES = ["suis-moi", "viens avec moi", "attends ici", "reste là", "rentre au poste", "retourne au travail", "remets-toi au travail",
          "montre-moi ton sac", "arrête de me suivre", "viens ici", "ne bouge pas", "accompagne-moi", "reviens au poste", "au boulot"]
ORDRES_METIER = {
    "mineur": ["ramène-moi dix fers", "mine du charbon", "va chercher du cuivre", "creuse jusqu'aux diamants", "garde trente-deux pierres en stock",
               "il me faut du fer, ramène-m'en vingt", "rapporte-moi de la redstone"],
    "bucheron": ["coupe du bouleau", "ramène vingt bûches de chêne", "va abattre des sapins", "rapporte-moi du bois de cerisier",
                 "garde toujours du chêne au dépôt", "coupe-moi une pile d'acacia"],
    "guerrier": ["va protéger la base", "fais la ronde", "garde le poste", "escorte-moi", "surveille les alentours", "défends le camp",
                 "protège la base"],
}
ORDRES_PL = ["suivez-moi", "venez avec moi", "attendez ici", "restez là", "rentrez au poste", "retournez au travail", "au travail",
             "venez ici", "arrêtez de me suivre", "au boulot", "revenez au poste"]
QUESTIONS = ["ça va ?", "tu as faim ?", "t'en es où ?", "qu'est-ce que tu fais ?", "pourquoi tu ne travailles pas ?", "tu es fatigué ?",
             "t'as besoin de quelque chose ?", "tout va bien ?", "tu as fini ?", "où tu vas ?", "tu as combien de fer ?"]
# Les actions du répertoire (0.8.0) : pour tous, et propres à un métier.
ACTIONS = ["danse", "garde à vous", "assieds-toi", "fais le cochon", "raconte une blague", "siffle un air", "mets-toi derrière moi", "tais-toi",
           "fais des pompes", "va là-bas", "salue-moi", "fais coucou", "lève les mains", "chante-nous quelque chose", "fige-toi", "regarde-moi",
           "fais la poule", "à genoux", "joue de la flûte", "tourne-toi", "fais le mort", "donne-moi ton pain", "allonge-toi", "fais une révérence"]
ACTIONS_PL = ["dansez", "garde à vous", "asseyez-vous", "en rang", "applaudissez", "en file indienne", "faites un cercle autour de moi",
              "taisez-vous", "repos", "rompez", "à genoux", "marchez au pas", "faites des pompes", "mettez-vous en rang"]
ACTIONS_METIER = {"mineur": ["mine ce bloc", "creuse un tunnel par là", "pose des torches", "creuse un trou ici", "éclaire le coin"],
                  "bucheron": ["abats cet arbre", "replante des arbres", "coupe les arbres autour", "débroussaille un peu"],
                  "guerrier": ["lève ton bouclier", "en garde", "attaque ce zombie", "tiens la position", "charge", "présentez armes"]}
POLITESSE = ["Merci !", "Merci beaucoup.", "Bien joué.", "Bravo !", "Beau travail.", "Salut !", "Bonjour !", "Bonsoir."]


def cap(s):
    return s[0].upper() + s[1:] if s else s


def fin(s):
    return s if s[-1] in ".!?" else s + R.choice([".", " !", "."])


# --- Les gabarits, par catégorie : chaque fonction rend (texte, règle) ; la règle donne les destinataires --------

def familles():
    f = {}

    def ajoute(cat, nom, fn):
        f.setdefault(cat, []).append((nom, fn))

    # Interpellé par son prénom (y compris pour un ordre d'un autre métier : il est visé, il dira que ce n'est pas son travail).
    def ordre_quelconque(cible):
        return R.choice(ORDRES + ORDRES_METIER[cible["metier"]] + ORDRES_METIER[R.choice(METIERS)])
    ajoute("vocatif", "V1", lambda e, c: (f"{c['prenom']}, {fin(ordre_quelconque(c))}", "cible"))
    ajoute("vocatif", "V2", lambda e, c: (f"{cap(ordre_quelconque(c))}, {c['prenom']}.", "cible"))
    ajoute("vocatif", "V3", lambda e, c: (f"Hé {c['prenom']}, {ordre_quelconque(c)} !", "cible"))
    ajoute("vocatif", "V4", lambda e, c: (f"{c['prenom']} ! {cap(fin(ordre_quelconque(c)))}", "cible"))
    ajoute("vocatif", "V5", lambda e, c: (f"Dis {c['prenom']}, {R.choice(QUESTIONS)}", "cible"))
    ajoute("vocatif", "V6", lambda e, c: (f"{c['prenom']}, {R.choice(QUESTIONS)}", "cible"))
    ajoute("vocatif", "V7", lambda e, c: (f"Merci {c['prenom']} !", "cible"))
    ajoute("vocatif", "V8", lambda e, c: (f"Bien joué {c['prenom']}.", "cible"))
    ajoute("vocatif", "V9", lambda e, c: (f"Salut {c['prenom']} !", "cible"))
    ajoute("vocatif", "V10", lambda e, c: (f"{c['prenom']}, tu peux {R.choice(['rentrer au poste', 'me suivre', 'attendre ici', 'aller miner', 'venir ici'])} ?", "cible"))
    ajoute("vocatif", "V11", lambda e, c: (f"Allez {c['prenom']}, {ordre_quelconque(c)} !", "cible"))
    ajoute("vocatif", "V12", lambda e, c: (f"{c['prenom']} ?", "cible"))
    ajoute("vocatif", "V13", lambda e, c: (f"{c['prenom']} !", "cible"))
    ajoute("vocatif", "V14", lambda e, c: (f"Écoute {c['prenom']}, {ordre_quelconque(c)}.", "cible"))
    ajoute("vocatif", "V15", lambda e, c: (f"{c['prenom']}, viens voir.", "cible"))
    ajoute("vocatif", "V16", lambda e, c: (f"Oh {c['prenom']}, t'es où ?", "cible"))
    ajoute("vocatif", "V17", lambda e, c: (f"{R.choice(['Bonne nuit', 'Bon courage', 'À demain', 'Bon travail'])} {c['prenom']} !", "cible"))

    # Deux prénoms.
    ajoute("deux_noms", "D1", lambda e, c: (f"{e[0]['prenom']} et {e[1]['prenom']}, {fin(R.choice(ORDRES_PL))}", "deux"))
    ajoute("deux_noms", "D2", lambda e, c: (f"{e[0]['prenom']}, {e[1]['prenom']}, {fin(R.choice(ORDRES_PL))}", "deux"))
    ajoute("deux_noms", "D3", lambda e, c: (f"Hé {e[0]['prenom']} et {e[1]['prenom']}, {R.choice(ORDRES_PL)} !", "deux"))

    # Tout le groupe.
    ajoute("groupe", "G1", lambda e, c: (f"Les gars, {fin(R.choice(ORDRES_PL))}", "tous"))
    ajoute("groupe", "G2", lambda e, c: (f"{cap(R.choice(ORDRES_PL))}, les gars !", "tous"))
    ajoute("groupe", "G3", lambda e, c: (f"Tout le monde, {fin(R.choice(ORDRES_PL))}", "tous"))
    ajoute("groupe", "G4", lambda e, c: (f"Allez les gars, {R.choice(ORDRES_PL)} !", "tous"))
    ajoute("groupe", "G5", lambda e, c: (f"Vous tous, {fin(R.choice(ORDRES_PL))}", "tous"))
    ajoute("groupe", "G6", lambda e, c: (R.choice(["Merci les gars !", "Salut tout le monde !", "Bien joué les gars.", "Bonjour à tous !",
                                                    "Bonne nuit les gars !", "À demain tout le monde.", "Bon courage les gars !"]), "tous"))
    ajoute("groupe", "G7", lambda e, c: (f"Bon, les gars, {fin(R.choice(ORDRES_PL))}", "tous"))
    ajoute("groupe", "G8", lambda e, c: (f"Les amis, {fin(R.choice(ORDRES_PL))}", "tous"))
    ajoute("groupe_sauf", "S1", lambda e, c: (f"Tout le monde sauf {c['prenom']}, {fin(R.choice(ORDRES_PL))}", "tous_sauf"))
    ajoute("groupe_sauf", "S2", lambda e, c: (f"Les gars, sauf {c['prenom']}, {fin(R.choice(ORDRES_PL))}", "tous_sauf"))
    ajoute("groupe_sauf", "S3", lambda e, c: (f"Tous sauf {c['prenom']}, {fin(R.choice(ORDRES_PL))}", "tous_sauf"))
    ajoute("groupe_sauf", "S4", lambda e, c: (f"{cap(R.choice(ORDRES_PL))}, tous sauf {c['prenom']}.", "tous_sauf"))

    # Par leur métier.
    ajoute("metier", "M1", lambda e, c: (f"Les {METIER_PL[c['metier']]}, {fin(R.choice(ORDRES_PL))}", "metier_tous"))
    ajoute("metier", "M2", lambda e, c: (f"{cap(METIER_PL[c['metier']])}, {R.choice(ORDRES_PL)} !", "metier_tous"))
    ajoute("metier", "M3", lambda e, c: (f"Le {METIER_SING[c['metier']]}, {fin(R.choice(ORDRES + ORDRES_METIER[c['metier']]))}", "metier_un"))
    ajoute("metier", "M4", lambda e, c: (f"Hé le {METIER_SING[c['metier']]}, {R.choice(ORDRES + ORDRES_METIER[c['metier']])} !", "metier_un"))

    # Il regarde un ouvrier et lui parle, sans prénom.
    ajoute("regard", "R1", lambda e, c: (cap(fin(R.choice(ORDRES))), "regarde"))
    ajoute("regard", "R2", lambda e, c: (cap(fin(R.choice(ORDRES_METIER[c['metier']]))), "regarde"))
    ajoute("regard", "R3", lambda e, c: (f"{cap(R.choice(ORDRES))}, s'il te plaît.", "regarde"))
    ajoute("regard", "R4", lambda e, c: (cap(R.choice(QUESTIONS)), "regarde"))
    ajoute("regard", "R5", lambda e, c: (R.choice(POLITESSE), "regarde"))
    ajoute("regard", "R6", lambda e, c: (f"Allez, {fin(R.choice(ORDRES + ORDRES_METIER[c['metier']]))}", "regarde"))
    ajoute("regard", "R7", lambda e, c: (f"Tu peux {R.choice(['me suivre', 'rentrer', 'attendre là', 'venir avec moi', 'retourner bosser'])} ?", "regarde"))
    ajoute("regard", "R8", lambda e, c: (f"Dis-moi, {R.choice(QUESTIONS)}", "regarde"))
    ajoute("regard", "R9", lambda e, c: (f"Hé, {R.choice(QUESTIONS)}", "regarde"))
    ajoute("regard", "R10", lambda e, c: (R.choice(["Bonne nuit.", "À demain !", "Bon courage.", "Bon travail !", "Merci, c'est parfait."]), "regarde"))
    ajoute("regard", "R11", lambda e, c: (R.choice(["Alors, ça vient ?", "C'est bon ?", "Fini ?", "Tout roule ?", "Ça se passe bien ?",
                                                   "Alors, ça donne quoi ?", "Ça tient le coup ?"]), "regarde"))

    # Il regarde l'un, mais l'ordre relève du métier d'un autre (« va protéger la base » en regardant le mineur).
    ajoute("autre_metier", "A1", lambda e, c: (cap(fin(R.choice(ORDRES_METIER[c['autre']]))), "metier_autre"))
    ajoute("autre_metier", "A2", lambda e, c: (f"Il faudrait que quelqu'un {R.choice({'mineur': ['ramène du fer', 'mine du charbon'], 'bucheron': ['coupe du bois', 'ramène des bûches'], 'guerrier': ['protège la base', 'garde le poste']}[c['autre']])}.", "metier_autre"))
    ajoute("autre_metier", "A3", lambda e, c: (f"Quelqu'un peut {R.choice({'mineur': ['aller miner du fer', 'ramener du charbon'], 'bucheron': ['couper du chêne', 'ramener du bois'], 'guerrier': ['surveiller la base', 'faire la ronde'], }[c['autre']])} ?", "metier_autre"))

    # Seul avec eux, sans regarder personne : l'ordre d'un métier.
    ajoute("sans_regard", "N1", lambda e, c: (cap(fin(R.choice(ORDRES_METIER[c['metier']]))), "metier_un"))
    ajoute("sans_regard", "N2", lambda e, c: (f"Il me faut quelqu'un pour {R.choice({'mineur': ['miner du fer', 'ramener du cuivre'], 'bucheron': ['couper du bouleau', 'ramener du chêne'], 'guerrier': ['garder la base', 'faire la ronde']}[c['metier']])}.", "metier_un"))

    # Un pluriel, sans mot de groupe.
    ajoute("pluriel", "P1", lambda e, c: (cap(fin(R.choice(ORDRES_PL))), "tous"))
    ajoute("pluriel", "P2", lambda e, c: (f"Allez, {fin(R.choice(ORDRES_PL))}", "tous"))
    ajoute("pluriel", "P3", lambda e, c: (f"Vous pouvez {R.choice(['rentrer au poste', 'me suivre', 'attendre ici', 'retourner au travail'])} ?", "tous"))

    # Des remarques sur eux, pas à eux.
    for i, t in enumerate(["C'est vraiment un chômeur ce mec.", "Il est lent, {N}.", "{N} bosse bien quand même.", "Regarde-le, il dort encore.",
                           "Ce type ne fait rien de la journée.", "Elle a encore faim, celle-là.", "Je crois que {N} est bloqué.",
                           "{N} a encore perdu sa pioche.", "Franchement, {N}, il sert à rien.", "Ils sont nuls, ces ouvriers.",
                           "Il est où, le mineur ?", "{N}, c'est le meilleur de mes ouvriers.", "Il a encore oublié ses torches.",
                           "{N} et {M}, ils s'entendent bien.", "Ce gars-là, il mine comme un dieu."]):
        ajoute("remarque", f"K{i + 1}", lambda e, c, t=t: (t.replace("{N}", c["prenom"]).replace("{M}", e[-1]["prenom"]), "personne"))

    # Il se parle à lui-même.
    for i, t in enumerate(["Bon, je vais miner un peu.", "Où est-ce que j'ai mis ma pioche ?", "Il me reste combien de fer ?", "J'ai faim, moi.",
                           "Zut, j'ai oublié la carte.", "Voyons voir…", "Je reviens dans cinq minutes.", "Il fait déjà nuit.",
                           "C'est quoi ce bloc ?", "Bon, on verra demain.", "Il me faut un four.", "Je vais poser une torche ici.",
                           "Ah, une grotte !", "Pff, encore des zombies.", "Allez, encore un effort.", "Mince, plus de charbon.",
                           "Je me demande où est le village.", "J'aurais dû prendre plus de pain.", "Plus de bois, zut.", "J'ai trouvé du fer !",
                           "Encore de la pierre…", "Enfin du diamant !", "Bon, il me manque du charbon.", "Il me reste trois lingots d'or.",
                           "On a assez de pierre.", "Zut, plus de torches."]):
        ajoute("soliloque", f"L{i + 1}", lambda e, c, t=t: (t, "personne"))

    # Il parle à un autre joueur.
    for i, t in enumerate(["{P}, tu viens ?", "Tu viens ?", "T'as vu ça ?", "Regarde ce mec, il dort.", "On va au village ?", "Attends-moi !",
                           "Suis-moi, je connais un raccourci.", "T'as du fer sur toi ?", "Tu as fini ta maison ?", "Viens voir ma mine.",
                           "Passe-moi des torches.", "On se retrouve au poste ?", "{P}, regarde !", "Mdr, t'es nul.", "Bien joué {P} !",
                           "Tu m'aides à construire le moulin ?", "{P}, tu as vu mes ouvriers ?"]):
        ajoute("autre_joueur", f"J{i + 1}", lambda e, c, t=t: (t, "personne"))

    # Une réponse à sa question, ou la suite de leur échange.
    for i, t in enumerate(["Du fer.", "Le cuivre.", "Oui.", "Non, laisse tomber.", "Les deux.", "Comme tu veux.", "D'accord, vas-y.", "Plutôt du chêne.",
                           "Oui, vas-y.", "Pas maintenant."]):
        ajoute("reponse", f"Q{i + 1}", lambda e, c, t=t: (t, "question"))
    for i, t in enumerate(["Et ramène aussi du charbon.", "Et après, rentre au poste.", "Ah, et prends des torches.", "Au fait, fais attention aux creepers.",
                           "Et ensuite, reviens me voir.", "Merci, et dépêche-toi."]):
        ajoute("suite", f"U{i + 1}", lambda e, c, t=t: (t, "suite"))

    # Les actions du répertoire (0.8.0) : en le regardant, par son prénom, au groupe, au métier, et les remarques sur elles.
    ajoute("rep_regard", "AR1", lambda e, c: (cap(fin(R.choice(ACTIONS))), "regarde"))
    ajoute("rep_regard", "AR2", lambda e, c: (f"Allez, {fin(R.choice(ACTIONS))}", "regarde"))
    ajoute("rep_regard", "AR3", lambda e, c: (f"{cap(R.choice(ACTIONS))}, s'il te plaît.", "regarde"))
    ajoute("rep_regard", "AR4", lambda e, c: (f"Tu peux {R.choice(['danser', 'te mettre au garde-à-vous', 't asseoir', 'faire le cochon', 'chanter', 'siffler'])} ?", "regarde"))
    ajoute("rep_vocatif", "AV1", lambda e, c: (f"{c['prenom']}, {fin(R.choice(ACTIONS + ACTIONS_METIER[c['metier']]))}", "cible"))
    ajoute("rep_vocatif", "AV2", lambda e, c: (f"{cap(R.choice(ACTIONS))}, {c['prenom']} !", "cible"))
    ajoute("rep_vocatif", "AV3", lambda e, c: (f"Hé {c['prenom']}, {R.choice(ACTIONS)} !", "cible"))
    ajoute("rep_vocatif", "AV4", lambda e, c: (f"{c['prenom']} ! {cap(fin(R.choice(ACTIONS)))}", "cible"))
    ajoute("rep_groupe", "AG1", lambda e, c: (f"Les gars, {fin(R.choice(ACTIONS_PL))}", "tous"))
    ajoute("rep_groupe", "AG2", lambda e, c: (f"Tout le monde, {fin(R.choice(ACTIONS_PL))}", "tous"))
    ajoute("rep_groupe", "AG3", lambda e, c: (f"{cap(R.choice(ACTIONS_PL))}, les gars !", "tous"))
    ajoute("rep_groupe", "AG4", lambda e, c: (cap(fin(R.choice(["dansez", "asseyez-vous", "applaudissez", "taisez-vous", "faites des pompes", "mettez-vous en rang"]))), "tous"))
    ajoute("rep_metier", "AM1", lambda e, c: (cap(fin(R.choice(ACTIONS_METIER[c['metier']]))), "metier_un"))
    ajoute("rep_metier", "AM2", lambda e, c: (f"Il faudrait que quelqu'un {R.choice({'mineur': ['pose des torches', 'creuse un tunnel'], 'bucheron': ['abatte cet arbre', 'replante des arbres'], 'guerrier': ['lève son bouclier', 'tienne la position']}[c['metier']])}.", "metier_un"))
    ajoute("rep_autre", "AA1", lambda e, c: (cap(fin(R.choice(ACTIONS_METIER[c['autre']]))), "metier_autre"))
    for i, t in enumerate(["Il danse comme un pied, celui-là.", "{N} chante faux, c'est terrible.", "Regarde-le faire le pitre.", "{N} fait encore le cochon.",
                           "Elle siffle tout le temps, celle-là.", "Il est toujours au garde-à-vous, {N}.", "Je vais danser un peu.", "Je m'assieds deux minutes.",
                           "Je chante faux, hein ?", "Il raconte des blagues nulles."]):
        ajoute("rep_remarque", f"AK{i + 1}", lambda e, c, t=t: (t.replace("{N}", c["prenom"]), "personne"))

    # Des bruits, des chansons.
    for i, t in enumerate(["La la la.", "Allez allez allez !", "Oh non !", "Aïe !", "Ah ah ah !", "Hmm.", "Ouais ouais.", "Yes !", "Oh là là."]):
        ajoute("bruit", f"B{i + 1}", lambda e, c, t=t: (t, "personne"))
    return f


# --- Les situations ------------------------------------------------------------------------------------------

def equipe(n, metiers=None):
    noms = R.sample(PRENOMS, n)
    return [{"prenom": noms[i], "metier": (metiers[i] if metiers and i < len(metiers) else R.choice(METIERS)), "distance": round(R.uniform(2, 15), 1),
             "regard": round(R.uniform(-0.9, 0.85), 2), "depuis": -1, "question": False, "fenetre": False, "derniers": []} for i in range(n)]


def regarder(e, x):
    x["regard"] = round(R.uniform(0.93, 1.0), 3)
    x["distance"] = round(R.uniform(1.5, 6), 1)


def joueurs(n, pres=False):
    noms = R.sample(JOUEURS, n)
    return [{"nom": noms[i], "distance": round(R.uniform(2, 8) if pres else R.uniform(4, 30), 1), "regard": round(R.uniform(-0.9, 0.85), 2)} for i in range(n)]


def situation(cat, regle, fn):
    """Une situation de cette catégorie, ou None si elle n'a pas de sens (pas assez d'ouvriers, etc.)."""
    n = R.choices([1, 2, 3, 4, 5], weights=[12, 28, 30, 18, 12])[0]
    if cat in ("deux_noms", "groupe_sauf", "autre_metier", "pluriel", "rep_autre") and n < 2:
        n = R.choice([2, 3, 4])
    e = equipe(n)
    cible = R.choice(e)
    micro = R.random() < 0.85
    js = joueurs(R.choices([0, 1, 2], weights=[60, 30, 10])[0])
    vu = None
    if cat in ("regard", "autre_metier", "rep_regard", "rep_autre"):
        vu = cible
    elif cat in ("vocatif", "groupe", "groupe_sauf", "deux_noms", "metier", "pluriel", "remarque", "soliloque", "bruit", "reponse", "suite",
                 "rep_vocatif", "rep_groupe", "rep_remarque"):
        vu = R.choice([None, None, cible, R.choice(e)])
    if cat in ("autre_metier", "rep_autre"):
        autres = [m for m in METIERS if m != cible["metier"]]
        cible["autre"] = R.choice(autres)
        if R.random() < 0.8 and not any(x["metier"] == cible["autre"] for x in e if x is not cible):
            R.choice([x for x in e if x is not cible])["metier"] = cible["autre"]
    if cat in ("sans_regard", "rep_metier"):
        js, vu = [], None
        micro = True
    if cat == "autre_joueur":
        js = joueurs(R.choice([1, 1, 2]), pres=True)
        vu = R.choice(e)
        if R.random() < 0.5:
            js[0]["regard"] = round(R.uniform(0.9, 1.0), 3)
        micro = True
    if cat == "reponse":
        cible["question"] = True
        cible["depuis"] = round(R.uniform(2, 25), 1)
        cible["derniers"] = [f"{cible['prenom']} : " + R.choice(["Tu veux du fer ou du cuivre, chef ?", "Je continue, chef ?", "Je prends du chêne ou du bouleau ?",
                                                                  "Je rentre au poste, chef ?", "Tu as besoin de moi ?"])]
        if R.random() < 0.3:
            other = R.choice(e)
            if other is not cible:
                other["depuis"] = round(R.uniform(60, 200), 1)
    if cat == "suite":
        cible["depuis"] = round(R.uniform(3, 30), 1)
        cible["derniers"] = ["Chef : " + R.choice(ORDRES + ORDRES_METIER[cible["metier"]]), f"{cible['prenom']} : D'accord, chef."]
    if vu is not None:
        regarder(e, vu)
    # Au micro, avec un autre joueur à portée de voix, on n'écoute que s'il regarde un de ses ouvriers (règle du mod).
    if micro and any(j["distance"] <= 32 for j in js) and vu is None:
        if cat in ("sans_regard",):
            js = []
        else:
            vu = R.choice(e)
            regarder(e, vu)
    # Écrit dans la fenêtre d'un ouvrier (celui sur qui il a cliqué : il le regarde).
    if not micro:
        if cat in ("autre_joueur", "bruit", "soliloque", "sans_regard", "rep_metier"):
            micro = True
        else:
            fen = cible if cat in ("regard", "autre_metier", "reponse", "suite", "rep_regard", "rep_autre") else R.choice(e)
            fen["fenetre"] = True
            if vu is None or vu is not fen:
                for x in e:
                    if x["regard"] >= 0.9:
                        x["regard"] = round(R.uniform(-0.5, 0.8), 2)
                regarder(e, fen)
                vu = fen
    # Un échange récent avec quelqu'un d'autre, parfois (pour que le contexte ne soit pas un indice trop facile).
    if R.random() < 0.25 and cat not in ("reponse", "suite"):
        x = R.choice(e)
        x["depuis"] = round(R.uniform(5, 150), 1)
        x["derniers"] = ["Chef : " + R.choice(ORDRES), f"{x['prenom']} : Oui chef."]
    texte, quoi = fn(e, cible)
    if "{P}" in texte:
        if not js:
            js = joueurs(1, pres=True)
        texte = texte.replace("{P}", js[0]["nom"])
    # Les destinataires attendus.
    fen = next((x for x in e if x["fenetre"]), None)
    if quoi == "cible":
        attendu = [cible["prenom"]]
    elif quoi == "deux":
        if n < 2:
            return None
        attendu = [e[0]["prenom"], e[1]["prenom"]]
    elif quoi == "tous":
        attendu = [x["prenom"] for x in e]
    elif quoi == "tous_sauf":
        attendu = [x["prenom"] for x in e if x is not cible]
    elif quoi == "metier_tous":
        attendu = [x["prenom"] for x in e if x["metier"] == cible["metier"]]
    elif quoi == "metier_un":
        meme = [x for x in e if x["metier"] == cible["metier"]]
        vus = [x for x in meme if x["regard"] >= 0.9]
        if not vus and len(meme) > 1:
            meme.sort(key=lambda x: x["distance"])
            if meme[1]["distance"] < meme[0]["distance"] * 1.5:
                return None  # ambigu : deux du même métier presque aussi proches
        attendu = [(vus or sorted(meme, key=lambda x: x["distance"]))[0]["prenom"]]
    elif quoi == "regarde":
        attendu = [cible["prenom"]]
    elif quoi == "metier_autre":
        meme = sorted([x for x in e if x["metier"] == cible["autre"] and x is not cible], key=lambda x: x["distance"])
        if len(meme) > 1 and meme[1]["distance"] < meme[0]["distance"] * 1.5:
            return None
        attendu = [meme[0]["prenom"]] if meme else [cible["prenom"]]
    elif quoi in ("question", "suite"):
        attendu = [cible["prenom"]]
    else:
        attendu = [fen["prenom"]] if fen else []
    if fen and quoi == "personne":
        attendu = [fen["prenom"]]
    for x in e:
        x.pop("autre", None)
    return {"categorie": cat, "texte": texte, "micro": micro, "pnj": e, "joueurs": js, "attendu": attendu}


def main():
    sortie = []
    for cat, fams in familles().items():
        for k, (nom, fn) in enumerate(sorted(fams, key=lambda x: x[0])):
            R.seed(f"{cat}/{nom}")
            jeu = "test" if k % 4 == 3 else "entrainement"
            n = 0
            essais = 0
            while n < 14 and essais < 60:
                essais += 1
                s = situation(cat, None, fn)
                if s is None:
                    continue
                s["famille"] = nom
                s["jeu"] = jeu
                s["id"] = f"{nom}-{n}"
                sortie.append(s)
                n += 1
    (ICI / "scenarios.json").write_text(json.dumps(sortie, ensure_ascii=False, indent=0), encoding="utf-8")
    par_jeu = {j: sum(1 for s in sortie if s["jeu"] == j) for j in ("entrainement", "test")}
    print(len(sortie), "situations", par_jeu)


if __name__ == "__main__":
    main()
