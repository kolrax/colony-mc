"""Le joueur fantôme (docs/refonte.md, partie 7) : une vraie partie sans écran, et ce qu'un joueur y verrait.

  python3 tools/fantome.py mod-npc/fantome/trois-ouvriers.txt [--graine 12345] [--garder-monde]

Lance `./gradlew runFantome` (serveur de développement, dossier mod-npc/run-fantome, monde généré normal), attend
« Done », joue le scénario puis écrit le rapport (`/fantome rapport <scénario>`) et arrête le serveur.

Le scénario : une commande du serveur par ligne (sans « / »), envoyée par RCON. Lignes spéciales :
  # ...            commentaire
  attendre N       attendre N secondes (temps réel)
  jours N          faire filer N jours de jeu (tick sprint), et attendre la fin
  ticks N          pareil, en ticks

Un seul serveur de développement à la fois sur la machine : le lancement prend le verrou /tmp/gametest.lock.
Le rapport complet : mod-npc/run-fantome/fantome/<scénario>.json ; la console : mod-npc/run-fantome/fantome/console.log.
"""
import argparse
import fcntl
import json
import os
import signal
import secrets
import shutil
import socket
import struct
import subprocess
import sys
import threading
import time
from pathlib import Path

RACINE = Path(__file__).resolve().parent.parent
MOD = RACINE / "mod-npc"
RUN = MOD / "run-fantome"
PORT_JEU, PORT_RCON = 25590, 25591

# La monnaie du monde d'essai : des objets de base (Numismatics n'est pas chargé en développement).
MONNAIE = [
    {"objet": "minecraft:copper_ingot", "nom": "lingot de cuivre", "valeur": 1},
    {"objet": "minecraft:gold_ingot", "nom": "lingot d'or", "valeur": 8},
    {"objet": "minecraft:emerald", "nom": "émeraude", "valeur": 64},
]


class Console:
    """Lit la sortie du serveur dans un fil : la garde dans console.log et repère les lignes attendues."""

    def __init__(self, proc, journal):
        self.proc, self.lignes, self.lock = proc, [], threading.Lock()
        self.journal = open(journal, "w", encoding="utf-8")
        threading.Thread(target=self._lire, daemon=True).start()

    def _lire(self):
        for brut in self.proc.stdout:
            ligne = brut.decode("utf-8", "replace").rstrip("\n")
            self.journal.write(ligne + "\n")
            with self.lock:
                self.lignes.append(ligne)
        self.journal.flush()

    def compte(self, texte):
        with self.lock:
            return sum(1 for l in self.lignes if texte in l)

    def attendre(self, texte, depuis, delai):
        """Attend qu'une ligne contenant `texte` apparaisse une fois de plus qu'au compte `depuis`."""
        fin = time.time() + delai
        while time.time() < fin:
            if self.compte(texte) > depuis:
                return True
            if self.proc.poll() is not None:
                return False
            time.sleep(0.5)
        return False

    def fin(self, n=40):
        with self.lock:
            return self.lignes[-n:]


class Rcon:
    def __init__(self, port, mdp):
        self.s = socket.create_connection(("127.0.0.1", port), timeout=120)
        self.n = 0
        if self._envoyer(3, mdp)[0] == -1:
            raise RuntimeError("RCON : mot de passe refusé")

    def _envoyer(self, genre, texte):
        self.n += 1
        corps = texte.encode("utf-8") + b"\x00\x00"
        self.s.sendall(struct.pack("<iii", len(corps) + 8, self.n, genre) + corps)
        taille = struct.unpack("<i", self._lire(4))[0]
        donnees = self._lire(taille)
        ident, _ = struct.unpack("<ii", donnees[:8])
        return ident, donnees[8:-2].decode("utf-8", "replace")

    def _lire(self, n):
        b = b""
        while len(b) < n:
            x = self.s.recv(n - len(b))
            if not x:
                raise ConnectionError("RCON fermé")
            b += x
        return b

    def cmd(self, texte):
        return self._envoyer(2, texte)[1]


def preparer(graine, garder):
    RUN.mkdir(parents=True, exist_ok=True)
    if not garder and (RUN / "world").exists():
        shutil.rmtree(RUN / "world")
    (RUN / "eula.txt").write_text("eula=true\n", encoding="utf-8")
    mdp = secrets.token_urlsafe(16)
    props = {
        "level-seed": str(graine), "level-name": "world", "online-mode": "false", "server-port": PORT_JEU,
        "enable-rcon": "true", "rcon.port": PORT_RCON, "rcon.password": mdp, "broadcast-rcon-to-ops": "false",
        "spawn-protection": "0", "view-distance": "8", "simulation-distance": "6", "max-players": "2",
        "difficulty": "normal", "gamemode": "survival", "spawn-monsters": "true", "white-list": "false",
        "sync-chunk-writes": "false", "motd": "Joueur fantome",
    }
    (RUN / "server.properties").write_text("".join(f"{k}={v}\n" for k, v in props.items()), encoding="utf-8")
    dossier = RUN / "config" / "colonie_pnj"
    dossier.mkdir(parents=True, exist_ok=True)
    f = dossier / "reglages.json"
    r = json.loads(f.read_text(encoding="utf-8")) if f.exists() else {}
    r.update({"fantome": True, "port_panneau": 25786, "monnaie": MONNAIE})
    f.write_text(json.dumps(r, ensure_ascii=False, indent=2), encoding="utf-8")
    (RUN / "fantome").mkdir(exist_ok=True)
    return mdp


