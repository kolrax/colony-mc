"""Relève l'état du serveur pour la console d'intendance (artefact) et l'écrit dans tools/etat.json.

  python etat.py
  puis ArtifactData set, collection "etat", doc "serveur", file_path = tools/etat.json

Depuis Git Bash : exporter MSYS_NO_PATHCONV=1 (voir panel.py).
"""
import json, re, sys
from datetime import datetime, timezone
from pathlib import Path

import discord
import panel

ROOT = Path(__file__).parent.parent
ADRESSE = "lacolonie.mintservers.fr"
# À changer quand le monde change (création du vrai monde à l'ouverture).
MONDE_NOTE = "Monde de test avec la configuration finale, pour le test de connexion. Le vrai monde sera créé à l'ouverture."
PORTS = [("Jeu", 25587), ("Chat vocal", 25951)]

# server.properties : (clé, nom affiché, pourquoi)
PROPRIETES = [
    ("difficulty", "Difficulté", ""),
    ("pvp", "Combat entre joueurs", "Guerres et fourberies RP autorisées."),
    ("white-list", "Liste blanche", "Seuls les colons ajoutés peuvent entrer."),
    ("spawn-protection", "Protection du spawn", "Aucune protection de terrain (ordre du chef)."),
    ("allow-flight", "Vol autorisé", "Évite les expulsions avec les jetpacks et les machines volantes de Create."),
    ("view-distance", "Distance de vue", "En chunks : équilibre entre portée et fluidité."),
    ("simulation-distance", "Distance de simulation", "En chunks : les machines tournent dans ce rayon autour des joueurs."),
    ("max-tick-time", "Arrêt forcé si le serveur bloque", "-1 = jamais : les grosses générations de monde ne le font pas tomber."),
]
GAMERULES = {
    "keepInventory": ("Garder son inventaire à la mort", "Non : la tombe garde les affaires (ordre du chef)."),
    "playersSleepingPercentage": ("Joueurs qui doivent dormir pour passer la nuit", "En %."),
}
ROLES = {
    "bluemap": "Carte du monde en 3D dans le navigateur.",
    "chunky": "Génère le monde à l'avance, contre le lag d'exploration.",
    "cloth-config": "Bibliothèque de réglages, requise par Cristel Lib.",
    "craterlib": "Bibliothèque, requise par Simple Discord Link.",
    "create": "Le cœur du serveur : engrenages, machines, trains.",
    "create-dragons-plus": "Bibliothèque Create, requise par Enchantment Industry.",
    "create-enchantment-industry": "Enchantements et expérience liquide avec Create.",
    "create-let-the-adventure-begin": "Structures sur le thème de Create (moulins, carrières, châteaux).",
    "create-steam-n-rails-1.21.1": "Rails, aiguillages et trains en plus.",
    "create-stuff-additions": "Équipements et gadgets Create (jetpack, outils).",
    "cristel-lib": "Bibliothèque, requise par Towns and Towers.",
    "ferrite-core": "Réduit la mémoire utilisée.",
    "gravestone-mod": "Tombe à la mort : on revient chercher ses affaires.",
    "iris": "Shaders, côté joueurs.",
    "jei": "Liste des objets et des recettes.",
    "kotlin-for-forge": "Bibliothèque, requise par Slice & Dice.",
    "lithostitched": "Bibliothèque de génération, requise par Terralith.",
    "modernfix": "Démarrage plus rapide, moins de mémoire.",
    "numismatics": "Les pièces de monnaie (distributeurs et banque retirés par le datapack).",
    "sdlink": "Relie le chat du jeu au salon 🎮┃chat-en-jeu.",
    "simple-backups": "Sauvegarde automatique du monde.",
    "simple-voice-chat": "Chat vocal de proximité.",
    "slice-and-dice": "Cuisine automatisée avec Create.",
    "sodium": "Performances graphiques, côté joueurs.",
    "spark": "Mesure du lag, pour l'admin.",
    "tectonic": "Relief : vraies chaînes de montagnes et vallées.",
    "terralith": "Une centaine de biomes, uniquement en blocs vanilla.",
    "towns-and-towers": "Villages et avant-postes médiévaux selon le biome.",
    "xaeros-minimap": "Mini-carte à l'écran ; le serveur impose ses réglages (pas de radar ni de grottes).",
    "xaeros-world-map": "Carte du monde en jeu (touche M), seulement ce que chacun a exploré.",
}


def fichier(path):
    try:
        return panel.req("GET", "/files/contents", raw=True, file=path)
    except SystemExit:
        return None


def go(octets):
    return f"{octets / 2**30:.1f} Go".replace(".", ",")


def duree(ms):
    m = ms // 60000
    j, h, m = m // 1440, m // 60 % 24, m % 60
    return f"{j} j {h} h" if j else f"{h} h {m:02d} min" if h else f"{m} min"


def cote(m):
    if m["server"] == "unsupported":
        return "joueurs"
    return "serveur" if m["client"] == "unsupported" else "tous"


