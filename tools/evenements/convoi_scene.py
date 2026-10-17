"""Événement « Le Convoi de l'Intendant » : construit la scène (sans les monstres)."""
import subprocess, sys

X, Z = -578, -570
PANEL = str(__import__("pathlib").Path(__file__).parent.parent / "panel.py")


def cmd(c):
    r = subprocess.run([sys.executable, PANEL, "cmd", c], capture_output=True, text=True, encoding="utf-8")
    if r.returncode:
        print("ÉCHEC", c, r.stderr[-300:])


def sol(dx, dz, bloc):  # pose le bloc sur le sol, à cette colonne
    cmd(f"execute positioned {X + dx} 0 {Z + dz} positioned over motion_blocking_no_leaves run setblock ~ ~ ~ {bloc}")


hache = ('{Slot:4b,id:"minecraft:iron_axe",count:1,components:{'
         '"minecraft:custom_name":\'{"text":"La Hache du Borgne","color":"gold","italic":false}\','
         '"minecraft:lore":[\'{"text":"Prise au chef des pillards","color":"gray","italic":true}\','
         '\'{"text":"qui attaqua le convoi de l\\\\\'Intendant.","color":"gray","italic":true}\'],'
         '"minecraft:enchantments":{levels:{"minecraft:sharpness":2,"minecraft:unbreaking":2}}}}')
contenu = ('[{Slot:11b,id:"numismatics:cog",count:2},{Slot:13b,id:"minecraft:diamond",count:3},'
           '{Slot:15b,id:"minecraft:bread",count:8},' + hache.replace("Slot:4b", "Slot:22b") + "]")

# nettoie la colonne (herbes hautes), puis la scène
for dx in range(-3, 5):
    for dz in range(-3, 4):
        cmd(f"execute positioned {X + dx} 0 {Z + dz} positioned over motion_blocking_no_leaves if block ~ ~ ~ #minecraft:replaceable run setblock ~ ~ ~ air")
sol(0, 0, f'chest[facing=east]{{CustomName:\'{{"text":"Cargaison de l\\\'Intendant","color":"gold"}}\',Lock:"Clé du convoi",Items:{contenu}}}')
sol(2, 1, "barrel[facing=up]")
sol(-2, -1, "barrel[facing=east]")       # renversé
sol(1, -2, "barrel[facing=north]")        # renversé
sol(-1, 2, "hay_block[axis=x]")
sol(3, -1, "campfire[lit=true]")
sol(-2, 1, "oak_slab")
sol(-3, 1, "oak_slab")
sol(-1, -3, "cobweb")
sol(2, -3, "oak_fence")
sol(1, 3, 'oak_sign[rotation=4]{front_text:{messages:[\'""\',\'"Cargaison de"\',\'"l\\\'Intendant"\',\'""\']}}')
print("scène posée")
