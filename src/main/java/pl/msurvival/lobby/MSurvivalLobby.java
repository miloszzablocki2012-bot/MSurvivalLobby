package pl.msurvival.lobby;

import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.block.*;
import org.bukkit.event.entity.*;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.*;
import org.bukkit.event.weather.WeatherChangeEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.*;
import java.util.*;

@SuppressWarnings("deprecation")
public final class MSurvivalLobby extends JavaPlugin implements Listener {
    private NamespacedKey menuItemKey;
    private NamespacedKey guiActionKey;
    private File dataFile;
    private FileConfiguration data;
    private final Random random = new Random();

    @Override
    public void onEnable() {
        saveDefaultConfig();
        loadData();
        menuItemKey = new NamespacedKey(this, "msurvival_lobby_menu");
        guiActionKey = new NamespacedKey(this, "msurvival_lobby_gui_action");
        Bukkit.getPluginManager().registerEvents(this, this);
        registerCommands();
    }

    private void registerCommands() {
        getCommand("menu").setExecutor((sender, command, label, args) -> {
            if (sender instanceof Player player) openMenu(player);
            return true;
        });

        getCommand("menuitem").setExecutor((sender, command, label, args) -> {
            if (sender instanceof Player player) {
                player.getInventory().addItem(createMenuItem());
                player.sendMessage(msg("menuitem-given"));
            }
            return true;
        });

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

        getCommand("lobbyreload").setExecutor((sender, command, label, args) -> {
            if (!sender.hasPermission("msurvivallobby.admin")) { sender.sendMessage(msg("no-permission")); return true; }
            reloadConfig();
            loadData();
            sender.sendMessage(msg("reload"));
            return true;
        });
    }

    private void openMenu(Player player) {
        Inventory inv = Bukkit.createInventory(null, getConfig().getInt("gui.size", 27), color(getConfig().getString("gui.title", "&6&lMSURVIVAL MENU")));
        fill(inv);

        addGuiItem(inv, "gui.lobby", "lobby");
        addGuiItem(inv, "gui.survival", "survival");
        addGuiItem(inv, "gui.keys", "keys");

        player.openInventory(inv);
    }

