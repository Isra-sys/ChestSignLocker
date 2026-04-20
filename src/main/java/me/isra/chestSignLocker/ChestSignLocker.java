package me.isra.chestSignLocker;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.*;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.Directional;
import org.bukkit.block.sign.Side;
import org.bukkit.entity.Player;
import org.bukkit.event.Listener;
import org.bukkit.event.EventHandler;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.block.SignChangeEvent;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.inventory.InventoryMoveItemEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.DoubleChestInventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.configuration.file.FileConfiguration;

import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.Component;

import java.io.File;
import java.io.IOException;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public class ChestSignLocker extends JavaPlugin implements Listener {

    private final Map<Location, UUID> protectedChests = new HashMap<>();
    private File chestFile;
    private FileConfiguration chestConfig;

    @Override
    public void onEnable() {
        Bukkit.getPluginManager().registerEvents(this, this);
        loadProtectedChests();
        getLogger().info("ChestSignLocker enabled.");
    }

    @Override
    public void onDisable() {
        saveProtectedChests();
        getLogger().info("ChestSignLocker disabled.");
    }

    @EventHandler
    public void onSignPlace(SignChangeEvent event) {
        Block signBlock = event.getBlock();
        Player player = event.getPlayer();

        if (!signBlock.getType().name().endsWith("_WALL_SIGN")) return;

        // Si ya tenía texto, alguien lo está editando (no colocando por primera vez)
        BlockState state = signBlock.getState();
        if (state instanceof Sign oldSign) {
            boolean wasPlaced = oldSign.getSide(Side.FRONT).lines().stream()
                    .anyMatch(line -> !line.equals(Component.empty()));

            if (wasPlaced) {
                // Cancelamos la edición si no es el dueño del cofre
                BlockData data = signBlock.getBlockData();
                if (data instanceof Directional directional) {
                    BlockFace attachedFace = directional.getFacing().getOppositeFace();
                    Block attachedBlock = signBlock.getRelative(attachedFace);
                    if (attachedBlock.getType() == Material.CHEST) {
                        Location chestLoc = attachedBlock.getLocation().toBlockLocation();
                        UUID owner = protectedChests.get(chestLoc);
                        if (owner != null && !owner.equals(player.getUniqueId())) {
                            event.setCancelled(true);
                            player.sendMessage(Component.text("You cannot edit another player's sign.").color(NamedTextColor.RED));
                            return;
                        } else {
                            event.setCancelled(true);
                            player.sendMessage(Component.text("This sign is protecting your chest, you cannot edit it").color(NamedTextColor.YELLOW));
                            return;
                        }
                    }
                }
            }
        }

        BlockData data = signBlock.getBlockData();
        if (!(data instanceof Directional directional)) return;

        // Obtener la cara del bloque al que está pegado el cartel
        BlockFace attachedFace = directional.getFacing().getOppositeFace();
        Block attachedBlock = signBlock.getRelative(attachedFace);

        if (attachedBlock.getType() == Material.CHEST) {
            UUID owner = event.getPlayer().getUniqueId();

            BlockState stateOfAttached = attachedBlock.getState();

            // Comprobar si es cofre doble
            if (stateOfAttached instanceof Chest chestState &&
                    chestState.getInventory() instanceof DoubleChestInventory doubleChestInventory) {

                InventoryHolder leftHolder = doubleChestInventory.getLeftSide().getHolder();
                InventoryHolder rightHolder = doubleChestInventory.getRightSide().getHolder();

                if (!(leftHolder instanceof Chest leftChest) || !(rightHolder instanceof Chest rightChest)) {
                    event.getPlayer().sendMessage(Component.text("Error while protecting double chest.").color(NamedTextColor.RED));
                    return;
                }

                Location leftLoc = leftChest.getBlock().getLocation().toBlockLocation();
                Location rightLoc = rightChest.getBlock().getLocation().toBlockLocation();

                // Si ya están protegidos por el mismo dueño
                if (protectedChests.containsKey(leftLoc) && protectedChests.get(leftLoc).equals(owner) &&
                        protectedChests.containsKey(rightLoc) && protectedChests.get(rightLoc).equals(owner)) {
                    event.getPlayer().sendMessage(Component.text("This double chest is already protected by you.").color(NamedTextColor.YELLOW));
                    event.setCancelled(true);
                    //DEVOLVER EL CARTEL
                    returnSignToPlayer(signBlock, player);
                    return;
                }

                // Sí están protegidos por otro dueño
                if ((protectedChests.containsKey(leftLoc) && !protectedChests.get(leftLoc).equals(owner)) ||
                        (protectedChests.containsKey(rightLoc) && !protectedChests.get(rightLoc).equals(owner))) {
                    event.getPlayer().sendMessage(Component.text("This double chest is already protected by another player.").color(NamedTextColor.RED));
                    event.setCancelled(true);
                    //DEVOLVER EL CARTEL
                    returnSignToPlayer(signBlock, player);
                    return;
                }

                // Guardar protección para ambas mitades
                protectedChests.put(leftLoc, owner);
                protectedChests.put(rightLoc, owner);

                event.line(0, Component.text(""));
                event.line(1, Component.text("[Protected]").color(NamedTextColor.GREEN));
                event.line(2, Component.text(event.getPlayer().getName()).color(NamedTextColor.BLACK));
                event.line(3, Component.text(""));

                event.getPlayer().sendMessage(Component.text("Double chest protected!").color(NamedTextColor.GREEN));
                return;
            }

            // Cofre simple
            Location loc = attachedBlock.getLocation().toBlockLocation();

            if (protectedChests.containsKey(loc)) {
                if (protectedChests.get(loc).equals(owner)) {
                    event.getPlayer().sendMessage(Component.text("This chest is already protected by you.").color(NamedTextColor.YELLOW));
                } else {
                    event.getPlayer().sendMessage(Component.text("This chest is already protected by another player.").color(NamedTextColor.RED));
                }
                event.setCancelled(true);
                //DEVOLVER EL CARTEL
                returnSignToPlayer(signBlock, player);
                return;
            }

            protectedChests.put(loc, owner);

            event.line(0, Component.text(""));
            event.line(1, Component.text("[Protected]").color(NamedTextColor.GREEN));
            event.line(2, Component.text(event.getPlayer().getName()).color(NamedTextColor.BLACK));
            event.line(3, Component.text(""));

            event.getPlayer().sendMessage(Component.text("Chest protected!").color(NamedTextColor.GREEN));
        }
    }

    public void returnSignToPlayer(Block signBlock, Player player) {
        // Mapear bloque de cartel de pared a su item correspondiente
        Material blockType = signBlock.getType();
        Material itemType;

        switch (blockType) {
            case OAK_WALL_SIGN -> itemType = Material.OAK_SIGN;
            case SPRUCE_WALL_SIGN -> itemType = Material.SPRUCE_SIGN;
            case BIRCH_WALL_SIGN -> itemType = Material.BIRCH_SIGN;
            case JUNGLE_WALL_SIGN -> itemType = Material.JUNGLE_SIGN;
            case ACACIA_WALL_SIGN -> itemType = Material.ACACIA_SIGN;
            case DARK_OAK_WALL_SIGN -> itemType = Material.DARK_OAK_SIGN;
            case MANGROVE_WALL_SIGN -> itemType = Material.MANGROVE_SIGN;
            case CHERRY_WALL_SIGN -> itemType = Material.CHERRY_SIGN;
            // Agrega más si hay otros tipos de carteles
            default -> {
                // Por si acaso no es un cartel válido, devolvemos null o no hacemos nada
                player.sendMessage(Component.text("Error: unknown sign type, cannot return it.").color(NamedTextColor.RED));
                return;
            }
        }

        signBlock.setType(Material.AIR);

        ItemStack signItem = new ItemStack(itemType);
        HashMap<Integer, ItemStack> leftovers = player.getInventory().addItem(signItem);
        if (!leftovers.isEmpty()) {
            // No caben en el inventario, soltar en el suelo
            signBlock.getWorld().dropItemNaturally(signBlock.getLocation(), signItem);
        }
    }

    @EventHandler
    public void onChestPlacedNextToProtected(BlockPlaceEvent event) {
        Block placedBlock = event.getBlockPlaced();
        Player player = event.getPlayer();

        if (placedBlock.getType() != Material.CHEST) return;

        Location placedLoc = placedBlock.getLocation().toBlockLocation();
        BlockState placedState = placedBlock.getState();

        if (!(placedState instanceof Chest placedChest)) return;

        // Esperar un tick para que el cofre se actualice como doble (si aplica)
        Bukkit.getScheduler().runTaskLater(this, () -> {
            if (!(placedChest.getInventory() instanceof DoubleChestInventory doubleInventory)) {
                // No es cofre doble, no extender protección
                return;
            }

            InventoryHolder leftHolder = doubleInventory.getLeftSide().getHolder();
            InventoryHolder rightHolder = doubleInventory.getRightSide().getHolder();

            if (!(leftHolder instanceof Chest leftChest) || !(rightHolder instanceof Chest rightChest)) return;

            Location leftLoc = leftChest.getBlock().getLocation().toBlockLocation();
            Location rightLoc = rightChest.getBlock().getLocation().toBlockLocation();

            // Identificar cuál es el cofre ya existente (el que no es el colocado)
            Location otherLoc = leftLoc.equals(placedLoc) ? rightLoc : leftLoc;

            if (!protectedChests.containsKey(otherLoc)) return;

            UUID owner = protectedChests.get(otherLoc);

            if (!owner.equals(player.getUniqueId())) {
                player.sendMessage(Component.text("You cannot form a double chest with a chest protected by another player.").color(NamedTextColor.RED));

                // Revertir el cofre colocado
                placedBlock.setType(Material.AIR);

                // Devolver el cofre al inventario del jugador (intenta añadir, si no espacio lo suelta en el suelo)
                ItemStack chestItem = new ItemStack(Material.CHEST);
                HashMap<Integer, ItemStack> leftovers = player.getInventory().addItem(chestItem);
                if (!leftovers.isEmpty()) {
                    // No caben en el inventario, soltar en el suelo
                    placedBlock.getWorld().dropItemNaturally(placedBlock.getLocation(), chestItem);
                }
                return;
            }

            // Es dueño, extender protección al nuevo cofre
            protectedChests.put(placedLoc, owner);
            saveProtectedChests();
            player.sendMessage(Component.text("Protection has been extended to the new chest.").color(NamedTextColor.GREEN));
        }, 1L); // Esperar 1 tick (20 ms)
    }

    @EventHandler
    public void onChestInteract(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK) return;

        if (event.getClickedBlock() != null &&
                (event.getClickedBlock().getType() == Material.CHEST)) {

            Location loc = event.getClickedBlock().getLocation().toBlockLocation();

            if (protectedChests.containsKey(loc)) {
                UUID owner = protectedChests.get(loc);
                if (!owner.equals(event.getPlayer().getUniqueId())) {
                    event.setCancelled(true);
                    event.getPlayer().sendMessage(Component.text("This chest is protected and does not belong to you.").color(NamedTextColor.RED));
                    Bukkit.broadcast(
                            Component.text()
                                    .append(Component.text(event.getPlayer().getName(), NamedTextColor.WHITE))
                                    .append(Component.text(" attempted to steal from ", NamedTextColor.RED))
                                    .append(Component.text(String.valueOf(Bukkit.getOfflinePlayer(owner).getName()), NamedTextColor.WHITE))
                                    .build()
                    );
                }
            }
        }
    }

    @EventHandler
    public void onChestBreak(BlockBreakEvent event) {
        Block block = event.getBlock();
        Player player = event.getPlayer();
        UUID playerUUID = player.getUniqueId();

        if (block.getType() != Material.CHEST) {
            return;
        }

        Location loc = block.getLocation().toBlockLocation();
        UUID owner = protectedChests.get(loc);
        if (owner == null) {
            return;
        }

        if (!owner.equals(playerUUID)) {
            event.setCancelled(true);
            player.sendMessage(Component.text("This chest is protected and you cannot break it.").color(NamedTextColor.RED));
            return;
        }

        boolean hasSignOnThisPart = hasAttachedSign(block);

        BlockState state = block.getState();
        if (!(state instanceof Chest chest)) {
            return;
        }

        InventoryHolder holder = chest.getInventory().getHolder();

        if (holder instanceof DoubleChest doubleChest) {
            Chest leftChest = (Chest) doubleChest.getLeftSide();
            Chest rightChest = (Chest) doubleChest.getRightSide();

            if (leftChest != null && rightChest != null) {
                Location leftLoc = leftChest.getBlock().getLocation().toBlockLocation();
                Location rightLoc = rightChest.getBlock().getLocation().toBlockLocation();

                if (hasSignOnThisPart) {
                    protectedChests.remove(leftLoc);
                    protectedChests.remove(rightLoc);
                    player.sendMessage(Component.text("You have removed the protection from the double chest.").color(NamedTextColor.YELLOW));
                } else {
                    protectedChests.remove(loc);
                    player.sendMessage(Component.text("You have removed the protection from this chest half.").color(NamedTextColor.YELLOW));
                }
            } else {
                protectedChests.remove(loc);
                player.sendMessage(Component.text("You have removed the protection from this chest half.").color(NamedTextColor.YELLOW));
            }
        } else {
            if (hasSignOnThisPart) {
                protectedChests.remove(loc);
                player.sendMessage(Component.text("You have removed the protection from this chest.").color(NamedTextColor.YELLOW));
            }
        }

        saveProtectedChests();
    }

    private boolean hasAttachedSign(Block block) {
        for (BlockFace face : Arrays.asList(BlockFace.NORTH, BlockFace.SOUTH, BlockFace.EAST, BlockFace.WEST)) {
            Block relative = block.getRelative(face);

            if (relative.getType().name().endsWith("_WALL_SIGN")) {
                BlockData data = relative.getBlockData();

                if (data instanceof Directional directional) {
                    BlockFace signFacing = directional.getFacing();

                    if (signFacing.equals(face)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    @EventHandler
    public void onSignBreak(BlockBreakEvent event) {
        Block block = event.getBlock();
        Player player = event.getPlayer();
        UUID playerUUID = player.getUniqueId();

        if (!block.getType().name().endsWith("_WALL_SIGN")) return;

        BlockData data = block.getBlockData();
        if (!(data instanceof Directional directional)) return;

        BlockFace attachedFace = directional.getFacing().getOppositeFace();
        Block attachedBlock = block.getRelative(attachedFace);

        if (attachedBlock.getType() != Material.CHEST) return;

        Location loc = attachedBlock.getLocation().toBlockLocation();
        UUID owner = protectedChests.get(loc);

        if (owner == null) return;

        if (!owner.equals(playerUUID)) {
            event.setCancelled(true);
            player.sendMessage(Component.text("This sign protects a chest that does not belong to you.").color(NamedTextColor.RED));
            return;
        }

        BlockState state = attachedBlock.getState();
        if (state instanceof Chest chest &&
                chest.getInventory() instanceof DoubleChestInventory doubleChestInventory) {

            InventoryHolder leftHolder = doubleChestInventory.getLeftSide().getHolder();
            InventoryHolder rightHolder = doubleChestInventory.getRightSide().getHolder();

            if (!(leftHolder instanceof Chest leftChest) || !(rightHolder instanceof Chest rightChest)) {
                player.sendMessage(Component.text("Error while removing protection from double chest.").color(NamedTextColor.RED));
                return;
            }

            Location leftLoc = leftChest.getBlock().getLocation().toBlockLocation();
            Location rightLoc = rightChest.getBlock().getLocation().toBlockLocation();

            protectedChests.remove(leftLoc);
            protectedChests.remove(rightLoc);

            player.sendMessage(Component.text("You have removed the protection from the double chest.").color(NamedTextColor.YELLOW));
        } else {
            protectedChests.remove(loc);
            player.sendMessage(Component.text("You have removed the protection from this chest.").color(NamedTextColor.YELLOW));
        }

        saveProtectedChests();
    }

    @EventHandler
    public void onHopperSuck(InventoryMoveItemEvent event) {
        // Solo nos interesa cuando el origen (source) es un cofre protegido
        InventoryHolder sourceHolder = event.getSource().getHolder();
        if (!(sourceHolder instanceof Chest || sourceHolder instanceof DoubleChest)) return;

        Location sourceLoc;

        if (sourceHolder instanceof Chest chest) {
            sourceLoc = chest.getBlock().getLocation().toBlockLocation();
        } else if (sourceHolder instanceof DoubleChest doubleChest) {
            // Un DoubleChest no tiene una única Location, comprobamos ambas mitades
            Chest left  = (Chest) doubleChest.getLeftSide();
            Chest right = (Chest) doubleChest.getRightSide();
            if (left == null || right == null) return;

            Location leftLoc  = left.getBlock().getLocation().toBlockLocation();
            Location rightLoc = right.getBlock().getLocation().toBlockLocation();

            // Si cualquiera de las dos mitades está protegida, bloqueamos
            if (protectedChests.containsKey(leftLoc) || protectedChests.containsKey(rightLoc)) {
                event.setCancelled(true);
            }
            return;
        } else {
            return;
        }

        if (protectedChests.containsKey(sourceLoc)) {
            event.setCancelled(true);
        }
    }

    private void loadProtectedChests() {
        chestFile = new File(getDataFolder(), "protected-chests.yml");
        if (!chestFile.exists()) {
            chestFile.getParentFile().mkdirs();
            try {
                chestFile.createNewFile();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
        chestConfig = YamlConfiguration.loadConfiguration(chestFile);
        protectedChests.clear();

        for (String key : chestConfig.getKeys(false)) {
            UUID owner = UUID.fromString(chestConfig.getString(key));
            String[] parts = key.split(",");
            int x = Integer.parseInt(parts[0]);
            int y = Integer.parseInt(parts[1]);
            int z = Integer.parseInt(parts[2]);
            String worldName = parts[3];
            World world = Bukkit.getWorld(worldName);
            if (world != null) {
                Location loc = new Location(world, x, y, z);
                protectedChests.put(loc, owner);
            }
        }
    }

    private void saveProtectedChests() {
        if (chestConfig == null || chestFile == null) return;

        for (String key : chestConfig.getKeys(false)) {
            chestConfig.set(key, null);
        }

        for (Map.Entry<Location, UUID> entry : protectedChests.entrySet()) {
            Location loc = entry.getKey();
            UUID owner = entry.getValue();
            String key = loc.getBlockX() + "," + loc.getBlockY() + "," + loc.getBlockZ() + "," + loc.getWorld().getName();
            chestConfig.set(key, owner.toString());
        }

        try {
            chestConfig.save(chestFile);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
}
