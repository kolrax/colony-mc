"""Les phrases que les PNJ n'ont pas comprises, lues sur le serveur (config/colonie_pnj/incompris.json). Lit ../.env.

  python incompris.py                  les 40 plus fréquentes, à l'écran
  python incompris.py 100              les 100 plus fréquentes
  python incompris.py --motif ia_seule seulement celles comprises par la seule IA (rien, ia_seule, ia_parole)
  python incompris.py --dossier D      copie aussi le fichier dans le dossier D (hors du dépôt : données de jeu)

Le fichier n'est jamais écrit dans le dépôt. Depuis Git Bash : exporter MSYS_NO_PATHCONV=1 (voir panel.py).
"""
import argparse, json, sys
from datetime import datetime
from pathlib import Path

import panel

DISTANT = "/config/colonie_pnj/incompris.json"
DEPOT = Path(__file__).resolve().parent.parent
MOTIFS = {"rien": "personne", "ia_seule": "IA seule", "ia_parole": "IA, paroles"}


def main():
    a = argparse.ArgumentParser(description="Phrases non comprises par les PNJ")
    a.add_argument("nombre", nargs="?", type=int, default=40)
    a.add_argument("--motif", choices=sorted(MOTIFS))
    a.add_argument("--dossier", type=Path)
    o = a.parse_args()

    texte = panel.req("GET", "/files/contents", raw=True, file=DISTANT)
    if o.dossier:
        d = o.dossier.resolve()
        if d == DEPOT or DEPOT in d.parents:
            sys.exit("Refusé : le dossier est dans le dépôt (public). Choisis un dossier hors du dépôt.")
        d.mkdir(parents=True, exist_ok=True)
        f = d / f"incompris-{datetime.now():%Y%m%d-%H%M}.json"
        f.write_text(texte, encoding="utf-8")
        print(f"copie : {f}")

    entrees = json.loads(texte).get("entrees", [])
    if o.motif:
        entrees = [e for e in entrees if e.get("motif") == o.motif]
    entrees.sort(key=lambda e: e.get("derniere", ""), reverse=True)  # les plus récentes d'abord…
    entrees.sort(key=lambda e: -e.get("nombre", 0))                  # …à fréquence égale (tri stable)
    print(f"{len(entrees)} phrases non comprises" + (f" (motif {o.motif})" if o.motif else ""))
    for e in entrees[:o.nombre]:
        quand = e.get("derniere", "")[:16].replace("T", " ")
        metiers = ", ".join(e.get("metiers", []))
        print(f"{e.get('nombre', 0):>4}x  {MOTIFS.get(e.get('motif'), e.get('motif')):<12} {quand}  {metiers:<20} « {e.get('phrase', '')} »")


if __name__ == "__main__":
    main()
