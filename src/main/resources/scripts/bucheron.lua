-- ============================================================================
-- Bûcheron
-- Commande : un type de bois (ou n'importe lequel). Il abat les arbres naturels
-- de ce type dans sa zone de travail (jamais les maisons en rondins : il faut des
-- feuilles naturelles), ramasse le bois et les pousses, replante les souches.
-- S'il n'y a plus d'arbre autour de lui, il explore sa zone.
-- Sans hache il travaille quand même, mais beaucoup plus lentement.
-- ============================================================================
local M = {
  nom = "Bûcheron",
  description = "Abat les arbres du bois demandé dans sa zone, ramasse le bois et les pousses, et replante.",
}

M.garder = { "nourriture", "#minecraft:axes", "#minecraft:saplings" }

-- Le catalogue des commandes (écran du bureau). « objet » est ce qui compte pour le stock ou la quantité ;
-- « bois » est le pied d'arbre qu'il cherche.
M.commandes = {
  { id = "tout",       nom = "N'importe quel bois", objet = "#minecraft:logs",       bois = "#minecraft:logs" },
  { id = "chene",      nom = "Chêne",               objet = "minecraft:oak_log",      bois = "minecraft:oak_log" },
  { id = "bouleau",    nom = "Bouleau",             objet = "minecraft:birch_log",    bois = "minecraft:birch_log" },
  { id = "sapin",      nom = "Sapin",               objet = "minecraft:spruce_log",   bois = "minecraft:spruce_log" },
  { id = "chene_noir", nom = "Chêne noir",          objet = "minecraft:dark_oak_log", bois = "minecraft:dark_oak_log" },
  { id = "jungle",     nom = "Bois de la jungle",   objet = "minecraft:jungle_log",   bois = "minecraft:jungle_log" },
  { id = "acacia",     nom = "Acacia",              objet = "minecraft:acacia_log",   bois = "minecraft:acacia_log" },
  { id = "cerisier",   nom = "Cerisier",            objet = "minecraft:cherry_log",   bois = "minecraft:cherry_log" },
  { id = "paletuvier", nom = "Palétuvier",          objet = "minecraft:mangrove_log", bois = "minecraft:mangrove_log" },
}

function M.travailler(pnj, commun)
  local m = pnj.memoire
  local c = pnj.commande() or M.commandes[1]   -- sans commande : n'importe quel bois
  m.souches = m.souches or {}

  if not pnj.equiper("#minecraft:axes") then
    commun.parfois(pnj, "hache", "Je n'ai pas de hache, chef : je coupe à la main, c'est bien plus lent.", true, 1800)
  end

  -- 1. Replanter les souches vides s'il a des pousses.
  if pnj.a("#minecraft:saplings") and #m.souches > 0 then
    local s = table.remove(m.souches, 1)
    if pnj.libre(s.x, s.y, s.z) and pnj.est(s.x, s.y - 1, s.z, "#minecraft:dirt") then
      return pnj.poser(s.x, s.y, s.z, "#minecraft:saplings")
    end
  end

  -- 2. Ramasser ce qui traîne (bois, pousses, pommes), pas plus d'une fois par 45 s
  --    (un objet coincé dans les feuilles ne doit pas l'occuper indéfiniment).
  if pnj.objetsAuSol(10) > 0 and pnj.temps() >= (m.prochainRamassage or 0) then
    m.prochainRamassage = pnj.temps() + 45
    return pnj.ramasser(10)
  end

  -- 3. L'arbre du bon bois le plus proche de lui, s'il est dans sa zone.
  local x, y, z = pnj.chercherArbre(24, { bois = c.bois })
  if x and pnj.dansZone(x, y, z) then
    if #m.souches < 16 then table.insert(m.souches, { x = x, y = y, z = z }) end
    m.explorations = 0
    return pnj.abattre(x, y, z)
  end

  -- 4. Plus rien autour de lui : il part explorer un autre coin de sa zone.
  m.explorations = (m.explorations or 0) + 1
  if m.explorations > 12 then
    commun.parfois(pnj, "plus_d_arbre", "Je ne trouve plus de " .. c.nom .. " dans ma zone. J'attends que ça repousse.", true, 1800)
    m.explorations = 0
    return pnj.attendre(60)
  end
  local ex, ey, ez = commun.pointDansZone(pnj)
  return pnj.aller(ex, ey, ez, 4)
end

return M
