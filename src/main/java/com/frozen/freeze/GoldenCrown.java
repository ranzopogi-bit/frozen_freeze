package com.frozen.freeze;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.EquipmentSlotGroup;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.components.EquippableComponent;
import org.bukkit.Material;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/** The Golden Crown: netherite helmet, +health, regeneration, unbreakable, max enchants, golden trail. */
public class GoldenCrown implements CommandExecutor {

    private final JavaPlugin plugin;
    private final NamespacedKey model;
    private final double extraHealth;
    private final int regenLevel;
    private final boolean trail;
    private final Map<UUID, Location> last = new HashMap<>();
    private int spin = 0;

    private final Particle.DustOptions gold = new Particle.DustOptions(Color.fromRGB(150, 60, 200), 1.1f);
    private final Particle.DustOptions paleGold = new Particle.DustOptions(Color.fromRGB(200, 25, 40), 0.9f);

    public GoldenCrown(JavaPlugin plugin) {
        this.plugin = plugin;
        var cfg = plugin.getConfig();
        NamespacedKey k = NamespacedKey.fromString(cfg.getString("crown-item-model", "minecraft:golden_crown"));
        this.model = k != null ? k : NamespacedKey.minecraft("golden_crown");
        this.extraHealth = cfg.getDouble("crown-extra-health", 20.0);
        this.regenLevel = Math.max(0, cfg.getInt("crown-regeneration-level", 1));
        this.trail = cfg.getBoolean("crown-trail", true);
    }

    public void start() {
        plugin.getServer().getScheduler().runTaskTimer(plugin, this::tickRegen, 20L, 20L);
        if (trail) plugin.getServer().getScheduler().runTaskTimer(plugin, this::tickTrail, 20L, 2L);
        if (plugin.getCommand("goldencrown") != null) plugin.getCommand("goldencrown").setExecutor(this);
    }

    public boolean isCrown(ItemStack item) {
        if (item == null || item.getType().isAir() || !item.hasItemMeta()) return false;
        ItemMeta meta = item.getItemMeta();
        return meta.hasItemModel() && model.equals(meta.getItemModel());
    }

    public ItemStack create() {
        ItemStack item = new ItemStack(Material.NETHERITE_HELMET);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.text("Golden Crown", TextColor.color(0xFFC828))
                .decoration(TextDecoration.BOLD, true).decoration(TextDecoration.ITALIC, false));
        List<Component> lore = new ArrayList<>();
        lore.add(Component.text("Worn by those who rule.", NamedTextColor.GOLD).decoration(TextDecoration.ITALIC, false));
        lore.add(Component.empty());
        lore.add(Component.text("+" + (int) (extraHealth / 2) + " hearts while worn", NamedTextColor.RED).decoration(TextDecoration.ITALIC, false));
        lore.add(Component.text("Constant Regeneration", NamedTextColor.LIGHT_PURPLE).decoration(TextDecoration.ITALIC, false));
        lore.add(Component.text("Never breaks", NamedTextColor.YELLOW).decoration(TextDecoration.ITALIC, false));
        meta.lore(lore);
        meta.setItemModel(model);
        meta.setUnbreakable(true);

        // maximum vanilla enchantments for a helmet
        meta.addEnchant(Enchantment.PROTECTION, 4, true);
        meta.addEnchant(Enchantment.FIRE_PROTECTION, 4, true);
        meta.addEnchant(Enchantment.BLAST_PROTECTION, 4, true);
        meta.addEnchant(Enchantment.PROJECTILE_PROTECTION, 4, true);
        meta.addEnchant(Enchantment.RESPIRATION, 3, true);
        meta.addEnchant(Enchantment.AQUA_AFFINITY, 1, true);
        meta.addEnchant(Enchantment.THORNS, 3, true);
        meta.addEnchant(Enchantment.UNBREAKING, 3, true);
        meta.addEnchant(Enchantment.MENDING, 1, true);

