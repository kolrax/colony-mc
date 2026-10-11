"""Données du panneau « Chantier des PNJ » (artefact), tirées du dépôt.

  python tools/suivi.py plan [sortie.json]     docs/plan.md -> {"maj", "lots": [{"num", "titre", "items": [{"texte", "fait"}]}]}

Ensuite : ArtifactData set, collection « plan », doc_id « lots », file_path = la sortie (avec if_version).
"""
import json, re, sys
from datetime import datetime, timezone
from pathlib import Path

ROOT = Path(__file__).parent.parent


def plan():
    lots, lot = [], None
    for ligne in (ROOT / "docs" / "plan.md").read_text(encoding="utf-8").splitlines():
        m = re.match(r"##\s+(?:(\d+)\.\s+)?(.+)", ligne)
        if m:
            lot = {"num": int(m[1]) if m[1] else None, "titre": m[2].strip(), "items": []}
            lots.append(lot)
            continue
        m = re.match(r"-\s+\[([ xX])\]\s+(.+)", ligne)
        if m and lot:
            lot["items"].append({"texte": m[2].strip(), "fait": m[1] != " "})
    lots = [l for l in lots if l["items"]]
    return {"maj": datetime.now(timezone.utc).isoformat(timespec="seconds"), "lots": lots}


if __name__ == "__main__":
    cmd, *a = sys.argv[1:] or ["help"]
    if cmd != "plan":
        sys.exit(__doc__)
    texte = json.dumps(plan(), ensure_ascii=False, indent=1)
    if a:
        Path(a[0]).write_text(texte, encoding="utf-8")
    else:
        sys.stdout.reconfigure(encoding="utf-8")
        print(texte)
