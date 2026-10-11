"""Publie une version du modpack.

  python release.py prepare <version> "<notes>"   ajoute la version (lock.json actuel) à releases.json et l'injecte
                                                   dans supabase/functions/modpack/index.ts, à redéployer ensuite
  python release.py announce <version>            poste la version dans 📦┃modpacks et remet le panneau en bas
"""
import base64, hashlib, json, re, shutil, sys
from datetime import date
from pathlib import Path

HERE = Path(__file__).parent
sys.path.insert(0, str(HERE.parent / "tools"))
from build import MC, NEOFORGE
import discord as d

FUNCTION = HERE.parent / "supabase/functions/modpack/index.ts"
EMBARQUES = HERE / "embarques"            # nos mods maison (pas sur Modrinth) : le jar à livrer avec la prochaine version
ARCHIVE = EMBARQUES / "archive"           # les jars déjà publiés, nommés <sha1>-<fichier>
GARDER_VERSIONS = 2                       # mods embarqués gardés pour les 2 dernières versions (les plus anciennes ne peuvent de toute façon plus rejoindre le serveur)
SELF = "https://wegefcdewecwjvmhaaxb.supabase.co/functions/v1/modpack"
LAUNCHERS = [("modrinth", "Modrinth App", "🟢"), ("curseforge", "CurseForge", "🟠"), ("zip", "Sans launcher", "📁")]
MOIS = "janvier février mars avril mai juin juillet août septembre octobre novembre décembre".split()


def fr(iso):
    y, m, j = map(int, iso.split("-"))
    return f"{j} {MOIS[m - 1]} {y}"


def load():
    p = HERE / "releases.json"
    return json.loads(p.read_text(encoding="utf-8")) if p.exists() else []


def prepare(version, notes):
    keep = ("file", "url", "sha1", "sha512", "size", "client", "server")
    mods = [{k: m[k] for k in keep} for m in json.loads((HERE / "lock.json").read_text(encoding="utf-8"))]
    over = HERE / "overrides"  # fichiers copiés dans le dossier du jeu (ex. servers.dat)
    overrides = [{"path": f.relative_to(over).as_posix(), "b64": base64.b64encode(f.read_bytes()).decode()}
                 for f in sorted(over.rglob("*")) if f.is_file()] if over.exists() else []
    embarques = []
    ARCHIVE.mkdir(parents=True, exist_ok=True)
    for f in sorted(EMBARQUES.glob("*.jar")):
        sha1 = hashlib.sha1(f.read_bytes()).hexdigest()
        shutil.copyfile(f, ARCHIVE / f"{sha1}-{f.name}")
        embarques.append({"file": f.name, "sha1": sha1})
    rel = {"version": version, "date": date.today().isoformat(), "notes": notes, "minecraft": MC, "neoforge": NEOFORGE,
           "mods": mods, "overrides": overrides, "embarques": embarques}
    releases = [rel] + [r for r in load() if r["version"] != version]
    (HERE / "releases.json").write_text(json.dumps(releases, indent=1, ensure_ascii=False), encoding="utf-8")
    fichiers = {}
    for r in releases[:GARDER_VERSIONS]:
        for e in r.get("embarques", []):
            fichiers[e["sha1"]] = base64.b64encode((ARCHIVE / f"{e['sha1']}-{e['file']}").read_bytes()).decode()
    code = FUNCTION.read_text(encoding="utf-8")
    code, n = re.subn(r"/\*RELEASES\*/.*?/\*FIN\*/", lambda _: f"/*RELEASES*/{json.dumps(releases, ensure_ascii=False)}/*FIN*/", code, flags=re.S)
    assert n == 1, "marqueurs RELEASES introuvables dans index.ts"
    code, n = re.subn(r"/\*FICHIERS\*/.*?/\*FIN_FICHIERS\*/", lambda _: f"/*FICHIERS*/{json.dumps(fichiers)}/*FIN_FICHIERS*/", code, flags=re.S)
    assert n == 1, "marqueurs FICHIERS introuvables dans index.ts"
    FUNCTION.write_text(code, encoding="utf-8")
    print(f"{version} préparée ({len(releases)} version(s)). Redéploie la fonction « modpack », puis : release.py announce {version}")


def panel(latest):
    return {
        "embeds": [{
            "title": "📦 Télécharger le modpack", "color": 0xC9A227,
            "description": "Ce salon rassemble toutes les versions du modpack, de la plus ancienne à la plus récente.\n"
                           "**Choisis ton launcher** : tu reçois la dernière version, et tu peux revenir à une version précédente si besoin.",
            "fields": [{"name": "Dernière version", "value": f"**{latest['version']}** · {fr(latest['date'])} · "
                                                            f"Minecraft {latest['minecraft']} · NeoForge {latest['neoforge']}"}],
            "footer": {"text": "Pas sûr ? Prends Modrinth App : l'installation se fait en un clic."},
        }],
        "components": [{"type": 1, "components": [
            {"type": 2, "style": 1 if l == "modrinth" else 2, "label": label, "emoji": {"name": e}, "custom_id": f"mp:launcher:{l}"}
            for l, label, e in LAUNCHERS]}],
    }


def announce(version):
    releases = load()
    rel = next(r for r in releases if r["version"] == version)
    cid = {d.short(c["name"]): c for c in d.call("GET", f"/guilds/{d.GUILD}/channels")}["modpacks"]["id"]
    n = sum(m["client"] != "unsupported" for m in rel["mods"])
    d.call("POST", f"/channels/{cid}/messages", {
        "embeds": [{
            "title": f"📦 Version {version}", "color": 0x6AA84F, "description": rel["notes"],
            "fields": [{"name": "Jeu", "value": f"Minecraft {rel['minecraft']} · NeoForge {rel['neoforge']}", "inline": True},
                       {"name": "Contenu", "value": f"{n} mods", "inline": True},
                       {"name": "Publiée le", "value": fr(rel["date"]), "inline": True}],
        }],
        "components": [{"type": 1, "components": [
            {"type": 2, "style": 5, "label": label, "emoji": {"name": e}, "url": f"{SELF}/download?l={l}&v={version}"}
            for l, label, e in LAUNCHERS]}],
    })
    # le panneau reste toujours le dernier message du salon
    for m in d.call("GET", f"/channels/{cid}/messages?limit=50"):
        ids = [c.get("custom_id", "") for row in m.get("components", []) for c in row.get("components", [])]
        if m["author"].get("bot") and any(i.startswith("mp:launcher") for i in ids):
            d.call("DELETE", f"/channels/{cid}/messages/{m['id']}")
    d.call("POST", f"/channels/{cid}/messages", panel(releases[0]))
    print(f"{version} annoncée dans 📦┃modpacks, panneau mis à jour (dernière : {releases[0]['version']})")


if __name__ == "__main__":
    cmd, *args = sys.argv[1:] or ["help"]
    {"prepare": prepare, "announce": announce}.get(cmd, lambda *_: sys.exit(__doc__))(*args)
