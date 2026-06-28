package pl.msurvival.lobby;

import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.block.*;
import org.bukkit.event.entity.*;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.*;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.weather.WeatherChangeEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionEffect;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.IOException;
import java.util.*;
import java.util.Base64;

@SuppressWarnings("deprecation")
public final class MSurvivalLobby extends JavaPlugin implements Listener {
    private NamespacedKey menuItemKey;
    private File dataFile;
    private FileConfiguration data;
    private final Random random = new Random();
    private NamespacedKey botIdKey;
    private final Map<String, Long> botCooldown = new HashMap<>();

    @Override
    public void onEnable() {
        saveDefaultConfig();
        loadData();
        menuItemKey = new NamespacedKey(this, "msurvival_lobby_menu");
        botIdKey = new NamespacedKey(this, "msurvival_lobby_bot");
        Bukkit.getPluginManager().registerEvents(this, this);
        registerCommands();
        Bukkit.getScheduler().runTaskLater(this, () -> {
            if (getConfig().getBoolean("bots.enabled", true) && getConfig().getBoolean("bots.auto-spawn-on-enable", true)) {
                spawnBots();
            }
        }, 40L);
    }

    private void registerCommands() {
        getCommand("lobby").setExecutor((sender, command, label, args) -> {
            Player target = resolveTarget(sender, args);
            if (target != null) teleportToLobby(target, sender == target);
            return true;
        });

        getCommand("survival").setExecutor((sender, command, label, args) -> {
            Player target = resolveTarget(sender, args);
            if (target != null) teleportToSurvival(target, sender == target);
            return true;
        });

        getCommand("setlobby").setExecutor((sender, command, label, args) -> {
            if (!(sender instanceof Player p)) return true;
            if (!p.hasPermission("msurvivallobby.admin")) { p.sendMessage(msg("no-permission")); return true; }
            saveLocation("lobby", p.getLocation());
            p.sendMessage(msg("lobby-set").replace("%world%", p.getWorld().getName()));
            return true;
        });

        getCommand("setsurvival").setExecutor((sender, command, label, args) -> {
            if (!(sender instanceof Player p)) return true;
            if (!p.hasPermission("msurvivallobby.admin")) { p.sendMessage(msg("no-permission")); return true; }
            saveLocation("survival", p.getLocation());
            p.sendMessage(msg("survival-set").replace("%world%", p.getWorld().getName()));
            return true;
        });

        getCommand("importlobby").setExecutor((sender, command, label, args) -> {
            if (!sender.hasPermission("msurvivallobby.admin")) { sender.sendMessage(msg("no-permission")); return true; }
            if (args.length < 1) { sender.sendMessage(msg("usage-import")); return true; }
            importLobbyWorld(sender, String.join(" ", args));
            return true;
        });

        getCommand("setbot").setExecutor((sender, command, label, args) -> {
            if (!(sender instanceof Player p)) return true;
            if (!p.hasPermission("msurvivallobby.admin")) { p.sendMessage(msg("no-permission")); return true; }
            if (args.length < 1) { p.sendMessage(color("&cUżycie: &e/setbot <id>")); return true; }

            String id = args[0].toLowerCase(Locale.ROOT);
            if (!getConfig().contains("bots." + id)) {
                p.sendMessage(msg("bot-not-found"));
                return true;
            }

            Location loc = p.getLocation();
            getConfig().set("bots." + id + ".world", loc.getWorld().getName());
            getConfig().set("bots." + id + ".x", loc.getX());
            getConfig().set("bots." + id + ".y", loc.getY());
            getConfig().set("bots." + id + ".z", loc.getZ());
            getConfig().set("bots." + id + ".yaw", loc.getYaw());
            getConfig().set("bots." + id + ".pitch", loc.getPitch());
            saveConfig();
            spawnBots();

            p.sendMessage(msg("bot-set").replace("%bot%", id));
            return true;
        });

        getCommand("spawnbots").setExecutor((sender, command, label, args) -> {
            if (!sender.hasPermission("msurvivallobby.admin")) { sender.sendMessage(msg("no-permission")); return true; }
            spawnBots();
            sender.sendMessage(msg("bots-spawned"));
            return true;
        });

        getCommand("removebots").setExecutor((sender, command, label, args) -> {
            if (!sender.hasPermission("msurvivallobby.admin")) { sender.sendMessage(msg("no-permission")); return true; }
            removeBots();
            sender.sendMessage(msg("bots-removed"));
            return true;
        });

        getCommand("lobbyreload").setExecutor((sender, command, label, args) -> {
            if (!sender.hasPermission("msurvivallobby.admin")) { sender.sendMessage(msg("no-permission")); return true; }
            reloadConfig();
            loadData();
            if (getConfig().getBoolean("bots.enabled", true)) spawnBots();
            sender.sendMessage(msg("reload"));
            return true;
        });
    }

