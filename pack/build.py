"""Résout mods.txt via Modrinth (1.21.1 / NeoForge), écrit lock.json et le .mrpack des joueurs.

Usage : python build.py <version_du_pack>   ex. python build.py 0.1.0
"""
import json, sys, urllib.parse, urllib.request, zipfile
from pathlib import Path

MC, LOADER, NEOFORGE = "1.21.1", "neoforge", "21.1.255"
HERE = Path(__file__).parent
API = "https://api.modrinth.com/v2"


def get(path, **params):
    q = "?" + urllib.parse.urlencode({k: json.dumps(v) for k, v in params.items()}) if params else ""
    req = urllib.request.Request(API + path + q, headers={"User-Agent": "la-colonie-pack/1.0"})
    with urllib.request.urlopen(req) as r:
        return json.load(r)


def best_version(project_id, pin=None):
    vs = get(f"/project/{project_id}/version", loaders=[LOADER], game_versions=[MC])
    if pin:  # « slug==version » dans mods.txt
        vs = [v for v in vs if v["version_number"] == pin]
    if not vs:
        raise SystemExit(f"Aucune version {MC}/{LOADER} {pin or ''} pour {project_id}")
    return next((v for v in vs if v["version_type"] == "release"), vs[0])


def resolve(entries):
    # tag : None = client+serveur, "client" / "server" = un seul côté. Une dépendance partagée prend l'union des côtés.
    mods, projects, todo = {}, {}, list(entries)
    while todo:
        ref, tag, parent, pin = todo.pop(0)
        p = projects.get(ref) or get(f"/project/{ref}")
        projects[ref] = projects[p["id"]] = p
        m = mods.get(p["id"])
        if m and tag in m["tags"]:
            continue
        if not m:
            v = best_version(p["id"], pin)
            f = next((f for f in v["files"] if f["primary"]), v["files"][0])
            m = mods[p["id"]] = dict(slug=p["slug"], title=p["title"], version=v["version_number"], file=f["filename"],
                                     url=f["url"], sha1=f["hashes"]["sha1"], sha512=f["hashes"]["sha512"], size=f["size"],
                                     required_by=parent, tags=set(), p=p, vid=v["id"],
                                     need=[(d["project_id"], d["version_id"]) for d in v["dependencies"]
                                           if d["dependency_type"] == "required" and d["project_id"] and d["version_id"]],
                                     deps=[d["project_id"] for d in v["dependencies"] if d["dependency_type"] == "required" and d["project_id"]],
                                     incompatible=[d["project_id"] for d in v["dependencies"] if d["dependency_type"] == "incompatible"])
        m["tags"].add(tag)
        todo += [(d, tag, p["slug"], None) for d in m["deps"]]
    # Une version exigée par un mod (ex. Iris 1.8.12 -> Sodium 0.6.13) doit être celle du pack : sinon le jeu plante.
    for m in mods.values():
        for pid, vid in m["need"]:
            if pid in mods and mods[pid]["vid"] != vid:
                want = get(f"/version/{vid}")["version_number"]
                raise SystemExit(f"{m['slug']} {m['version']} exige {mods[pid]['slug']} {want}, le pack a {mods[pid]['version']} : "
                                 f"épingle « {mods[pid]['slug']}=={want} » dans mods.txt ou change de version de {m['slug']}")
    for m in mods.values():
        m.pop("need"), m.pop("vid")
        clash = [mods[i]["slug"] for i in m.pop("incompatible") if i in mods]
        assert not clash, f"{m['slug']} est incompatible avec {clash}"
        tags, p = m.pop("tags"), m.pop("p")
        m.pop("deps")
        m["client"] = "required" if tags & {None, "client"} and p["client_side"] != "unsupported" else "unsupported"
        m["server"] = "required" if tags & {None, "server"} and p["server_side"] != "unsupported" else "unsupported"
    return sorted(mods.values(), key=lambda m: m["slug"])


def main(pack_version):
    entries = []
    for line in (HERE / "mods.txt").read_text(encoding="utf-8").splitlines():
        parts = line.split("#")[0].split()
        if parts:
            ref, _, pin = parts[0].partition("==")
            entries.append((ref, parts[1] if len(parts) > 1 else None, None, pin or None))
    mods = resolve(entries)
    assert all(m["sha1"] and m["sha512"] for m in mods)
    (HERE / "lock.json").write_text(json.dumps(mods, indent=2, ensure_ascii=False), encoding="utf-8")
    from verifier import verifier  # jars imbriqués compris : un pack qui ne se lancerait pas s'arrête ici
    if err := verifier(mods):
        raise SystemExit("Le pack ne se lancerait pas :\n" + "\n".join(err))

    index = {"formatVersion": 1, "game": "minecraft", "versionId": pack_version, "name": "La Colonie",
             "dependencies": {"minecraft": MC, "neoforge": NEOFORGE},
             "files": [{"path": f"mods/{m['file']}", "hashes": {"sha1": m["sha1"], "sha512": m["sha512"]},
                        "env": {"client": m["client"], "server": m["server"]},
                        "downloads": [m["url"]], "fileSize": m["size"]}
                       for m in mods if m["client"] != "unsupported"]}
    out = HERE.parent / f"La-Colonie-{pack_version}.mrpack"
    with zipfile.ZipFile(out, "w", zipfile.ZIP_DEFLATED) as z:
        z.writestr("modrinth.index.json", json.dumps(index, indent=2))
        for f in (HERE / "overrides").rglob("*") if (HERE / "overrides").exists() else []:
            if f.is_file():
                z.write(f, f.relative_to(HERE).as_posix())

    for m in mods:
        side = "client+serveur" if m["client"] == m["server"] == "required" else ("client" if m["server"] == "unsupported" else "serveur")
        dep = f"  (dépendance de {m['required_by']})" if m["required_by"] else ""
        print(f"{m['title']:<40} {m['version']:<28} {side}{dep}")
    print(f"\n{len(mods)} mods -> {out.name}")


if __name__ == "__main__":
    main(sys.argv[1] if len(sys.argv) > 1 else "0.1.0")
