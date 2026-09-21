package plugin.roleplay.police;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.Collection;
import java.util.Collections;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.entity.Snowball;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.ProjectileHitEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;
import com.comphenix.protocol.PacketType;
import com.comphenix.protocol.ProtocolLibrary;
import com.comphenix.protocol.ProtocolManager;
import com.comphenix.protocol.events.PacketContainer;
import com.comphenix.protocol.wrappers.EnumWrappers;
import com.comphenix.protocol.wrappers.WrappedChatComponent;

public class App extends JavaPlugin implements Listener {
    private static final long CUFF_DELAY_TICKS = 5 * 20L;
    private static final double MAX_CUFF_DISTANCE_SQUARED = 25.0D;
    private static final int MAX_WANTED_POINTS = 60;
    private static final Map<String, WantedOffense> WANTED_OFFENSES = createWantedOffenses();

    private final Map<UUID, UUID> cuffedPlayers = new HashMap<>();
    private final Map<UUID, CuffAttempt> cuffAttempts = new HashMap<>();
    private final Map<UUID, Boolean> onDuty = new HashMap<>();
    private final Set<UUID> followTeleports = new HashSet<>();
    private BukkitTask followTask;
    private ProtocolManager protocolManager;
    private NamespacedKey tazerItemKey;
    private NamespacedKey tazerProjectileKey;
    private NamespacedKey tazerCooldownKey;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        tazerItemKey = new NamespacedKey(this, "tazer");
        tazerProjectileKey = new NamespacedKey(this, "tazer_projectile");
        tazerCooldownKey = new NamespacedKey(this, "tazer_cooldown");
        Bukkit.getPluginManager().registerEvents(this, this);
        if (Bukkit.getPluginManager().isPluginEnabled("ProtocolLib")) {
            protocolManager = ProtocolLibrary.getProtocolManager();
        } else {
            getLogger().warning("ProtocolLib fehlt. Viewer-spezifische Wanted-Nametags sind deaktiviert.");
        }
        for (Player player : Bukkit.getOnlinePlayers()) {
            restoreCuffs(player);
            updateWantedNametag(player);
        }
        Bukkit.getScheduler().runTaskTimer(this, this::refreshAllWantedNametags, 20L, 20L);
        followTask = Bukkit.getScheduler().runTaskTimer(this, this::followOfficers, 1L, 2L);
        getLogger().info("Police cuff system enabled.");
    }

    @Override
    public void onDisable() {
        for (CuffAttempt attempt : cuffAttempts.values()) {
            attempt.task.cancel();
        }
        cuffAttempts.clear();
        cuffedPlayers.clear();
        onDuty.clear();
        followTeleports.clear();
        if (followTask != null) {
            followTask.cancel();
        }
        saveConfig();
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (command.getName().equalsIgnoreCase("duty")) {
            return toggleDuty(sender);
        }
        if (command.getName().equalsIgnoreCase("rankup")) {
            return changeRank(sender, args, 1);
        }
        if (command.getName().equalsIgnoreCase("rankdown")) {
            return changeRank(sender, args, -1);
        }
        if (command.getName().equalsIgnoreCase("setrank")) {
            return setRank(sender, args);
        }
        if (command.getName().equalsIgnoreCase("setleader")) {
            return setLeader(sender, args);
        }
        if (command.getName().equalsIgnoreCase("invite")) {
            return invite(sender, args);
        }
        if (command.getName().equalsIgnoreCase("annehmen")) {
            return acceptInvite(sender);
        }
        if (command.getName().equalsIgnoreCase("ablehnen")) {
            return declineInvite(sender);
        }
        if (command.getName().equalsIgnoreCase("uninvite")) {
            return uninvite(sender, args);
        }
        if (command.getName().equalsIgnoreCase("uncuff")) {
            return uncuffCommand(sender, args);
        }
        if (command.getName().equalsIgnoreCase("wps")) {
            return setWanted(sender, args);
        }
        if (command.getName().equalsIgnoreCase("delwps")) {
            return clearWanted(sender, args);
        }
        if (command.getName().equalsIgnoreCase("f")
                || command.getName().equalsIgnoreCase("policechat")) {
            return policeChat(sender, args);
        }
        if (command.getName().equalsIgnoreCase("search")) {
            return searchDrugs(sender, args);
        }
        if (command.getName().equalsIgnoreCase("takedrugs")) {
            return takeDrugs(sender, args);
        }
        if (!command.getName().equalsIgnoreCase("cuff")) {
            return false;
        }
        if (!(sender instanceof Player officer)) {
            sender.sendMessage(ChatColor.RED + "Dieser Befehl kann nur von einem Spieler verwendet werden.");
            return true;
        }
        if (getHighestPoliceRank(officer) < 0) {
            officer.sendMessage(ChatColor.RED + "Nur Polizei-Mitglieder dürfen Spieler verhaften.");
            return true;
        }
        if (!onDuty.getOrDefault(officer.getUniqueId(), false)) {
            officer.sendMessage(ChatColor.RED + "Du musst im Dienst sein, um Spieler zu verhaften.");
            return true;
        }
        if (!isCuffs(officer.getInventory().getItemInMainHand())) {
            officer.sendMessage(ChatColor.YELLOW + "Du musst Cuffs in der Haupthand halten.");
            return true;
        }
        if (args.length != 1) {
            officer.sendMessage(ChatColor.YELLOW + "Verwendung: /cuff <Spieler>");
            return true;
        }

        Player target = Bukkit.getPlayerExact(args[0]);
        if (target == null) {
            officer.sendMessage(ChatColor.RED + "Dieser Spieler ist nicht online.");
            return true;
        }
        if (target.equals(officer)) {
            officer.sendMessage(ChatColor.RED + "Du kannst dich nicht selbst verhaften.");
            return true;
        }
        if (isAdminDuty(target)) {
            officer.sendMessage(ChatColor.YELLOW + target.getName()
                    + " kann im Admin-Dienst nicht verhaftet werden.");
            return true;
        }
        if (getWantedPoints(target) <= 0) {
            officer.sendMessage(ChatColor.YELLOW + target.getName()
                + " hat keine Wanted-Punkte und kann nicht verhaftet werden.");
            return true;
        }

        if (isCuffed(target)) {
            officer.sendMessage(ChatColor.YELLOW + target.getName()
                    + " ist bereits verhaftet. Nutze /uncuff zum Freilassen.");
        } else {
            cuff(target, officer);
            officer.sendMessage(ChatColor.GREEN + target.getName() + " wurde verhaftet.");
        }
        return true;
    }

    @EventHandler
    public void onPlayerInteractEntity(PlayerInteractEntityEvent event) {
        if (event.getHand() != EquipmentSlot.HAND || !(event.getRightClicked() instanceof Player target)) {
            return;
        }

        Player officer = event.getPlayer();
    if (getHighestPoliceRank(officer) < 0
        || !onDuty.getOrDefault(officer.getUniqueId(), false)
        || target.equals(officer)) {
            return;
        }
        if (isCuffed(target)) {
            officer.sendMessage(ChatColor.YELLOW + target.getName() + " ist bereits verhaftet.");
            return;
        }
        if (isAdminDuty(target)) {
            officer.sendMessage(ChatColor.YELLOW + target.getName()
                    + " kann im Admin-Dienst nicht verhaftet werden.");
            return;
        }
        if (getWantedPoints(target) <= 0) {
            officer.sendMessage(ChatColor.YELLOW + target.getName()
                + " hat keine Wanted-Punkte und kann nicht verhaftet werden.");
            return;
        }
        if (!isCuffs(officer.getInventory().getItemInMainHand())) {
            return;
        }
        startCuffAttempt(officer, target);
    }

    @EventHandler
    public void onPlayerMove(PlayerMoveEvent event) {
        if (!isCuffed(event.getPlayer()) || event.getTo() == null) {
            return;
        }
        if (followTeleports.remove(event.getPlayer().getUniqueId())) {
            return;
        }
        if (event.getFrom().getX() != event.getTo().getX()
                || event.getFrom().getY() != event.getTo().getY()
                || event.getFrom().getZ() != event.getTo().getZ()) {
            event.setTo(event.getFrom());
        }
    }

    @EventHandler
    public void onPlayerDamage(EntityDamageEvent event) {
        if (event.getEntity() instanceof Player player && isCuffed(player)) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onTazerDamage(EntityDamageByEntityEvent event) {
        if (event.getDamager() instanceof Snowball projectile && isTazerProjectile(projectile)) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onTazerInteract(PlayerInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND
                || (event.getAction() != org.bukkit.event.block.Action.RIGHT_CLICK_AIR
                && event.getAction() != org.bukkit.event.block.Action.RIGHT_CLICK_BLOCK)) {
            return;
        }

        Player officer = event.getPlayer();
        if (!onDuty.getOrDefault(officer.getUniqueId(), false) || !isTazer(event.getItem())) {
            return;
        }
        if (!isTazerReady(event.getItem())) {
            long remainingSeconds = getTazerRemainingSeconds(event.getItem());
            officer.sendMessage(ChatColor.YELLOW + "Dein Tazer lädt noch " + remainingSeconds + " Sekunden auf.");
            event.setCancelled(true);
            return;
        }

        event.setCancelled(true);
        Snowball projectile = officer.launchProjectile(Snowball.class);
        projectile.getPersistentDataContainer().set(tazerProjectileKey, PersistentDataType.BYTE, (byte) 1);
        projectile.setVelocity(officer.getLocation().getDirection().normalize().multiply(1.8D));
        startTazerCooldown(officer);
        officer.getWorld().playSound(officer.getLocation(), Sound.ENTITY_GUARDIAN_ATTACK, 1.0F, 1.2F);
        showTazerTrail(projectile);
    }

    @EventHandler
    public void onTazerHit(ProjectileHitEvent event) {
        if (!(event.getEntity() instanceof Snowball projectile) || !isTazerProjectile(projectile)) {
            return;
        }

        if (event.getHitEntity() instanceof Player target) {
            target.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, 20 * 10, 19));
            target.addPotionEffect(new PotionEffect(PotionEffectType.BLINDNESS, 20 * 5, 0));
            target.getWorld().playSound(target.getLocation(), Sound.ENTITY_GUARDIAN_ATTACK, 1.0F, 0.8F);
            target.getWorld().spawnParticle(Particle.ELECTRIC_SPARK, target.getLocation().add(0, 1, 0), 30, 0.4, 0.7, 0.4, 0.1);
            target.sendMessage(ChatColor.RED + "Du wurdest mit einem Tazer getroffen.");
        }
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        cancelCuffAttempt(player.getUniqueId());
        onDuty.remove(player.getUniqueId());
        removeCuffs(player);
        removeTazers(player);
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        restoreCuffs(event.getPlayer());
        updateWantedNametag(event.getPlayer());
    }

    private boolean searchDrugs(CommandSender sender, String[] args) {
        if (!(sender instanceof Player officer)) {
            sender.sendMessage(ChatColor.RED + "Dieser Befehl kann nur von einem Spieler verwendet werden.");
            return true;
        }
        if (getHighestPoliceRank(officer) < 0) {
            officer.sendMessage(ChatColor.RED + "Nur Polizei-Mitglieder dürfen Drogen suchen.");
            return true;
        }
        if (args.length != 1) {
            officer.sendMessage(ChatColor.YELLOW + "Verwendung: /search <Spieler>");
            return true;
        }

        Player target = Bukkit.getPlayerExact(args[0]);
        if (target == null) {
            officer.sendMessage(ChatColor.RED + "Dieser Spieler ist nicht online.");
            return true;
        }
        if (target.equals(officer)) {
            officer.sendMessage(ChatColor.RED + "Du kannst dich nicht selbst durchsuchen.");
            return true;
        }

        openDrugInventoryForInspector(officer, target);
        officer.sendMessage(ChatColor.GREEN + "Du prüfst jetzt die Drogen von " + target.getName() + ".");
        return true;
    }

    private boolean takeDrugs(CommandSender sender, String[] args) {
        if (!(sender instanceof Player officer)) {
            sender.sendMessage(ChatColor.RED + "Dieser Befehl kann nur von einem Spieler verwendet werden.");
            return true;
        }
        if (getHighestPoliceRank(officer) < 0) {
            officer.sendMessage(ChatColor.RED + "Nur Polizei-Mitglieder dürfen Drogen entnehmen.");
            return true;
        }
        if (args.length != 1) {
            officer.sendMessage(ChatColor.YELLOW + "Verwendung: /takedrugs <Spieler>");
            return true;
        }

        Player target = Bukkit.getPlayerExact(args[0]);
        if (target == null) {
            officer.sendMessage(ChatColor.RED + "Dieser Spieler ist nicht online.");
            return true;
        }
        if (!targetHasAnyDrug(target)) {
            officer.sendMessage(ChatColor.YELLOW + target.getName() + " hat keine Drogen dabei.");
            return true;
        }

        removeAllStoredDrugs(target);
        officer.sendMessage(ChatColor.GREEN + "Du hast " + target.getName() + " die Drogen abgenommen.");
        target.sendMessage(ChatColor.RED + officer.getName() + " hat dir die Drogen abgenommen");
        return true;
    }

    private void openDrugInventoryForInspector(Player officer, Player target) {
        JavaPlugin drugsPlugin = (JavaPlugin) Bukkit.getPluginManager().getPlugin("DrugsPlugin");
        if (drugsPlugin == null) {
            officer.sendMessage(ChatColor.RED + "Das DrugPlugin ist nicht installiert.");
            return;
        }

        try {
            Class<?> pluginClass = drugsPlugin.getClass();
            Class<?> holderClass = Class.forName("plugin.roleplay.guns.drugs.DrugsPlugin$DrugInventoryHolder");
            Constructor<?> holderConstructor = holderClass.getDeclaredConstructor();
            holderConstructor.setAccessible(true);
            Object holder = holderConstructor.newInstance();

            Field titleField = pluginClass.getDeclaredField("INVENTORY_TITLE");
            titleField.setAccessible(true);
            String inventoryTitle = (String) titleField.get(null);

            Field inventoryField = holderClass.getDeclaredField("inventory");
            inventoryField.setAccessible(true);
            Inventory inventory = Bukkit.createInventory((InventoryHolder) holder, 27, inventoryTitle);
            inventoryField.set(holder, inventory);

            Method refreshMethod = pluginClass.getDeclaredMethod("refreshDrugInventory", Player.class, Inventory.class);
            refreshMethod.setAccessible(true);
            refreshMethod.invoke(drugsPlugin, target, inventory);

            officer.openInventory(inventory);
        } catch (ReflectiveOperationException exception) {
            officer.sendMessage(ChatColor.RED + "Die Drogen-Ansicht konnte nicht geöffnet werden.");
            getLogger().warning("DrugPlugin inventory could not be opened for " + officer.getName() + ": " + exception.getMessage());
        }
    }

    private boolean targetHasAnyDrug(Player target) {
        try {
            JavaPlugin drugsPlugin = (JavaPlugin) Bukkit.getPluginManager().getPlugin("DrugsPlugin");
            if (drugsPlugin == null) {
                return false;
            }
            Class<?> drugClass = Class.forName("plugin.roleplay.guns.drugs.DrugsPlugin$Drug");
            Object[] values = drugClass.getEnumConstants();
            Method getStoredAmount = drugsPlugin.getClass().getDeclaredMethod("getStoredAmount", Player.class, drugClass);
            getStoredAmount.setAccessible(true);
            for (Object drug : values) {
                if ((Integer) getStoredAmount.invoke(drugsPlugin, target, drug) > 0) {
                    return true;
                }
            }
            return false;
        } catch (ReflectiveOperationException exception) {
            getLogger().warning("Could not read drug amounts for " + target.getName() + ": " + exception.getMessage());
            return false;
        }
    }

    private void removeAllStoredDrugs(Player target) {
        JavaPlugin drugsPlugin = (JavaPlugin) Bukkit.getPluginManager().getPlugin("DrugsPlugin");
        if (drugsPlugin == null) {
            return;
        }

        try {
            Class<?> drugClass = Class.forName("plugin.roleplay.guns.drugs.DrugsPlugin$Drug");
            Object[] values = drugClass.getEnumConstants();
            Method getStoredAmount = drugsPlugin.getClass().getDeclaredMethod("getStoredAmount", Player.class, drugClass);
            Method consumeDrug = drugsPlugin.getClass().getDeclaredMethod("consumeDrug", Player.class, drugClass);
            getStoredAmount.setAccessible(true);
            consumeDrug.setAccessible(true);

            for (Object drug : values) {
                while ((Integer) getStoredAmount.invoke(drugsPlugin, target, drug) > 0) {
                    consumeDrug.invoke(drugsPlugin, target, drug);
                }
            }
        } catch (ReflectiveOperationException exception) {
            getLogger().warning("Could not remove drugs from " + target.getName() + ": " + exception.getMessage());
        }
    }

    private boolean toggleDuty(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(ChatColor.RED + "Dieser Befehl kann nur von einem Spieler verwendet werden.");
            return true;
        }

        int rank = getHighestPoliceRank(player);
        if (rank < 0) {
            player.sendMessage(ChatColor.RED + "Nur Spieler mit einem Polizei-Rang dürfen Dienst machen.");
            return true;
        }

        if (onDuty.getOrDefault(player.getUniqueId(), false)) {
            onDuty.remove(player.getUniqueId());
            removeCuffs(player);
            removeTazers(player);
            player.sendMessage(ChatColor.YELLOW + "Du bist jetzt außer Dienst.");
            return true;
        }

        if (countEmptySlots(player) < 2) {
            player.sendMessage(ChatColor.RED + "Du brauchst mindestens zwei freie Inventarplätze für Cuffs und Tazer.");
            return true;
        }

        ItemStack cuffs = createCuffs();
        cuffs.setAmount(2);
        player.getInventory().addItem(cuffs);
        player.getInventory().addItem(createTazer());
        onDuty.put(player.getUniqueId(), true);
        player.sendMessage(ChatColor.GREEN + "Du bist jetzt im Dienst als Polizei Rang " + rank
                + ". Du hast Cuffs und einen Tazer erhalten.");
        return true;
    }

    private int getHighestPoliceRank(Player player) {
        String rankPath = "ranks." + player.getUniqueId();
        if (getConfig().contains(rankPath)) {
            return getConfig().getInt(rankPath);
        }

        for (int rank = 6; rank >= 0; rank--) {
            if (player.hasPermission("police.rank." + rank)) {
                return rank;
            }
        }
        return -1;
    }

    private boolean changeRank(CommandSender sender, String[] args, int direction) {
        if (!(sender instanceof Player officer)) {
            sender.sendMessage(ChatColor.RED + "Dieser Befehl kann nur von einem Spieler verwendet werden.");
            return true;
        }
        if (args.length != 1) {
            officer.sendMessage(ChatColor.YELLOW + "Verwendung: /"
                    + (direction > 0 ? "rankup" : "rankdown") + " <Spieler>");
            return true;
        }

        int officerRank = getHighestPoliceRank(officer);
        if (officerRank < 0) {
            officer.sendMessage(ChatColor.RED + "Nur Spieler mit einem Polizei-Rang dürfen Ränge verwalten.");
            return true;
        }
        boolean hasLeaderRights = hasLeaderRights(officer);
        if (!hasLeaderRights) {
            officer.sendMessage(ChatColor.RED + "Nur Rang 6 oder Spieler mit Leaderrechten dürfen Ränge verwalten.");
            return true;
        }

        Player target = Bukkit.getPlayerExact(args[0]);
        if (target == null) {
            officer.sendMessage(ChatColor.RED + "Dieser Spieler ist nicht online.");
            return true;
        }
        if (target.equals(officer)) {
            officer.sendMessage(ChatColor.RED + "Du kannst deinen eigenen Rang nicht ändern.");
            return true;
        }

        int targetRank = getHighestPoliceRank(target);
        int newRank = targetRank + direction;
        if (newRank < 0 || newRank > 6) {
            officer.sendMessage(direction > 0
                    ? ChatColor.RED + "Dieser Spieler hat bereits Rang 6."
                    : ChatColor.RED + "Dieser Spieler hat bereits Rang 0.");
            return true;
        }
        if (targetRank >= officerRank) {
            officer.sendMessage(ChatColor.RED + "Du darfst nur Spieler unter deinem Rang verwalten.");
            return true;
        }
        if (direction > 0 && newRank > officerRank) {
            officer.sendMessage(ChatColor.RED + "Du kannst niemanden über deinen eigenen Rang befördern.");
            return true;
        }

        getConfig().set("ranks." + target.getUniqueId(), newRank);
        saveConfig();
        target.sendMessage(ChatColor.GREEN + "Dein Polizei-Rang ist jetzt Rang " + newRank + ".");
        officer.sendMessage(ChatColor.GREEN + target.getName() + " ist jetzt Polizei Rang " + newRank + ".");
        return true;
    }

    private boolean setRank(CommandSender sender, String[] args) {
        if (!(sender instanceof Player operator)) {
            sender.sendMessage(ChatColor.RED + "Dieser Befehl kann nur von einem Spieler verwendet werden.");
            return true;
        }
        if (!operator.isOp()) {
            operator.sendMessage(ChatColor.RED + "Nur Operatoren dürfen Spieler in die Polizei aufnehmen.");
            return true;
        }
        if (args.length != 2) {
            operator.sendMessage(ChatColor.YELLOW + "Verwendung: /setrank <Spieler> <Rang 0-6>");
            return true;
        }

        Player target = Bukkit.getPlayerExact(args[0]);
        if (target == null) {
            operator.sendMessage(ChatColor.RED + "Dieser Spieler ist nicht online.");
            return true;
        }

        int rank;
        try {
            rank = Integer.parseInt(args[1]);
        } catch (NumberFormatException exception) {
            operator.sendMessage(ChatColor.RED + "Der Rang muss eine Zahl von 0 bis 6 sein.");
            return true;
        }
        if (rank < 0 || rank > 6) {
            operator.sendMessage(ChatColor.RED + "Der Rang muss zwischen 0 und 6 liegen.");
            return true;
        }

        getConfig().set("ranks." + target.getUniqueId(), rank);
        saveConfig();
        target.sendMessage(ChatColor.GREEN + "Du bist jetzt Mitglied der Polizei, Rang " + rank + ".");
        operator.sendMessage(ChatColor.GREEN + target.getName() + " wurde der Polizei auf Rang " + rank + " zugewiesen.");
        return true;
    }

    private boolean setLeader(CommandSender sender, String[] args) {
        if (!(sender instanceof Player operator)) {
            sender.sendMessage(ChatColor.RED + "Dieser Befehl kann nur von einem Spieler verwendet werden.");
            return true;
        }
        if (!operator.isOp()) {
            operator.sendMessage(ChatColor.RED + "Nur Operatoren dürfen Leaderrechte vergeben.");
            return true;
        }
        if (args.length != 1) {
            operator.sendMessage(ChatColor.YELLOW + "Verwendung: /setleader <Spieler>");
            return true;
        }

        Player target = Bukkit.getPlayerExact(args[0]);
        if (target == null) {
            operator.sendMessage(ChatColor.RED + "Dieser Spieler ist nicht online.");
            return true;
        }
        int targetRank = getHighestPoliceRank(target);
        if (targetRank != 5 && targetRank != 6) {
            operator.sendMessage(ChatColor.RED + "Leaderrechte können nur an Rang 5 oder Rang 6 vergeben werden.");
            return true;
        }

        String leaderPath = "leaders." + target.getUniqueId();
        boolean nowLeader = !getConfig().getBoolean(leaderPath, false);
        getConfig().set(leaderPath, nowLeader);
        saveConfig();
        target.sendMessage(nowLeader
                ? ChatColor.GREEN + "Du hast jetzt Leaderrechte erhalten."
                : ChatColor.YELLOW + "Deine Leaderrechte wurden entfernt.");
        operator.sendMessage(nowLeader
                ? ChatColor.GREEN + target.getName() + " hat jetzt Leaderrechte."
                : ChatColor.YELLOW + target.getName() + " hat keine Leaderrechte mehr.");
        return true;
    }

    private boolean hasLeaderRights(Player player) {
        return getHighestPoliceRank(player) == 6
                || player.hasPermission("police.rank.leader")
                || getConfig().getBoolean("leaders." + player.getUniqueId(), false);
    }

    private boolean invite(CommandSender sender, String[] args) {
        if (!(sender instanceof Player inviter)) {
            sender.sendMessage(ChatColor.RED + "Dieser Befehl kann nur von einem Spieler verwendet werden.");
            return true;
        }
        if (!hasLeaderRights(inviter)) {
            inviter.sendMessage(ChatColor.RED + "Nur Rang 6 oder Leader dürfen Spieler einladen.");
            return true;
        }
        if (args.length != 1) {
            inviter.sendMessage(ChatColor.YELLOW + "Verwendung: /invite <Spieler>");
            return true;
        }

        Player target = Bukkit.getPlayerExact(args[0]);
        if (target == null) {
            inviter.sendMessage(ChatColor.RED + "Dieser Spieler ist nicht online.");
            return true;
        }
        if (target.equals(inviter)) {
            inviter.sendMessage(ChatColor.RED + "Du kannst dich nicht selbst einladen.");
            return true;
        }
        if (getHighestPoliceRank(target) >= 0) {
            inviter.sendMessage(ChatColor.YELLOW + target.getName() + " ist bereits Mitglied der Polizei.");
            return true;
        }

        String invitePath = "invites." + target.getUniqueId();
        getConfig().set(invitePath + ".inviter", inviter.getUniqueId().toString());
        getConfig().set(invitePath + ".inviterName", inviter.getName());
        saveConfig();
        target.sendMessage(ChatColor.AQUA + inviter.getName() + " hat dich in die Polizei eingeladen.");
        target.sendMessage(ChatColor.YELLOW + "Nutze /annehmen oder /ablehnen.");
        inviter.sendMessage(ChatColor.GREEN + "Einladung an " + target.getName() + " gesendet.");
        return true;
    }

    private boolean acceptInvite(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(ChatColor.RED + "Dieser Befehl kann nur von einem Spieler verwendet werden.");
            return true;
        }
        String invitePath = "invites." + player.getUniqueId();
        if (!getConfig().contains(invitePath)) {
            player.sendMessage(ChatColor.YELLOW + "Du hast keine offene Polizei-Einladung.");
            return true;
        }

        getConfig().set("ranks." + player.getUniqueId(), 0);
        getConfig().set(invitePath, null);
        saveConfig();
        player.sendMessage(ChatColor.GREEN + "Du bist der Polizei beigetreten und hast Rang 0 (Auszubildener).");
        return true;
    }

    private boolean declineInvite(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(ChatColor.RED + "Dieser Befehl kann nur von einem Spieler verwendet werden.");
            return true;
        }
        String invitePath = "invites." + player.getUniqueId();
        if (!getConfig().contains(invitePath)) {
            player.sendMessage(ChatColor.YELLOW + "Du hast keine offene Polizei-Einladung.");
            return true;
        }

        getConfig().set(invitePath, null);
        saveConfig();
        player.sendMessage(ChatColor.YELLOW + "Du hast die Polizei-Einladung abgelehnt.");
        return true;
    }

    private boolean uninvite(CommandSender sender, String[] args) {
        if (!(sender instanceof Player officer)) {
            sender.sendMessage(ChatColor.RED + "Dieser Befehl kann nur von einem Spieler verwendet werden.");
            return true;
        }
        if (args.length == 0 && officer.isOp()) {
            removeFromPolice(officer, officer);
            return true;
        }
        if (!hasLeaderRights(officer)) {
            officer.sendMessage(ChatColor.RED + "Nur Rang 6 oder ein Leader darf andere entfernen.");
            return true;
        }
        if (args.length != 1) {
            officer.sendMessage(ChatColor.YELLOW + "Verwendung: /uninvite <Spieler>");
            return true;
        }

        Player target = Bukkit.getPlayerExact(args[0]);
        if (target == null) {
            officer.sendMessage(ChatColor.RED + "Dieser Spieler ist nicht online.");
            return true;
        }
        if (target.equals(officer)) {
            officer.sendMessage(ChatColor.RED + "Du kannst dich nicht selbst aus der Fraktion entfernen.");
            return true;
        }

        removeFromPolice(target, officer);
        return true;
    }

    private boolean uncuffCommand(CommandSender sender, String[] args) {
        if (!(sender instanceof Player officer)) {
            sender.sendMessage(ChatColor.RED + "Dieser Befehl kann nur von einem Spieler verwendet werden.");
            return true;
        }
        if (getHighestPoliceRank(officer) < 0) {
            officer.sendMessage(ChatColor.RED + "Nur Polizei-Mitglieder dürfen Handschellen entfernen.");
            return true;
        }
        if (args.length != 1) {
            officer.sendMessage(ChatColor.YELLOW + "Verwendung: /uncuff <Spieler>");
            return true;
        }

        Player target = Bukkit.getPlayerExact(args[0]);
        if (target == null) {
            officer.sendMessage(ChatColor.RED + "Dieser Spieler ist nicht online.");
            return true;
        }
        if (!isCuffed(target)) {
            officer.sendMessage(ChatColor.YELLOW + target.getName() + " ist nicht verhaftet.");
            return true;
        }

        uncuff(target);
        officer.sendMessage(ChatColor.GREEN + target.getName() + " wurde freigelassen.");
        return true;
    }

    private void removeFromPolice(Player target, Player notifier) {
        getConfig().set("ranks." + target.getUniqueId(), -1);
        saveConfig();
        if (onDuty.getOrDefault(target.getUniqueId(), false)) {
            onDuty.remove(target.getUniqueId());
            removeCuffs(target);
            removeTazers(target);
        }
        target.sendMessage(ChatColor.RED + "Du wurdest aus der Polizei-Fraktion entfernt.");
        if (target.equals(notifier)) {
            notifier.sendMessage(ChatColor.YELLOW + "Du hast die Polizei-Fraktion verlassen.");
        } else {
            notifier.sendMessage(ChatColor.GREEN + target.getName() + " wurde aus der Polizei-Fraktion entfernt.");
        }
    }

    private boolean policeChat(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(ChatColor.RED + "Dieser Chat kann nur von Spielern verwendet werden.");
            return true;
        }
        if (getHighestPoliceRank(player) < 0) {
            player.sendMessage(ChatColor.RED + "Nur Mitglieder der Polizei können den Polizei-Chat verwenden.");
            return true;
        }
        if (args.length == 0) {
            player.sendMessage(ChatColor.YELLOW + "Verwendung: /f <Nachricht>");
            return true;
        }

        String message = String.join(" ", args);
        int rank = getHighestPoliceRank(player);
        String formattedMessage = ChatColor.BLUE + "[Polizei] " + ChatColor.WHITE
            + getRankName(rank) + " " + player.getName() + ChatColor.GRAY + ": "
            + ChatColor.WHITE + message;
        for (Player onlinePlayer : Bukkit.getOnlinePlayers()) {
                if (getHighestPoliceRank(onlinePlayer) >= 0
                    || onlinePlayer.hasPermission("ranks.teamchat")) {
                onlinePlayer.sendMessage(formattedMessage);
            }
        }
        return true;
    }

    private boolean setWanted(CommandSender sender, String[] args) {
        if (!(sender instanceof Player officer)) {
            sender.sendMessage(ChatColor.RED + "Dieser Befehl kann nur von einem Spieler verwendet werden.");
            return true;
        }
        if (getHighestPoliceRank(officer) < 0) {
            officer.sendMessage(ChatColor.RED + "Nur Polizei-Mitglieder dürfen Spieler ausschreiben.");
            return true;
        }
        if (args.length == 0) {
            officer.sendMessage(ChatColor.YELLOW + "Verwendung: /wps <Spieler> <Grund>");
                officer.sendMessage(ChatColor.GRAY + "Gründe: " + WANTED_OFFENSES.values().stream()
                    .map(offense -> offense.displayName).collect(java.util.stream.Collectors.joining(", ")));
            return true;
        }

        Player target = Bukkit.getPlayerExact(args[0]);
        if (target == null) {
            officer.sendMessage(ChatColor.RED + "Dieser Spieler ist nicht online.");
            return true;
        }
        if (args.length == 2 && args[1].equalsIgnoreCase("info")) {
            officer.sendMessage(ChatColor.YELLOW + target.getName() + " hat "
                    + getWantedPoints(target) + "/" + MAX_WANTED_POINTS + " Wanted-Punkte.");
            return true;
        }
        if (args.length < 2) {
            officer.sendMessage(ChatColor.YELLOW + "Verwendung: /wps <Spieler> <Grund>");
            return true;
        }

        String offenseKey = normalizeOffenseKey(String.join("_", java.util.Arrays.copyOfRange(args, 1, args.length)));
        WantedOffense offense = WANTED_OFFENSES.get(offenseKey);
        if (offense == null) {
            officer.sendMessage(ChatColor.RED + "Unbekannter Grund. Nutze /wps für die Liste.");
            return true;
        }

        int newPoints = Math.min(MAX_WANTED_POINTS, getWantedPoints(target) + offense.points);
        getConfig().set("wanted." + target.getUniqueId() + ".points", newPoints);
        getConfig().set("wanted." + target.getUniqueId() + ".reason", offense.displayName);
        saveConfig();
        updateWantedNametag(target);
        target.sendMessage(ChatColor.RED + "Du wirst gesucht wegen: " + offense.displayName
                + " (" + newPoints + "/" + MAX_WANTED_POINTS + " Punkte).");
        officer.sendMessage(ChatColor.GREEN + target.getName() + " wurde wegen " + offense.displayName
                + " ausgeschrieben. Punkte: " + newPoints + "/" + MAX_WANTED_POINTS + ".");
        return true;
    }

    private boolean clearWanted(CommandSender sender, String[] args) {
        if (!(sender instanceof Player officer)) {
            sender.sendMessage(ChatColor.RED + "Dieser Befehl kann nur von einem Spieler verwendet werden.");
            return true;
        }
        if (getHighestPoliceRank(officer) < 4) {
            officer.sendMessage(ChatColor.RED + "Nur Polizei ab Rang 4 darf Wanted-Akten löschen.");
            return true;
        }
        if (args.length != 1) {
            officer.sendMessage(ChatColor.YELLOW + "Verwendung: /delwps <Spieler>");
            return true;
        }

        Player target = Bukkit.getPlayerExact(args[0]);
        if (target == null) {
            officer.sendMessage(ChatColor.RED + "Dieser Spieler ist nicht online.");
            return true;
        }
        if (getWantedPoints(target) == 0) {
            officer.sendMessage(ChatColor.YELLOW + target.getName() + " hat keine Wanted-Punkte.");
            return true;
        }

        getConfig().set("wanted." + target.getUniqueId(), null);
        saveConfig();
        updateWantedNametag(target);
        target.sendMessage(ChatColor.GREEN + "Deine Akte wurde von " + officer.getName() + " Gelöscht.");
        officer.sendMessage(ChatColor.GREEN + "Die Wanted-Akte von " + target.getName() + " wurde gelöscht.");
        return true;
    }

    private int getWantedPoints(Player player) {
        return Math.min(MAX_WANTED_POINTS, getConfig().getInt("wanted." + player.getUniqueId() + ".points", 0));
    }

    private void updateWantedNametag(Player target) {
        for (Player viewer : Bukkit.getOnlinePlayers()) {
            sendWantedNametag(viewer, target, isPoliceOrStaff(viewer) && getWantedPoints(target) > 0);
        }
    }

    private void refreshAllWantedNametags() {
        for (Player viewer : Bukkit.getOnlinePlayers()) {
            for (Player target : Bukkit.getOnlinePlayers()) {
                sendWantedNametag(viewer, target, isPoliceOrStaff(viewer) && getWantedPoints(target) > 0);
            }
        }
    }

    private boolean isPoliceOrStaff(Player player) {
        return getHighestPoliceRank(player) >= 0 || player.hasPermission("ranks.teamchat");
    }

    private void sendWantedNametag(Player viewer, Player target, boolean red) {
        if (protocolManager == null || viewer.equals(target)) {
            return;
        }
        String teamName = "PW" + target.getUniqueId().toString().replace("-", "").substring(0, 14);
        try {
            PacketContainer remove = protocolManager.createPacket(PacketType.Play.Server.SCOREBOARD_TEAM);
            remove.getStrings().write(0, teamName);
            remove.getIntegers().write(0, 1);
            protocolManager.sendServerPacket(viewer, remove);
            if (!red) {
                return;
            }

            PacketContainer create = protocolManager.createPacket(PacketType.Play.Server.SCOREBOARD_TEAM);
            create.getStrings().write(0, teamName);
            create.getIntegers().write(0, 0);
            create.getChatComponents().write(0, WrappedChatComponent.fromText(""));
                create.getChatComponents().write(1, WrappedChatComponent.fromText(""));
                create.getChatComponents().write(2, WrappedChatComponent.fromText(""));
                create.getStrings().write(1, "always");
                create.getStrings().write(2, "always");
                create.getIntegers().write(1, 0);
                create.getEnumModifier(EnumWrappers.ChatFormatting.class, 0)
                    .write(0, EnumWrappers.ChatFormatting.RED);
            create.getSpecificModifier(Collection.class).write(0, Collections.singletonList(target.getName()));
            protocolManager.sendServerPacket(viewer, create);
        } catch (Exception exception) {
            getLogger().warning("Wanted-Nametag konnte nicht an " + viewer.getName() + " gesendet werden: "
                    + exception.getMessage());
        }
    }

    private String normalizeOffenseKey(String key) {
        return key.toLowerCase().replace("ä", "ae").replace("ö", "oe")
            .replace("ü", "ue").replace("ß", "ss")
            .replace(" ", "_").replace("-", "_");
    }

    private static Map<String, WantedOffense> createWantedOffenses() {
        Map<String, WantedOffense> offenses = new LinkedHashMap<>();
        addOffense(offenses, "drogenkonsum", "Drogenkonsum", 5);
        addOffense(offenses, "drogenbesitz", "Drogenbesitz", 8);
        addOffense(offenses, "drogenhandel", "Drogenhandel", 15);
        addOffense(offenses, "schwerer_drogenhandel", "Schwerer Drogenhandel", 25);
        addOffense(offenses, "hausfriedensbruch", "Hausfriedensbruch", 5);
        addOffense(offenses, "koerperverletzung", "Körperliche Gewalt", 10);
        addOffense(offenses, "schwere_koerperverletzung", "Schwere körperliche Gewalt", 20);
        addOffense(offenses, "versuchter_mord", "Versuchter Mord", 35);
        addOffense(offenses, "mord", "Mord", 60);
        addOffense(offenses, "beleidigung", "Beleidigung", 2);
        addOffense(offenses, "drohung", "Bedrohung", 6);
        addOffense(offenses, "noetigung", "Nötigung", 8);
        addOffense(offenses, "belaestigung", "Belästigung", 5);
        addOffense(offenses, "sexuelle_belaestigung", "Sexuelle Belästigung", 15);
        addOffense(offenses, "sachbeschaedigung", "Sachbeschädigung", 7);
        addOffense(offenses, "diebstahl", "Diebstahl", 8);
        addOffense(offenses, "schwerer_diebstahl", "Schwerer Diebstahl", 15);
        addOffense(offenses, "raub", "Raub", 20);
        addOffense(offenses, "schwerer_raub", "Schwerer Raub", 35);
        addOffense(offenses, "betrug", "Betrug", 10);
        addOffense(offenses, "geldwaesche", "Geldwäsche", 25);
        addOffense(offenses, "geldfaelschung", "Geldfälschung", 30);
        addOffense(offenses, "urkundenfaelschung", "Urkundenfälschung", 20);
        addOffense(offenses, "bestechung", "Bestechung", 15);
        addOffense(offenses, "korruption", "Korruption", 25);
        addOffense(offenses, "widerstand", "Widerstand gegen Vollstreckungsbeamte", 12);
        addOffense(offenses, "gefangenenbefreiung", "Gefangenenbefreiung", 20);
        addOffense(offenses, "illegaler_waffenbesitz", "Illegaler Waffenbesitz", 15);
        addOffense(offenses, "waffenhandel", "Waffenhandel", 25);
        addOffense(offenses, "schusswaffengebrauch", "Unerlaubter Schusswaffengebrauch", 25);
        addOffense(offenses, "fahrerflucht", "Fahrerflucht", 10);
        addOffense(offenses, "strassenrennen", "Illegales Straßenrennen", 12);
        addOffense(offenses, "amtsanmassung", "Amtsanmaßung", 12);
        addOffense(offenses, "falschaussage", "Falschaussage", 12);
        addOffense(offenses, "verleumdung", "Verleumdung", 8);
        addOffense(offenses, "flucht", "Flucht vor der Polizei", 15);
        return offenses;
    }

    private static void addOffense(Map<String, WantedOffense> offenses, String key, String displayName, int points) {
        offenses.put(key, new WantedOffense(displayName, points));
    }

    private static final class WantedOffense {
        private final String displayName;
        private final int points;

        private WantedOffense(String displayName, int points) {
            this.displayName = displayName;
            this.points = points;
        }
    }

    private String getRankName(int rank) {
        return switch (rank) {
            case 0 -> "Auszubildener";
            case 1 -> "Streifencop";
            case 2 -> "Deputy";
            case 3 -> "Detective";
            case 4 -> "Sergeant";
            case 5 -> "Chief";
            case 6 -> "Direktor";
            default -> "Polizei";
        };
    }

    private ItemStack createCuffs() {
        ItemStack cuffs = new ItemStack(Material.LEAD);
        ItemMeta meta = cuffs.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(ChatColor.AQUA + "Cuffs");
            cuffs.setItemMeta(meta);
        }
        return cuffs;
    }

    private ItemStack createTazer() {
        ItemStack tazer = new ItemStack(Material.WOODEN_HOE);
        ItemMeta meta = tazer.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(ChatColor.YELLOW + "Tazer");
            meta.getPersistentDataContainer().set(tazerItemKey, PersistentDataType.BYTE, (byte) 1);
            meta.setLore(Collections.singletonList(ChatColor.GREEN + "Aufgeladen"));
            tazer.setItemMeta(meta);
        }
        return tazer;
    }

    private boolean isTazer(ItemStack item) {
        if (item == null || !item.hasItemMeta()) {
            return false;
        }
        ItemMeta meta = item.getItemMeta();
        return meta != null && meta.getPersistentDataContainer().has(tazerItemKey, PersistentDataType.BYTE);
    }

    private boolean isTazerReady(ItemStack item) {
        if (!isTazer(item)) {
            return false;
        }
        ItemMeta meta = item.getItemMeta();
        if (meta == null) {
            return false;
        }
        long cooldownUntil = meta.getPersistentDataContainer().getOrDefault(
                tazerCooldownKey, PersistentDataType.LONG, 0L);
        return cooldownUntil <= System.currentTimeMillis();
    }

    private long getTazerRemainingSeconds(ItemStack item) {
        ItemMeta meta = item.getItemMeta();
        if (meta == null) {
            return 0L;
        }
        long cooldownUntil = meta.getPersistentDataContainer().getOrDefault(
                tazerCooldownKey, PersistentDataType.LONG, 0L);
        long remainingMillis = Math.max(0L, cooldownUntil - System.currentTimeMillis());
        return Math.max(1L, (remainingMillis + 999L) / 1000L);
    }

    private void startTazerCooldown(Player officer) {
        ItemStack item = officer.getInventory().getItemInMainHand();
        if (!isTazer(item)) {
            return;
        }
        ItemMeta meta = item.getItemMeta();
        if (meta == null) {
            return;
        }
        long cooldownUntil = System.currentTimeMillis() + 20_000L;
        meta.getPersistentDataContainer().set(tazerCooldownKey, PersistentDataType.LONG, cooldownUntil);
        meta.setLore(Collections.singletonList(ChatColor.RED + "Lädt 20 Sekunden auf"));
        item.setItemMeta(meta);
        officer.sendMessage(ChatColor.YELLOW + "Dein Tazer lädt 20 Sekunden auf.");
    }

    private void removeTazers(Player player) {
        for (int slot = 0; slot < player.getInventory().getSize(); slot++) {
            if (isTazer(player.getInventory().getItem(slot))) {
                player.getInventory().setItem(slot, null);
            }
        }
    }

    private boolean isTazerProjectile(Projectile projectile) {
        return projectile.getPersistentDataContainer().has(tazerProjectileKey, PersistentDataType.BYTE);
    }

    private int countEmptySlots(Player player) {
        int emptySlots = 0;
        for (ItemStack item : player.getInventory().getStorageContents()) {
            if (item == null || item.getType() == Material.AIR) {
                emptySlots++;
            }
        }
        return emptySlots;
    }

    private void showTazerTrail(Snowball projectile) {
        BukkitTask trailTask = Bukkit.getScheduler().runTaskTimer(this, () -> {
            if (!projectile.isValid() || projectile.isDead()) {
                return;
            }
            projectile.getWorld().spawnParticle(Particle.ELECTRIC_SPARK, projectile.getLocation(), 8, 0.08, 0.08, 0.08, 0.02);
            projectile.getWorld().spawnParticle(Particle.END_ROD, projectile.getLocation(), 2, 0.03, 0.03, 0.03, 0.01);
        }, 1L, 1L);

        Bukkit.getScheduler().runTaskLater(this, trailTask::cancel, 60L);
    }

    private boolean isCuffs(ItemStack item) {
        if (item == null || item.getType() != Material.LEAD || !item.hasItemMeta()) {
            return false;
        }
        ItemMeta meta = item.getItemMeta();
        return meta != null && (ChatColor.AQUA + "Cuffs").equals(meta.getDisplayName());
    }

    private void removeCuffs(Player player) {
        for (int slot = 0; slot < player.getInventory().getSize(); slot++) {
            if (isCuffs(player.getInventory().getItem(slot))) {
                player.getInventory().setItem(slot, null);
            }
        }
    }

    private void startCuffAttempt(Player officer, Player target) {
        UUID targetId = target.getUniqueId();
        cancelCuffAttempt(targetId);
        officer.sendMessage(ChatColor.YELLOW + "Halte die Leine 5 Sekunden auf " + target.getName() + "...");

        BukkitTask task = Bukkit.getScheduler().runTaskTimer(this, () -> {
            if (!officer.isOnline() || !target.isOnline()
                    || !onDuty.getOrDefault(officer.getUniqueId(), false)
                    || officer.getWorld() != target.getWorld()
                    || officer.getLocation().distanceSquared(target.getLocation()) > MAX_CUFF_DISTANCE_SQUARED
                    || isAdminDuty(target)
                    || getWantedPoints(target) <= 0
                    || !isCuffs(officer.getInventory().getItemInMainHand())) {
                cancelCuffAttempt(targetId);
                if (officer.isOnline()) {
                        officer.sendMessage(isAdminDuty(target)
                            ? ChatColor.YELLOW + "Die Verhaftung wurde abgebrochen: Der Spieler ist im Admin-Dienst."
                            : getWantedPoints(target) <= 0
                            ? ChatColor.YELLOW + "Die Verhaftung wurde abgebrochen: Der Spieler hat keine Wanted-Punkte."
                            : ChatColor.RED + "Die Verhaftung wurde abgebrochen.");
                }
                return;
            }

            CuffAttempt attempt = cuffAttempts.get(targetId);
            if (attempt != null && attempt.elapsedTicks >= CUFF_DELAY_TICKS) {
                cancelCuffAttempt(targetId);
                cuff(target, officer);
                officer.sendMessage(ChatColor.GREEN + target.getName() + " wurde verhaftet.");
            } else if (attempt != null) {
                attempt.elapsedTicks += 5;
            }
        }, 0L, 5L);

        cuffAttempts.put(targetId, new CuffAttempt(task));
    }

    private void cuff(Player target, Player officer) {
        cuffedPlayers.put(target.getUniqueId(), officer.getUniqueId());
        consumeCuff(officer);
        getConfig().set("cuffed." + target.getUniqueId() + ".officer", officer.getUniqueId().toString());
        saveConfig();
        target.sendMessage(ChatColor.RED + "Du wurdest von " + officer.getName() + " verhaftet.");
    }

    private void consumeCuff(Player officer) {
        ItemStack item = officer.getInventory().getItemInMainHand();
        if (!isCuffs(item)) {
            return;
        }
        if (item.getAmount() <= 1) {
            officer.getInventory().setItemInMainHand(null);
        } else {
            item.setAmount(item.getAmount() - 1);
        }
    }

    private void uncuff(Player target) {
        cuffedPlayers.remove(target.getUniqueId());
        followTeleports.remove(target.getUniqueId());
        getConfig().set("cuffed." + target.getUniqueId(), null);
        saveConfig();
        target.sendMessage(ChatColor.GREEN + "Du wurdest freigelassen. Deine Handschellen wurden entfernt.");
    }

    private boolean isCuffed(Player player) {
        return cuffedPlayers.containsKey(player.getUniqueId());
    }

    private void followOfficers() {
        for (Map.Entry<UUID, UUID> entry : cuffedPlayers.entrySet()) {
            Player target = Bukkit.getPlayer(entry.getKey());
            Player officer = Bukkit.getPlayer(entry.getValue());
            if (target == null || officer == null || !target.isOnline() || !officer.isOnline()
                    || target.getWorld() != officer.getWorld()) {
                continue;
            }

            org.bukkit.Location officerLocation = officer.getLocation().clone();
            org.bukkit.util.Vector direction = officerLocation.getDirection().setY(0).normalize();
            org.bukkit.Location followLocation = officerLocation.subtract(direction.multiply(1.5));
            followLocation.setY(officerLocation.getY());
            followLocation.setYaw(target.getLocation().getYaw());
            followLocation.setPitch(target.getLocation().getPitch());
            if (target.getLocation().distanceSquared(followLocation) > 2.25D) {
                followTeleports.add(target.getUniqueId());
                target.teleport(followLocation);
            }
        }
    }

    private void restoreCuffs(Player target) {
        String path = "cuffed." + target.getUniqueId() + ".officer";
        String officerId = getConfig().getString(path);
        if (officerId == null) {
            return;
        }
        try {
            cuffedPlayers.put(target.getUniqueId(), UUID.fromString(officerId));
        } catch (IllegalArgumentException exception) {
            getConfig().set("cuffed." + target.getUniqueId(), null);
            saveConfig();
            return;
        }
        target.sendMessage(ChatColor.RED + "Du bist weiterhin verhaftet. Deine Cuffs wurden wiederhergestellt.");
    }

    private boolean isAdminDuty(Player player) {
        return player.hasPermission("ranks.teamchat");
    }

    private void cancelCuffAttempt(UUID targetId) {
        CuffAttempt attempt = cuffAttempts.remove(targetId);
        if (attempt != null) {
            attempt.task.cancel();
        }
    }

    private static final class CuffAttempt {
        private final BukkitTask task;
        private long elapsedTicks;

        private CuffAttempt(BukkitTask task) {
            this.task = task;
        }
    }
}
