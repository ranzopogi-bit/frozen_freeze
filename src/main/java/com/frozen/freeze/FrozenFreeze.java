package com.frozen.freeze;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerItemHeldEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.Vector;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

public class FrozenFreeze extends JavaPlugin implements Listener {

    // ----- Frozen Sword -----
    private NamespacedKey swordModel;
    private int seconds;
    private boolean particles;

    // ----- Death Mace -----
    private NamespacedKey maceModel;
    private int teleportBlocks;
    private int teleportCooldownSeconds;
    private double lookUpPitch;
    private int blindnessSeconds;

    // ----- trails -----
    private boolean swordTrail;
    private boolean maceTrail;
    private double trailForward;
    private double trailUp;
    private double trailSide;
    private final Map<UUID, Location> lastTrailPoint = new HashMap<>();
    private final Particle.DustOptions maceDustPurple = new Particle.DustOptions(Color.fromRGB(70, 20, 120), 1.3f);
    private final Particle.DustOptions maceDustBlack = new Particle.DustOptions(Color.fromRGB(12, 8, 20), 1.5f);
    private final Particle.DustOptions iceDustBlue = new Particle.DustOptions(Color.fromRGB(110, 190, 255), 1.2f);
    private final Particle.DustOptions iceDustWhite = new Particle.DustOptions(Color.fromRGB(225, 245, 255), 1.0f);

    // ----- shared -----
    private int leewayTicks;

    // Server tick when each player last switched away from the item (used for breach swapping).
    private final Map<UUID, Integer> lastSwordTick = new HashMap<>();
    private final Map<UUID, Integer> lastMaceTick = new HashMap<>();
    // Death Mace teleport cooldown: millis timestamp when it is ready again.
    private final Map<UUID, Long> teleportReadyAt = new HashMap<>();

    @Override
    public void onEnable() {
        saveDefaultConfig();
        swordModel = NamespacedKey.fromString(getConfig().getString("item-model", "minecraft:frozen_sword"));
        seconds = getConfig().getInt("freeze-seconds", 5);
        particles = getConfig().getBoolean("particles", true);
        leewayTicks = getConfig().getInt("swap-leeway-ticks", 5);

        maceModel = NamespacedKey.fromString(getConfig().getString("mace-item-model", "minecraft:death_mace"));
        teleportBlocks = getConfig().getInt("mace-teleport-blocks", 10);
        teleportCooldownSeconds = getConfig().getInt("mace-teleport-cooldown-seconds", 60);
        lookUpPitch = getConfig().getDouble("mace-look-up-pitch", -45.0);
        blindnessSeconds = getConfig().getInt("mace-blindness-seconds", 3);

        swordTrail = getConfig().getBoolean("sword-trail", true);
        maceTrail = getConfig().getBoolean("mace-trail", true);
        trailForward = getConfig().getDouble("trail-forward", 0.9);
        trailUp = getConfig().getDouble("trail-up", 0.9);
        trailSide = getConfig().getDouble("trail-side", 0.5);
        long trailInterval = Math.max(1, getConfig().getInt("trail-interval-ticks", 2));

        getServer().getPluginManager().registerEvents(this, this);
        if (swordTrail || maceTrail) {
            getServer().getScheduler().runTaskTimer(this, this::tickTrails, 20L, trailInterval);
        }
        getLogger().info("FrozenFreeze 1.3.0 enabled: sword freeze " + seconds + "s, mace teleport "
                + teleportBlocks + " blocks / " + teleportCooldownSeconds + "s cooldown, blindness "
                + blindnessSeconds + "s, swap-leeway-ticks=" + leewayTicks);
    }

    private boolean hasModel(ItemStack item, NamespacedKey key) {
        if (item == null || item.getType().isAir() || !item.hasItemMeta()) return false;
        ItemMeta meta = item.getItemMeta();
        return meta.hasItemModel() && key.equals(meta.getItemModel());
    }

    private boolean isSword(ItemStack item) {
        return hasModel(item, swordModel);
    }

    private boolean isMace(ItemStack item) {
        return hasModel(item, maceModel);
    }