def resume(rapport):
    r = rapport.get("resume", {})
    print("\n=== Ce qu'un joueur verrait (jour %s, %s h) ===" % (rapport.get("jour"), rapport.get("heure")))
    for o in rapport.get("ouvriers", []):
        missions = o.get("fiche", {}).get("missions") or []
        print(f"- {o['prenom']} ({o['metier']}) : statut « {o.get('statut', '')} » | « {o['au_dessus']} » | rôle « {o.get('role', '')} » | état « {o.get('etat', '')} »"
              f" | {o.get('mode')} / {o.get('action') or '-'} | à {o.get('distance_poste', '?')} blocs du poste"
              + (f" | missions : {missions}" if missions else ""))
        sac = o.get("sac") or {}
        if sac:
            print(f"    sac : {sac}")
    poste = rapport.get("poste", {})
    print(f"Poste : {poste.get('pos', 'aucun')} ; dépôt : {poste.get('depot', {})} ; caisse : {poste.get('caisse', {})}")
    if poste.get("reserve"):
        print(f"Réserve du dépôt (au-delà des 27 cases) : {poste['reserve']}")
    print(f"Blocs cassés (64 blocs autour du poste) : {r.get('casses_par_nature', {})}")
    print(f"Blocs cassés par métier : {r.get('blocs_casses_par_metier', {})}")
    construits = r.get("construits_casses", {})
    print(("BLOCS CONSTRUITS CASSÉS : %s" % construits) if construits else "Aucun bloc construit cassé.")
    print(f"Blocs cassés dans un village : {r.get('casses_dans_un_village', 0)}")
    s = r.get("survie") or {}
    print(f"Mises à terre : {s.get('blessures', 0)} {s.get('blessures_par_cause', {})} ; relevés seuls : {s.get('releves_seuls', 0)} ;"
          f" morts : {s.get('morts', 0)} {s.get('morts_par_cause', {})}")
    for e in rapport.get("blessures", []) + rapport.get("morts", []):
        print(f"    jour {e['jour']} {e['heure']} h : {e['ouvrier']} ({e['metier']}) {e['genre']}, {e['cause']}, en {e['pos']}")
    msgs = rapport.get("messages", [])
    print(f"Messages reçus : {len(msgs)}" + (" ; les derniers :" if msgs else ""))
    for m in msgs[-10:]:
        print(f"    [{m['tick']}] {m['texte']}")
    print(f"Écrans reçus : {[e.get('ecran') for e in rapport.get('ecrans', [])][-10:]}")


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("scenario")
    ap.add_argument("--graine", default="1234567")
    ap.add_argument("--garder-monde", action="store_true")
    ap.add_argument("--delai-demarrage", type=int, default=1200)
    a = ap.parse_args()
    scenario = Path(a.scenario)
    nom = scenario.stem
    lignes = scenario.read_text(encoding="utf-8").splitlines()

    mdp = preparer(a.graine, a.garder_monde)
    print(f"Lancement du serveur de développement (graine {a.graine}, dossier {RUN})…", flush=True)
    # Le verrou d'abord (un autre serveur de test peut tourner) : le délai de démarrage ne compte qu'après.
    verrou = open("/tmp/gametest.lock", "a")
    print("Attente du verrou /tmp/gametest.lock…", flush=True)
    fcntl.flock(verrou, fcntl.LOCK_EX)
    proc = subprocess.Popen(["./gradlew", "runFantome", "--no-daemon"], cwd=MOD, start_new_session=True,
                            stdin=subprocess.DEVNULL, stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
    con = Console(proc, RUN / "fantome" / "console.log")
    code = 0
    try:
        if not con.attendre("Done (", 0, a.delai_demarrage):
            print("Le serveur n'a pas démarré. Fin de la console :\n" + "\n".join(con.fin()))
            return 2
        print("Serveur prêt.", flush=True)
        rcon = Rcon(PORT_RCON, mdp)
        for ligne in lignes:
            ligne = ligne.strip()
            if not ligne or ligne.startswith("#"):
                continue
            mots = ligne.split()
            if mots[0] == "attendre":
                time.sleep(float(mots[1]))
                continue
            if mots[0] in ("jours", "ticks"):
                ticks = int(float(mots[1]) * (24000 if mots[0] == "jours" else 1))
                avant = con.compte("Sprint completed")
                print(f"> tick sprint {ticks}", flush=True)
                print("  " + rcon.cmd(f"tick sprint {ticks}"), flush=True)
                t0 = time.time()
                if not con.attendre("Sprint completed", avant, max(600, ticks // 10)):
                    print("  Le temps n'a pas fini de filer (serveur arrêté ou trop lent).")
                    code = 3
                else:
                    print(f"  {ticks} ticks en {time.time() - t0:.0f} s", flush=True)
                continue
            print(f"> {ligne}", flush=True)
            print("  " + rcon.cmd(ligne.lstrip("/")).replace("\n", "\n  "), flush=True)
        print("  " + rcon.cmd(f"fantome rapport {nom}"), flush=True)
        try:
            rcon.cmd("stop")
        except (ConnectionError, OSError):
            pass
    finally:
        try:
            proc.wait(timeout=180)
        except subprocess.TimeoutExpired:
            os.killpg(proc.pid, signal.SIGKILL)  # gradle et le serveur avec lui
        verrou.close()
    f = RUN / "fantome" / f"{nom}.json"
    if not f.exists():
        print("Pas de rapport. Fin de la console :\n" + "\n".join(con.fin()))
        return 4
    rapport = json.loads(f.read_text(encoding="utf-8"))
    resume(rapport)
    print(f"\nRapport complet : {f}")
    return code


if __name__ == "__main__":
    sys.exit(main())
