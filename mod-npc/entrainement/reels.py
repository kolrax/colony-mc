"""Des situations réalistes, écrites à la main (tournures absentes des gabarits) : entrainement/reels.json.
Elles ne servent qu'à mesurer : jamais à l'entraînement. Les premières sont de vraies phrases du serveur (10/10/2026).

    python entrainement/reels.py
"""
import json
from pathlib import Path


def P(prenom, metier, d, regard, depuis=-1, question=False, fenetre=False, derniers=()):
    return {"prenom": prenom, "metier": metier, "distance": d, "regard": regard, "depuis": depuis, "question": question, "fenetre": fenetre,
            "derniers": list(derniers)}


def J(nom, d, regard=0.2):
    return {"nom": nom, "distance": d, "regard": regard}


def S(texte, pnj, attendu, joueurs=(), micro=True, note=""):
    return {"texte": texte, "micro": micro, "pnj": pnj, "joueurs": list(joueurs), "attendu": attendu, "note": note}


ENG, RAO = P("Enguerrand", "bucheron", 4, 0.96), P("Raoul", "guerrier", 3, 0.98)
L = [
    # Vraies phrases du serveur.
    S("C'est vraiment un chômeur ce mec.", [ENG], [], note="serveur : remarque sur lui"),
    S("Orphan, Enguerrand, tu peux y aller.", [P("Enguerrand", "bucheron", 5, 0.97)], ["Enguerrand"], [J("Orphan", 6)], note="serveur"),
    S("Tu peux courir 50 blocs et revenir vers moi ?", [RAO], ["Raoul"], note="serveur"),
    S("Merci.", [P("Raoul", "guerrier", 3, 0.97, depuis=10, derniers=["Raoul : Oui chef, je surveille les alentours."])], ["Raoul"], note="serveur"),
    S("Surveille les alentours.", [RAO], ["Raoul"], note="serveur"),
    # Prénoms, métiers, groupe.
    S("Raoul, va protéger la base.", [P("Raoul", "guerrier", 10, 0.2), P("Bertrand", "mineur", 3, 0.97)], ["Raoul"]),
    S("Il faudrait quelqu'un pour garder l'entrée.", [P("Raoul", "guerrier", 8, 0.1), P("Bertrand", "mineur", 4, 0.3)], ["Raoul"]),
    S("Les gars, on rentre.", [P("Bertrand", "mineur", 3, 0.95), P("Raoul", "guerrier", 6, 0.2), P("Odon", "bucheron", 9, -0.3)], ["Bertrand", "Raoul", "Odon"]),
    S("Bertrand, t'as trouvé du diamant ?", [P("Bertrand", "mineur", 7, 0.4), P("Raoul", "guerrier", 3, 0.95)], ["Bertrand"]),
    S("Ramène-moi du charbon.", [P("Raoul", "guerrier", 4, 0.96), P("Bertrand", "mineur", 9, 0.1)], ["Bertrand"]),
    S("Ramène-moi du charbon.", [P("Bertrand", "mineur", 4, 0.97), P("Raoul", "guerrier", 9, 0.1)], ["Bertrand"]),
    S("Coupe-moi du bouleau.", [P("Odon", "bucheron", 6, 0.3), P("Raoul", "guerrier", 3, 0.5)], ["Odon"]),
    S("Toi, suis-moi.", [P("Odon", "bucheron", 3, 0.97), P("Raoul", "guerrier", 6, 0.1)], ["Odon"]),
    S("Vous deux, suivez-moi.", [P("Odon", "bucheron", 3, 0.4), P("Raoul", "guerrier", 6, 0.1)], ["Odon", "Raoul"]),
    S("Hé, le guerrier, viens par là !", [P("Raoul", "guerrier", 12, 0.2), P("Bertrand", "mineur", 4, 0.96)], ["Raoul"]),
    S("Les mineurs, rentrez au poste.", [P("Bertrand", "mineur", 5, 0.5), P("Thibaut", "mineur", 8, 0.1), P("Raoul", "guerrier", 4, 0.95)], ["Bertrand", "Thibaut"]),
    S("Tout le monde sauf Odon, suivez-moi.", [P("Odon", "bucheron", 4, 0.3), P("Raoul", "guerrier", 6, 0.1), P("Bertrand", "mineur", 5, 0.2)], ["Raoul", "Bertrand"]),
    S("Odon, Bertrand, au travail !", [P("Odon", "bucheron", 4, 0.3), P("Raoul", "guerrier", 6, 0.95), P("Bertrand", "mineur", 5, 0.2)], ["Odon", "Bertrand"]),
    S("Bonne nuit les gars.", [P("Odon", "bucheron", 4, 0.3), P("Raoul", "guerrier", 6, 0.1)], ["Odon", "Raoul"]),
    S("Allez hop, au travail tout le monde !", [P("Odon", "bucheron", 4, 0.3), P("Raoul", "guerrier", 6, 0.1), P("Bertrand", "mineur", 9, 0.0)], ["Odon", "Raoul", "Bertrand"]),
    S("Lancelin, Thibaut, venez m'aider.", [P("Lancelin", "bucheron", 5, 0.2), P("Thibaut", "mineur", 8, 0.4)], ["Lancelin", "Thibaut"]),
    S("Enguerran, tu reviens quand ?", [P("Enguerrand", "bucheron", 14, -0.2)], ["Enguerrand"], note="prénom mal transcrit"),
    S("Bérangère, au boulot.", [P("Bérengère", "mineur", 5, 0.3), P("Raoul", "guerrier", 3, 0.96)], ["Bérengère"], note="prénom mal transcrit"),
    S("Raoul, Raoul !", [P("Raoul", "guerrier", 14, -0.5), P("Odon", "bucheron", 3, 0.2)], ["Raoul"]),
    S("Bon travail, Bertrand.", [P("Bertrand", "mineur", 5, 0.4), P("Odon", "bucheron", 3, 0.96)], ["Bertrand"]),
    # Il regarde et parle.
    S("Salut !", [P("Bertrand", "mineur", 3, 0.97), P("Odon", "bucheron", 7, 0.1)], ["Bertrand"]),
    S("Va dormir un peu.", [P("Odon", "bucheron", 3, 0.98)], ["Odon"]),
    S("Donne-moi ce que tu as dans ton sac.", [P("Bertrand", "mineur", 2.5, 0.99), P("Raoul", "guerrier", 5, 0.2)], ["Bertrand"]),
    S("Fais gaffe aux creepers.", [P("Odon", "bucheron", 4, 0.96)], ["Odon"]),
    S("Prends ça.", [P("Odon", "bucheron", 2, 0.97), P("Raoul", "guerrier", 6, 0.3)], ["Odon"]),
    S("Combien t'as de bûches ?", [P("Odon", "bucheron", 4, 0.95), P("Bertrand", "mineur", 6, 0.1)], ["Odon"]),
    S("Viens par ici toi.", [P("Thibaut", "mineur", 4, 0.97), P("Lancelin", "bucheron", 8, 0.3)], ["Thibaut"]),
    S("Va chercher du fer et du charbon.", [P("Lancelin", "bucheron", 4, 0.96), P("Thibaut", "mineur", 10, 0.0)], ["Thibaut"]),
    S("Tu peux me laisser tranquille ?", [P("Lancelin", "bucheron", 3, 0.97)], ["Lancelin"]),
    S("Raoul, suis-moi.", [P("Raoul", "guerrier", 3, 0.97)], ["Raoul"], [J("Nielscutt", 6, 0.2)]),
    S("Suis-moi.", [P("Raoul", "guerrier", 3, 0.97)], ["Raoul"], [J("Nielscutt", 6, 0.3)]),
    # La suite d'un échange, une réponse.
    S("Ça marche, merci !", [P("Odon", "bucheron", 5, 0.5, depuis=5, derniers=["Odon : Je vais couper du chêne, chef."])], ["Odon"]),
    S("T'es où ?", [P("Odon", "bucheron", 15, -0.3, depuis=20, derniers=["Chef : Ramène du bouleau.", "Odon : J'y vais, chef."])], ["Odon"]),
    S("Oui.", [P("Raoul", "guerrier", 6, 0.2, depuis=5, question=True, derniers=["Raoul : Je reste ici, chef ?"]), P("Odon", "bucheron", 4, 0.3)], ["Raoul"]),
    S("Non merci.", [P("Bertrand", "mineur", 7, 0.1, depuis=8, question=True, derniers=["Bertrand : Tu veux que je continue à miner ?"])], ["Bertrand"]),
    S("Arrête de me suivre.", [P("Lancelin", "bucheron", 2, 0.3, depuis=40, derniers=["Chef : Suis-moi.", "Lancelin : Je te suis, chef."]), P("Thibaut", "mineur", 9, 0.1)], ["Lancelin"]),
    # Écrit dans la fenêtre de conversation.
    S("comment tu vas ?", [P("Odon", "bucheron", 3, 0.97, fenetre=True), P("Raoul", "guerrier", 6, 0.2)], ["Odon"], micro=False),
    S("Raoul viens ici", [P("Odon", "bucheron", 3, 0.97, fenetre=True), P("Raoul", "guerrier", 6, 0.2)], ["Raoul"], micro=False),
    S("va garder la base", [P("Bertrand", "mineur", 3, 0.97, fenetre=True), P("Raoul", "guerrier", 8, 0.2)], ["Raoul"], micro=False),
    S("c'est un fainéant ce Raoul", [P("Bertrand", "mineur", 3, 0.97, fenetre=True), P("Raoul", "guerrier", 8, 0.2)], ["Bertrand"], micro=False),
    S("les gars venez", [P("Odon", "bucheron", 3, 0.97, fenetre=True), P("Raoul", "guerrier", 6, 0.2)], ["Odon", "Raoul"], micro=False),
    S("ramène 64 pierres", [P("Odon", "bucheron", 3, 0.97, fenetre=True)], ["Odon"], micro=False),
    # Pas pour eux.
    S("Il bosse bien Bertrand, hein ?", [P("Bertrand", "mineur", 6, 0.93)], [], [J("Nielscutt", 5, 0.3)]),
    S("Niels, viens voir mes ouvriers.", [P("Bertrand", "mineur", 4, 0.95)], [], [J("Nielscutt", 6, 0.4)]),
    S("Je vais au village, je reviens.", [P("Bertrand", "mineur", 5, 0.2), P("Odon", "bucheron", 8, 0.1)], []),
    S("Il est quelle heure ?", [P("Bertrand", "mineur", 5, 0.2)], []),
    S("Regarde ce que Bertrand a trouvé !", [P("Bertrand", "mineur", 4, 0.95)], [], [J("Nielscutt", 4, 0.5)]),
    S("Mais qu'est-ce qu'il fabrique, celui-là ?", [P("Bertrand", "mineur", 6, 0.96)], []),
    S("Pourquoi il ne travaille pas, lui ?", [P("Raoul", "guerrier", 7, 0.95), P("Odon", "bucheron", 4, 0.2)], []),
    S("Kalia, t'as vu mon guerrier ?", [P("Raoul", "guerrier", 5, 0.95)], [], [J("Kalia", 6, 0.3)]),
    S("Mon guerrier est trop fort.", [P("Raoul", "guerrier", 5, 0.95)], [], [J("Kalia", 6, 0.3)]),
    S("Bertrand est vraiment rapide.", [P("Bertrand", "mineur", 5, 0.95)], []),
    S("Je crois qu'on va manquer de fer.", [P("Thibaut", "mineur", 6, 0.3)], []),
    S("On a assez de bois là.", [P("Odon", "bucheron", 6, 0.3)], []),
    S("Merci Niels !", [P("Bertrand", "mineur", 4, 0.94)], [], [J("Nielscutt", 4, 0.6)]),
    S("Attends-moi, j'arrive !", [P("Bertrand", "mineur", 4, 0.94)], [], [J("Nielscutt", 9, 0.92)]),
    S("Ah, enfin du diamant !", [P("Bertrand", "mineur", 5, 0.3)], []),
    S("Purée, il fait noir ici.", [P("Bertrand", "mineur", 3, 0.95)], []),
]

if __name__ == "__main__":
    for i, s in enumerate(L):
        s["id"] = f"reel-{i + 1}"
        s["categorie"] = "reel"
        s["famille"] = "reel"
        s["jeu"] = "reel"
    (Path(__file__).parent / "reels.json").write_text(json.dumps(L, ensure_ascii=False, indent=0), encoding="utf-8")
    print(len(L), "situations réelles")