    private void teleportToLobby(Player player, boolean message) {
        saveAndHideLobbyInventory(player);
        teleport(player, getLocation("lobby"), "lobby-teleported", message);
        giveMenuItem(player);
    }

    private void teleportToSurvival(Player player, boolean message) {
        restoreLobbyInventory(player);
        Location destination = null;
        String messageKey = "survival-teleported";

        if (getConfig().getBoolean("survival-teleport.use-bed-spawn", true)) {
            Location bed = player.getBedSpawnLocation();
            if (bed != null && bed.getWorld() != null) {
                destination = bed.clone().add(0.5, 0.0, 0.5);
                messageKey = "survival-bed";
            }
        }

        if (destination == null && getConfig().getBoolean("survival-teleport.remember-random-spawn", true)) {
            destination = getSavedRandomSpawn(player);
            if (destination != null) {
                messageKey = "survival-random";
            }
        }

        if (destination == null && getConfig().getBoolean("survival-teleport.random-spawn-for-new-players", true)) {
            destination = generateRandomSurvivalSpawn();
            if (destination != null) {
                saveRandomSpawn(player, destination);
                messageKey = "survival-random";
            }
        }

        if (destination == null) {
            destination = getLocation("survival");
        }

        teleport(player, destination, messageKey, message);
    }

    private Location getSavedRandomSpawn(Player player) {
        String path = "players." + player.getUniqueId();

        if (!data.contains(path + ".world")) return null;

        World world = Bukkit.getWorld(data.getString(path + ".world", ""));

        if (world == null) return null;

        return new Location(
                world,
                data.getDouble(path + ".x"),
                data.getDouble(path + ".y"),
                data.getDouble(path + ".z"),
                (float) data.getDouble(path + ".yaw", 0.0),
                (float) data.getDouble(path + ".pitch", 0.0)
        );
    }

    private void saveRandomSpawn(Player player, Location loc) {
        String path = "players." + player.getUniqueId();
        data.set(path + ".name", player.getName());
        data.set(path + ".world", loc.getWorld().getName());
        data.set(path + ".x", loc.getX());
        data.set(path + ".y", loc.getY());
        data.set(path + ".z", loc.getZ());
        data.set(path + ".yaw", loc.getYaw());
        data.set(path + ".pitch", loc.getPitch());
        saveData();
    }

    private Location generateRandomSurvivalSpawn() {
        Location center = getLocation("survival");

        if (center == null || center.getWorld() == null) return null;

        World world = center.getWorld();
        int radius = getConfig().getInt("survival-teleport.random-radius", 800);
        int minY = getConfig().getInt("survival-teleport.min-y", 60);
        int maxTries = getConfig().getInt("survival-teleport.max-tries", 80);

        for (int i = 0; i < maxTries; i++) {
            int x = center.getBlockX() + random.nextInt(radius * 2 + 1) - radius;
            int z = center.getBlockZ() + random.nextInt(radius * 2 + 1) - radius;
            int y = world.getHighestBlockYAt(x, z);

            if (y < minY) continue;

            Block ground = world.getBlockAt(x, y - 1, z);
            Block feet = world.getBlockAt(x, y, z);
            Block head = world.getBlockAt(x, y + 1, z);

            if (!ground.getType().isSolid()) continue;
            if (!feet.getType().isAir()) continue;
            if (!head.getType().isAir()) continue;

            Material g = ground.getType();
            if (g == Material.LAVA || g == Material.WATER || g == Material.MAGMA_BLOCK || g == Material.CACTUS) continue;

            return new Location(world, x + 0.5, y, z + 0.5, center.getYaw(), center.getPitch());
        }

        return center;
    }

