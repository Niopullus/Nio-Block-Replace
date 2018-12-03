import net.minecraft.server.v1_13_R2.IRegistry;
import net.minecraft.server.v1_13_R2.Item;
import net.minecraft.server.v1_13_R2.ItemStack;
import net.minecraft.server.v1_13_R2.MinecraftKey;
import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.craftbukkit.v1_13_R2.inventory.CraftItemStack;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class NioBlockReplace extends JavaPlugin {

    private CommandSender sender;
    private Command command;
    private String label;
    private String[] args;
    private boolean result;
    private boolean stop;
    private Block currentBlock;
    private boolean waiting;
    private Location[] locations;
    private int index;

    private static final int BUFFER_SIZE = 10000;
    private static final int INTERVAL_FACTOR = 10000;

    public void onEnable() {
        waiting = false;
    }

    public boolean onCommand(final CommandSender sender, final Command command, final String label, final String[] args) {
        if (command.getName().equals("nioreplace")) {
            this.sender = sender;
            this.command = command;
            this.label = label;
            this.args = args;
            stop = false;
            getServer().getScheduler().runTaskAsynchronously(this, new Runnable() {
                @Override
                public void run() {
                    executeCommand();
                }
            });
            return true;
        } else if (command.getName().equals("nioreplacecancel")) {
            stop = true;
            return true;
        }
        return false;
    }

    private void executeCommand() {
        try {
            final String worldName;
            final int x1;
            final int y1;
            final int z1;
            final int x2;
            final int y2;
            final int z2;
            final World world;
            final int minX;
            final int minY;
            final int minZ;
            final int maxX;
            final int maxY;
            final int maxZ;
            final double iterations;
            int currentIterations;
            int blockChangeCount;
            final List<TargetBlock> targetBlocks;
            result = true;
            worldName = args[0];
            x1 = Integer.parseInt(args[1]);
            y1 = Integer.parseInt(args[2]);
            z1 = Integer.parseInt(args[3]);
            x2 = Integer.parseInt(args[4]);
            y2 = Integer.parseInt(args[5]);
            z2 = Integer.parseInt(args[6]);
            targetBlocks = new ArrayList<>();
            for (int i = 7; i < args.length && args[i] != null; i++) {
                final String arg;
                final String targetBlockName;
                final String replacingBlockName;
                final Material targetBlock;
                final Material replacingBlock;
                int colon1;
                int colon2;
                int colons;
                final double chance;
                final TargetBlock targetBlockBundle;
                colon1 = -1;
                colon2 = -1;
                arg = args[i];
                colons = 0;
                for (int j = 0; j < arg.length(); j++) {
                    if (arg.charAt(j) == '|') {
                        if (colons == 0) {
                            colon1 = j;
                            colons += 1;
                        } else if (colons == 1) {
                            colon2 = j;
                            colons += 1;
                            break;
                        }
                    }
                }
                if (colons != 2) {
                    sender.sendMessage("Invalid replacement selection.");
                    return;
                }
                targetBlockName = arg.substring(0, colon1);
                replacingBlockName = arg.substring(colon1 + 1, colon2);
                chance = Double.parseDouble(arg.substring(colon2 + 1));
                targetBlock = materialFromId(targetBlockName);
                replacingBlock = materialFromId(replacingBlockName);
                if (targetBlock == null || !targetBlock.isBlock()) {
                    sender.sendMessage("Invalid target block.");
                    return;
                }
                if (replacingBlock == null || !replacingBlock.isBlock()) {
                    sender.sendMessage("Invalid replacing block.");
                    return;
                }
                targetBlockBundle = new TargetBlock(targetBlock, replacingBlock, chance);
                targetBlocks.add(targetBlockBundle);
            }
            world = Bukkit.getWorld(worldName);
            if (world == null) {
                sender.sendMessage("Invalid world.");
                result = false;
                return;
            }
            minX = getMin(x1, x2);
            minY = getMin(y1, y2);
            minZ = getMin(z1, z2);
            maxX = getMax(x1, x2);
            maxY = getMax(y1, y2);
            maxZ = getMax(z1, z2);
            iterations = (int) ((double) ((maxX - minX + 1) * (maxY - minY + 1) * (maxZ - minZ + 1)));
            currentIterations = 0;
            blockChangeCount = 0;
            locations = new Location[BUFFER_SIZE];
            index = 0;
            x:
            for (int i = minX; i <= maxX; i++) {
                y:
                for (int j = minY; j <= maxY; j++) {
                    z:
                    for (int k = minZ; k <= maxZ; k++) {
                        final Location location;
                        location = new Location(world, i, j, k);
                        locations[index] = location;
                        blockChangeCount += 1;
                        currentIterations += 1;
                        index += 1;
                        if (blockChangeCount >= INTERVAL_FACTOR && iterations > 0) {
                            final String message;
                            message = "[NioBlockReplace] " + ((double) currentIterations * 100 / iterations) + "% done.";
                            blockChangeCount = 0;
                            broadcast(message);
                        }
                        if (index >= BUFFER_SIZE - 1) {
                            executeReplacement(world, targetBlocks);
                        }
                        if (stop) {
                            break x;
                        }
                    }
                }
            }
            executeReplacement(world, targetBlocks);
            getServer().getScheduler().runTask(this, new Runnable() {
                @Override
                public void run() {
                    world.save();
                }
            });
            broadcast("[NioBlockReplace] Block replacement complete.");
        } catch (final Exception e) {
            sender.sendMessage("[NioBlockReplace] Something went wrong.");
            sender.sendMessage("Usage: /nioreplace <world> <x1> <y1> <z1> <x2> <y2> <z2> <target block> <replacing block>.");
            result = false;
            e.printStackTrace();
            return;
        }
    }

    private void executeReplacement(final World world, final List<TargetBlock> targetBlocks) {
        waiting = true;
        getServer().getScheduler().runTask(this, new Runnable() {
            @Override
            public void run() {
                for (int i = 0; i < index; i++) {
                    final Location location2;
                    final Material material;
                    final Block block;
                    location2 = locations[i];
                    currentBlock = world.getBlockAt(location2);
                    block = currentBlock;
                    material = block.getType();
                    for (final TargetBlock target : targetBlocks) {
                        if (target.getTarget().equals(material)) {
                            final double chance;
                            chance = target.getChance();
                            if (chance != 100.0) {
                                final double random;
                                random = Math.random() * 100;
                                if (random < chance) {
                                    block.setType(target.getReplacement());
                                }
                            } else {
                                block.setType(target.getReplacement());
                            }
                            break;
                        }
                    }
                }
                waiting = false;
                synchronized (NioBlockReplace.this) {
                    NioBlockReplace.this.notify();
                }
            }
        });
        while (waiting) {
            synchronized (this) {
                try {
                    wait();
                } catch (final InterruptedException e) {
                    e.printStackTrace();
                }
            }
        }
        locations = new Location[BUFFER_SIZE];
        index = 0;
    }

    private Material materialFromId(final String id) {
        final MinecraftKey minecraftKey;
        final Item item;
        String name;
        minecraftKey = new MinecraftKey(id);
        item = IRegistry.ITEM.get(minecraftKey);
        if (item != null) {
            final Material material;
            name = item.getName();
            name = name.substring(name.lastIndexOf('.') + 1).toUpperCase();
            material = Material.getMaterial(name);
            return material;
        }
        return null;
    }

    private void broadcast(final String message) {
        for (final Player player : getServer().getOnlinePlayers()) {
            player.sendMessage(message);
        }
    }

    private int getMax(final int... args) {
        if (args.length > 0) {
            int max = args[0];
            for (int arg : args) {
                if (arg > max) {
                    max = arg;
                }
            }
            return max;
        }
        return 0;
    }

    private int getMin(final int... args) {
        if (args.length > 0) {
            int min = args[0];
            for (int arg : args) {
                if (arg < min) {
                    min = arg;
                }
            }
            return min;
        }
        return 0;
    }

    private class TargetBlock {

        private Material target;
        private Material replacement;
        private double chance;

        public TargetBlock(final Material target, final Material replacement, final double chance) {
            this.target = target;
            this.replacement = replacement;
            this.chance = chance;
        }

        public Material getTarget() {
            return target;
        }

        public Material getReplacement() {
            return replacement;
        }

        public double getChance() {
            return chance;
        }

    }

}
