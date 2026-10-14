package fr.lacolonie.pnj;

import fr.lacolonie.pnj.Schemas.Bloc;
import fr.lacolonie.pnj.Schemas.Genre;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.block.state.properties.SlabType;
import net.minecraft.world.level.gameevent.GameEvent;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.neoforged.fml.loading.FMLPaths;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * Le maçon (lot 10, cahier 4.4) : il bâtit un schéma ({@link Schemas}) à l'endroit que lui montre son chef (« construis la
 * cabane ici »), ou là où le chef a placé un plan de Create qu'il lui a donné, avec les matériaux du dépôt de son poste.
 * <p>
 * Les schémas sont des fichiers {@code .nbt} de blocs de structure : ceux du dossier {@code config/colonie_pnj/schemas/} du
 * serveur (une cabane d'exemple y est posée au démarrage), puis ceux envoyés par les joueurs à la table de Create
 * ({@code schematics/uploaded/<joueur>/}). Lus par le jeu ({@code StructureTemplate}, données mises à jour), gardés en mémoire.
 * <p>
 * Le chantier (dans {@code memoire.chantier}, repris après un redémarrage) : le schéma, sa place, sa rotation, et l'index du
 * premier bloc pas encore fait dans l'ordre de pose. À chaque décision, il ne regarde que les 64 blocs suivants au plus. Pour
 * chaque bloc : déjà là, il passe ; du naturel qui gêne (terre, pierre, herbe), il le casse et le garde ; un bloc d'un joueur,
 * il n'y touche jamais et le saute ; sinon il le pose, comme un joueur, à portée de bras. Ce qui manque, il va le chercher au
 * dépôt (et seulement là) ; si le dépôt n'en a pas, il le dit, écrit une seule lettre par matériau, et regarde de nouveau le
 * dépôt toutes les 30 s. Les entités et le contenu des coffres du schéma sont ignorés.
 */
final class Macon extends Metier {
    /** Blocs regardés au plus par décision ; besoins comptés au plus sur tant de blocs à venir ; essais avant de sauter un bloc. */
    static final int REGARD = 64, AVANCE = 512, ESSAIS = 3;
    /** Un matériau qui manque : le dépôt est revu toutes les tant de secondes. */
    static final int REVUE = 30;
    static final String SCHEMATIC = "create:schematic";

    Macon() {
        super("macon", "Maçon", "Bâtit, d'après un plan, ce que lui montre son chef, avec les matériaux du dépôt : couche par couche, "
                + "le solide d'abord, les portes et les torches ensuite. Il dégage la terre et la pierre qui gênent, jamais l'ouvrage d'autrui.", false,
                "nourriture", "#minecraft:pickaxes", "#minecraft:shovels", SCHEMATIC);
    }

    // --- Les schémas connus -----------------------------------------------------------------------------------

    static Path dossier() { return Reglages.dossier().resolve("schemas"); }

    static Path envois() { return FMLPaths.GAMEDIR.get().resolve("schematics").resolve("uploaded"); }

    /** Au démarrage : le dossier des schémas, et la cabane d'exemple s'il n'y en a aucun. */
    static void preparerDossier() {
        try {
            Path d = dossier();
            Files.createDirectories(d);
            Path cabane = d.resolve("cabane.nbt");
            boolean vide;
            try (Stream<Path> s = Files.list(d)) { vide = s.noneMatch(p -> p.toString().endsWith(".nbt")); }
            if (vide) try (InputStream in = Macon.class.getResourceAsStream("/schemas/cabane.nbt")) { if (in != null) Files.copy(in, cabane); }
        } catch (Exception e) {
            ColoniePnj.LOG.warn("Dossier des schémas du maçon : impossible à préparer", e);
        }
    }

    /** Les schémas qu'il connaît : nom dit → clé (« config:cabane.nbt », « envoi:Robert/tour.nbt »). Ceux du serveur d'abord. */
    static Map<String, String> connus() {
        Map<String, String> r = new LinkedHashMap<>();
        lister(dossier(), "config:", r);
        if (Files.isDirectory(envois()))
            try (Stream<Path> joueurs = Files.list(envois())) {
                for (Path j : joueurs.sorted().toList()) if (Files.isDirectory(j)) lister(j, "envoi:" + j.getFileName() + "/", r);
            } catch (Exception e) { ColoniePnj.LOG.warn("Schémas envoyés illisibles", e); }
        return r;
    }

    static void lister(Path d, String prefixe, Map<String, String> r) {
        if (!Files.isDirectory(d)) return;
        try (Stream<Path> s = Files.list(d)) {
            for (Path p : s.sorted().toList()) {
                String f = p.getFileName().toString();
                if (f.toLowerCase().endsWith(".nbt") && Files.isRegularFile(p)) r.putIfAbsent(Schemas.nom(f), prefixe + f);
            }
        } catch (Exception e) { ColoniePnj.LOG.warn("Schémas illisibles dans " + d, e); }
    }

    /** Le fichier d'une clé, jamais hors de son dossier ; null sinon. */
    static Path fichier(String cle) {
        Path base;
        String rel;
        if (cle.startsWith("config:")) { base = dossier(); rel = cle.substring(7); }
        else if (cle.startsWith("envoi:")) { base = envois(); rel = cle.substring(6); }
        else return null;
        base = base.toAbsolutePath().normalize();
        Path p = base.resolve(rel).normalize();
        return p.startsWith(base) && rel.toLowerCase().endsWith(".nbt") ? p : null;
    }

    /** Un schéma lu : sa palette (états du jeu et leur écriture « id[k=v] »), ses blocs dans l'ordre de pose, sa taille. */
    record Modele(List<BlockState> palette, List<String> etats, List<Bloc> ordre, int sx, int sy, int sz, int pleins, long date) {}

    /** Les schémas lus, par clé (relus si le fichier a changé, au moment d'un nouvel ordre). */
    static final Map<String, Modele> MODELES = new HashMap<>();

    static Modele modele(ServerLevel l, String cle, boolean revoir) {
        Modele m = MODELES.get(cle);
        if (m != null && !revoir) return m;
        Path p = fichier(cle);
        if (p == null || !Files.isRegularFile(p)) return null;
        try {
            long date = Files.getLastModifiedTime(p).toMillis();
            if (m != null && m.date() == date) return m;
            CompoundTag brut = NbtIo.readCompressed(p, NbtAccounter.create(0x20000000L));
            StructureTemplate gabarit = l.getStructureManager().readStructure(brut); // les données d'une vieille version mises à jour
            CompoundTag tag = gabarit.save(new CompoundTag());
            ListTag taille = tag.getList("size", Tag.TAG_INT);
            ListTag pal = tag.contains("palettes") ? tag.getList("palettes", Tag.TAG_LIST).getList(0) : tag.getList("palette", Tag.TAG_COMPOUND);
            var blocsJeu = l.holderLookup(Registries.BLOCK);
            List<BlockState> palette = new ArrayList<>();
            List<String> etats = new ArrayList<>();
            for (int i = 0; i < pal.size(); i++) {
                CompoundTag c = pal.getCompound(i);
                palette.add(NbtUtils.readBlockState(blocsJeu, c));
                StringBuilder e = new StringBuilder(c.getString("Name"));
                CompoundTag pr = c.getCompound("Properties");
                if (!pr.isEmpty()) {
                    e.append('[');
                    e.append(String.join(",", pr.getAllKeys().stream().sorted().map(k -> k + "=" + pr.getString(k)).toList()));
                    e.append(']');
                }
                etats.add(e.toString());
            }
            List<Bloc> blocs = new ArrayList<>();
            int pleins = 0;
            ListTag lb = tag.getList("blocks", Tag.TAG_COMPOUND);
            for (int i = 0; i < lb.size(); i++) {
                CompoundTag c = lb.getCompound(i);
                ListTag pos = c.getList("pos", Tag.TAG_INT);
                int s = c.getInt("state");
                if (s < 0 || s >= etats.size()) continue;
                blocs.add(new Bloc(pos.getInt(0), pos.getInt(1), pos.getInt(2), s));
                if (Schemas.genre(etats.get(s)) != Genre.VIDE) pleins++;
            }
            m = new Modele(palette, etats, Schemas.ordre(blocs, etats), taille.getInt(0), taille.getInt(1), taille.getInt(2), pleins, date);
            MODELES.put(cle, m);
            return m;
        } catch (Exception e) {
            ColoniePnj.LOG.warn("Schéma illisible : " + cle, e);
            return null;
        }
    }

    // --- Le chantier ---------------------------------------------------------------------------------------------

    static CompoundTag chantier(Travailleur t) { return t.memoire.getCompound("chantier"); }

    static BlockPos place(CompoundTag c, Bloc b) {
        int[] q = Schemas.tourner(b.x(), b.z(), c.getInt("m"), c.getInt("r"));
        return new BlockPos(c.getInt("ox") + q[0], c.getInt("oy") + b.y(), c.getInt("oz") + q[1]);
    }

    static BlockState etat(CompoundTag c, Modele m, Bloc b) {
        return m.palette().get(b.etat()).mirror(Mirror.values()[Math.floorMod(c.getInt("m"), 3)]).rotate(Rotation.values()[Math.floorMod(c.getInt("r"), 4)]);
    }

    /** L'objet qu'il faut pour poser ce bloc (AIR : rien ne le pose, il le saute) et combien (2 pour une double dalle). */
    static Item objet(BlockState s) { return s.getBlock().asItem(); }

    static int nombre(BlockState s) {
        return s.hasProperty(BlockStateProperties.SLAB_TYPE) && s.getValue(BlockStateProperties.SLAB_TYPE) == SlabType.DOUBLE ? 2 : 1;
    }

    static Actions.Filtre filtre(Item i) { return Actions.Filtre.de(BuiltInRegistries.ITEM.getKey(i).toString()); }

    /** L'autre moitié d'une porte, d'une grande plante ou d'un lit, posée avec celle-ci ; null s'il n'y en a pas. */
    static BlockPos compagnon(BlockPos p, BlockState s) {
        if (s.hasProperty(BlockStateProperties.DOUBLE_BLOCK_HALF) && s.getValue(BlockStateProperties.DOUBLE_BLOCK_HALF) == DoubleBlockHalf.LOWER) return p.above();
        if (s.getBlock() instanceof BedBlock && s.getValue(BedBlock.PART) == BedPart.FOOT) return p.relative(s.getValue(BedBlock.FACING));
        return null;
    }

    static BlockState etatCompagnon(BlockState s) {
        if (s.hasProperty(BlockStateProperties.DOUBLE_BLOCK_HALF)) return s.setValue(BlockStateProperties.DOUBLE_BLOCK_HALF, DoubleBlockHalf.UPPER);
        return s.setValue(BedBlock.PART, BedPart.HEAD);
    }

    /**
     * Ce qu'il peut casser pour faire place : terre, herbe, pierre, sable, gravier, neige, argile, et les plantes qui se
     * remplacent. Jamais un bloc construit, un coffre, un liquide, un champ labouré ou un chemin (l'ouvrage de quelqu'un), ni du
     * bois ou des feuilles (un arbre, c'est l'affaire du bûcheron).
     */
    static boolean naturel(Level l, BlockPos p, BlockState s) {
        if (s.isAir() || !s.getFluidState().isEmpty() || l.getBlockEntity(p) != null || s.getDestroySpeed(l, p) < 0 || Monde.construit(l, p)) return false;
        if (s.canBeReplaced()) return true;
        return s.is(BlockTags.DIRT) || s.is(BlockTags.BASE_STONE_OVERWORLD) || s.is(BlockTags.SAND) || s.is(Blocks.GRAVEL) || s.is(BlockTags.SNOW)
                || s.is(Blocks.CLAY);
    }

    /** Il commence un chantier (il oublie celui d'avant). */
    static void commencer(Travailleur t, String cle, String nom, BlockPos origine, int rotation, int miroir, String objet) {
        CompoundTag c = new CompoundTag();
        c.putString("cle", cle);
        c.putString("nom", nom);
        c.putInt("ox", origine.getX());
        c.putInt("oy", origine.getY());
        c.putInt("oz", origine.getZ());
        c.putInt("r", rotation);
        c.putInt("m", miroir);
        c.putLong("debut", t.level().getGameTime());
        if (objet != null) c.putString("objet", objet);
        t.memoire.put("chantier", c);
    }

    @Override
    void travailler(Travailleur t) {
        if (t.bureau == null || !(t.level() instanceof ServerLevel l)) { Cerveau.attendre(t, 5); return; }
        planDuSac(t);
        CompoundTag c = chantier(t);
        long maintenant = Cerveau.temps(t);
        if (c.isEmpty() || c.getBoolean("pause") || c.getLong("attente") > maintenant) {
            if (c.isEmpty()) Cerveau.dire(t, "sans_chantier", 600, "Je n'ai rien à bâtir. Montre-moi l'endroit, et dis-moi quel plan suivre.");
            if (t.distanceToSqr(Vec3.atBottomCenterOf(t.bureau)) > 6 * 6) Cerveau.rentrer(t);
            else Cerveau.attendre(t, 10);
            return;
        }
        Modele m = modele(l, c.getString("cle"), false);
        if (m == null) {
            Cerveau.ecrire(t, "schema_perdu", Cerveau.UN_JOUR, "schema_perdu", c.getString("nom"));
            t.memoire.remove("chantier");
            return;
        }
        int i = c.getInt("i");
        // La dernière action sur ce bloc a échoué (hors d'atteinte, la place prise) : au bout de 3 fois, il le remet à la fin
        // (une seconde passe, une fois tout le reste fait) ; à la seconde passe, il le saute.
        if (c.contains("vise")) {
            if (c.getInt("vise") == i && !t.resultat.isEmpty() && !t.resultat.equals("ok")) c.putInt("essais", c.getInt("essais") + 1);
            c.remove("vise");
            if (c.getInt("essais") >= ESSAIS) {
                if (c.getBoolean("repasse")) c.putInt("sautes", c.getInt("sautes") + 1);
                else {
                    int[] tard = c.getIntArray("tard"), plus = java.util.Arrays.copyOf(tard, tard.length + 1);
                    plus[tard.length] = i;
                    c.putIntArray("tard", plus);
                }
                c.putInt("essais", 0);
                i++;
            }
        }
        int taille = taille(c, m);
        for (int k = 0; k < REGARD && i < taille; k++) {
            Bloc b = entree(c, m, i);
            BlockPos p = place(c, b);
            if (!l.isLoaded(p)) { c.putInt("i", i); Cerveau.attendre(t, 5); return; }
            BlockState voulu = etat(c, m, b), la = l.getBlockState(p);
            if (Schemas.genre(m.etats().get(b.etat())) == Genre.VIDE) {
                // De l'air dans le plan : il dégage le naturel, et laisse tout le reste (l'ouvrage d'un joueur, l'eau).
                if (naturel(l, p, la)) { casser(t, c, i, p, la); return; }
                i++;
                continue;
            }
            if (la.getBlock() == voulu.getBlock()) { i++; c.putInt("essais", 0); continue; }
            Item o = objet(voulu);
            if (o == Items.AIR) { i++; continue; }
            BlockPos gene = !la.canBeReplaced() ? p : null;
            BlockPos autre = compagnon(p, voulu);
            if (gene == null && autre != null && !l.getBlockState(autre).canBeReplaced() && l.getBlockState(autre).getBlock() != voulu.getBlock()) gene = autre;
            if (gene != null) {
                BlockState g = l.getBlockState(gene);
                if (naturel(l, gene, g)) { casser(t, c, i, gene, g); return; }
                // L'ouvrage de quelqu'un : il n'y touche pas, le dit une fois par chantier, et passe.
                Cerveau.ecrire(t, "gene_" + c.getLong("debut"), 30 * Cerveau.UN_JOUR, "chantier_gene", c.getString("nom"), gene.toShortString());
                c.putInt("sautes", c.getInt("sautes") + 1);
                i++;
                continue;
            }
            int n = nombre(voulu);
            Actions.Filtre f = filtre(o);
            if (t.compter(f) >= n) {
                if (c.contains("manque")) {
                    c.remove("manque");
                    c.remove("attente");
                    Cerveau.dire(t, "reprend", 30, "J'ai ce qu'il faut. Je reprends le chantier.");
                }
                c.putInt("i", i);
                c.putInt("vise", i);
                Cerveau.lancer(t, new Batir(p, voulu, o, n));
                return;
            }
            Bureau.Entite d = Bureau.entite(t);
            if (d != null && d.compter(f) >= n - t.compter(f)) {
                c.putInt("i", i);
                Cerveau.lancer(t, new Approvisionner(besoins(t, c, m, i, d)));
                return;
            }
            manque(t, c, i, o, n - t.compter(f), maintenant);
            return;
        }
        c.putInt("i", i);
        if (i < taille) return;
        if (!c.getBoolean("repasse") && c.getIntArray("tard").length > 0) {
            c.putBoolean("repasse", true);
            c.putInt("i", 0);
            return;
        }
        finir(t, c);
    }

    /** Le i-ème bloc à faire : dans l'ordre de pose, ou, à la seconde passe, parmi ceux remis à la fin. */
    static Bloc entree(CompoundTag c, Modele m, int i) { return c.getBoolean("repasse") ? m.ordre().get(c.getIntArray("tard")[i]) : m.ordre().get(i); }

    static int taille(CompoundTag c, Modele m) { return c.getBoolean("repasse") ? c.getIntArray("tard").length : m.ordre().size(); }

    static void casser(Travailleur t, CompoundTag c, int i, BlockPos p, BlockState st) {
        c.putInt("i", i);
        c.putInt("vise", i);
        Itineraire.meilleurOutil(t, st, p);
        Cerveau.lancer(t, new Actions.Casser(p));
    }

    /** Le dépôt n'a pas ce matériau : il le dit, l'écrit (une lettre par matériau et par chantier), et revoit le dépôt dans 30 s. */
    static void manque(Travailleur t, CompoundTag c, int i, Item o, int n, long maintenant) {
        String nom = Consignes.nom(new ItemStack(o));
        String id = BuiltInRegistries.ITEM.getKey(o).toString();
        c.putInt("i", i);
        c.putString("manque", id);
        c.putLong("attente", maintenant + REVUE);
        Cerveau.dire(t, "manque_" + id, 300, "Il me manque " + nom + " pour " + Schemas.nom(c.getString("nom")) + ". J'attends qu'il y en ait au dépôt.");
        Cerveau.ecrire(t, "manque_" + id + "_" + c.getLong("debut"), 30 * Cerveau.UN_JOUR, "materiau_manque", nom, c.getString("nom"), Math.max(1, n));
        if (t.distanceToSqr(Vec3.atBottomCenterOf(t.bureau)) > 6 * 6) Cerveau.rentrer(t);
    }

    /** Fini : il le dit, l'écrit, et range au dépôt ce qui lui reste. */
    static void finir(Travailleur t, CompoundTag c) {
        String nom = c.getString("nom");
        int sautes = c.getInt("sautes");
        if (c.contains("objet")) t.memoire.putString("plan_fait", c.getString("objet"));
        t.memoire.remove("chantier");
        Voix.dire(t, "L'ouvrage « " + nom + " » est bâti, chef !" + (sautes > 0 ? " Il reste " + sautes + " place" + (sautes > 1 ? "s" : "") + " que je n'ai pas pu faire." : ""), "calme");
        Courrier.ecrire(t, "chantier_fini", nom, sautes == 0 ? "" : " Je n'ai pas pu faire " + sautes + " bloc" + (sautes > 1 ? "s" : "")
                + " : la place était prise par un ouvrage que je ne touche pas, ou hors de ma portée.");
        Cerveau.lancer(t, new Actions.Deposer(Metier.de("macon").garder));
    }

    /**
     * Ce qu'il emporte du dépôt : de quoi poser les {@link #AVANCE} blocs suivants au plus, sans dépasser la place de son sac
     * (une case gardée pour ce qu'il casse), en commençant par ce qu'il faut tout de suite.
     */
    static List<ItemStack> besoins(Travailleur t, CompoundTag c, Modele m, int i, Bureau.Entite d) {
        Map<Item, Integer> total = new LinkedHashMap<>();
        for (int j = i; j < Math.min(taille(c, m), i + AVANCE); j++) {
            Bloc b = entree(c, m, j);
            if (Schemas.genre(m.etats().get(b.etat())) == Genre.VIDE) continue;
            BlockState s = etat(c, m, b);
            Item o = objet(s);
            if (o != Items.AIR) total.merge(o, nombre(s), Integer::sum);
        }
        int place = Math.max(1, Monde.libres(t) - 1);
        List<ItemStack> r = new ArrayList<>();
        for (var e : total.entrySet()) {
            if (place <= 0) break;
            Actions.Filtre f = filtre(e.getKey());
            int n = Math.min(e.getValue() - t.compter(f), d.compter(f));
            if (n <= 0) continue;
            int pile = Math.max(1, new ItemStack(e.getKey()).getMaxStackSize());
            n = Math.min(n, place * pile);
            place -= (n + pile - 1) / pile;
            r.add(new ItemStack(e.getKey(), n));
        }
        return r;
    }

    /** Un plan de Create placé par le chef, qu'il lui a donné (dans son sac) : il le bâtit là où il est placé, dans son sens. */
    static void planDuSac(Travailleur t) {
        for (int k = 0; k < t.inventaire.getContainerSize(); k++) {
            ItemStack s = t.inventaire.getItem(k);
            if (s.isEmpty() || !SCHEMATIC.equals(BuiltInRegistries.ITEM.getKey(s.getItem()).toString())) continue;
            if (!(composant(s, "schematic_deployed") instanceof Boolean b && b)) continue;
            if (!(composant(s, "schematic_owner") instanceof String qui) || !(composant(s, "schematic_file") instanceof String f)
                    || !(composant(s, "schematic_anchor") instanceof BlockPos ancre)) continue;
            int r = composant(s, "schematic_rotation") instanceof Rotation x ? x.ordinal() : 0;
            int mi = composant(s, "schematic_mirror") instanceof Mirror x ? x.ordinal() : 0;
            String sig = qui + "/" + f + "@" + ancre.asLong() + "/" + r + "/" + mi;
            if (sig.equals(t.memoire.getString("plan_fait")) || sig.equals(chantier(t).getString("objet"))) continue;
            String cle = "envoi:" + qui + "/" + f;
            if (!(t.level() instanceof ServerLevel l) || modele(l, cle, true) == null) {
                Cerveau.dire(t, "plan_illisible", 600, "Ce plan, je n'arrive pas à le lire, chef. Il faut que tu l'envoies à la table d'abord.");
                continue;
            }
            if (!t.chezLui(ancre)) { Cerveau.dire(t, "plan_loin", 600, "Ce plan est placé trop loin de notre poste, chef : je bâtis près de chez nous."); continue; }
            commencer(t, cle, Schemas.nom(f), ancre, r, mi, sig);
            Voix.dire(t, "Le plan que tu m'as donné : « " + Schemas.nom(f) + " ». Je le bâtis là où tu l'as placé.", "calme");
            return;
        }
    }

    /** Un composant de données d'un autre mod, lu par son identifiant (sans dépendre de ses classes) ; null s'il n'existe pas. */
    static Object composant(ItemStack s, String nom) {
        var type = BuiltInRegistries.DATA_COMPONENT_TYPE.get(ResourceLocation.fromNamespaceAndPath("create", nom));
        return type == null ? null : s.get(type);
    }

    /** Renvoyé ou réembauché : le chantier de l'ancien chef est oublié. */
    static void oublier(Travailleur t) {
        t.memoire.remove("chantier");
        t.memoire.remove("plan_fait");
    }

    // --- Les gestes ---------------------------------------------------------------------------------------------

    /**
     * Pose un bloc du plan, comme un joueur : il s'approche à portée de bras, s'écarte s'il se tient à cette place, retire
     * l'objet de son sac, pose le bloc dans le sens du plan (avec l'autre moitié d'une porte ou d'un lit), au bruit du bloc.
     */
    static final class Batir extends Actions.Action {
        final BlockPos pos;
        final BlockState etat;
        final Item objet;
        final int n;
        int gene;
        BlockPos ecart, pied;

        Batir(BlockPos pos, BlockState etat, Item objet, int n) {
            super("batir");
            this.pos = pos;
            this.etat = etat;
            this.objet = objet;
            this.n = n;
            travail = true;
        }

        Actions.Etat tick(Travailleur t) {
            ServerLevel l = (ServerLevel) t.level();
            BlockState la = l.getBlockState(pos);
            if (la.getBlock() == etat.getBlock()) return Actions.Etat.OK;
            if (!la.canBeReplaced()) return echec("occupe");
            if (!Actions.aPortee(t, pos)) {
                if (coince() || age > 20 * 60) return echec("inaccessible");
                // Il marche vers une place debout d'où il touche le bloc (un bloc en l'air ne s'atteint pas en y allant).
                if (pied == null) pied = place(t, pos, 3, -4);
                marcher(t, pied == null ? Vec3.atCenterOf(pos) : Vec3.atBottomCenterOf(pied), 1.0);
                return Actions.Etat.EN_COURS;
            }
            t.getLookControl().setLookAt(Vec3.atCenterOf(pos));
            if (!l.isUnobstructed(etat, pos, CollisionContext.empty())) {
                // Quelqu'un se tient là (lui-même, souvent) : il s'écarte, ou attend qu'on lui fasse place.
                if (++gene > 120) return echec("occupe");
                if (t.getBoundingBox().intersects(new net.minecraft.world.phys.AABB(pos))) {
                    if (gene % 40 == 1) ecart = place(t, pos, 2, -3);
                    if (ecart != null) {
                        t.getNavigation().stop();
                        Vec3 v = Vec3.atBottomCenterOf(ecart);
                        t.getMoveControl().setWantedPosition(v.x, v.y, v.z, 0.7);
                        if (ecart.getY() > t.getBlockY()) t.getJumpControl().jump();
                    }
                }
                return Actions.Etat.EN_COURS;
            }
            t.getNavigation().stop();
            ItemStack pris = Transporteur.Tour.sortir(t, new ItemStack(objet, n));
            if (pris.getCount() < n) { if (!pris.isEmpty()) t.ranger(pris); return echec("pas_d_objet"); }
            BlockState s = etat.hasProperty(BlockStateProperties.WATERLOGGED) ? etat.setValue(BlockStateProperties.WATERLOGGED, false) : etat;
            if (!la.isAir()) l.destroyBlock(pos, false, t);
            l.setBlock(pos, s, 3);
            BlockPos autre = compagnon(pos, s);
            if (autre != null && l.getBlockState(autre).canBeReplaced()) {
                if (!l.getBlockState(autre).isAir()) l.destroyBlock(autre, false, t);
                l.setBlock(autre, etatCompagnon(s), 3);
            }
            t.swing(InteractionHand.MAIN_HAND);
            Sons.pose(t, pos, s);
            l.gameEvent(t, GameEvent.BLOCK_PLACE, pos);
            return Actions.Etat.OK;
        }

        /** Une place debout près du bloc (à {@code r} blocs, de {@code bas} à +1 en hauteur), hors de sa colonne, d'où il le touche. */
        static BlockPos place(Travailleur t, BlockPos pos, int r, int bas) {
            Level l = t.level();
            BlockPos meilleur = null;
            double dMin = Double.MAX_VALUE;
            for (BlockPos q : BlockPos.betweenClosed(pos.offset(-r, bas, -r), pos.offset(r, 1, r))) {
                if (q.getX() == pos.getX() && q.getZ() == pos.getZ()) continue;
                if (!Monde.debout(l, q) || Monde.mortel(l, q)) continue;
                if (Vec3.atBottomCenterOf(q).add(0, 1.6, 0).distanceToSqr(Vec3.atCenterOf(pos)) > 4.2 * 4.2) continue;
                // Plutôt en dessous : à la même hauteur, il se mettrait sur une autre place du plan (le haut d'un mur).
                double d = t.position().distanceToSqr(Vec3.atBottomCenterOf(q)) + (q.getY() >= pos.getY() ? 16 : 0);
                if (d < dMin) { dMin = d; meilleur = q.immutable(); }
            }
            return meilleur;
        }
    }

    /** Au poste : il prend au dépôt ce qu'il lui faut pour les blocs à venir. */
    static final class Approvisionner extends Actions.AuBureau {
        final List<ItemStack> voulus;

        Approvisionner(List<ItemStack> voulus) { super("approvisionner"); this.voulus = voulus; travail = true; }

        Actions.Etat tick(Travailleur t) {
            Bureau.Entite b = arriver(t);
            if (b == null) return raison.isEmpty() ? Actions.Etat.EN_COURS : Actions.Etat.ECHEC;
            int pris = 0;
            for (ItemStack v : voulus)
                for (ItemStack x : b.prendre(filtre(v.getItem()), v.getCount())) { pris += x.getCount(); t.ranger(x); }
            t.swing(InteractionHand.MAIN_HAND);
            Sons.coffre(t, b.getBlockPos());
            if (pris > 0) Cerveau.dire(t, "approvisionne", 120, "Je prends de quoi bâtir au dépôt.");
            return pris > 0 ? Actions.Etat.OK : echec("rien_au_depot");
        }
    }

    // --- Sur ordre ------------------------------------------------------------------------------------------------

    /** Ce que le chef demande, prêt à retenir ({@code texte} : clé|nom|ox,oy,oz|rotation), ou son refus. */
    record Preparation(String refus, String texte) {}

    /** « Construis la cabane ici » : le schéma dit, l'endroit regardé (ou là où il est), le sens du regard du chef. */
    static Preparation preparer(Travailleur t, ServerPlayer chef, String phrase, BlockPos vu) {
        if (t.bureau == null) return new Preparation("Il me faut un poste de travail pour ça, chef.", null);
        Map<String, String> connus = connus();
        if (connus.isEmpty()) return new Preparation("Je n'ai aucun plan, chef. Il faut d'abord en déposer un.", null);
        String nom = Schemas.choisir(phrase, connus.keySet());
        if (nom == null && connus.size() == 1) nom = connus.keySet().iterator().next();
        if (nom == null) return new Preparation("Quel plan, chef ? Je connais : " + Schemas.liste(List.copyOf(connus.keySet())) + ".", null);
        ServerLevel l = (ServerLevel) t.level();
        Modele m = modele(l, connus.get(nom), true);
        if (m == null) return new Preparation("Le plan « " + nom + " », je n'arrive pas à le lire, chef.", null);
        if (m.pleins() > Schemas.BLOCS_MAX || Math.max(m.sx(), Math.max(m.sy(), m.sz())) > Schemas.COTE_MAX)
            return new Preparation("Le plan « " + nom + " » est bien trop grand pour moi seul, chef.", null);
        BlockPos ancre = vu == null ? t.blockPosition() : l.getBlockState(vu).canBeReplaced() ? vu : vu.above();
        int r = Schemas.rotation(chef.getDirection().getName());
        int[] o = Schemas.centre(ancre.getX(), ancre.getY(), ancre.getZ(), m.sx(), m.sz(), 0, r);
        int[] a = Schemas.tourner(0, 0, 0, r), b = Schemas.tourner(m.sx() - 1, m.sz() - 1, 0, r);
        for (int[] q : new int[][]{{a[0], a[1]}, {a[0], b[1]}, {b[0], a[1]}, {b[0], b[1]}, {(a[0] + b[0]) / 2, (a[1] + b[1]) / 2}}) {
            BlockPos coin = new BlockPos(o[0] + q[0], o[1], o[2] + q[1]);
            if (!t.chezLui(coin)) return new Preparation("C'est trop loin de mon poste, chef : je bâtis près de chez nous.", null);
            if (Mineur.interdit(t, coin, false)) return new Preparation("Ce terrain n'est pas à nous, chef : je n'y bâtis rien.", null);
        }
        return new Preparation(null, connus.get(nom) + "|" + nom + "|" + o[0] + "," + o[1] + "," + o[2] + "|" + r);
    }

    static Actions.Action consigne(Travailleur t, String mode, Consignes.Consigne c) { return new Ordre(mode, c); }

    /** Il retient le chantier (ou le met en pause, le reprend, l'abandonne), et dit ce qu'il a compris. */
    static final class Ordre extends Actions.Action {
        final String mode;
        final Consignes.Consigne c;

        Ordre(String mode, Consignes.Consigne c) { super("chantier"); this.mode = mode; this.c = c; }

        Actions.Etat tick(Travailleur t) {
            CompoundTag ch = chantier(t);
            switch (mode) {
                case "construire" -> {
                    String[] x = c.texte.split("\\|");
                    if (x.length < 4) return echec("ordre");
                    String[] o = x[2].split(",");
                    commencer(t, x[0], x[1], new BlockPos(Integer.parseInt(o[0]), Integer.parseInt(o[1]), Integer.parseInt(o[2])), Integer.parseInt(x[3]), 0, null);
                    Voix.dire(t, "Je bâtis l'ouvrage « " + x[1] + " » ici, chef. Je prends les matériaux au dépôt.", "calme");
                }
                case "arreter_chantier" -> {
                    if (ch.isEmpty()) { Voix.dire(t, "Je n'ai pas de chantier en cours, chef.", "calme"); return Actions.Etat.OK; }
                    ch.putBoolean("pause", true);
                    Voix.dire(t, "Je pose mes outils. Le chantier attendra que tu me dises de reprendre.", "calme");
                }
                case "reprendre_chantier" -> {
                    if (ch.isEmpty()) { Voix.dire(t, "Je n'ai pas de chantier à reprendre, chef.", "calme"); return Actions.Etat.OK; }
                    ch.remove("pause");
                    ch.remove("attente");
                    Voix.dire(t, "Je reprends le chantier, chef.", "calme");
                }
                default -> {
                    if (ch.isEmpty()) { Voix.dire(t, "Je n'ai pas de chantier, chef.", "calme"); return Actions.Etat.OK; }
                    t.memoire.remove("chantier");
                    Voix.dire(t, "J'abandonne le chantier. Ce qui est bâti reste bâti.", "calme");
                }
            }
            return Actions.Etat.OK;
        }
    }

    // --- Ce qu'il sait dire ----------------------------------------------------------------------------------

    /** « Quels plans connais-tu ? » */
    static String schemasTexte() {
        List<String> n = List.copyOf(connus().keySet());
        return n.isEmpty() ? "Je ne connais aucun plan, chef. Il faut m'en donner." : "Je connais " + (n.size() == 1 ? "un plan : " : n.size() + " plans : ") + Schemas.liste(n) + ".";
    }

    /** « Où en est le chantier ? » : le pour cent, et ce qui manque (ni sur lui ni au dépôt). */
    static String etatTexte(Travailleur t) {
        CompoundTag c = chantier(t);
        if (c.isEmpty()) return "Je n'ai pas de chantier en cours, chef.";
        if (!(t.level() instanceof ServerLevel l)) return "";
        Modele m = modele(l, c.getString("cle"), false);
        if (m == null) return "Je ne retrouve plus le plan de ce chantier, chef.";
        int total = m.ordre().size(), i = c.getBoolean("repasse") ? total - taille(c, m) + c.getInt("i") : c.getInt("i");
        String r = "L'ouvrage « " + c.getString("nom") + " » est fait à " + Schemas.pourcent(i, total) + " pour cent" + (c.getBoolean("pause") ? ", et il attend que tu me dises de reprendre." : ".");
        Map<Item, Integer> reste = new LinkedHashMap<>();
        for (int j = c.getBoolean("repasse") ? 0 : i; j < total; j++) {
            Bloc b = m.ordre().get(j);
            if (Schemas.genre(m.etats().get(b.etat())) == Genre.VIDE) continue;
            BlockState s = etat(c, m, b);
            BlockPos p = place(c, b);
            if (l.isLoaded(p) && l.getBlockState(p).getBlock() == s.getBlock()) continue;
            if (objet(s) != Items.AIR) reste.merge(objet(s), nombre(s), Integer::sum);
        }
        Bureau.Entite d = Bureau.entite(t);
        List<String> manque = new ArrayList<>();
        for (var e : reste.entrySet()) {
            Actions.Filtre f = filtre(e.getKey());
            int n = e.getValue() - t.compter(f) - (d == null ? 0 : d.compter(f));
            if (n > 0) manque.add(n + " " + Consignes.nom(new ItemStack(e.getKey())));
        }
        if (manque.isEmpty()) return r + " J'ai tout ce qu'il faut pour finir.";
        return r + " Il me manque : " + Schemas.liste(manque.subList(0, Math.min(5, manque.size()))) + (manque.size() > 5 ? ", et d'autres choses." : ".");
    }
}
