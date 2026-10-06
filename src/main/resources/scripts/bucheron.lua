-- ============================================================================
-- Bûcheron
-- Commande : un type de bois (ou n'importe lequel). Il abat les arbres naturels
-- de ce type (jamais les maisons en rondins : il faut des feuilles naturelles),
-- ramasse le bois et les pousses, et replante les souches.
-- Il cherche où il veut : d'abord autour de lui, puis dans les coins où il a
-- déjà trouvé ce bois, puis il explore en spirale autour du bureau, de plus en
-- plus loin. Sans lit, il rentre chaque soir ; avec un lit, il part en
-- expédition plusieurs jours. Sans hache il travaille quand même, bien plus lentement.
-- ============================================================================
local M = {
  nom = "Bûcheron",
  description = "Trouve seul le bois demandé, même loin, l'abat, ramasse le bois et les pousses, et replante.",
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
  m.coins = m.coins or {}

  if not pnj.equiper("#minecraft:axes") then
    commun.parfois(pnj, "hache", "Je n'ai pas de hache, chef : je coupe à la main, c'est bien plus lent.", true, 1800)
  end

  -- 1. Replanter les souches vides s'il a des pousses (celles qui sont à portée).
  if pnj.a("#minecraft:saplings") and #m.souches > 0 then
    local s = table.remove(m.souches, 1)
    if pnj.distance(s.x, s.y, s.z) < 24 and pnj.libre(s.x, s.y, s.z) and pnj.est(s.x, s.y - 1, s.z, "#minecraft:dirt") then
      return pnj.poser(s.x, s.y, s.z, "#minecraft:saplings")
    end
  end

  -- 2. Ramasser ce qui traîne (bois, pousses, pommes), pas plus d'une fois par 45 s
  --    (un objet coincé dans les feuilles ne doit pas l'occuper indéfiniment).
  if pnj.objetsAuSol(10) > 0 and pnj.temps() >= (m.prochainRamassage or 0) then
    m.prochainRamassage = pnj.temps() + 45
    return pnj.ramasser(10)
  end

  -- 3. L'arbre du bon bois le plus proche de lui, s'il peut y aller et rentrer à temps.
  local x, y, z = pnj.chercherArbre(24, { bois = c.bois })
  if x and pnj.peutAller(x, y, z) then
    if #m.souches < 16 then table.insert(m.souches, { x = x, y = y, z = z }) end
    -- Un bon coin loin de chez lui : il s'en souviendra pour y revenir.
    if not pnj.chezLui(x, y, z) then m.coins[c.id] = { x = x, y = y, z = z } end
    return pnj.abattre(x, y, z)
  end

  -- 4. Un coin où il a déjà trouvé ce bois.
  local k = m.coins[c.id]
  if k then
    if pnj.distance(k.x, k.y, k.z) <= 16 then
      m.coins[c.id] = nil                       -- il y est, et il n'y a plus rien : il l'oublie
    elseif pnj.peutAller(k.x, k.y, k.z) then
      return pnj.aller(k.x, k.y, k.z, 6)
    end
  end

  -- 5. Explorer, de plus en plus loin.
  local ex, ey, ez, raison = commun.pointAExplorer(pnj, c.id)
  if ex then return pnj.allerVers(ex, ez, 4) end

  -- 6. Plus rien à sa portée.
  if raison == "tout_fouille" then
    if pnj.lit() then
      commun.parfois(pnj, "plus_d_arbre", "Je n'ai trouvé aucun arbre de ce bois (" .. c.nom .. ") à " .. math.floor(pnj.porteeMax())
        .. " blocs à la ronde. J'attends que ça repousse.", true, 1800)
    else
      commun.parfois(pnj, "plus_d_arbre", "Plus d'arbre de ce bois (" .. c.nom .. ") à une journée de marche, chef. Donne-moi un lit près du bureau : "
        .. "je partirai en expédition plus loin.", true, 1800)
    end
  end
  if not pnj.chezLui() then return pnj.rentrer() end
  return pnj.attendre(60)   -- trop tard pour repartir loin aujourd'hui : il attend
end

return M