    private Player resolveTarget(CommandSender sender, String[] args) {
        if (args.length >= 1 && sender.hasPermission("msurvivallobby.admin")) {
            Player t = Bukkit.getPlayerExact(args[0]);
            if (t == null) sender.sendMessage(color("&cNie znaleziono gracza online."));
            return t;
        }
        if (sender instanceof Player p) return p;
        sender.sendMessage(color("&cKonsola musi podać gracza."));
        return null;
    }

    private void importLobbyWorld(CommandSender sender, String worldName) {
        File folder = new File(Bukkit.getWorldContainer(), worldName);
        if (!folder.exists() || !new File(folder, "level.dat").exists()) {
            sender.sendMessage(msg("import-failed"));
            sender.sendMessage(color("&7Folder świata musi być w głównym katalogu serwera i mieć &elevel.dat&7."));
            return;
        }
        sender.sendMessage(msg("importing").replace("%world%", worldName));
        if (Bukkit.getPluginManager().getPlugin("Multiverse-Core") != null) {
            Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "mv import \"" + worldName + "\" normal");
        } else {
            Bukkit.createWorld(new WorldCreator(worldName));
        }
        Bukkit.getScheduler().runTaskLater(this, () -> {
            World world = Bukkit.getWorld(worldName);
            if (world == null) { sender.sendMessage(msg("import-failed")); return; }
            getConfig().set("lobby.world", worldName);
            saveConfig();
            sender.sendMessage(msg("imported").replace("%world%", worldName));
        }, 60L);
    }

    @EventHandler
    public void onBotClick(PlayerInteractEntityEvent event) {
        String id = getBotId(event.getRightClicked());
        if (id == null) return;

        event.setCancelled(true);

        Player player = event.getPlayer();
        long now = System.currentTimeMillis();
        long cd = getConfig().getLong("bots.click-cooldown-ms", 1500L);
        String cooldownKey = player.getUniqueId() + ":" + id;

        if (botCooldown.containsKey(cooldownKey) && now - botCooldown.get(cooldownKey) < cd) {
            return;
        }

        botCooldown.put(cooldownKey, now);

        String command = getConfig().getString("bots." + id + ".command", "");
        if (command.isBlank()) return;

        player.sendMessage(msg("bot-click"));

        String finalCommand = color(command.replace("%player%", player.getName()));

        if (finalCommand.startsWith("tell ")) {
            Bukkit.dispatchCommand(Bukkit.getConsoleSender(), finalCommand.substring(0));
        } else {
            Bukkit.dispatchCommand(Bukkit.getConsoleSender(), finalCommand);
        }
    }

    private void spawnBots() {
        if (!getConfig().getBoolean("bots.enabled", true)) return;

        removeBots();

        ConfigurationSection section = getConfig().getConfigurationSection("bots");
        if (section == null) return;

        for (String id : section.getKeys(false)) {
            if (id.equalsIgnoreCase("enabled") || id.equalsIgnoreCase("auto-spawn-on-enable") || id.equalsIgnoreCase("type") || id.equalsIgnoreCase("click-cooldown-ms")) {
                continue;
            }

            if (!getConfig().getBoolean("bots." + id + ".enabled", true)) {
                continue;
            }

            Location loc = getBotLocation(id);
            if (loc == null) continue;

            EntityType type = parseEntityType(getConfig().getString("bots.type", "VILLAGER"));
            Entity entity = loc.getWorld().spawnEntity(loc, type);

            entity.setCustomName(color(getConfig().getString("bots." + id + ".name", "&a&lBOT")));
            entity.setCustomNameVisible(true);
            entity.getPersistentDataContainer().set(botIdKey, PersistentDataType.STRING, id);

            if (entity instanceof LivingEntity living) {
                living.setAI(false);
                living.setInvulnerable(true);
                living.setCollidable(false);
                living.setSilent(true);
                living.setRemoveWhenFarAway(false);
            }

            spawnSubtitleArmorStand(id, loc);
        }
    }

    private void spawnSubtitleArmorStand(String id, Location loc) {
        String subtitle = getConfig().getString("bots." + id + ".subtitle", "");
        if (subtitle == null || subtitle.isBlank()) return;

        Location subtitleLoc = loc.clone().add(0, 2.15, 0);
        Entity armorStand = subtitleLoc.getWorld().spawnEntity(subtitleLoc, EntityType.ARMOR_STAND);
        armorStand.setCustomName(color(subtitle));
        armorStand.setCustomNameVisible(true);
        armorStand.getPersistentDataContainer().set(botIdKey, PersistentDataType.STRING, "subtitle:" + id);

        if (armorStand instanceof LivingEntity living) {
            living.setInvulnerable(true);
            living.setSilent(true);
            living.setGravity(false);
            living.setAI(false);
        }
    }

    private void removeBots() {
        for (World world : Bukkit.getWorlds()) {
            for (Entity entity : world.getEntities()) {
                if (entity.getPersistentDataContainer().has(botIdKey, PersistentDataType.STRING)) {
                    entity.remove();
                }
            }
        }
    }

    private String getBotId(Entity entity) {
        String id = entity.getPersistentDataContainer().get(botIdKey, PersistentDataType.STRING);
        if (id == null) return null;
        if (id.startsWith("subtitle:")) return null;
        return id;
    }

    private Location getBotLocation(String id) {
        World world = Bukkit.getWorld(getConfig().getString("bots." + id + ".world", getConfig().getString("lobby.world", "Lobby")));
        if (world == null) return null;

        return new Location(
                world,
                getConfig().getDouble("bots." + id + ".x", 0.5),
                getConfig().getDouble("bots." + id + ".y", 100.0),
                getConfig().getDouble("bots." + id + ".z", 0.5),
                (float) getConfig().getDouble("bots." + id + ".yaw", 0.0),
                (float) getConfig().getDouble("bots." + id + ".pitch", 0.0)
        );
    }

    private EntityType parseEntityType(String value) {
        try {
            return EntityType.valueOf(value.toUpperCase(Locale.ROOT));
        } catch (Exception e) {
            return EntityType.VILLAGER;
        }
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        Player p = e.getPlayer();
        if (getConfig().getBoolean("settings.teleport-on-join", true)) {
            Bukkit.getScheduler().runTaskLater(this, () -> { if (p.isOnline()) teleportToLobby(p, false); }, getConfig().getLong("settings.teleport-delay-ticks", 20L));
        }
        if (getConfig().getBoolean("settings.give-menu-item-on-join", true)) {
            Bukkit.getScheduler().runTaskLater(this, () -> giveMenuItem(p), 30L);
        }
    }

    @EventHandler
    public void onMenuClick(PlayerInteractEvent e) {
        Action a = e.getAction();
        if (a != Action.RIGHT_CLICK_AIR && a != Action.RIGHT_CLICK_BLOCK) return;
        if (!isMenuItem(e.getItem())) return;
        e.setCancelled(true);
        String cmd = getConfig().getString("settings.menu-command", "menu");
        if (cmd != null && !cmd.isBlank()) Bukkit.dispatchCommand(e.getPlayer(), cmd);
        else teleportToLobby(e.getPlayer(), true);
    }

    @EventHandler public void onBreak(BlockBreakEvent e){ if(blocked(e.getPlayer(),"block-break")) e.setCancelled(true); }
    @EventHandler public void onPlace(BlockPlaceEvent e){ if(blocked(e.getPlayer(),"block-place")) e.setCancelled(true); }
    @EventHandler public void onDrop(PlayerDropItemEvent e){ if(blocked(e.getPlayer(),"item-drop")) e.setCancelled(true); }
    @EventHandler public void onPickup(PlayerPickupItemEvent e){ if(blocked(e.getPlayer(),"item-pickup")) e.setCancelled(true); }
    @EventHandler public void onInv(InventoryClickEvent e){ if(e.getWhoClicked() instanceof Player p && blocked(p,"inventory-click")) e.setCancelled(true); }

    @EventHandler
    public void onDamage(EntityDamageEvent e) {
        if (e.getEntity() instanceof Player p && inLobby(p) && !getConfig().getBoolean("protection.damage", false)) e.setCancelled(true);
    }

    @EventHandler
    public void onPvp(EntityDamageByEntityEvent e) {
        if (!getConfig().getBoolean("protection.pvp", false)) {
            if (e.getDamager() instanceof Player p && inLobby(p)) e.setCancelled(true);
            if (e.getEntity() instanceof Player p && inLobby(p)) e.setCancelled(true);
        }
    }

    @EventHandler
    public void onFood(FoodLevelChangeEvent e) {
        if (e.getEntity() instanceof Player p && inLobby(p) && !getConfig().getBoolean("protection.hunger", false)) {
            e.setCancelled(true);
            p.setFoodLevel(20);
            p.setSaturation(20f);
        }
    }

    @EventHandler
    public void onWeather(WeatherChangeEvent e) {
        if (getConfig().getBoolean("protection.weather-clear", true) && e.toWeatherState() && e.getWorld().getName().equalsIgnoreCase(getConfig().getString("lobby.world","Lobby"))) e.setCancelled(true);
    }

    private boolean blocked(Player p, String key) {
        return getConfig().getBoolean("protection.enabled", true) && inLobby(p) && getConfig().getBoolean("protection." + key, true) && !canBypass(p);
    }

    private boolean canBypass(Player p) {
        return p.hasPermission("msurvivallobby.admin") && p.getGameMode() == GameMode.CREATIVE;
    }

    private boolean inLobby(Player p) {
        return p.getWorld().getName().equalsIgnoreCase(getConfig().getString("lobby.world", "Lobby"));
    }

    private void saveLocation(String path, Location loc) {
        getConfig().set(path + ".world", loc.getWorld().getName());
        getConfig().set(path + ".x", loc.getX());
        getConfig().set(path + ".y", loc.getY());
        getConfig().set(path + ".z", loc.getZ());
        getConfig().set(path + ".yaw", loc.getYaw());
        getConfig().set(path + ".pitch", loc.getPitch());
        saveConfig();
    }

    private Location getLocation(String path) {
        World w = Bukkit.getWorld(getConfig().getString(path + ".world", path.equals("lobby") ? "Lobby" : "world"));
        if (w == null) return null;
        return new Location(w, getConfig().getDouble(path + ".x", 0.5), getConfig().getDouble(path + ".y", 100), getConfig().getDouble(path + ".z", 0.5), (float)getConfig().getDouble(path + ".yaw",0), (float)getConfig().getDouble(path + ".pitch",0));
    }

    private void teleport(Player p, Location loc, String msgKey, boolean sendMsg) {
        if (loc == null) { p.sendMessage(msg("world-not-loaded")); return; }
        p.teleport(loc);
        p.setFoodLevel(20);
        p.setSaturation(20f);
        if (sendMsg && msgKey != null) p.sendMessage(msg(msgKey));
    }

    private void saveAndHideLobbyInventory(Player player) {
        if (!getConfig().getBoolean("lobby-inventory.enabled", true)) return;
        if (!getConfig().getBoolean("lobby-inventory.save-and-hide-items", true)) return;

        String path = "lobbyInventories." + player.getUniqueId();

        if (!data.contains(path + ".contents")) {
            try {
                PlayerInventory inv = player.getInventory();

                data.set(path + ".name", player.getName());
                data.set(path + ".contents", serialize(inv.getContents()));
                data.set(path + ".armor", serialize(inv.getArmorContents()));
                data.set(path + ".offhand", serialize(new ItemStack[]{inv.getItemInOffHand()}));

                if (getConfig().getBoolean("lobby-inventory.save-xp", true)) {
                    data.set(path + ".level", player.getLevel());
                    data.set(path + ".exp", player.getExp());
                    data.set(path + ".totalExp", player.getTotalExperience());
                }

                saveData();
            } catch (Exception e) {
                getLogger().warning("Nie udalo sie zapisac ekwipunku gracza " + player.getName());
                e.printStackTrace();
            }
        }

        player.getInventory().clear();

        if (getConfig().getBoolean("lobby-inventory.clear-armor", true)) {
            player.getInventory().setArmorContents(null);
        }

        if (getConfig().getBoolean("lobby-inventory.clear-offhand", true)) {
            player.getInventory().setItemInOffHand(null);
        }

        if (getConfig().getBoolean("lobby-inventory.save-effects", false)) {
            for (PotionEffect effect : player.getActivePotionEffects()) {
                player.removePotionEffect(effect.getType());
            }
        }

        player.updateInventory();
    }

    private void restoreLobbyInventory(Player player) {
        if (!getConfig().getBoolean("lobby-inventory.enabled", true)) return;
        if (!getConfig().getBoolean("lobby-inventory.restore-on-survival", true)) return;

        String path = "lobbyInventories." + player.getUniqueId();

        if (!data.contains(path + ".contents")) {
            return;
        }

        try {
            PlayerInventory inv = player.getInventory();
            inv.clear();

            inv.setContents(deserialize(data.getString(path + ".contents", "")));
            inv.setArmorContents(deserialize(data.getString(path + ".armor", "")));

            ItemStack[] offhand = deserialize(data.getString(path + ".offhand", ""));
            if (offhand.length > 0 && offhand[0] != null) {
                inv.setItemInOffHand(offhand[0]);
            } else {
                inv.setItemInOffHand(null);
            }

            if (getConfig().getBoolean("lobby-inventory.save-xp", true)) {
                player.setLevel(data.getInt(path + ".level", 0));
                player.setExp((float) data.getDouble(path + ".exp", 0.0));
                player.setTotalExperience(data.getInt(path + ".totalExp", 0));
            }

            data.set(path, null);
            saveData();

            player.updateInventory();
            player.sendMessage(msg("inventory-restored"));
        } catch (Exception e) {
            getLogger().warning("Nie udalo sie przywrocic ekwipunku gracza " + player.getName());
            e.printStackTrace();
        }
    }

    private String serialize(ItemStack[] items) throws Exception {
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        ObjectOutputStream dataOutput = new ObjectOutputStream(outputStream);

        dataOutput.writeInt(items.length);

        for (ItemStack item : items) {
            dataOutput.writeObject(item);
        }

        dataOutput.close();
        return Base64.getEncoder().encodeToString(outputStream.toByteArray());
    }

    private ItemStack[] deserialize(String dataString) throws Exception {
        if (dataString == null || dataString.isBlank()) {
            return new ItemStack[0];
        }

        ByteArrayInputStream inputStream = new ByteArrayInputStream(Base64.getDecoder().decode(dataString));
        ObjectInputStream dataInput = new ObjectInputStream(inputStream);

        int length = dataInput.readInt();
        ItemStack[] items = new ItemStack[length];

        for (int i = 0; i < length; i++) {
            items[i] = (ItemStack) dataInput.readObject();
        }

        dataInput.close();
        return items;
    }

    private void giveMenuItem(Player p) {
        if (p == null || !p.isOnline() || hasMenuItem(p)) return;
        int slot = getConfig().getInt("menu-item.slot", 4);
        ItemStack item = createMenuItem();
        if (slot >= 0 && slot <= 35) {
            ItemStack cur = p.getInventory().getItem(slot);
            if (cur == null || cur.getType() == Material.AIR || isMenuItem(cur)) {
                p.getInventory().setItem(slot, item);
                return;
            }
        }
        p.getInventory().addItem(item);
    }

    private ItemStack createMenuItem() {
        ItemStack item = new ItemStack(parseMaterial(getConfig().getString("menu-item.material", "COMPASS")));
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(color(getConfig().getString("menu-item.name", "&6&lMSurvival Menu")));
            List<String> lore = new ArrayList<>();
            for (String line : getConfig().getStringList("menu-item.lore")) lore.add(color(line));
            meta.setLore(lore);
            meta.getPersistentDataContainer().set(menuItemKey, PersistentDataType.STRING, "true");
            item.setItemMeta(meta);
        }
        return item;
    }

    private boolean hasMenuItem(Player p) {
        for (ItemStack item : p.getInventory().getContents()) if (isMenuItem(item)) return true;
        return false;
    }

    private boolean isMenuItem(ItemStack item) {
        if (item == null || item.getType() == Material.AIR || !item.hasItemMeta()) return false;
        ItemMeta meta = item.getItemMeta();
        return meta != null && meta.getPersistentDataContainer().has(menuItemKey, PersistentDataType.STRING);
    }

    private Material parseMaterial(String v) {
        try { return Material.valueOf(v.toUpperCase(Locale.ROOT)); } catch (Exception e) { return Material.COMPASS; }
    }

    private void loadData() {
        dataFile = new File(getDataFolder(), "data.yml");
        if (!dataFile.exists()) {
            try {
                getDataFolder().mkdirs();
                dataFile.createNewFile();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
        data = YamlConfiguration.loadConfiguration(dataFile);
    }

    private void saveData() {
        try {
            data.save(dataFile);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private String msg(String key) {
        return color(getConfig().getString("messages.prefix", "") + getConfig().getString("messages." + key, ""));
    }

    private String color(String t) {
        return t == null ? "" : ChatColor.translateAlternateColorCodes('&', t);
    }
}
