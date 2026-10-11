"""Bot « Intendant de la Colonie » via l'API REST Discord. Lit ../.env.

  python discord.py setup                                   construit/met à jour le serveur (idempotent)
  python discord.py post <salon> <fichier.md|.json> [pièce_jointe] [--pin]
  python discord.py dm <fichier.md>                         message privé au fondateur (KOLRAX)

Dans les messages : {{salon}} -> lien vers le salon (nom sans emoji), {{@fondateur}} -> mention du fondateur.
"""
import base64, json, os, re, sys, time, urllib.error, urllib.request
from pathlib import Path

ROOT = Path(__file__).parent.parent
_FICHIER = ROOT / ".env"  # sur le PC ; dans le cloud, les variables d'environnement
ENV = {**os.environ, **(dict(l.split("=", 1) for l in _FICHIER.read_text(encoding="utf-8").splitlines()
                             if "=" in l and not l.startswith("#")) if _FICHIER.exists() else {})}
API, GUILD = "https://discord.com/api/v10", ENV["DISCORD_GUILD_ID"]

TEXT, VOICE, CATEGORY, FORUM = 0, 2, 4, 15
P = {k: 1 << b for k, b in dict(
    INVITE=0, ADMIN=3, REACT=6, PRIORITY=8, STREAM=9, VIEW=10, SEND=11, MANAGE_MSG=13, EMBED=14, ATTACH=15,
    HISTORY=16, MENTION_ALL=17, EXT_EMOJI=18, CONNECT=20, SPEAK=21, VAD=25, NICK=26, APP_CMDS=31, MANAGE_EVENTS=33,
    MANAGE_THREADS=34, PUB_THREADS=35, PRIV_THREADS=36, EXT_STICKERS=37, THREAD_SEND=38, ACTIVITIES=39,
    SOUNDBOARD=42, CREATE_EVENTS=44, EXT_SOUNDS=45, VOICE_MSG=46, POLLS=49, EXT_APPS=50, PIN=51).items()}
perms = lambda names: sum(P[n] for n in names.split())
WRITE = perms("SEND THREAD_SEND PUB_THREADS PRIV_THREADS")

# Droits de base par rôle. Les salons ne portent que les exceptions (lecture seule, privé, droits du Maire).
EVERYONE = perms("HISTORY")  # visiteur : ne voit que l'Accueil, ouvert par le salon lui-même
COLON = perms("VIEW HISTORY SEND THREAD_SEND PUB_THREADS EMBED ATTACH REACT EXT_EMOJI EXT_STICKERS POLLS VOICE_MSG "
              "CONNECT SPEAK VAD STREAM SOUNDBOARD EXT_SOUNDS ACTIVITIES APP_CMDS EXT_APPS NICK INVITE CREATE_EVENTS")
ROLES = [  # (nom, ancien nom, droits de base, couleur) — du plus haut au plus bas
    ("👑 Fondateur", "Fondateur", P["ADMIN"], 0xE8B73A),
    ("🏛️ Maire", "Maire", COLON | perms("MENTION_ALL MANAGE_EVENTS PRIORITY"), 0xB03A48),
    ("⚒️ Colon", "Colon", COLON, 0x6AA84F),
]
BOT_ROLE = ("🤖 Intendant", 0xC08A3E)