    private void fill(Inventory inv) {
        Material filler = parseMaterial(getConfig().getString("gui.filler", "BLACK_STAINED_GLASS_PANE"));
        ItemStack item = new ItemStack(filler);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(" ");
            item.setItemMeta(meta);
        }
        for (int i = 0; i < inv.getSize(); i++) inv.setItem(i, item);
    }

    private void addGuiItem(Inventory inv, String path, String action) {
        int slot = getConfig().getInt(path + ".slot", 13);
        ItemStack item = new ItemStack(parseMaterial(getConfig().getString(path + ".material", "STONE")));
        ItemMeta meta = item.getItemMeta();

        if (meta != null) {
            meta.setDisplayName(color(getConfig().getString(path + ".name", "&fItem")));
            List<String> lore = new ArrayList<>();
            for (String line : getConfig().getStringList(path + ".lore")) lore.add(color(line));
            meta.setLore(lore);
            meta.getPersistentDataContainer().set(guiActionKey, PersistentDataType.STRING, action);
            item.setItemMeta(meta);
        }

        inv.setItem(slot, item);
    }

    @EventHandler
    public void onGuiClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) return;
        if (!event.getView().getTitle().equals(color(getConfig().getString("gui.title", "&6&lMSURVIVAL MENU")))) return;

        event.setCancelled(true);

        ItemStack clicked = event.getCurrentItem();
        if (clicked == null || !clicked.hasItemMeta()) return;

        ItemMeta meta = clicked.getItemMeta();
        if (meta == null) return;

        String action = meta.getPersistentDataContainer().get(guiActionKey, PersistentDataType.STRING);
        if (action == null) return;

        player.closeInventory();

        if (action.equals("lobby")) {
            teleportToLobby(player, true);
            return;
        }

        if (action.equals("survival")) {
            teleportToSurvival(player, true);
            return;
        }

        if (action.equals("keys")) {
            if (Bukkit.getPluginManager().getPlugin("MSurvivalKeys") != null) {
                Bukkit.dispatchCommand(player, "keysmenu");
            } else {
                player.sendMessage(color("&cMenu kluczy nie jest teraz dostępne."));
            }
        }
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
            if (destination != null) messageKey = "survival-random";
        }

        if (destination == null && getConfig().getBoolean("survival-teleport.random-spawn-for-new-players", true)) {
            destination = generateRandomSurvivalSpawn();
            if (destination != null) {
                saveRandomSpawn(player, destination);
                messageKey = "survival-random";
            }
        }

        if (destination == null) destination = getLocation("survival");
        teleport(player, destination, messageKey, message);
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        Player p = e.getPlayer();

        if (getConfig().getBoolean("settings.teleport-on-join", true)) {
            Bukkit.getScheduler().runTaskLater(this, () -> {
                if (p.isOnline()) teleportToLobby(p, false);
            }, getConfig().getLong("settings.teleport-delay-ticks", 20L));
        }

        if (getConfig().getBoolean("settings.give-menu-item-on-join", true)) {
            Bukkit.getScheduler().runTaskLater(this, () -> giveMenuItem(p), 40L);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onMenuClick(PlayerInteractEvent e) {
        Action a = e.getAction();
        if (a != Action.RIGHT_CLICK_AIR && a != Action.RIGHT_CLICK_BLOCK) return;
        if (!isMenuItem(e.getItem())) return;

        e.setCancelled(true);
        openMenu(e.getPlayer());
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

    private Location getSavedRandomSpawn(Player player) {
        String path = "players." + player.getUniqueId();
        if (!data.contains(path + ".world")) return null;
        World world = Bukkit.getWorld(data.getString(path + ".world", ""));
        if (world == null) return null;
        return new Location(world, data.getDouble(path + ".x"), data.getDouble(path + ".y"), data.getDouble(path + ".z"), (float) data.getDouble(path + ".yaw", 0), (float) data.getDouble(path + ".pitch", 0));
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

    @EventHandler public void onBreak(BlockBreakEvent e){ if(blocked(e.getPlayer(),"block-break")) e.setCancelled(true); }
    @EventHandler public void onPlace(BlockPlaceEvent e){ if(blocked(e.getPlayer(),"block-place")) e.setCancelled(true); }
    @EventHandler public void onDrop(PlayerDropItemEvent e){ if(blocked(e.getPlayer(),"item-drop")) e.setCancelled(true); }
    @EventHandler public void onPickup(PlayerPickupItemEvent e){ if(blocked(e.getPlayer(),"item-pickup")) e.setCancelled(true); }

    @EventHandler
    public void onInv(InventoryClickEvent e){
        if (e.getView().getTitle().equals(color(getConfig().getString("gui.title", "&6&lMSURVIVAL MENU")))) return;
        if(e.getWhoClicked() instanceof Player p && blocked(p,"inventory-click")) e.setCancelled(true);
    }

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
                data.set(path + ".level", player.getLevel());
                data.set(path + ".exp", player.getExp());
                data.set(path + ".totalExp", player.getTotalExperience());
                saveData();
            } catch (Exception e) {
                getLogger().warning("Nie udalo sie zapisac ekwipunku gracza " + player.getName());
            }
        }

        player.getInventory().clear();

        if (getConfig().getBoolean("lobby-inventory.clear-armor", true)) player.getInventory().setArmorContents(null);
        if (getConfig().getBoolean("lobby-inventory.clear-offhand", true)) player.getInventory().setItemInOffHand(null);

        player.updateInventory();
    }

    private void restoreLobbyInventory(Player player) {
        if (!getConfig().getBoolean("lobby-inventory.enabled", true)) return;
        if (!getConfig().getBoolean("lobby-inventory.restore-on-survival", true)) return;

        String path = "lobbyInventories." + player.getUniqueId();
        if (!data.contains(path + ".contents")) return;

        try {
            PlayerInventory inv = player.getInventory();
            inv.clear();
            inv.setContents(deserialize(data.getString(path + ".contents", "")));
            inv.setArmorContents(deserialize(data.getString(path + ".armor", "")));

            ItemStack[] offhand = deserialize(data.getString(path + ".offhand", ""));
            if (offhand.length > 0 && offhand[0] != null) inv.setItemInOffHand(offhand[0]);
            else inv.setItemInOffHand(null);

            player.setLevel(data.getInt(path + ".level", 0));
            player.setExp((float) data.getDouble(path + ".exp", 0.0));
            player.setTotalExperience(data.getInt(path + ".totalExp", 0));

            data.set(path, null);
            saveData();
            player.updateInventory();
            player.sendMessage(msg("inventory-restored"));
        } catch (Exception e) {
            getLogger().warning("Nie udalo sie przywrocic ekwipunku gracza " + player.getName());
        }
    }

    private String serialize(ItemStack[] items) throws Exception {
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        ObjectOutputStream dataOutput = new ObjectOutputStream(outputStream);
        dataOutput.writeInt(items.length);
        for (ItemStack item : items) dataOutput.writeObject(item);
        dataOutput.close();
        return Base64.getEncoder().encodeToString(outputStream.toByteArray());
    }

    private ItemStack[] deserialize(String dataString) throws Exception {
        if (dataString == null || dataString.isBlank()) return new ItemStack[0];
        ByteArrayInputStream inputStream = new ByteArrayInputStream(Base64.getDecoder().decode(dataString));
        ObjectInputStream dataInput = new ObjectInputStream(inputStream);
        int length = dataInput.readInt();
        ItemStack[] items = new ItemStack[length];
        for (int i = 0; i < length; i++) items[i] = (ItemStack) dataInput.readObject();
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
        if (meta == null) return false;
        if (meta.getPersistentDataContainer().has(menuItemKey, PersistentDataType.STRING)) return true;
        return meta.hasDisplayName() && meta.getDisplayName().equals(color(getConfig().getString("menu-item.name", "&6&lMSurvival Menu")));
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
