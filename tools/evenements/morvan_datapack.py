"""Écrit le datapack « morvan » (logique de l'événement, autonome sur le serveur) à partir de morvan_pos.json, et le zippe."""
import json, zipfile
from pathlib import Path

ICI = Path(__file__).parent
p = json.loads((ICI / "morvan_pos.json").read_text(encoding="utf-8"))
X, Y, Z = p["X"], p["Y"], p["Z"]
cx, cy, cz = p["C"]
L = [p["L1"], p["L2"], p["L3"]]
J = "Nielscutt"
CENTRE = f"x={cx},y={cy},z={cz}"


def txt(t):  # texte JSON dans une chaîne SNBT entre apostrophes droites
    return "'" + json.dumps({"text": t}, ensure_ascii=False).replace("\\", "\\\\") + "'"


lettre = ",".join(txt(t) for t in [
    f"{J},\n\nLe guetteur de la Tour de l’Est a allumé le signal rouge. Il ne l’allume que si les morts se relèvent.\n\n"
    "Suis le rayon dans le ciel, et découvre ce qui s’est passé.\n\n— L’Intendant"])
sceau = ('{id:"minecraft:tripwire_hook",count:1,components:{"minecraft:custom_name":\'{"text":"Sceau de Morvan","color":"dark_purple","italic":false}\','
         '"minecraft:lore":[\'{"text":"Il ouvre le trésor de la crypte.","color":"gray"}\']}}')

F = {}
F["load"] = f"""scoreboard objectives add morvan dummy
scoreboard players add #etat morvan 0
scoreboard players add #n morvan 0
bossbar add morvan:boss {{"text":"Morvan le Nécromancien","color":"dark_purple"}}
bossbar set morvan:boss color purple
bossbar set morvan:boss style notched_10
bossbar set morvan:boss max 120
schedule function morvan:boucle 10t
"""
F["boucle"] = f"""execute if score #etat morvan matches 0 if entity @a[name={J}] run function morvan:acte1
execute if score #etat morvan matches 1 if score #l1 morvan matches ..0 if block {L[0][0]} {L[0][1]} {L[0][2]} lever[powered=true] run function morvan:levier1
execute if score #etat morvan matches 1 if score #l2 morvan matches ..0 if block {L[1][0]} {L[1][1]} {L[1][2]} lever[powered=true] run function morvan:levier2
execute if score #etat morvan matches 1 if score #l3 morvan matches ..0 if block {L[2][0]} {L[2][1]} {L[2][2]} lever[powered=true] run function morvan:levier3
execute if score #etat morvan matches 1 if score #n morvan matches 3.. run function morvan:ouvrir
execute if score #etat morvan matches 2 if entity @a[name={J},{CENTRE},distance=..8] run function morvan:boss
execute if score #etat morvan matches 3..5 as @e[tag=morvan_mob] at @s unless entity @s[{CENTRE},distance=..9] facing {cx} {cy} {cz} run tp @s ^ ^ ^2
execute if score #etat morvan matches 3..5 store result score #pv morvan run data get entity @e[tag=morvan_boss,limit=1] Health
execute if score #etat morvan matches 3..5 store result bossbar morvan:boss value run scoreboard players get #pv morvan
execute if score #etat morvan matches 3 if score #pv morvan matches 1..80 run function morvan:phase2
execute if score #etat morvan matches 4 if score #pv morvan matches 1..40 run function morvan:phase3
execute if score #etat morvan matches 3..5 if entity @a[name={J},{CENTRE},distance=..25] unless entity @e[tag=morvan_boss] run function morvan:victoire
schedule function morvan:boucle 10t
"""
F["acte1"] = f"""scoreboard players set #etat morvan 1
title {J} times 20 100 30
title {J} subtitle {{"text":"Un rayon rouge déchire le ciel, à l’est…","color":"gray","italic":true}}
title {J} title {{"text":"Le signal de la Tour de l’Est","color":"dark_red","bold":true}}
execute at {J} run playsound minecraft:entity.ghast.scream master {J} ~ ~ ~ 0.8 0.5
execute at {J} run playsound minecraft:block.bell.resonate master {J} ~ ~ ~ 1 0.6
give {J} written_book[written_book_content={{title:"Lettre scellée",author:"L’Intendant",pages:[{lettre}]}}]
tellraw {J} ["",{{"text":"[Intendant] ","color":"gold"}},{{"text":"Une lettre scellée vient de t’arriver. Lis-la vite.","color":"yellow"}}]
"""
for i in (1, 2, 3):
    F[f"levier{i}"] = f"""scoreboard players set #l{i} morvan 1
scoreboard players add #n morvan 1
execute at {J} run playsound minecraft:block.iron_door.open master {J} ~ ~ ~ 1 0.5
title {J} actionbar [{{"text":"Un mécanisme grince sous la tour… (","color":"gray"}},{{"score":{{"name":"#n","objective":"morvan"}},"color":"gold"}},{{"text":"/3)","color":"gray"}}]
"""
F["ouvrir"] = f"""scoreboard players set #etat morvan 2
setblock {X + 1} {Y - 1} {Z + 1} ladder[facing=south]
setblock {X + 1} {Y - 2} {Z + 1} ladder[facing=south]
title {J} subtitle {{"text":"La grille de la crypte s’est ouverte.","color":"gray","italic":true}}
title {J} title {{"text":"Descends.","color":"dark_purple","bold":true}}
execute at {J} run playsound minecraft:block.chain.break master {J} ~ ~ ~ 1 0.5
execute at {J} run playsound minecraft:ambient.cave master {J} ~ ~ ~ 1 0.7
"""
F["boss"] = f"""scoreboard players set #etat morvan 3
summon evoker {cx} {cy} {cz - 4} {{Tags:["morvan_mob","morvan_boss"],PersistenceRequired:1b,CustomName:'{{"text":"Morvan le Nécromancien","color":"dark_purple","bold":true}}',CustomNameVisible:1b,Health:120f,attributes:[{{id:"minecraft:generic.max_health",base:120d}}],HandItems:[{{}},{sceau}],HandDropChances:[0f,1f]}}
bossbar set morvan:boss value 120
bossbar set morvan:boss players @a[name={J}]
title {J} title {{"text":"Morvan s’éveille","color":"dark_purple","bold":true}}
title {J} subtitle {{"text":"« Encore un vivant qui vient mourir chez moi… »","color":"gray","italic":true}}
playsound minecraft:entity.wither.spawn hostile @a {cx} {cy} {cz} 0.6 1.2
particle minecraft:soul {cx} {cy + 1} {cz - 4} 1 1 1 0.05 80
"""
F["phase2"] = f"""scoreboard players set #etat morvan 4
title {J} actionbar {{"text":"« Levez-vous, mes gardiens ! »","color":"dark_purple","italic":true}}
""" + "".join(f'summon skeleton {cx + dx} {cy} {cz + dz} {{Tags:["morvan_mob"],PersistenceRequired:1b,HandItems:[{{id:"minecraft:bow",count:1}},{{}}],HandDropChances:[0f,0f]}}\n'
              f"particle minecraft:soul {cx + dx} {cy + 1} {cz + dz} 0.5 1 0.5 0.05 30\n" for dx, dz in ((-6, -5), (6, -5), (-6, 5), (6, 5))) + \
    f"playsound minecraft:entity.skeleton.ambient hostile @a {cx} {cy} {cz} 1 0.6\n"