def main():
    info = panel.req("GET", "")["attributes"]
    res = panel.req("GET", "/resources")["attributes"]
    r, lim = res["resources"], info["limits"]
    props = dict(l.split("=", 1) for l in (fichier("/server.properties") or "").splitlines() if "=" in l and not l.startswith("#"))
    noms = lambda f: sorted(p["name"] for p in json.loads(fichier(f) or "[]"))
    lock = json.loads((ROOT / "pack/lock.json").read_text(encoding="utf-8"))
    releases = json.loads((ROOT / "pack/releases.json").read_text(encoding="utf-8"))
    create = next(m["version"].split("+")[0] for m in lock if m["slug"] == "create")
    gamerules = dict(re.findall(r"^gamerule (\S+) (\S+)", (ROOT / "server/init-commands.txt").read_text(encoding="utf-8"), re.M))

    monde = props.get("level-name", "world")
    try:
        sauv = [f["attributes"] for f in panel.req("GET", "/files/list", directory=f"/simplebackups/{monde}")["data"]]
    except SystemExit:  # le dossier n'existe qu'après la première sauvegarde
        sauv = []
    sauv = sorted((f for f in sauv if f["is_file"]), key=lambda f: f["modified_at"])
    log = (ROOT / "sauvegardes/entretien.log")
    lignes = log.read_text(encoding="utf-8").strip().splitlines() if log.exists() else []
    copies = [l for l in lignes if "sauvegarde" in l.lower() or "copi" in l.lower()]
    copie = copies[-1] if copies else (f"Rien à copier pour l'instant (dernier passage de la tâche : {lignes[-1][:16]})" if lignes else "")

    membres = []
    for m in discord.call("GET", f"/guilds/{discord.GUILD}/members?limit=100"):
        if not m["user"].get("bot"):
            membres.append(m["user"]["username"])

    etat = {
        "releve": datetime.now(timezone.utc).isoformat(timespec="seconds"),
        "statut": res["current_state"],
        "ressources": [
            ["Mémoire", f"{go(r['memory_bytes'])} sur {lim['memory'] / 1000:.0f} Go"],
            ["Processeur", f"{r['cpu_absolute']:.0f} % (max {lim['cpu']} %)"],
            ["Disque", f"{go(r['disk_bytes'])} sur {lim['disk'] / 1024:.0f} Go"],
            ["Allumé depuis", duree(r["uptime"]) if res["current_state"] == "running" else "arrêté"],
        ],
        "adresse": ADRESSE,
        "versions": [["Minecraft", "1.21.1"], ["NeoForge", "21.1.255"], ["Create", create],
                     ["Modpack", releases[0]["version"]]],
        "hebergeur": f"MintServers · {info.get('node', '')}",
        "monde": {"actif": monde, "note": MONDE_NOTE},
        "ports": [{"usage": u, "port": p} for u, p in PORTS],
        "reglages": [{"nom": n, "valeur": props.get(k, "?"), "pourquoi": w} for k, n, w in PROPRIETES]
                    + [{"nom": GAMERULES[k][0], "valeur": v, "pourquoi": GAMERULES[k][1]} for k, v in gamerules.items() if k in GAMERULES],
        "liste_blanche": noms("/whitelist.json"),
        "operateurs": noms("/ops.json"),
        "discord_membres": membres,
        "mods": [{"nom": m["title"], "version": m["version"], "cote": cote(m), "role": ROLES.get(m["slug"], ""),
                  "url": f"https://modrinth.com/mod/{m['slug']}"} for m in sorted(lock, key=lambda m: m["title"].lower())],
        "modpack": [{"version": x["version"], "date": x["date"], "notes": x["notes"]} for x in releases],
        "sauvegardes": {
            "politique": "Toutes les 2 h quand quelqu'un joue, 24 gardées sur le serveur. Copie sur ton PC à chaque ouverture de session et à 18 h.",
            "derniere": f"{sauv[-1]['name']} ({sauv[-1]['modified_at'][:16].replace('T', ' ')})" if sauv else "Aucune pour l'instant : elles démarrent dès que quelqu'un joue.",
            "nombre": len(sauv),
            "copie_pc": copie,
        },
        "liens": [
            {"nom": "Discord", "url": f"https://discord.com/channels/{discord.GUILD}/1556348267133018175", "note": "💬┃discussion"},
            {"nom": "Panel MintServers", "url": f"{panel.ENV['PANEL_URL']}/server/{panel.ENV['SERVER_ID']}", "note": "démarrer, arrêter, fichiers"},
            {"nom": "Modpacks", "url": f"https://discord.com/channels/{discord.GUILD}/1556370196053237911", "note": "📦┃modpacks"},
            {"nom": "Console sur Discord", "url": f"https://discord.com/channels/{discord.GUILD}/1556356004105552002", "note": "🖥️┃console"},
            {"nom": "Questionnaire des colons", "url": "https://claude.ai/artifact/3zU74H5HLMGsD8uo4HotXZ", "note": "à envoyer au joueur 3"},
        ],
    }
    out = Path(__file__).parent / "etat.json"
    out.write_text(json.dumps(etat, ensure_ascii=False, indent=1), encoding="utf-8")
    print(f"{out} : {etat['statut']}, monde {monde}, {len(etat['mods'])} mods, liste blanche {etat['liste_blanche']}")


if __name__ == "__main__":
    main()