# (catégorie, ancien nom, accès, salons) ; accès : accueil = visible par tous en lecture seule,
# membres = rôle Colon, admin = fondateur seul. Salon : (nom, ancien nom, type, sujet, options)
# Options : ro = lecture seule ; maire = le Maire épingle et range ; tags de forum : (nom, émoji, réservé à l'admin)
LAYOUT = [
    ("🏰 Accueil", "La Colonie", "accueil", [
        ("📜┃règlement", "bienvenue", TEXT, "Les règles de la Colonie, à lire avant de jouer.", {}),
        ("🧭┃rejoindre", "contrats", TEXT, "Installer le modpack et se connecter au serveur.", {}),
        ("📦┃modpacks", None, TEXT, "Toutes les versions du modpack : choisis ton launcher et télécharge.", {}),
        ("📢┃annonces", "annonces", TEXT, "Maintenances, mises à jour du modpack, nouvelles du serveur.", {}),
    ]),
    ("🍺 La Taverne", None, "membres", [
        ("💬┃discussion", "taverne", TEXT, "Discussion libre, hors roleplay : on s'organise, on rigole.", {}),
        ("🎮┃chat-en-jeu", None, TEXT, "Le chat du serveur Minecraft en direct : écrire ici, c'est parler en jeu.", {}),
        ("📸┃captures", "captures", TEXT, "Vos plus belles images de la colonie.", {}),
        ("💡┃idées-et-bugs", None, FORUM, "Une idée ou un bug ? Ouvre un fil par sujet, l'Intendant y répond.",
         {"tags": [("Idée", "💡", False), ("Bug", "🐛", False), ("Résolu", "✅", True)]}),
    ]),
    ("🏛️ La Cité", "La Cité", "membres", [
        ("📰┃la-gazette", "gazette", TEXT, "La gazette de l'Intendant : récit de la semaine, événements à venir.", {"ro": True}),
        ("📈┃cours-du-marché", "événements", TEXT, "Les prix officiels du comptoir de l'Intendant, mis à jour chaque semaine.", {"ro": True}),
        ("🤝┃marché", None, FORUM, "Une annonce = un fil : quoi, combien, à quel prix, où livrer.",
         {"tags": [("Vente", "🟢", False), ("Achat", "🔵", False), ("Commande", "📜", False),
                   ("Contrat officiel", "👑", True), ("Conclu", "✅", False)]}),
        ("⚖️┃hôtel-de-ville", "hôtel-de-ville", TEXT, "Lois, votes, impôts, élections et grands chantiers.", {"maire": True}),
        ("🎭┃personnages", None, FORUM, "Un fil par personnage : son nom, son métier, son histoire.", {"tags": []}),
    ]),
    ("🔊 Vocaux", "Vocaux", "membres", [
        ("🍺 Taverne", "Taverne", VOICE, None, {}),
        ("⚒️ Atelier", "Atelier", VOICE, None, {}),
    ]),
    ("🛠️ Intendance", "Intendance", "admin", [
        ("🖥️┃console", "journal-serveur", TEXT, "Console du serveur Minecraft : journaux et commandes.", {}),
        ("🗒️┃coulisses", "intendance", TEXT, "Notes d'administration et préparation des événements.", {}),
    ]),
]


def call(method, path, data=None, files=None):
    headers = {"Authorization": f"Bot {ENV['DISCORD_BOT_TOKEN']}", "User-Agent": "DiscordBot (la-colonie, 1.0)"}
    if files:  # multipart : payload_json + pièces jointes
        b = "colonie" + str(int(time.time()))
        parts = [f'--{b}\r\nContent-Disposition: form-data; name="payload_json"\r\nContent-Type: application/json\r\n\r\n'.encode()
                 + json.dumps(data).encode() + b"\r\n"]
        for i, f in enumerate(files):
            parts.append(f'--{b}\r\nContent-Disposition: form-data; name="files[{i}]"; filename="{f.name}"\r\n'
                         f'Content-Type: application/octet-stream\r\n\r\n'.encode() + f.read_bytes() + b"\r\n")
        body, headers["Content-Type"] = b"".join(parts) + f"--{b}--\r\n".encode(), f"multipart/form-data; boundary={b}"
    else:
        body = json.dumps(data).encode() if data is not None else None
        headers["Content-Type"] = "application/json"
    while True:
        try:
            with urllib.request.urlopen(urllib.request.Request(API + path, body, headers, method=method)) as r:
                out = r.read()
                return json.loads(out) if out else None
        except urllib.error.HTTPError as e:
            if e.code == 429:
                time.sleep(json.loads(e.read()).get("retry_after", 1) + 0.2)
                continue
            raise SystemExit(f"Discord {e.code} {method} {path}: {e.read().decode(errors='replace')[:400]}")


def short(name):
    return name.split("┃")[-1]


