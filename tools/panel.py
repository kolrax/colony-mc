"""Pilotage du serveur via l'API client du panel MintServers (Pterodactyl). Lit ../.env.

  python panel.py status
  python panel.py ls /mods
  python panel.py cat /logs/latest.log [N]      (N dernières lignes)
  python panel.py write /server.properties fichier_local
  python panel.py upload fichier_local /dossier_distant
  python panel.py pull <url> /mods [nom_fichier]
  python panel.py mv /mods/ancien.jar /mods-off/ancien.jar
  python panel.py cmd "say Bonjour"
  python panel.py power start|stop|restart|kill

Depuis Git Bash : exporter MSYS_NO_PATHCONV=1, sinon "/mods" devient "C:/Program Files/Git/mods".
"""
import json, os, sys, urllib.error, urllib.parse, urllib.request
from pathlib import Path

_FICHIER = Path(__file__).parent.parent / ".env"  # sur le PC ; dans le cloud, les variables d'environnement
ENV = {**os.environ, **(dict(l.split("=", 1) for l in _FICHIER.read_text().splitlines()
                             if "=" in l and not l.startswith("#")) if _FICHIER.exists() else {})}
BASE = f"{ENV['PANEL_URL']}/api/client/servers/{ENV['SERVER_ID']}"
sys.stdout.reconfigure(encoding="utf-8", errors="replace")  # la console Windows (cp1252) plante sur ✔, ·, etc.


def req(method, path, data=None, raw=False, **params):
    url = BASE + path + ("?" + urllib.parse.urlencode(params) if params else "")
    body = data if isinstance(data, bytes) else (json.dumps(data).encode() if data is not None else None)
    r = urllib.request.Request(url, body, method=method, headers={
        "Authorization": f"Bearer {ENV['PANEL_API_KEY']}", "Accept": "application/json",
        "Content-Type": "text/plain" if isinstance(data, bytes) else "application/json",
        "User-Agent": "la-colonie-admin/1.0"})
    try:
        with urllib.request.urlopen(r) as resp:
            out = resp.read()
    except urllib.error.HTTPError as e:
        sys.exit(f"HTTP {e.code} {method} {path}: {e.read().decode(errors='replace')[:500]}")
    return out.decode("utf-8", errors="replace") if raw else (json.loads(out) if out else None)


def main(cmd, *a):
    if cmd == "status":
        s = req("GET", "/resources")["attributes"]
        r = s["resources"]
        print(f"{s['current_state']}  RAM {r['memory_bytes'] / 2**30:.1f} Go  CPU {r['cpu_absolute']:.0f} %  disque {r['disk_bytes'] / 2**30:.2f} Go  uptime {r['uptime'] // 60000} min")
    elif cmd == "ls":
        for f in req("GET", "/files/list", directory=a[0] if a else "/")["data"]:
            f = f["attributes"]
            print(f"{'d' if not f['is_file'] else '-'} {f['size']:>12}  {f['modified_at'][:16]}  {f['name']}")
    elif cmd == "cat":
        text = req("GET", "/files/contents", raw=True, file=a[0])
        print("\n".join(text.splitlines()[-int(a[1]):]) if len(a) > 1 else text)
    elif cmd == "write":
        req("POST", "/files/write", Path(a[1]).read_bytes(), file=a[0])
        print(f"écrit : {a[0]}")
    elif cmd == "upload":  # fichier binaire (zip, jar) -> dossier distant
        url = req("GET", "/files/upload")["attributes"]["url"] + "&" + urllib.parse.urlencode({"directory": a[1]})
        f, b = Path(a[0]), b"----colonie"
        body = (b"--" + b + f'\r\nContent-Disposition: form-data; name="files"; filename="{f.name}"\r\n'.encode()
                + b"Content-Type: application/octet-stream\r\n\r\n" + f.read_bytes() + b"\r\n--" + b + b"--\r\n")
        urllib.request.urlopen(urllib.request.Request(url, body, method="POST", headers={
            "Content-Type": "multipart/form-data; boundary=" + b.decode()}))
        print(f"envoyé : {a[1]}/{f.name}")
    elif cmd == "pull":
        data = {"url": a[0], "directory": a[1], "use_header": True, "foreground": True}
        if len(a) > 2:
            data["filename"] = a[2]
        req("POST", "/files/pull", data)
        print(f"téléchargé sur le serveur : {a[1]}/{a[2] if len(a) > 2 else a[0].rsplit('/', 1)[-1]}")
    elif cmd == "mv":  # déplace ou renomme (ex. un ancien jar vers /mods-off : on ne supprime jamais un mod)
        src, dst = a[0].lstrip("/"), a[1].lstrip("/")
        req("PUT", "/files/rename", {"root": "/", "files": [{"from": src, "to": dst}]})
        print(f"déplacé : /{src} -> /{dst}")
    elif cmd == "cmd":
        req("POST", "/command", {"command": a[0]})
        print(f"> {a[0]}")
    elif cmd == "power":
        req("POST", "/power", {"signal": a[0]})
        print(f"power {a[0]}")
    else:
        sys.exit(__doc__)


if __name__ == "__main__":
    main(*(sys.argv[1:] or ["help"]))