    private void remember(Player p, ItemStack leftBehind) {
        int now = Bukkit.getCurrentTick();
        if (isSword(leftBehind)) lastSwordTick.put(p.getUniqueId(), now);
        if (isMace(leftBehind)) lastMaceTick.put(p.getUniqueId(), now);
    }

    private boolean recent(Map<UUID, Integer> map, Player p) {
        Integer last = map.get(p.getUniqueId());
        return last != null && Bukkit.getCurrentTick() - last <= leewayTicks;
    }

    // Hotbar swap (number keys / scroll): remember the item in the slot we are leaving.
    @EventHandler(ignoreCancelled = true)
    public void onHeld(PlayerItemHeldEvent e) {
        Player p = e.getPlayer();
        remember(p, p.getInventory().getItem(e.getPreviousSlot()));
    }

    // F-key swap between hands: the item moving to the off hand was in the main hand.
    @EventHandler(ignoreCancelled = true)
    public void onSwapHands(PlayerSwapHandItemsEvent e) {
        remember(e.getPlayer(), e.getOffHandItem());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        lastSwordTick.remove(e.getPlayer().getUniqueId());
        lastMaceTick.remove(e.getPlayer().getUniqueId());
        lastTrailPoint.remove(e.getPlayer().getUniqueId());
    }

    // ---------------------------------------------------------------- hits
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onHit(EntityDamageByEntityEvent e) {
        if (!(e.getDamager() instanceof Player p)) return;
        if (!(e.getEntity() instanceof LivingEntity target)) return;

        ItemStack hand = p.getInventory().getItemInMainHand();
        boolean sword = isSword(hand) || recent(lastSwordTick, p);
        boolean mace = isMace(hand) || recent(lastMaceTick, p);

        if (sword) {
            if (particles) {
                Location center = target.getLocation().add(0, target.getHeight() / 2.0, 0);
                target.getWorld().spawnParticle(Particle.SWEEP_ATTACK, center, 1, 0, 0, 0, 0);
                target.getWorld().spawnParticle(Particle.CRIT, center, 20, 0.4, 0.5, 0.4, 0.3);
            }
            // Fire Aspect sets the target on fire right AFTER this event, and fire clears freezing.
            // So freeze now, then again a couple of ticks later after putting any fire out.
            freeze(target);
            getServer().getScheduler().runTaskLater(this, () -> freeze(target), 1L);
            getServer().getScheduler().runTaskLater(this, () -> freeze(target), 3L);
        }

        if (mace) {
            target.addPotionEffect(new PotionEffect(PotionEffectType.BLINDNESS, blindnessSeconds * 20, 0));
        }
    }

    private void freeze(LivingEntity target) {
        if (!target.isValid() || target.isDead()) return;
        target.setFireTicks(0);
        // Freeze ticks drop by 2 per tick outside powder snow, so 40 per second keeps it frozen for about `seconds`.
        int ticks = target.getMaxFreezeTicks() + seconds * 40;
        target.setFreezeTicks(Math.max(target.getFreezeTicks(), ticks));
    }

    // ---------------------------------------------------------------- Death Mace teleport
    @EventHandler
    public void onUse(PlayerInteractEvent e) {
        if (e.getHand() != EquipmentSlot.HAND) return;
        if (!e.getAction().isRightClick()) return;
        if (!isMace(e.getItem())) return;

        Player p = e.getPlayer();
        // Only when looking up; a normal right-click does nothing special.
        if (p.getLocation().getPitch() > lookUpPitch) return;

        long now = System.currentTimeMillis();
        Long ready = teleportReadyAt.get(p.getUniqueId());
        if (ready != null && ready > now) {
            long left = (ready - now + 999) / 1000;
            p.sendActionBar(Component.text("Death Mace ready in " + left + "s", NamedTextColor.RED));
            e.setCancelled(true);
            return;
        }

        Location dest = findDestination(p);
        if (dest == null) {
            p.sendActionBar(Component.text("No room above you", NamedTextColor.RED));
            return;
        }

        e.setCancelled(true);
        teleportReadyAt.put(p.getUniqueId(), now + teleportCooldownSeconds * 1000L);

        Location from = p.getLocation();
        from.getWorld().spawnParticle(Particle.PORTAL, from.clone().add(0, 1, 0), 40, 0.3, 0.6, 0.3, 0.2);
        p.teleport(dest);
        p.setFallDistance(0f);
        dest.getWorld().spawnParticle(Particle.PORTAL, dest.clone().add(0, 1, 0), 40, 0.3, 0.6, 0.3, 0.2);
        dest.getWorld().playSound(dest, Sound.ENTITY_ENDERMAN_TELEPORT, 1.0f, 0.8f);
    }

