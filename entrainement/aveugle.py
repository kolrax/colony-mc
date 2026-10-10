"""Un second jeu de situations réalistes, écrit AVANT la mise au point de la formule et jamais regardé pendant :
la mesure finale, honnête (reels.json a servi à repérer des trous de vocabulaire, il n'est plus « neuf »).

    python entrainement/aveugle.py
"""
import json
from pathlib import Path

from reels import P, J, S

L = [
    S("Gauthier, file au poste !", [P("Gauthier", "mineur", 9, 0.1), P("Ysolde", "bucheron", 3, 0.97)], ["Gauthier"]),
    S("Ysolde, tu me coupes un peu de sapin ?", [P("Gauthier", "mineur", 3, 0.96), P("Ysolde", "bucheron", 8, 0.2)], ["Ysolde"]),
    S("Merci ma grande.", [P("Ysolde", "bucheron", 3, 0.96, depuis=6, derniers=["Ysolde : Voilà tes bûches, chef."])], ["Ysolde"]),
    S("On bouge, tout le monde !", [P("Gauthier", "mineur", 3, 0.3), P("Ysolde", "bucheron", 6, 0.2), P("Raoul", "guerrier", 9, 0.1)], ["Gauthier", "Ysolde", "Raoul"]),
    S("Restez tous ici, je reviens.", [P("Gauthier", "mineur", 3, 0.3), P("Ysolde", "bucheron", 6, 0.2)], ["Gauthier", "Ysolde"]),
    S("Les bûcherons, au boulot !", [P("Ysolde", "bucheron", 6, 0.2), P("Odon", "bucheron", 8, 0.1), P("Gauthier", "mineur", 3, 0.95)], ["Ysolde", "Odon"]),
    S("Qui peut me ramener du cuivre ?", [P("Gauthier", "mineur", 7, 0.2), P("Ysolde", "bucheron", 3, 0.3)], ["Gauthier"]),
    S("Il me faut quelqu'un pour garder la porte.", [P("Raoul", "guerrier", 9, 0.1), P("Gauthier", "mineur", 3, 0.4)], ["Raoul"]),
    S("Viens là, toi.", [P("Gauthier", "mineur", 4, 0.97), P("Ysolde", "bucheron", 6, 0.3)], ["Gauthier"]),
    S("T'as mangé ?", [P("Ysolde", "bucheron", 3, 0.97)], ["Ysolde"]),
    S("Ça avance ?", [P("Gauthier", "mineur", 5, 0.95), P("Raoul", "guerrier", 8, 0.2)], ["Gauthier"]),
    S("Bonne nuit Raoul.", [P("Raoul", "guerrier", 6, 0.3), P("Gauthier", "mineur", 3, 0.96)], ["Raoul"]),
    S("Raoul, protège Ysolde.", [P("Raoul", "guerrier", 6, 0.3), P("Ysolde", "bucheron", 3, 0.96)], ["Raoul"]),
    S("Arrêtez tout, rentrez !", [P("Gauthier", "mineur", 3, 0.3), P("Ysolde", "bucheron", 6, 0.2)], ["Gauthier", "Ysolde"]),
    S("Oui, ramène-en trente.", [P("Gauthier", "mineur", 6, 0.3, depuis=7, question=True, derniers=["Gauthier : J'en ramène combien, chef ?"])], ["Gauthier"]),
    S("Le guerrier, reste avec moi.", [P("Raoul", "guerrier", 10, 0.1), P("Gauthier", "mineur", 3, 0.96)], ["Raoul"]),
    S("toi viens", [P("Ysolde", "bucheron", 3, 0.97, fenetre=True), P("Raoul", "guerrier", 6, 0.2)], ["Ysolde"], micro=False),
    S("dis à Raoul de rentrer", [P("Ysolde", "bucheron", 3, 0.97, fenetre=True), P("Raoul", "guerrier", 6, 0.2)], ["Ysolde"], micro=False),
    S("Gauthier il est trop lent", [P("Ysolde", "bucheron", 3, 0.97, fenetre=True), P("Gauthier", "mineur", 6, 0.2)], ["Ysolde"], micro=False),
    # Pas pour eux.
    S("Il est super efficace, Gauthier.", [P("Gauthier", "mineur", 5, 0.95)], [], [J("Nielscutt", 6, 0.3)]),
    S("Niels, t'as vu Ysolde ?", [P("Ysolde", "bucheron", 4, 0.95)], [], [J("Nielscutt", 5, 0.5)]),
    S("Viens on va à la mine.", [P("Gauthier", "mineur", 4, 0.94)], [], [J("Nielscutt", 4, 0.93)]),
    S("Encore un creeper, génial.", [P("Raoul", "guerrier", 5, 0.4)], []),
    S("Je vais me coucher.", [P("Raoul", "guerrier", 5, 0.95)], []),
    S("Où j'ai mis mes torches ?", [P("Gauthier", "mineur", 4, 0.3)], []),
    S("Il nous faudrait plus de bois pour le toit.", [P("Ysolde", "bucheron", 7, 0.3)], [], [J("Nielscutt", 5, 0.4)]),
    S("Elle est où la mine déjà ?", [P("Gauthier", "mineur", 5, 0.95)], [], [J("Nielscutt", 5, 0.5)]),
    S("Trop bien ce château !", [P("Gauthier", "mineur", 5, 0.3)], []),
    S("Mes ouvriers sont au top.", [P("Gauthier", "mineur", 5, 0.95), P("Ysolde", "bucheron", 7, 0.2)], [], [J("Kalia", 6, 0.4)]),
    S("Hmm, voyons ce qu'il me reste.", [P("Gauthier", "mineur", 3, 0.96)], []),
    S("Ah non, j'ai plus de pain.", [P("Ysolde", "bucheron", 3, 0.95)], []),
    S("Bravo Kalia !", [P("Raoul", "guerrier", 4, 0.94)], [], [J("Kalia", 5, 0.6)]),
]

if __name__ == "__main__":
    for i, s in enumerate(L):
        s["id"] = f"aveugle-{i + 1}"
        s["categorie"] = "aveugle"
        s["famille"] = "aveugle"
        s["jeu"] = "aveugle"
    (Path(__file__).parent / "aveugle.json").write_text(json.dumps(L, ensure_ascii=False, indent=0), encoding="utf-8")
    print(len(L), "situations à l'aveugle")
