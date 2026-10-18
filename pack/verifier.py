"""Vérifie que chaque jar du pack (et les jars qu'il contient) accepte notre NeoForge et notre Minecraft,
et que ses dépendances obligatoires sont présentes. Évite un modpack qui ne se lance pas (1.0.11 : Forgified
Fabric API 2.3.6 exigeait NeoForge 21.1.262 dans un jar imbriqué).

Usage : python verifier.py   (lit lock.json et embarques/*.jar ; appelé aussi par build.py)
"""
import io, json, re, sys, urllib.request, zipfile
from pathlib import Path

HERE = Path(__file__).parent
CACHE = HERE / "cache"
VERSIONS = {"neoforge": "21.1.255", "minecraft": "1.21.1"}
VERIFIEES = {"neoforge"}  # la plage « minecraft » n'est pas appliquée par NeoForge (Iris et JEI déclarent [1.21,1.21.1) et tournent)


def num(v):
    return tuple(int(x) for x in re.findall(r"\d+", v))


def accepte(plage, v):
    """Plage Maven ([a,b), [a], a seul = toute version) ; plusieurs plages séparées par des virgules hors crochets."""
    plage = plage.replace(" ", "")
    if plage in ("", "*") or not plage.startswith(("[", "(")):
        return True
    for p in re.findall(r"[\[(][^\])]*[\])]", plage):
        bas, _, haut = p[1:-1].partition(",") if "," in p else (p[1:-1], "", p[1:-1])
        ok_bas = bas in ("", "*") or (num(v) >= num(bas) if p[0] == "[" else num(v) > num(bas))
        ok_haut = haut in ("", "*") or (num(v) <= num(haut) if p[-1] == "]" else num(v) < num(haut))
        if ok_bas and ok_haut:
            return True
    return False


def tomls(octets, nom, out):
    """Toutes les déclarations de mods d'un jar et de ses jars imbriqués : [(nom du jar, texte du toml)]."""
    z = zipfile.ZipFile(io.BytesIO(octets))
    for n in z.namelist():
        if n == "META-INF/neoforge.mods.toml":
            out.append((nom, z.read(n).decode("utf-8", "replace")))
        elif n.endswith(".jar"):
            tomls(z.read(n), n.rsplit("/", 1)[-1], out)
    return out


def jar(m):
    f = CACHE / m["file"]
    if not f.exists():
        CACHE.mkdir(exist_ok=True)
        req = urllib.request.Request(m["url"], headers={"User-Agent": "la-colonie-pack/1.0"})
        f.write_bytes(urllib.request.urlopen(req).read())
    return f.read_bytes()


def verifier(mods):
    erreurs = []
    for cote in ("client", "server"):
        decl = []
        for m in mods:
            if m[cote] == "required":
                tomls(jar(m), m["file"], decl)
        for f in sorted((HERE / "embarques").glob("*.jar")):
            tomls(f.read_bytes(), f.name, decl)
        presents = {i for _, t in decl for i in re.findall(r'^\s*modId\s*=\s*"([^"]+)"', t, re.M)} | set(VERSIONS)
        for nom, t in decl:
            for bloc in t.split("[[dependencies")[1:]:
                g = lambda k: (re.search(rf'^\s*{k}\s*=\s*"?([^"\n#]*)"?', bloc, re.M) or [None, ""])[1].strip()
                dep, sorte, cotes = g("modId"), (g("type") or ("required" if g("mandatory") != "false" else "optional")).lower(), g("side").upper()
                if sorte != "required" or cotes == ("CLIENT" if cote == "server" else "SERVER"):
                    continue
                if dep in VERIFIEES and not accepte(g("versionRange"), VERSIONS[dep]):
                    erreurs.append(f"[{cote}] {nom} exige {dep} {g('versionRange')}, le pack a {VERSIONS[dep]}")
                elif dep not in presents:
                    erreurs.append(f"[{cote}] {nom} exige le mod « {dep} », absent du pack")
    return sorted(set(erreurs))


if __name__ == "__main__":
    assert accepte("[21.1.169,)", "21.1.255") and not accepte("[21.1.262,)", "21.1.255")
    assert accepte("[1.21],[1.21.1]", "1.21.1") and accepte("[1.21, 1.21.1]", "1.21.1") and not accepte("[1.21.2,1.22)", "1.21.1")
    err = verifier(json.loads((HERE / "lock.json").read_text(encoding="utf-8")))
    print("\n".join(err) or "OK : tous les jars acceptent NeoForge 21.1.255, dépendances présentes")
    sys.exit(1 if err else 0)