F["phase3"] = f"""scoreboard players set #etat morvan 5
title {J} actionbar {{"text":"« Ténèbres, à moi ! »","color":"dark_purple","italic":true}}
effect give {J} minecraft:darkness 15 0
summon wither_skeleton {cx - 4} {cy} {cz - 5} {{Tags:["morvan_mob"],PersistenceRequired:1b}}
summon wither_skeleton {cx + 4} {cy} {cz - 5} {{Tags:["morvan_mob"],PersistenceRequired:1b}}
particle minecraft:large_smoke {cx} {cy + 1} {cz - 5} 4 1 1 0.02 120
playsound minecraft:entity.wither_skeleton.ambient hostile @a {cx} {cy} {cz} 1 0.5
"""
F["victoire"] = f"""scoreboard players set #etat morvan 6
bossbar remove morvan:boss
execute as @e[tag=morvan_mob] at @s run particle minecraft:soul ~ ~1 ~ 0.3 0.6 0.3 0.05 20
kill @e[tag=morvan_mob]
setblock {X} {Y + 13} {Z} yellow_stained_glass
title {J} subtitle {{"text":"Le signal brille d’or : la colonie est sauvée. Le sceau ouvre son trésor.","color":"gray","italic":true}}
title {J} title {{"text":"Morvan est vaincu !","color":"gold","bold":true}}
execute at {J} run playsound minecraft:ui.toast.challenge_complete master {J} ~ ~ ~ 1 1
tellraw @a ["",{{"text":"[Intendant] ","color":"gold"}},{{"text":"{J} a vaincu Morvan le Nécromancien dans la crypte de la Tour de l’Est ! Le signal brille d’or.","color":"yellow"}}]
"""

out = ICI / "morvan.zip"
with zipfile.ZipFile(out, "w", zipfile.ZIP_DEFLATED) as z:
    z.writestr("pack.mcmeta", json.dumps({"pack": {"pack_format": 48, "description": "Événement : la Crypte de Morvan"}}, ensure_ascii=False))
    z.writestr("data/minecraft/tags/function/load.json", json.dumps({"values": ["morvan:load"]}))
    for nom, contenu in F.items():
        z.writestr(f"data/morvan/function/{nom}.mcfunction", contenu)
print(out, len(F), "fonctions")