    // Highest free spot up to `teleportBlocks` above the player (a ceiling makes it stop lower).
    private Location findDestination(Player p) {
        Location base = p.getLocation();
        World w = base.getWorld();
        for (int dy = teleportBlocks; dy >= 1; dy--) {
            Location t = base.clone().add(0, dy, 0);
            if (t.getBlockY() + 1 >= w.getMaxHeight()) continue;
            if (isFree(t)) return t;
        }
        return null;
    }

    private boolean isFree(Location t) {
        Block feet = t.getBlock();
        Block head = t.clone().add(0, 1, 0).getBlock();
        return feet.isPassable() && head.isPassable() && !feet.isLiquid() && !head.isLiquid();
    }

    // ---------------------------------------------------------------- particle trails
    private void tickTrails() {
        for (Player p : Bukkit.getOnlinePlayers()) {
            ItemStack hand = p.getInventory().getItemInMainHand();
            boolean mace = maceTrail && isMace(hand);
            boolean sword = swordTrail && isSword(hand);
            UUID id = p.getUniqueId();
            if (!mace && !sword) {
                lastTrailPoint.remove(id);
                continue;
            }

            Location cur = headPoint(p);
            Location prev = lastTrailPoint.put(id, cur.clone());

            // Trail: fill in the gap since the last sample so swings leave a streak.
            if (prev != null && prev.getWorld() == cur.getWorld() && prev.distanceSquared(cur) < 9.0) {
                Vector step = cur.toVector().subtract(prev.toVector());
                int n = Math.max(1, (int) Math.ceil(step.length() / 0.3));
                for (int i = 1; i <= n; i++) {
                    Location l = prev.clone().add(step.clone().multiply(i / (double) n));
                    emit(l, mace, 1);
                }
            }
            // Ambient glow around the weapon even when standing still.
            emit(cur, mace, 2);
        }
    }

    // Rough position of the weapon head: in front of and above the right hand.
    private Location headPoint(Player p) {
        Location eye = p.getEyeLocation();
        Vector dir = eye.getDirection();
        Vector right = dir.clone().crossProduct(new Vector(0, 1, 0));
        if (right.lengthSquared() < 1.0E-6) right = new Vector(1, 0, 0);
        right.normalize();
        return eye.clone()
                .add(dir.clone().multiply(trailForward))
                .add(right.multiply(trailSide))
                .add(0, trailUp - 1.0, 0);
    }

    private void emit(Location l, boolean mace, int density) {
        World w = l.getWorld();
        ThreadLocalRandom r = ThreadLocalRandom.current();
        if (mace) {
            w.spawnParticle(Particle.DUST, l, density, 0.12, 0.12, 0.12, 0, maceDustPurple);
            w.spawnParticle(Particle.DUST, l, density, 0.15, 0.15, 0.15, 0, maceDustBlack);
            if (r.nextInt(8) == 0) w.spawnParticle(Particle.SOUL, l, 1, 0.1, 0.1, 0.1, 0.01);
            if (r.nextInt(10) == 0) w.spawnParticle(Particle.SQUID_INK, l, 1, 0.1, 0.1, 0.1, 0.01);
        } else {
            w.spawnParticle(Particle.DUST, l, density, 0.1, 0.1, 0.1, 0, iceDustBlue);
            w.spawnParticle(Particle.DUST, l, 1, 0.12, 0.12, 0.12, 0, iceDustWhite);
            if (r.nextInt(3) == 0) w.spawnParticle(Particle.SNOWFLAKE, l, 1, 0.12, 0.12, 0.12, 0.01);
        }
    }
}
