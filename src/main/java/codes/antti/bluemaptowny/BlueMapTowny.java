package codes.antti.bluemaptowny;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import com.flowpowered.math.vector.Vector2d;
import com.flowpowered.math.vector.Vector2i;
import com.technicjelle.BMUtils.Cheese;
import de.bluecolored.bluemap.api.BlueMapAPI;
import de.bluecolored.bluemap.api.markers.*;
import de.bluecolored.bluemap.api.math.Color;
import de.bluecolored.bluemap.api.math.Shape;

import org.apache.commons.lang.WordUtils;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.configuration.Configuration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import com.gmail.goosius.siegewar.SiegeWarAPI;
import com.gmail.goosius.siegewar.enums.SiegeSide;
import com.gmail.goosius.siegewar.objects.Siege;
import com.gmail.goosius.siegewar.settings.SiegeWarSettings;

import com.palmergames.bukkit.config.ConfigNodes;
import com.palmergames.bukkit.towny.TownyAPI;
import com.palmergames.bukkit.towny.TownyEconomyHandler;
import com.palmergames.bukkit.towny.TownyFormatter;
import com.palmergames.bukkit.towny.TownySettings;
import com.palmergames.bukkit.towny.object.Resident;
import com.palmergames.bukkit.towny.object.Town;
import com.palmergames.bukkit.towny.object.TownyObject;
import com.palmergames.bukkit.towny.object.TownyWorld;
import com.palmergames.bukkit.towny.utils.TownRuinUtil;

public final class BlueMapTowny extends JavaPlugin {
    private final Map<UUID, MarkerSet> townMarkerSets = new ConcurrentHashMap<>();
    private Configuration config;

    @Override
    public void onEnable() {
        final boolean isFolia = isFolia();
        BlueMapAPI.onEnable((api) -> {
            saveDefaultConfig();
            reloadConfig();
            this.config = getConfig();
            initMarkerSets();
            long intervalSec = Math.max(1L, this.config.getLong("update-interval", 30L));
            if (isFolia) {
                Bukkit.getServer().getAsyncScheduler().runAtFixedRate(
                        this,
                        task -> safeUpdateMarkers(),
                        1L,
                        intervalSec,
                        TimeUnit.SECONDS
                );
            } else {
                Bukkit.getScheduler().runTaskTimerAsynchronously(
                        this,
                        this::safeUpdateMarkers,
                        0L,
                        intervalSec * 20L
                );
            }
        });
        BlueMapAPI.onDisable((api) -> {
            if (isFolia()) {
                Bukkit.getServer().getGlobalRegionScheduler().cancelTasks(this);
            } else {
                Bukkit.getScheduler().cancelTasks(this);
            }
        });
    }

    private static boolean isFolia() {
        try {
            Class.forName("io.papermc.paper.threadedregions.RegionizedServer");
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        }
    }

    private void initMarkerSets() {
        BlueMapAPI.getInstance().ifPresent((api) -> {
            townMarkerSets.clear();
            for (World world : Bukkit.getWorlds()) {
                if (world == null) continue;
                api.getWorld(world).ifPresent(bmWorld -> {
                    MarkerSet set = new MarkerSet("Towns");
                    townMarkerSets.put(world.getUID(), set);
                    // Attach to every map type of this world
                    for (var map : bmWorld.getMaps()) {
                        try {
                            Map<String, MarkerSet> sets = map.getMarkerSets();
                            if (sets != null) {
                                sets.put("towny", set);
                            }
                        } catch (Throwable t) {
                            getLogger().warning("Failed to attach MarkerSet to map " + safeMapName(map) + ": " + t.getMessage());
                        }
                    }
                });
            }
        });
    }

    private String safeMapName(de.bluecolored.bluemap.api.BlueMapMap map) {
        try { return map.getId(); } catch (Throwable ignored) {}
        try { return map.getName(); } catch (Throwable ignored) {}
        return "<unknown>";
    }

    private void safeUpdateMarkers() {
        try {
            updateMarkers();
        } catch (Throwable t) {
            getLogger().warning("updateMarkers threw: " + t.toString());
        }
    }

    private Color getFillColor(Town town) {
        String opacity = String.format("%02X", (int) Math.max(0, Math.min(255, this.config.getDouble("style.fill-opacity", 0.4D) * 255)));
        try {
            if (this.config.getBoolean("dynamic-town-colors", false)) {
                String hex = town == null ? null : town.getMapColorHexCode();
                if (hex != null && !hex.isEmpty()) return new Color("#" + hex + opacity);
            }
            if (this.config.getBoolean("dynamic-nation-colors", false)) {
                String hex = town == null ? null : town.getNationMapColorHexCode();
                if (hex != null && !hex.isEmpty()) return new Color("#" + hex + opacity);
            }
            String base = nonEmpty(this.config.getString("style.fill-color"), "#00FF00"); // default green-ish
            return new Color(base + opacity);
        } catch (Throwable t) {
            return new Color("#00FF00" + opacity);
        }
    }