        // setting any attribute replaces the helmet's defaults, so re-add the netherite ones
        meta.addAttributeModifier(Attribute.ARMOR, new AttributeModifier(new NamespacedKey(plugin, "crown_armor"), 3.0, AttributeModifier.Operation.ADD_NUMBER, EquipmentSlotGroup.HEAD));
        meta.addAttributeModifier(Attribute.ARMOR_TOUGHNESS, new AttributeModifier(new NamespacedKey(plugin, "crown_toughness"), 3.0, AttributeModifier.Operation.ADD_NUMBER, EquipmentSlotGroup.HEAD));
        meta.addAttributeModifier(Attribute.KNOCKBACK_RESISTANCE, new AttributeModifier(new NamespacedKey(plugin, "crown_kb"), 0.1, AttributeModifier.Operation.ADD_NUMBER, EquipmentSlotGroup.HEAD));
        meta.addAttributeModifier(Attribute.MAX_HEALTH, new AttributeModifier(new NamespacedKey(plugin, "crown_health"), extraHealth, AttributeModifier.Operation.ADD_NUMBER, EquipmentSlotGroup.HEAD));

        // no armor texture: the 3D crown model is drawn on the head instead
        EquippableComponent eq = meta.getEquippable();
        eq.setSlot(EquipmentSlot.HEAD);
        eq.setModel(null);
        meta.setEquippable(eq);

        item.setItemMeta(meta);
        return item;
    }

    private void tickRegen() {
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (isCrown(p.getInventory().getHelmet())) {
                p.addPotionEffect(new PotionEffect(PotionEffectType.REGENERATION, 80, regenLevel, true, false, true));
            }
        }
    }

    private void tickTrail() {
        spin++;
        for (Player p : Bukkit.getOnlinePlayers()) {
            UUID id = p.getUniqueId();
            if (!isCrown(p.getInventory().getHelmet())) { last.remove(id); continue; }
            World w = p.getWorld();
            ThreadLocalRandom r = ThreadLocalRandom.current();
            Location head = p.getLocation().add(0, p.isSneaking() ? 1.85 : 2.1, 0);

            // rotating golden halo above the crown
            for (int i = 0; i < 3; i++) {
                double a = (spin * 0.35) + i * (Math.PI * 2 / 3);
                Location l = head.clone().add(Math.cos(a) * 0.42, 0.28 + Math.sin(spin * 0.2 + i) * 0.04, Math.sin(a) * 0.42);
                w.spawnParticle(Particle.DUST, l, 1, 0, 0, 0, 0, gold);
            }
            if (r.nextInt(4) == 0) w.spawnParticle(Particle.SOUL_FIRE_FLAME, head.clone().add(0, 0.35, 0), 1, 0.2, 0.1, 0.2, 0.0);
            if (r.nextInt(3) == 0) w.spawnParticle(Particle.WITCH, head, 1, 0.3, 0.2, 0.3, 0.0);

            // trail: golden streak behind you while moving
            Location prev = last.put(id, p.getLocation().clone());
            if (prev != null && prev.getWorld() == w) {
                double d = prev.distance(p.getLocation());
                if (d > 0.08 && d < 6) {
                    int n = Math.max(1, (int) Math.ceil(d / 0.35));
                    for (int i = 0; i < n; i++) {
                        Location l = prev.clone().add(p.getLocation().toVector().subtract(prev.toVector()).multiply(i / (double) n)).add(0, 2.05, 0);
                        w.spawnParticle(Particle.DUST, l, 1, 0.08, 0.08, 0.08, 0, r.nextBoolean() ? gold : paleGold);
                        if (r.nextInt(3) == 0) w.spawnParticle(Particle.ENCHANT, l, 1, 0.1, 0.1, 0.1, 0.01);
                    }
                }
            }
        }
    }

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        int i = 0;
        if (args.length > 0 && args[0].equalsIgnoreCase("give")) i = 1;
        Player target = null;
        if (args.length > i) target = Bukkit.getPlayerExact(args[i]);
        else if (sender instanceof Player p) target = p;
        if (target == null) { sender.sendMessage(Component.text("Usage: /goldencrown [give] <player>", NamedTextColor.RED)); return true; }
        final Player who = target;
        who.getInventory().addItem(create()).values().forEach(left -> who.getWorld().dropItemNaturally(who.getLocation(), left));
        sender.sendMessage(Component.text("Gave the Golden Crown to " + who.getName(), NamedTextColor.GOLD));
        return true;
    }
}