def human_messages(ch):
    if ch["type"] not in (TEXT, VOICE):
        return 0
    return sum(not m["author"].get("bot") and m["type"] == 0 for m in call("GET", f"/channels/{ch['id']}/messages?limit=50"))


def setup():
    # --- rôles
    roles = call("GET", f"/guilds/{GUILD}/roles")
    by_name = {r["name"]: r for r in roles}
    call("PATCH", f"/guilds/{GUILD}/roles/{GUILD}", {"permissions": str(EVERYONE)})  # @everyone
    for name, old, base, color in ROLES:
        spec = {"name": name, "permissions": str(base), "color": color, "hoist": True, "mentionable": True}
        r = by_name.get(name) or by_name.get(old)
        by_name[name] = call("PATCH", f"/guilds/{GUILD}/roles/{r['id']}", spec) if r else call("POST", f"/guilds/{GUILD}/roles", spec)
    bot_role = next((r for r in roles if r.get("tags", {}).get("bot_id") == ENV["DISCORD_APP_ID"]), None)
    if bot_role and bot_role["name"] != BOT_ROLE[0]:  # un bot ne peut pas éditer son propre rôle : renommé à la main
        try:
            call("PATCH", f"/guilds/{GUILD}/roles/{bot_role['id']}", {"name": BOT_ROLE[0], "color": BOT_ROLE[1], "hoist": False})
        except SystemExit as e:
            print("rôle du bot non renommé :", e)
    top = bot_role["position"] if bot_role else len(roles)
    call("PATCH", f"/guilds/{GUILD}/roles", [{"id": by_name[n]["id"], "position": top - 1 - i} for i, (n, *_) in enumerate(ROLES)])
    guild = call("GET", f"/guilds/{GUILD}")
    call("PUT", f"/guilds/{GUILD}/members/{guild['owner_id']}/roles/{by_name['👑 Fondateur']['id']}")

    # --- salons
    def ow(access, opts):
        o = lambda i, allow=0, deny=0: {"id": i, "type": 0, "allow": str(allow), "deny": str(deny)}
        colon, maire = by_name["⚒️ Colon"]["id"], by_name["🏛️ Maire"]["id"]
        if access == "accueil":
            return [o(GUILD, perms("VIEW HISTORY"), WRITE)]
        if access == "admin":
            return [o(colon, deny=P["VIEW"]), o(maire, deny=P["VIEW"])]
        return ([o(GUILD, deny=WRITE)] if opts.get("ro") else []) + \
               ([o(maire, perms("MANAGE_MSG PIN MANAGE_THREADS"))] if opts.get("maire") else [])

    existing = call("GET", f"/guilds/{GUILD}/channels")
    find = lambda typ, *names: next((c for c in existing if c["type"] == typ and c["name"] in names), None)
    kept = set()

    def upsert(spec, current):
        ch = call("PATCH", f"/channels/{current['id']}", spec) if current else call("POST", f"/guilds/{GUILD}/channels", spec)
        kept.add(ch["id"])
        return ch

    for pos, (cat_name, cat_old, access, items) in enumerate(LAYOUT):
        cat = upsert({"name": cat_name, "type": CATEGORY, "position": pos, "permission_overwrites": ow(access, {})},
                     find(CATEGORY, cat_name, cat_old))
        for i, (name, old, typ, topic, opts) in enumerate(items):
            spec = {"name": name, "type": typ, "parent_id": cat["id"], "position": i, "permission_overwrites": ow(access, opts)}
            if topic:
                spec["topic"] = topic
            current = find(typ, name, old)
            if typ == FORUM:
                tags = {t["name"]: t for t in (current or {}).get("available_tags", [])}
                spec |= {"default_forum_layout": 1, "default_sort_order": 0,
                         "available_tags": [{**({"id": tags[n]["id"]} if n in tags else {}), "name": n, "emoji_name": e,
                                             "moderated": mod} for n, e, mod in opts["tags"]]}
                try:
                    upsert(spec, current)
                except SystemExit as e:  # forums indisponibles : salon texte à la place
                    print(f"forum refusé pour {name} ({e}), salon texte à la place")
                    upsert({k: v for k, v in spec.items() if not k.startswith(("default_", "available"))} | {"type": TEXT},
                           find(TEXT, name, old))
            else:
                upsert(spec, current)

    # --- anciens salons vides de messages humains : supprimés ; les autres sont signalés
    for c in call("GET", f"/guilds/{GUILD}/channels"):
        if c["id"] not in kept and c["type"] != CATEGORY:
            if human_messages(c):
                print(f"gardé (contient des messages) : {c['name']}")
            else:
                call("DELETE", f"/channels/{c['id']}")
    for c in call("GET", f"/guilds/{GUILD}/channels"):
        if c["id"] not in kept and c["type"] == CATEGORY and not any(x.get("parent_id") == c["id"] for x in existing):
            call("DELETE", f"/channels/{c['id']}")

    # --- serveur : salon système, icône ; bot : avatar
    emblem = "data:image/png;base64," + base64.b64encode((ROOT / "discord/emblem.png").read_bytes()).decode()
    chans = {short(c["name"]): c for c in call("GET", f"/guilds/{GUILD}/channels")}
    call("PATCH", f"/guilds/{GUILD}", {"system_channel_id": chans["discussion"]["id"], "system_channel_flags": (1 << 1) | (1 << 2),
                                      **({"icon": emblem} if not guild["icon"] else {})})
    if not call("GET", "/users/@me")["avatar"]:
        call("PATCH", "/users/@me", {"avatar": emblem})

    cats = {c["id"]: c["name"] for c in call("GET", f"/guilds/{GUILD}/channels") if c["type"] == CATEGORY}
    for cat_id, cat_name in sorted(cats.items(), key=lambda kv: [x[0] for x in LAYOUT].index(kv[1]) if kv[1] in [x[0] for x in LAYOUT] else 99):
        print(cat_name.upper())
        for c in sorted((c for c in call("GET", f"/guilds/{GUILD}/channels") if c.get("parent_id") == cat_id), key=lambda c: c["position"]):
            print(f"   {'🔊' if c['type'] == VOICE else ('🗂️' if c['type'] == FORUM else '#')} {c['name']}")