    private Color getLineColor(Town town) {
        String opacity = String.format("%02X", (int) Math.max(0, Math.min(255, this.config.getDouble("style.border-opacity", 0.8D) * 255)));
        try {
            if (this.config.getBoolean("dynamic-nation-colors", false)) {
                String hex = town == null ? null : town.getNationMapColorHexCode();
                if (hex != null && !hex.isEmpty()) return new Color("#" + hex + opacity);
            }
            if (this.config.getBoolean("dynamic-town-colors", false)) {
                String hex = town == null ? null : town.getMapColorHexCode();
                if (hex != null && !hex.isEmpty()) return new Color("#" + hex + opacity);
            }
            String base = nonEmpty(this.config.getString("style.border-color"), "#000000");
            return new Color(base + opacity);
        } catch (Throwable t) {
            return new Color("#000000" + opacity);
        }
    }

    private String fillPlaceholders(String template, Town town) {
        String t = template == null ? "" : template;
        if (town == null) return t;

        try {
            t = t.replace("%name%", safe(town.getName()).replace("_", " "));
            t = t.replace("%mayor%", town.hasMayor() && town.getMayor() != null ? safe(town.getMayor().getName()) : "");

            List<Resident> resList = safeList(town.getResidents());
            String[] residents = resList.stream().map(TownyObject::getName).filter(Objects::nonNull).toArray(String[]::new);
            residents = capWithMore(residents, 36);
            t = t.replace("%residents%", String.join(", ", residents));

            String[] residentsDisplay = resList.stream().map(r -> {
                try {
                    Player p = Bukkit.getPlayer(r.getName());
                    return p == null ? r.getFormattedName() : p.getDisplayName();
                } catch (Throwable ignored) {
                    return r.getFormattedName();
                }
            }).filter(Objects::nonNull).toArray(String[]::new);
            residentsDisplay = capWithMore(residentsDisplay, 36);
            t = t.replace("%residentdisplaynames%", String.join(", ", residentsDisplay));

            List<Resident> assistants = safeList(town.getRank("assistant"));
            t = t.replace("%assistants%", assistants.isEmpty()
                    ? ""
                    : assistants.stream().map(TownyObject::getName).filter(Objects::nonNull).collect(Collectors.joining(", ")));

            t = t.replace("%residentcount%", String.valueOf(resList.size()));

            long reg = town.getRegistered();
            t = t.replace("%founded%", reg != 0 ? TownyFormatter.registeredFormat.format(reg) : "None");

            t = t.replace("%board%", safe(town.getBoard()));

            List<Resident> trusted = safeList(town.getTrustedResidents());
            t = t.replace("%trusted%", trusted.isEmpty()
                    ? "None"
                    : trusted.stream().map(TownyObject::getName).filter(Objects::nonNull).collect(Collectors.joining(", ")));

            if (TownySettings.isUsingEconomy() && TownyEconomyHandler.isActive()) {
                try {
                    if (town.isTaxPercentage()) t = t.replace("%tax%", String.valueOf(town.getTaxes()) + "%");
                    else t = t.replace("%tax%", TownyEconomyHandler.getFormattedBalance(town.getTaxes()));
                } catch (Throwable ignored) { t = t.replace("%tax%", ""); }
                try {
                    t = t.replace("%bank%", TownyEconomyHandler.getFormattedBalance(town.getAccount().getCachedBalance()));
                } catch (Throwable ignored) { t = t.replace("%bank%", ""); }
            } else {
                t = t.replace("%tax%", "");
                t = t.replace("%bank%", "");
            }

            String nation = town.hasNation() && town.getNationOrNull() != null
                    ? safe(town.getNationOrNull().getName()).replace("_", " ")
                    : "";
            t = t.replace("%nation%", nation);
            t = t.replace("%nationflag%", town.hasNation() && town.getNationOrNull() != null ? safe(town.getNationOrNull().getName()) : "");
            t = t.replace("%nationstatus%", town.hasNation()
                    ? (town.isCapital() ? "Capital of " + nation : "Member of " + nation)
                    : "");

            if (town.isForSale()) t = t.replace("%forsaleprice%", String.valueOf(town.getForSalePrice()));
            else t = t.replace("%forsaleprice%", "");

            t = t.replace("%public%", String.valueOf(town.isPublic()));
            t = t.replace("%peaceful%", String.valueOf(town.isNeutral()));
            t = t.replace("%war%", String.valueOf(town.hasActiveWar()));

            List<String> flags = new ArrayList<>();
            flags.add("Has Upkeep: " + town.hasUpkeep());
            flags.add("PvP: " + town.isPVP());
            flags.add("Mobs: " + town.hasMobs());
            flags.add("Explosion: " + town.isExplosion());
            flags.add("Fire: " + town.isFire());
            flags.add("Nation: " + nation);
            if (TownySettings.getBoolean(ConfigNodes.TOWN_RUINING_TOWN_RUINS_ENABLED)) {
                String ruinedString = "Ruined: " + town.isRuined();
                try {
                    if (town.isRuined()) ruinedString += " (Time left: " +
                            Math.max(0, TownySettings.getTownRuinsMaxDurationHours() - TownRuinUtil.getTimeSinceRuining(town)) + " hours)";
                } catch (Throwable ignored) {}
                flags.add(ruinedString);
            }
            t = t.replace("%flags%", String.join("<br />", flags));

            t = t.replace("%town_culture%", town.hasMeta("townycultures_culture") && town.getMetadata("townycultures_culture") != null
                    ? String.valueOf(town.getMetadata("townycultures_culture").getValue())
                    : "");

            t = t.replace("%town_resources%", town.hasMeta("townyresources_dailyproduction") && town.getMetadata("townyresources_dailyproduction") != null
                    ? String.valueOf(town.getMetadata("townyresources_dailyproduction").getValue())
                    : "");

            if (Bukkit.getPluginManager().isPluginEnabled("SiegeWar")) {
                Optional<Siege> optSiege = SiegeWarAPI.hasSiege(town) ? SiegeWarAPI.getSiege(town) : Optional.empty();
                if (optSiege.isPresent()) {
                    Siege siege = optSiege.get();
                    t = t.replace("%attacker%", safe(siege.getAttackerNameForDisplay()));
                    t = t.replace("%defender%", safe(siege.getDefenderNameForDisplay()));
                    t = t.replace("%siege_type%", safe(siege.getSiegeType().getName()));
                    t = t.replace("%sessions_completed%", String.valueOf(siege.getNumBattleSessionsCompleted()));
                    t = t.replace("%sessions_total%", String.valueOf(SiegeWarSettings.getSiegeDurationBattleSessions()));
                    if (TownyEconomyHandler.isActive()) {
                        try {
                            t = t.replace("%war_chest%", TownyEconomyHandler.getFormattedBalance(siege.getWarChestAmount()));
                        } catch (Throwable ignored) { t = t.replace("%war_chest%", ""); }
                    } else {
                        t = t.replace("%war_chest%", "");
                    }
                    SiegeSide side = siege.getBannerControllingSide();
                    String sideStr = side == null ? "Nobody" : WordUtils.capitalizeFully(side.name());
                    int count = 0;
                    try { count = siege.getBannerControllingResidents().size(); } catch (Throwable ignored) {}
                    t = t.replace("%banner_control%", side == null || side == SiegeSide.NOBODY ? sideStr : sideStr + " (" + count + ")");
                    t = t.replace("%siege_status%", safe(siege.getStatus().getName()));
                    t = t.replace("%siege_balance%", String.valueOf(siege.getSiegeBalance()));
                    t = t.replace("%battle_points_attacker%", safe(siege.getFormattedAttackerBattlePoints()));
                    t = t.replace("%battle_points_defender%", safe(siege.getFormattedDefenderBattlePoints()));
                    t = t.replace("%battle_time_left%", safe(siege.getFormattedBattleTimeRemaining()));
                }
            }

        } catch (Throwable tErr) {
            getLogger().warning("fillPlaceholders error for town " + safe(town.getName()) + ": " + tErr);
        }
        return t;
    }

