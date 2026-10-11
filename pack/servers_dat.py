"""Écrit overrides/servers.dat : « La Colonie » pré-enregistré dans la liste des serveurs, avec son icône.
Format NBT non compressé : { servers: [ { name, ip, icon (PNG 64x64 en base64) } ] }."""
import base64, struct
from pathlib import Path

HERE = Path(__file__).parent
ICON = HERE.parent / "discord/icone-64.png"   # généré par : python discord/emblem.py 64 icone-64.png


def s(text):  # chaîne NBT : longueur sur 2 octets + UTF-8
    b = text.encode("utf-8")
    return struct.pack(">H", len(b)) + b


def tag_string(name, value):
    return b"\x08" + s(name) + s(value)


server = tag_string("name", "La Colonie") + tag_string("ip", "lacolonie.mintservers.fr") \
         + tag_string("icon", base64.b64encode(ICON.read_bytes()).decode()) + b"\x00"
data = b"\x0a" + s("") + b"\x09" + s("servers") + b"\x0a" + struct.pack(">i", 1) + server + b"\x00"

out = HERE / "overrides/servers.dat"
out.parent.mkdir(exist_ok=True)
out.write_bytes(data)
assert data.count(b"lacolonie.mintservers.fr") == 1 and data.endswith(b"\x00\x00")
print(out, len(data), "octets")