def render(text):
    guild = call("GET", f"/guilds/{GUILD}")
    chans = {short(c["name"]): c["id"] for c in call("GET", f"/guilds/{GUILD}/channels")}
    text = text.replace("{{@fondateur}}", f"<@{guild['owner_id']}>")
    return re.sub(r"\{\{(.+?)\}\}", lambda m: f"<#{chans[m.group(1)]}>", text)


def post(salon, message_file, *rest):
    pin, attachment = "--pin" in rest, next((a for a in rest if a != "--pin"), None)
    chans = {short(c["name"]): c for c in call("GET", f"/guilds/{GUILD}/channels")}
    text = render(Path(message_file).read_text(encoding="utf-8"))
    payload = json.loads(text) if message_file.endswith(".json") else {"content": text}
    msg = call("POST", f"/channels/{chans[salon]['id']}/messages", payload, [Path(attachment)] if attachment else None)
    if pin:
        call("PUT", f"/channels/{msg['channel_id']}/pins/{msg['id']}")
    print(f"posté dans {chans[salon]['name']} (message {msg['id']}{', épinglé' if pin else ''})")
    return msg


def dm(message_file):
    """Message privé au fondateur (KOLRAX) : comptes rendus et décisions. Discord coupe à 2000 caractères."""
    owner = call("GET", f"/guilds/{GUILD}")["owner_id"]
    canal = call("POST", "/users/@me/channels", {"recipient_id": owner})["id"]
    text = render(Path(message_file).read_text(encoding="utf-8"))
    for i in range(0, len(text), 1900):
        call("POST", f"/channels/{canal}/messages", {"content": text[i:i + 1900]})
    print(f"message privé envoyé au fondateur ({len(text)} caractères)")


if __name__ == "__main__":
    cmd, *args = sys.argv[1:] or ["help"]
    if cmd == "setup":
        setup()
    elif cmd == "post":
        post(*args)
    elif cmd == "dm":
        dm(*args)
    else:
        sys.exit(__doc__)