    private void updateMarkers() {
        BlueMapAPI.getInstance().ifPresent((api) -> {
            for (World world : Bukkit.getWorlds()) {
                if (world == null) continue;
                // Ensure world is known to BlueMap
                if (api.getWorld(world.getName()).isEmpty() && api.getWorld(world).isEmpty()) continue;

                MarkerSet set = townMarkerSets.get(world.getUID());
                if (set == null) continue;

                Map<String, Marker> markers = set.getMarkers();
                if (markers == null) continue;
                markers.clear();

                TownyWorld townyworld = TownyAPI.getInstance().getTownyWorld(world);
                if (townyworld == null) continue;

                for (Town town : safeList(TownyAPI.getInstance().getTowns())) {
                    if (town == null) continue;
                    try {
                        Vector2i[] chunks = town.getTownBlocks().stream()
                                .filter(Objects::nonNull)
                                .filter(tb -> tb.getWorld() != null && tb.getWorld().equals(townyworld))
                                .map(tb -> new Vector2i(tb.getX(), tb.getZ()))
                                .toArray(Vector2i[]::new);

                        int minBlocks = this.config.getInt("min-town-blocks", -1);
                        if (minBlocks != -1 && chunks.length < minBlocks) continue;

                        int minResidents = this.config.getInt("min-residents", -1);
                        if (minResidents != -1 && town.getNumResidents() < minResidents) continue;

                        int townSize = Math.max(1, TownySettings.getTownBlockSize());
                        Vector2d cellSize = new Vector2d(townSize, townSize);
                        Collection<Cheese> cheeses;
                        try {
                            cheeses = Cheese.createPlatterFromCells(cellSize, chunks);
                        } catch (Throwable t) {
                            getLogger().warning("Cheese.createPlatterFromCells failed for " + safe(town.getName()) + ": " + t.getMessage());
                            continue;
                        }

                        double layerY = this.config.getBoolean("style.use-home-y", false)
                                ? Optional.ofNullable(town.getSpawnOrNull()).map(Location::getY).orElse(this.config.getDouble("style.y-level", 64.0))
                                : this.config.getDouble("style.y-level", 64.0);

                        String townName = safe(town.getName());
                        String townDetails = fillPlaceholders(this.config.getString("popup"), town);
                        String siegeDetails = fillPlaceholders(this.config.getString("popup-siege"), town);

                        int seq = 0;
                        for (Cheese cheese : safeList(cheeses)) {
                            if (cheese == null || cheese.getShape() == null) continue;
                            ShapeMarker.Builder builder = new ShapeMarker.Builder()
                                    .label(townName)
                                    .detail(townDetails)
                                    .lineColor(getLineColor(town))
                                    .lineWidth(Math.max(0, this.config.getInt("style.border-width", 2)))
                                    .fillColor(getFillColor(town))
                                    .depthTestEnabled(false)
                                    .shape(cheese.getShape(), (float) layerY);
                            try {
                                if (!this.config.getBoolean("lie-about-holes", false)) {
                                    List<Shape> holes = safeList(cheese.getHoles());
                                    if (!holes.isEmpty()) builder.holes(holes.toArray(Shape[]::new));
                                }
                            } catch (Throwable ignored) {}
                            ShapeMarker chunkMarker = builder.centerPosition().build();
                            markers.put("towny." + townName + ".area." + seq, chunkMarker);
                            seq++;
                        }

                        Optional<Location> spawn = Optional.ofNullable(town.getSpawnOrNull());
                        if (this.config.getBoolean("style.outpost-icon-enabled", false)) {
                            int i = 0;
                            for (Location outpost : safeList(town.getAllOutpostSpawns())) {
                                if (outpost == null || outpost.getWorld() == null || !outpost.getWorld().equals(world)) continue;
                                i++;
                                String icon = nonEmpty(this.config.getString("style.outpost-icon"), null);
                                if (icon == null) continue; // do not call .icon with null
                                POIMarker iconMarker = new POIMarker.Builder()
                                        .label(townName + " Outpost " + i)
                                        .detail(townDetails)
                                        .icon(icon,
                                                this.config.getInt("style.outpost-icon-anchor-x", 8),
                                                this.config.getInt("style.outpost-icon-anchor-y", 8))
                                        .styleClasses("towny-icon")
                                        .position(outpost.getX(), outpost.getY(), outpost.getZ())
                                        .build();
                                markers.put("towny." + townName + ".outpost." + i + ".icon", iconMarker);
                            }
                        }

                        if (spawn.isPresent() && spawn.get().getWorld() != null && spawn.get().getWorld().equals(world)) {
                            double sx = spawn.get().getX();
                            double sy = spawn.get().getY();
                            double sz = spawn.get().getZ();

                            // ruined
                            if (this.config.getBoolean("style.ruined-icon-enabled", false) && town.isRuined()) {
                                putIconIfPresent(markers, "style.ruined-icon",
                                        "towny." + townName + ".icon",
                                        builder -> builder
                                                .label(townName)
                                                .detail(townDetails)
                                                .position(sx, layerY, sz),
                                        this.config.getInt("style.ruined-icon-anchor-x", 8),
                                        this.config.getInt("style.ruined-icon-anchor-y", 8));
                            }
                            // war (only if not ruined)
                            else if (this.config.getBoolean("style.war-icon-enabled", false) && town.hasActiveWar()) {
                                putIconIfPresent(markers, "style.war-icon",
                                        "towny." + townName + ".icon",
                                        builder -> builder
                                                .label(townName)
                                                .detail(townDetails)
                                                .position(sx, sy, sz),
                                        this.config.getInt("style.war-icon-anchor-x", 8),
                                        this.config.getInt("style.war-icon-anchor-y", 8));
                            }
                            // capital
                            else if (this.config.getBoolean("style.capital-icon-enabled", false) && town.isCapital()) {
                                putIconIfPresent(markers, "style.capital-icon",
                                        "towny." + townName + ".icon",
                                        builder -> builder
                                                .label(townName)
                                                .detail(townDetails)
                                                .position(sx, sy, sz),
                                        this.config.getInt("style.capital-icon-anchor-x", 8),
                                        this.config.getInt("style.capital-icon-anchor-y", 8));
                            }
                            // home
                            else if (this.config.getBoolean("style.home-icon-enabled", false)) {
                                putIconIfPresent(markers, "style.home-icon",
                                        "towny." + townName + ".icon",
                                        builder -> builder
                                                .label(townName)
                                                .detail(townDetails)
                                                .position(sx, sy, sz),
                                        this.config.getInt("style.home-icon-anchor-x", 8),
                                        this.config.getInt("style.home-icon-anchor-y", 8));
                            }
                            // for sale
                            else if (this.config.getBoolean("style.for-sale-icon-enabled", false) && town.isForSale()) {
                                putIconIfPresent(markers, "style.sale-icon",
                                        "towny." + townName + ".icon",
                                        builder -> builder
                                                .label(townName)
                                                .detail(townDetails)
                                                .position(sx, sy, sz),
                                        this.config.getInt("style.sale-icon-anchor-x", 8),
                                        this.config.getInt("style.sale-icon-anchor-y", 8));
                            }
                        }

                        // Siege flag icon
                        if (Bukkit.getPluginManager().isPluginEnabled("SiegeWar")
                                && this.config.getBoolean("style.war-icon-enabled", false)
                                && SiegeWarAPI.hasActiveSiege(town)) {
                            Optional<Siege> siegeOpt = SiegeWarAPI.getSiege(town);
                            if (siegeOpt.isPresent()) {
                                Location flagLoc = siegeOpt.get().getFlagLocation();
                                if (flagLoc != null && flagLoc.getWorld() != null && flagLoc.getWorld().equals(world)) {
                                    putIconIfPresent(markers, "style.war-icon",
                                            "towny." + townName + ".siege",
                                            builder -> builder
                                                    .label(townName)
                                                    .detail(siegeDetails)
                                                    .position(flagLoc.getX(), flagLoc.getY(), flagLoc.getZ()),
                                            this.config.getInt("style.war-icon-anchor-x", 8),
                                            this.config.getInt("style.war-icon-anchor-y", 8));
                                }
                            }
                        }

                    } catch (Throwable t) {
                        getLogger().warning("Failed to render town '" + safe(town.getName()) + "': " + t.toString());
                    }
                }
            }
        });
    }

