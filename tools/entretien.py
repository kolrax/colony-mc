"""Entretien automatique, lancé par une tâche Windows à l'ouverture de session et chaque jour :
1. réveille le panneau de téléchargement (projet Supabase gratuit, qui s'endort après 7 jours sans visite) ;
2. copie la dernière sauvegarde du serveur (Simple Backups) dans ../sauvegardes/<monde>/ si elle n'y est pas déjà.
Rien n'est supprimé : les anciennes copies restent, à trier à la main. Journal : ../sauvegardes/entretien.log
"""
import shutil, urllib.request
from datetime import datetime
from pathlib import Path

import panel

DEST = Path(__file__).parent.parent / "sauvegardes"
MODPACK = "https://wegefcdewecwjvmhaaxb.supabase.co/functions/v1/modpack"


def log(msg):
    DEST.mkdir(exist_ok=True)
    with open(DEST / "entretien.log", "a", encoding="utf-8") as f:
        f.write(f"{datetime.now():%Y-%m-%d %H:%M}  {msg}\n")


def ls(path):
    try:
        return [f["attributes"] for f in panel.req("GET", "/files/list", directory=path)["data"]]
    except SystemExit:  # dossier absent : aucune sauvegarde encore
        return []


def main():
    try:
        urllib.request.urlopen(MODPACK, timeout=30).read()
        log("panneau de téléchargement : réveillé")
    except Exception as e:
        log(f"panneau de téléchargement : ERREUR {e}")

    for world in (d for d in ls("/simplebackups") if not d["is_file"]):
        zips = sorted((f for f in ls(f"/simplebackups/{world['name']}") if f["name"].endswith(".zip")), key=lambda f: f["modified_at"])
        if not zips:
            continue
        last, target = zips[-1], DEST / world["name"] / zips[-1]["name"]
        if target.exists():
            log(f"sauvegarde {world['name']} : déjà copiée ({last['name']})")
            continue
        url = panel.req("GET", "/files/download", file=f"/simplebackups/{world['name']}/{last['name']}")["attributes"]["url"]
        target.parent.mkdir(parents=True, exist_ok=True)
        part = target.with_suffix(".part")
        with urllib.request.urlopen(url, timeout=600) as r, open(part, "wb") as f:
            shutil.copyfileobj(r, f)
        part.rename(target)
        log(f"sauvegarde {world['name']} : copiée {last['name']} ({target.stat().st_size / 2**20:.0f} Mo)")


if __name__ == "__main__":
    try:
        main()
    except BaseException as e:  # tâche planifiée sans console : tout passe par le journal
        log(f"ERREUR {e}")
