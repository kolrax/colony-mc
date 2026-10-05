-- ============================================================================
-- Bûcheron
-- Abat les arbres naturels autour de l'endroit où son chef lui a dit
-- « Travailler ici » (jamais les maisons en rondins : il faut des feuilles
-- naturelles), ramasse le bois et les pousses, puis replante les souches.
-- Sans hache il travaille quand même, mais beaucoup plus lentement.
-- ============================================================================
local M = {
  nom = "Bûcheron",
  description = "Abat les arbres autour de son lieu de travail, ramasse le bois et les pousses, et replante.",
}

M.rayon = 16              -- distance de recherche des arbres autour du lieu de travail
M.garder = { "nourriture", "#minecraft:axes", "#minecraft:saplings" }

function M.travailler(pnj, commun)
  local m = pnj.memoire
  local lx, ly, lz = pnj.lieu()
  if not lx then return pnj.attendre(5) end
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

  -- 2. Ramasser ce qui traîne autour de lui (bois, pousses, pommes), pas plus d'une fois par 45 s
  --    (un objet coincé dans les feuilles ne doit pas l'occuper indéfiniment).
  if pnj.objetsAuSol(10) > 0 and pnj.temps() >= (m.prochainRamassage or 0) then
    m.prochainRamassage = pnj.temps() + 45
    return pnj.ramasser(10)
  end

  -- 3. Abattre l'arbre le plus proche du lieu de travail.
  local x, y, z = pnj.chercherArbre(M.rayon, { x = lx, y = ly, z = lz })
  if x then
    if #m.souches < 16 then table.insert(m.souches, { x = x, y = y, z = z }) end
    return pnj.abattre(x, y, z)
  end

  commun.parfois(pnj, "plus_d_arbre", "Il n'y a plus d'arbre à abattre par ici. J'attends que ça repousse.", true, 1800)
  return pnj.attendre(30)
end

return M