    /* ---------- helpers ---------- */

    private interface PoiBuilderApplier {
        POIMarker.Builder apply(POIMarker.Builder b);
    }

    private void putIconIfPresent(Map<String, Marker> markers,
                                  String configPath,
                                  String key,
                                  PoiBuilderApplier applier,
                                  int ax, int ay) {
        String iconPath = nonEmpty(this.config.getString(configPath), null);
        if (iconPath == null) {
            // getLogger().fine("Config path missing or empty: " + configPath);
            return;
        }
        try {
            POIMarker.Builder builder = new POIMarker.Builder()
                    .icon(iconPath, ax, ay)
                    .styleClasses("towny-icon");
            POIMarker marker = applier.apply(builder).build();
            markers.put(key, marker);
        } catch (Throwable t) {
            getLogger().warning("putIconIfPresent failed for key " + key + " with icon " + iconPath + ": " + t.getMessage());
        }
    }

    private static String[] capWithMore(String[] arr, int max) {
        if (arr == null) return new String[0];
        if (arr.length <= max) return arr;
        String[] out = Arrays.copyOf(arr, max);
        out[max - 1] = "and more...";
        return out;
    }

    private static <T> List<T> safeList(Collection<T> in) {
        if (in == null) return Collections.emptyList();
        List<T> list = new ArrayList<>(in.size());
        for (T t : in) if (t != null) list.add(t);
        return list;
    }

    private static String safe(String s) {
        return s == null ? "" : s;
    }

    private static String nonEmpty(String s, String fallback) {
        return (s == null || s.isEmpty()) ? fallback : s;
    }
}
