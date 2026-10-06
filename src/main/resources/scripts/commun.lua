-- ============================================================================
-- Cerveau commun à tous les PNJ employés.
-- Appelé toutes les demi-secondes, AVANT le métier. Il s'occupe de ce que tout
-- PNJ fait, quel que soit son métier : grève, fuite, faim, paie, ordres
-- « suivre » et « attendre », bureau, retour à temps, repos, sac plein,
-- commande terminée. Ensuite seulement, il laisse la main au métier :
-- metier.travailler(pnj, commun).
--
-- Où travailler, c'est le PNJ qui le décide : il va où il veut, tant qu'il
-- peut rentrer à temps dans son espace de vie (50 blocs autour du bureau).
--   * Sans lit, il rentre chaque soir (et avant d'être épuisé).
--   * Avec un lit près du bureau, il peut partir en expédition aussi loin
--     qu'il veut : il campe la nuit, et rentre au plus tard au bout du nombre
--     de jours réglé au bureau.
-- Guide complet : GUIDE-SCRIPTS.md (onglet « Guide » du panneau).
-- ============================================================================
local M = {}

-- Ce que le PNJ garde sur lui quand il vide son sac au bureau
-- (une pile de chaque : une pioche, des torches, de quoi manger...).
M.garder = { "nourriture", "minecraft:torch", "#minecraft:pickaxes", "#minecraft:axes",
             "#minecraft:swords", "#c:cobblestones", "#minecraft:saplings" }

-- Dire quelque chose au plus une fois toutes les `delai` secondes (évite de radoter).
-- auChef = true : message privé au chef ; sinon, à voix haute autour de lui.
function M.parfois(pnj, cle, texte, auChef, delai)
  local m = pnj.memoire
  m.dits = m.dits or {}
  local maintenant = pnj.temps()
  if m.dits[cle] and maintenant - m.dits[cle] < (delai or 600) then return end
  m.dits[cle] = maintenant
  if auChef then pnj.direAuChef(texte) else pnj.dire(texte) end
end

-- Une durée en heures du jeu, en toutes lettres (« 5 h », « 2 jours »).
function M.duree(heures)
  if heures < 24 then return math.max(1, math.floor(heures)) .. " h" end
  local j = math.floor(heures / 24 + 0.5)
  return j .. (j > 1 and " jours" or " jour")
end

-- Un point au hasard dans son espace de vie (50 blocs autour du bureau), à la surface si elle est connue.
function M.pointDansEspaceDeVie(pnj)
  local cx, cy, cz, r = pnj.espaceDeVie()
  local angle, d = math.random() * 2 * math.pi, math.sqrt(math.random()) * r
  local x, z = math.floor(cx + math.cos(angle) * d), math.floor(cz + math.sin(angle) * d)
  return x, pnj.surface(x, z) or cy, z
end

-- Le prochain endroit à explorer pour trouver « cle » (par exemple le bois commandé) :
-- une spirale autour du bureau, de plus en plus loin, pas après pas (un pas = 40 blocs).
-- Il reprend la spirale là où il l'avait laissée, même le lendemain.
-- Renvoie x, y, z ; ou nil, "plus_tard" si le prochain point est trop loin pour rentrer à temps aujourd'hui ;
-- ou nil, "tout_fouille" quand il a tout fouillé jusqu'où il peut aller (la spirale repart alors de zéro).
M.pas = 40
function M.pointAExplorer(pnj, cle)
  local m = pnj.memoire
  m.explo = m.explo or {}
  local e = m.explo[cle] or { a = math.random() * 2 * math.pi, r = 16 }
  local cx, cy, cz = pnj.espaceDeVie()
  local a = e.a + M.pas / e.r
  local r = e.r + M.pas * M.pas / e.r / (2 * math.pi)   -- 40 blocs plus loin à chaque tour
  if r > pnj.porteeMax() then
    m.explo[cle] = nil
    return nil, "tout_fouille"
  end
  local x, z = math.floor(cx + math.cos(a) * r), math.floor(cz + math.sin(a) * r)
  local y = pnj.surface(x, z) or cy
  if not pnj.peutAller(x, y, z) then return nil, "plus_tard" end
  e.a, e.r = a, r
  m.explo[cle] = e
  return x, y, z
end

function M.cerveau(pnj, metier)
  -- Une action est en cours : le métier peut seulement surveiller
  -- (le guerrier lâche tout s'il voit un monstre).
  if pnj.occupe() then
    if metier.vigilance then metier.vigilance(pnj) end
    return
  end

  local mode = pnj.mode()
  local m = pnj.memoire

  -- En grève : il attend au bureau que la caisse couvre ce qu'on lui doit.
  if mode == "greve" then
    local du = pnj.du()
    if du > 0 and pnj.caisse() >= du then return pnj.toucherPaie() end
    local bx, by, bz = pnj.bureau()
    if bx and pnj.distance(bx, by, bz) > 4 then return pnj.aller(bx, by, bz, 3) end
    M.parfois(pnj, "greve", "Toujours en grève. Il me faut " .. pnj.montant(du) .. " dans la caisse du bureau.", true, 1800)
    return pnj.attendre(20)
  end

  -- Attaqué : un combattant riposte, les autres s'enfuient.
  local agresseur = pnj.agresseur()
  if agresseur then
    if metier.combattant then return pnj.attaquer(agresseur.id) end
    M.parfois(pnj, "fuite", "À l'aide ! On m'attaque !", false, 30)
    return pnj.fuir(agresseur.id)
  end

  -- La faim : il mange ce qu'il a ; s'il n'a rien, il se sert au dépôt ou demande à son chef.
  if pnj.faim() <= 14 and pnj.manger() then return end
  if pnj.faim() <= 8 then
    if pnj.depotContient("nourriture") > 0 then return pnj.prendre("nourriture", 16) end
    return pnj.demander("J'ai faim, chef. Il me faudrait de quoi manger.", "nourriture", 1)
  end

  -- La paie : il va la chercher à la caisse du bureau (s'il est parti loin, à son retour).
  if pnj.paieDue() and pnj.chezLui() then return pnj.toucherPaie() end

  -- Les ordres du chef.
  if mode == "suivre" then
    if metier.escorter and metier.escorter(pnj) then return end
    return pnj.suivreChef()
  end
  if mode == "attendre" then return pnj.attendre(2) end

  -- Au travail. Sans bureau, pas de travail.
  if not pnj.aBureau() then
    M.parfois(pnj, "bureau", "Il me faut un bureau pour travailler : pose-en un, puis rattache-moi dans son écran.", true, 1800)
    return pnj.attendre(20)
  end

  local s = pnj.sortie()
  if not s.dehors and m.expedition then
    m.expedition = nil
    M.parfois(pnj, "retour", "Me voilà rentré d'expédition, chef.", true, 60)
  end

  -- Rentrer à temps : le soir sans lit, au bout des jours d'expédition avec un lit.
  if pnj.doitRentrer() then
    if pnj.resultat() == "bloque" then
      local x, y, z = pnj.position()
      M.parfois(pnj, "perdu", "Je n'arrive pas à rentrer au bureau : je suis coincé en " .. x .. " " .. y .. " " .. z .. ".", true, 1800)
    elseif pnj.lit() then
      M.parfois(pnj, "rentre", "Mon expédition touche à sa fin : je rentre au bureau (" .. math.floor(s.distance) .. " blocs).", true, 1800)
    else
      M.parfois(pnj, "rentre", "La journée avance : je rentre au bureau.", false, 600)
    end
    return pnj.rentrer()
  end

  -- Hors de ses heures, ou épuisé : il se repose.
  if pnj.fatigue() >= 100 then m.epuise = true elseif pnj.fatigue() <= 40 then m.epuise = nil end
  if not pnj.enService() or m.epuise then
    if m.epuise then M.parfois(pnj, "epuise", "Je suis épuisé, chef : je vais me reposer.", true, 1800) end
    if s.dehors then
      -- Loin de chez lui : il campe sur place (il a un lit, sinon il serait déjà rentré),
      -- sauf s'il est à moins d'une heure de marche : il rentre dormir dans son lit.
      if s.retour < 1 then return pnj.rentrer() end
      if not m.expedition then
        m.expedition = true
        M.parfois(pnj, "camp", "Je passe la nuit dehors, à " .. math.floor(s.distance) .. " blocs du bureau. Je serai rentré dans "
          .. M.duree(s.reste) .. " au plus tard.", true, 600)
      end
      return pnj.seReposer()
    end
    if pnj.libres() < 24 and not m.sacVide then
      m.sacVide = true
      return pnj.deposer(metier.garder or M.garder)
    end
    return pnj.seReposer()
  end
  m.sacVide = nil

  -- Sac plein : direction le dépôt du bureau, même s'il est loin.
  if pnj.plein() then
    if pnj.resultat() == "depot_plein" then
      M.parfois(pnj, "depot", "Le dépôt du bureau est plein, chef ! Vide-le (ou colle un coffre au bureau), je ne peux plus rien ranger.", true, 900)
      return pnj.attendre(30)
    end
    if pnj.resultat() == "pas_de_bureau" then return pnj.attendre(30) end
    return pnj.deposer(metier.garder or M.garder)
  end

  -- Loin de chez lui, s'il a de quoi remplir la commande dans son sac, il rentre la livrer.
  local c = pnj.commande()
  if c and c.objet and s.dehors then
    local fait, total = pnj.avancement()
    if fait >= 0 and fait + pnj.compter(c.objet) >= total then return pnj.deposer(metier.garder or M.garder) end
  end

  -- Commande remplie : en mode quantité, c'est fini ; en mode stock, il attend que le stock baisse.
  if c and pnj.commandeRemplie() then
    if pnj.libres() < 24 and not m.livre then
      m.livre = true
      return pnj.deposer(metier.garder or M.garder)
    end
    local fait, total = pnj.avancement()
    if c.mode == "quantite" then
      M.parfois(pnj, "commande_finie", "Commande terminée : " .. total .. " " .. c.nom .. ". Donne-moi la suivante dans le bureau.", true, 3600)
    else
      M.parfois(pnj, "stock_plein", "Le stock de " .. c.nom .. " est plein (" .. fait .. "/" .. total .. "). Je reprends dès qu'il baisse.", true, 3600)
    end
    if s.dehors then return pnj.rentrer() end
    return pnj.attendre(20)
  end
  m.livre = nil

  return metier.travailler(pnj, M)
end

return M
