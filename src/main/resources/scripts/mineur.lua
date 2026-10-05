-- ============================================================================
-- Mineur
-- Creuse une galerie droite (1 bloc de large, 2 de haut) depuis l'endroit où
-- son chef lui a dit « Travailler ici », dans la direction où le chef regardait.
-- Il suit les filons visibles dans les parois, pose une torche tous les 8 blocs,
-- bouche les trous du sol avec de la pierre et s'arrête devant la lave ou l'eau.
-- Quand son sac est plein, le cerveau commun l'envoie au dépôt du bureau.
-- ============================================================================
local M = {
  nom = "Mineur",
  description = "Creuse une galerie droite dans la direction que tu regardes, suit les filons, pose des torches et rapporte tout au bureau.",
}

M.longueur = 32           -- longueur de la galerie à 1 étoile (+8 blocs par étoile en plus)
M.torches = 8             -- une torche tous les N blocs
M.minerais = "#c:ores"    -- ce qu'il suit dans les parois

-- Ce qu'il garde en vidant son sac (une pile de chaque).
M.garder = { "nourriture", "minecraft:torch", "#minecraft:pickaxes", "#c:cobblestones" }

local function cle(x, y, z) return x .. "," .. y .. "," .. z end

-- Lance l'action et retient la cible, pour repérer un échec répété sur le même bloc.
local function casser(pnj, m, x, y, z)
  m.cible = cle(x, y, z)
  return pnj.casser(x, y, z)
end

local function echecSur(pnj, m, x, y, z)
  local r = pnj.resultat()
  return m.cible == cle(x, y, z) and r ~= "" and r ~= "ok"
end

function M.travailler(pnj, commun)
  local m = pnj.memoire
  local lx, ly, lz = pnj.lieu()
  if not lx then return pnj.attendre(5) end
  local dx, dz = pnj.direction()

  -- Nouveau chantier (nouvel endroit ou nouvelle direction) : on repart de zéro.
  local chantier = cle(lx, ly, lz) .. "," .. dx .. "," .. dz
  if m.chantier ~= chantier then
    m.chantier, m.avance, m.fini, m.torche, m.cible, m.depose = chantier, 0, false, 0, nil, false
    m.ignores = {}
  end
  m.ignores = m.ignores or {}

  -- Galerie terminée : il vide son sac une fois, puis attend de nouveaux ordres.
  if m.fini then
    if not m.depose then m.depose = true; return pnj.deposer(M.garder) end
    commun.parfois(pnj, "fini", "La galerie est finie, chef. Dis-moi où creuser la prochaine (« Travailler ici »).", true, 1800)
    return pnj.attendre(10)
  end

  -- Sans pioche, pas de mine.
  if not pnj.equiper("#minecraft:pickaxes") then
    return pnj.demander("Il me faut une pioche pour creuser.", "#minecraft:pickaxes", 1)
  end

  -- 1. Les filons visibles juste autour de lui (à portée de pioche).
  local fx, fy, fz = pnj.chercherBloc(M.minerais, 2, { expose = true, recoltable = true })
  if fx and not m.ignores[cle(fx, fy, fz)] then
    if echecSur(pnj, m, fx, fy, fz) then m.ignores[cle(fx, fy, fz)] = true   -- inaccessible : on laisse tomber
    else return casser(pnj, m, fx, fy, fz) end
  end

  -- 2. Une torche tous les M.torches blocs, là où il fait sombre.
  local n0 = m.avance
  if n0 > 0 and n0 % M.torches == 0 and m.torche ~= n0 then
    m.torche = n0
    local tx, tz = lx + dx * n0, lz + dz * n0
    if pnj.a("minecraft:torch") then
      if pnj.lumiere(tx, ly + 1, tz) < 10 then return pnj.poser(tx, ly + 1, tz, "minecraft:torch") end
    else
      commun.parfois(pnj, "torches", "Je n'ai plus de torches, chef : la galerie va rester sombre.", true, 1200)
    end
  end

  -- 3. Le bloc suivant de la galerie.
  local n = n0 + 1
  if n > M.longueur + 8 * (pnj.niveau() - 1) then m.fini = true; return end
  local x, y, z = lx + dx * n, ly, lz + dz * n

  -- Danger : un liquide autour du prochain pas, on s'arrête là.
  local autour = { {0,0,0}, {0,1,0}, {0,2,0}, {0,-1,0}, {dx,0,dz}, {dx,1,dz},
                   {dz,0,dx}, {-dz,0,-dx}, {dz,1,dx}, {-dz,1,-dx} }
  for _, d in ipairs(autour) do
    local l = pnj.liquide(x + d[1], y + d[2], z + d[3])
    if l then
      pnj.dire(l == "lave" and "De la lave devant ! J'arrête la galerie ici." or "De l'eau devant, j'arrête la galerie ici.")
      m.fini = true
      return
    end
  end

  -- Creuser les pieds puis la tête.
  for h = 0, 1 do
    if not pnj.libre(x, y + h, z) then
      if echecSur(pnj, m, x, y + h, z) then
        pnj.dire("Je n'arrive pas à creuser plus loin (" .. pnj.resultat() .. ").")
        m.fini = true
        return
      end
      return casser(pnj, m, x, y + h, z)
    end
  end

  -- Le sol : un trou devant, on le bouche avec de la pierre.
  if pnj.libre(x, y - 1, z) then
    if echecSur(pnj, m, x, y - 1, z) then
      pnj.dire("Je n'arrive pas à combler le trou devant moi, j'arrête la galerie ici.")
      m.fini = true
      return
    end
    if pnj.a("#c:cobblestones") then
      m.cible = cle(x, y - 1, z)
      return pnj.poser(x, y - 1, z, "#c:cobblestones")
    end
    return pnj.demander("Il y a un trou devant moi : il me faudrait de la pierre pour le combler.", "#c:cobblestones", 8)
  end

  -- Avancer d'un pas.
  m.avance = n
  return pnj.aller(x, y, z, 0.7)
end

return M
