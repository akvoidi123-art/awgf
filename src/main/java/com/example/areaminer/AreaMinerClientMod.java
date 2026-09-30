package com.example.areaminer;

import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.event.player.AttackBlockCallback;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.commands.arguments.EntityAnchorArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * AreaMiner (клиентский мод).
 *
 * === Копание области ===
 * Деревянная мотыга — палочка выделения. ЛКМ/ПКМ по блоку — точки 1/2.
 * /areaminer start (или кнопка) — бот идёт по области "змейкой" по столбцам,
 * выкапывая каждый столбец сверху донизу на месте, без прыжков, застраивая
 * дыры под ногами при необходимости.
 *
 * === Замена блоков ===
 * Деревянная лопата — палочка выделения ОБЛАСТИ ЗАМЕНЫ (независимая от копания).
 * Клавиша "Заменяемый блок" — берёте в руку блок, нажимаете — он запоминается
 * как блок, который нужно менять. Клавиша "Блок для замены" — аналогично для
 * того, на что менять. /areaminer replace start (или кнопка) — бот идёт по
 * области и заменяет только совпадающие блоки, остальные не трогает.
 *
 * === Самооборона ===
 * /areaminer combat on — если рядом появляется враждебный моб, бот на время
 * прерывает работу и отбивается, потом продолжает.
 *
 * Работает только через обычные действия игрока, поэтому на сервере ничего
 * ставить не нужно.
 */
public class AreaMinerClientMod implements ClientModInitializer {

    public static final String MOD_ID = "areaminer";
    private static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    private static final int MAX_COLUMNS = 10_000;
    private static final int MAX_CELL_TICKS = 600;
    private static final float MIN_HEALTH = 8.0F;
    private static final int FULL_MESSAGE_INTERVAL = 40;
    private static final int PARTICLE_INTERVAL = 10;
    private static final double COMBAT_RADIUS = 6.0;

    private static final Item[] FILLER_PRIORITY = {
            Items.COBBLESTONE, Items.DIRT, Items.NETHERRACK, Items.STONE
    };

    /** Порядок граней при поиске опоры для установки блока: сначала пол, потом бока, в крайнем случае потолок. */
    private static final Direction[] PLACEMENT_FACES = {
            Direction.DOWN, Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST, Direction.UP
    };

    private enum Phase { APPROACH, ADVANCE }

    // ---- клавиши ----
    private KeyMapping toggleKey;
    private KeyMapping replaceToggleKey;
    private KeyMapping setTargetKey;
    private KeyMapping setReplacementKey;

    // ---- выделение: копание ----
    private Item wandItem = Items.WOODEN_HOE;
    private BlockPos pos1;
    private BlockPos pos2;

    // ---- выделение: замена ----
    private Item replaceWandItem = Items.WOODEN_SHOVEL;
    private BlockPos rpos1;
    private BlockPos rpos2;
    private Block targetBlock;
    private Block replaceBlock;

    private int particleCooldown;
    private boolean combatEnabled = false;

    // ---- состояние копания ----
    private boolean working = false;
    private Phase phase = Phase.APPROACH;
    private int minX, maxX, minY, maxY, minZ, maxZ;
    private List<int[]> columns = new ArrayList<>();
    private int index;
    private int clearY;
    private int originalSlot = -1;
    private BlockPos lastToolPos;

    // ---- состояние замены ----
    private boolean replacing = false;
    private Phase rPhase = Phase.APPROACH;
    private int rMinX, rMaxX, rMinY, rMaxY, rMinZ, rMaxZ;
    private List<int[]> rColumns = new ArrayList<>();
    private int rIndex;
    private int rClearY;
    private BlockPos pendingReplacePos;
    private int rOriginalSlot = -1;

    private int cellTicks;
    private int fullMessageCooldown;
    private boolean fullNotified;

    @Override
    public void onInitializeClient() {
        KeyMapping.Category category = KeyMapping.Category.register(
                Identifier.fromNamespaceAndPath(MOD_ID, "main"));

        toggleKey = KeyMappingHelper.registerKeyMapping(new KeyMapping(
                "key.areaminer.toggle", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_UNKNOWN, category));
        replaceToggleKey = KeyMappingHelper.registerKeyMapping(new KeyMapping(
                "key.areaminer.replace_toggle", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_UNKNOWN, category));
        setTargetKey = KeyMappingHelper.registerKeyMapping(new KeyMapping(
                "key.areaminer.set_target", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_UNKNOWN, category));
        setReplacementKey = KeyMappingHelper.registerKeyMapping(new KeyMapping(
                "key.areaminer.set_replacement", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_UNKNOWN, category));

        registerSelectionCallbacks();
        registerCommands();
        ClientTickEvents.END_CLIENT_TICK.register(this::onClientTick);

        LOGGER.info("AreaMiner (клиент) загружен");
    }

    // =====================================================================
    //  Выделение области предметами-палочками + подсветка частицами
    // =====================================================================

    private void registerSelectionCallbacks() {
        AttackBlockCallback.EVENT.register((player, level, hand, pos, direction) -> {
            if (!level.isClientSide() || hand != InteractionHand.MAIN_HAND) {
                return InteractionResult.PASS;
            }
            ItemStack held = player.getMainHandItem();
            if (!working && held.is(wandItem)) {
                setPoint(true, 1, pos);
                return InteractionResult.FAIL;
            }
            if (!replacing && held.is(replaceWandItem)) {
                setPoint(false, 1, pos);
                return InteractionResult.FAIL;
            }
            return InteractionResult.PASS;
        });

        UseBlockCallback.EVENT.register((player, level, hand, hitResult) -> {
            if (!level.isClientSide() || hand != InteractionHand.MAIN_HAND) {
                return InteractionResult.PASS;
            }
            ItemStack held = player.getMainHandItem();
            if (!working && held.is(wandItem)) {
                setPoint(true, 2, hitResult.getBlockPos());
                return InteractionResult.FAIL;
            }
            if (!replacing && held.is(replaceWandItem)) {
                setPoint(false, 2, hitResult.getBlockPos());
                return InteractionResult.FAIL;
            }
            return InteractionResult.PASS;
        });
    }

    private void setPoint(boolean mining, int which, BlockPos pos) {
        BlockPos p = new BlockPos(pos.getX(), pos.getY(), pos.getZ());
        BlockPos current = mining ? (which == 1 ? pos1 : pos2) : (which == 1 ? rpos1 : rpos2);
        if (p.equals(current)) {
            return;
        }
        if (mining) {
            if (which == 1) pos1 = p; else pos2 = p;
        } else {
            if (which == 1) rpos1 = p; else rpos2 = p;
        }

        BlockPos a = mining ? pos1 : rpos1;
        BlockPos b = mining ? pos2 : rpos2;
        String label = mining ? "копания" : "замены";
        String text = "§aТочка " + which + " (" + label + "): " + p.getX() + " " + p.getY() + " " + p.getZ();
        if (a != null && b != null) {
            text += " §7| " + describeRegion(a, b);
        }
        showOverlay(text);
    }

    private static String describeRegion(BlockPos a, BlockPos b) {
        int dx = Math.abs(a.getX() - b.getX()) + 1;
        int dy = Math.abs(a.getY() - b.getY()) + 1;
        int dz = Math.abs(a.getZ() - b.getZ()) + 1;
        return "Область " + dx + "×" + dy + "×" + dz + " (" + ((long) dx * dy * dz) + " блоков)";
    }

    private void spawnSelectionParticles(Minecraft mc) {
        spawnBoxParticles(mc, pos1, pos2, ParticleTypes.END_ROD);
        spawnBoxParticles(mc, rpos1, rpos2, ParticleTypes.HAPPY_VILLAGER);
    }

    private static void spawnBoxParticles(Minecraft mc, BlockPos a, BlockPos b, net.minecraft.core.particles.ParticleOptions type) {
        if (a == null || b == null || mc.level == null) {
            return;
        }
        double x0 = Math.min(a.getX(), b.getX());
        double x1 = Math.max(a.getX(), b.getX()) + 1.0;
        double y0 = Math.min(a.getY(), b.getY());
        double y1 = Math.max(a.getY(), b.getY()) + 1.0;
        double z0 = Math.min(a.getZ(), b.getZ());
        double z1 = Math.max(a.getZ(), b.getZ()) + 1.0;

        double[][] c = {
                {x0, y0, z0}, {x1, y0, z0}, {x1, y0, z1}, {x0, y0, z1},
                {x0, y1, z0}, {x1, y1, z0}, {x1, y1, z1}, {x0, y1, z1}
        };
        int[][] edges = {
                {0, 1}, {1, 2}, {2, 3}, {3, 0}, {4, 5}, {5, 6}, {6, 7}, {7, 4}, {0, 4}, {1, 5}, {2, 6}, {3, 7}
        };
        for (int[] e : edges) {
            double[] p1 = c[e[0]];
            double[] p2 = c[e[1]];
            double len = Math.sqrt(sq(p2[0] - p1[0]) + sq(p2[1] - p1[1]) + sq(p2[2] - p1[2]));
            int steps = Math.max(1, (int) Math.round(len));
            for (int i = 0; i <= steps; i++) {
                double t = (double) i / steps;
                mc.level.addParticle(type,
                        p1[0] + (p2[0] - p1[0]) * t,
                        p1[1] + (p2[1] - p1[1]) * t,
                        p1[2] + (p2[2] - p1[2]) * t,
                        0, 0, 0);
            }
        }
    }

    private static double sq(double v) {
        return v * v;
    }

    // =====================================================================
    //  Команды
    // =====================================================================

    private void registerCommands() {
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, buildContext) ->
            dispatcher.register(ClientCommands.literal("areaminer")
                .then(ClientCommands.literal("start").executes(ctx -> {
                    startMining(ctx.getSource().getClient());
                    return 1;
                }))
                .then(ClientCommands.literal("stop").executes(ctx -> {
                    stopMining(ctx.getSource().getClient(), "§eАвтокопание остановлено");
                    return 1;
                }))
                .then(ClientCommands.literal("clear").executes(ctx -> {
                    pos1 = null;
                    pos2 = null;
                    ctx.getSource().sendFeedback(Component.literal("Выделение копания сброшено"));
                    return 1;
                }))
                .then(ClientCommands.literal("wand").executes(ctx -> {
                    ItemStack held = ctx.getSource().getPlayer().getMainHandItem();
                    if (held.isEmpty()) {
                        ctx.getSource().sendError(Component.literal("Возьмите в руку предмет для палочки выделения"));
                        return 0;
                    }
                    wandItem = held.getItem();
                    ctx.getSource().sendFeedback(Component.literal("Палочка выделения копания — предмет в руке"));
                    return 1;
                }))
                .then(ClientCommands.literal("status").executes(ctx -> {
                    ctx.getSource().sendFeedback(Component.literal(statusText()));
                    return 1;
                }))
                .then(ClientCommands.literal("combat")
                    .then(ClientCommands.literal("on").executes(ctx -> {
                        combatEnabled = true;
                        ctx.getSource().sendFeedback(Component.literal("§aСамооборона включена"));
                        return 1;
                    }))
                    .then(ClientCommands.literal("off").executes(ctx -> {
                        combatEnabled = false;
                        ctx.getSource().sendFeedback(Component.literal("§eСамооборона выключена"));
                        return 1;
                    })))
                .then(ClientCommands.literal("replace")
                    .then(ClientCommands.literal("start").executes(ctx -> {
                        startReplacing(ctx.getSource().getClient());
                        return 1;
                    }))
                    .then(ClientCommands.literal("stop").executes(ctx -> {
                        stopReplacing(ctx.getSource().getClient(), "§eЗамена блоков остановлена");
                        return 1;
                    }))
                    .then(ClientCommands.literal("clear").executes(ctx -> {
                        rpos1 = null;
                        rpos2 = null;
                        ctx.getSource().sendFeedback(Component.literal("Выделение замены сброшено"));
                        return 1;
                    }))
                    .then(ClientCommands.literal("wand").executes(ctx -> {
                        ItemStack held = ctx.getSource().getPlayer().getMainHandItem();
                        if (held.isEmpty()) {
                            ctx.getSource().sendError(Component.literal("Возьмите в руку предмет для палочки выделения"));
                            return 0;
                        }
                        replaceWandItem = held.getItem();
                        ctx.getSource().sendFeedback(Component.literal("Палочка выделения замены — предмет в руке"));
                        return 1;
                    })))
            )
        );
    }

    private String statusText() {
        String p1 = pos1 == null ? "не задана" : pos1.getX() + " " + pos1.getY() + " " + pos1.getZ();
        String p2 = pos2 == null ? "не задана" : pos2.getX() + " " + pos2.getY() + " " + pos2.getZ();
        String rp1 = rpos1 == null ? "не задана" : rpos1.getX() + " " + rpos1.getY() + " " + rpos1.getZ();
        String rp2 = rpos2 == null ? "не задана" : rpos2.getX() + " " + rpos2.getY() + " " + rpos2.getZ();
        return "Копание: т1 " + p1 + ", т2 " + p2 + " | " + (working ? "§aработает" : "§eостановлено") + "§r\n"
                + "Замена: т1 " + rp1 + ", т2 " + rp2
                + " | заменяем " + (targetBlock == null ? "?" : nameOf(targetBlock))
                + " -> " + (replaceBlock == null ? "?" : nameOf(replaceBlock))
                + " | " + (replacing ? "§aработает" : "§eостановлено") + "§r"
                + " | бой: " + (combatEnabled ? "§aвкл" : "§eвыкл");
    }

    private static String nameOf(Block block) {
        return new ItemStack(block.asItem()).getHoverName().getString();
    }

    // =====================================================================
    //  Запуск / остановка — копание
    // =====================================================================

    private void startMining(Minecraft mc) {
        LocalPlayer player = mc.player;
        if (player == null || mc.level == null) return;
        if (working) { showOverlay("§eАвтокопание уже работает"); return; }
        if (replacing) { player.sendSystemMessage(Component.literal("§cСначала остановите замену блоков")); return; }
        if (pos1 == null || pos2 == null) {
            player.sendSystemMessage(Component.literal(
                    "§cСначала выделите область деревянной мотыгой: ЛКМ — точка 1, ПКМ — точка 2."));
            return;
        }

        minX = Math.min(pos1.getX(), pos2.getX());
        maxX = Math.max(pos1.getX(), pos2.getX());
        minY = Math.min(pos1.getY(), pos2.getY());
        maxY = Math.max(pos1.getY(), pos2.getY());
        minZ = Math.min(pos1.getZ(), pos2.getZ());
        maxZ = Math.max(pos1.getZ(), pos2.getZ());

        if ((long) (maxX - minX + 1) * (maxZ - minZ + 1) > MAX_COLUMNS) {
            player.sendSystemMessage(Component.literal("§cОбласть слишком большая (максимум " + MAX_COLUMNS + " клеток по площади)."));
            return;
        }

        BlockPos feet = player.blockPosition();
        int startX = Math.abs(feet.getX() - minX) <= Math.abs(feet.getX() - maxX) ? minX : maxX;
        int startZ = Math.abs(feet.getZ() - minZ) <= Math.abs(feet.getZ() - maxZ) ? minZ : maxZ;
        columns = buildSnakeColumns(minX, maxX, minZ, maxZ, startX, startZ);

        index = 0;
        clearY = maxY;
        phase = Phase.APPROACH;
        cellTicks = 0;
        fullMessageCooldown = 0;
        fullNotified = false;
        lastToolPos = null;
        originalSlot = player.getInventory().getSelectedSlot();
        working = true;

        showOverlay("§aАвтокопание запущено");
    }

    private void stopMining(Minecraft mc, String message) {
        working = false;
        releaseKeys(mc);
        if (mc.player != null) {
            if (originalSlot >= 0) mc.player.getInventory().setSelectedSlot(originalSlot);
            mc.player.sendSystemMessage(Component.literal(message));
        }
        originalSlot = -1;
    }

    // =====================================================================
    //  Запуск / остановка — замена блоков
    // =====================================================================

    private void startReplacing(Minecraft mc) {
        LocalPlayer player = mc.player;
        if (player == null || mc.level == null) return;
        if (replacing) { showOverlay("§eЗамена уже работает"); return; }
        if (working) { player.sendSystemMessage(Component.literal("§cСначала остановите автокопание")); return; }
        if (rpos1 == null || rpos2 == null) {
            player.sendSystemMessage(Component.literal(
                    "§cСначала выделите область деревянной лопатой: ЛКМ — точка 1, ПКМ — точка 2."));
            return;
        }
        if (targetBlock == null || replaceBlock == null) {
            player.sendSystemMessage(Component.literal(
                    "§cСначала назначьте блоки: держите в руке блок и нажмите клавишу "
                            + "\"Заменяемый блок\", затем возьмите другой блок и нажмите \"Блок для замены\" "
                            + "(Настройки > Управление > AreaMiner)."));
            return;
        }

        rMinX = Math.min(rpos1.getX(), rpos2.getX());
        rMaxX = Math.max(rpos1.getX(), rpos2.getX());
        rMinY = Math.min(rpos1.getY(), rpos2.getY());
        rMaxY = Math.max(rpos1.getY(), rpos2.getY());
        rMinZ = Math.min(rpos1.getZ(), rpos2.getZ());
        rMaxZ = Math.max(rpos1.getZ(), rpos2.getZ());

        if ((long) (rMaxX - rMinX + 1) * (rMaxZ - rMinZ + 1) > MAX_COLUMNS) {
            player.sendSystemMessage(Component.literal("§cОбласть слишком большая (максимум " + MAX_COLUMNS + " клеток по площади)."));
            return;
        }

        BlockPos feet = player.blockPosition();
        int startX = Math.abs(feet.getX() - rMinX) <= Math.abs(feet.getX() - rMaxX) ? rMinX : rMaxX;
        int startZ = Math.abs(feet.getZ() - rMinZ) <= Math.abs(feet.getZ() - rMaxZ) ? rMinZ : rMaxZ;
        rColumns = buildSnakeColumns(rMinX, rMaxX, rMinZ, rMaxZ, startX, startZ);

        rIndex = 0;
        rClearY = rMaxY;
        rPhase = Phase.APPROACH;
        pendingReplacePos = null;
        cellTicks = 0;
        fullMessageCooldown = 0;
        fullNotified = false;
        lastToolPos = null;
        rOriginalSlot = player.getInventory().getSelectedSlot();
        replacing = true;

        showOverlay("§aЗамена блоков запущена: " + nameOf(targetBlock) + " -> " + nameOf(replaceBlock));
    }

    private void stopReplacing(Minecraft mc, String message) {
        replacing = false;
        releaseKeys(mc);
        if (mc.player != null) {
            if (rOriginalSlot >= 0) mc.player.getInventory().setSelectedSlot(rOriginalSlot);
            mc.player.sendSystemMessage(Component.literal(message));
        }
        rOriginalSlot = -1;
    }

    /** Строит порядок обхода столбцов "змейкой", начиная с угла (startX, startZ). */
    private static List<int[]> buildSnakeColumns(int minX, int maxX, int minZ, int maxZ, int startX, int startZ) {
        List<int[]> result = new ArrayList<>();
        int otherX = (startX == minX) ? maxX : minX;
        int endZ = (startZ == minZ) ? maxZ : minZ;
        int stepZ = endZ >= startZ ? 1 : -1;

        boolean forward = true;
        for (int z = startZ; ; z += stepZ) {
            int fromX = forward ? startX : otherX;
            int toX = forward ? otherX : startX;
            int stepX = toX >= fromX ? 1 : -1;
            for (int x = fromX; ; x += stepX) {
                result.add(new int[]{x, z});
                if (x == toX) break;
            }
            forward = !forward;
            if (z == endZ) break;
        }
        return result;
    }

    // =====================================================================
    //  Главный цикл
    // =====================================================================

    private void onClientTick(Minecraft mc) {
        while (toggleKey.consumeClick()) {
            if (working) stopMining(mc, "§eАвтокопание остановлено");
            else startMining(mc);
        }
        while (replaceToggleKey.consumeClick()) {
            if (replacing) stopReplacing(mc, "§eЗамена блоков остановлена");
            else startReplacing(mc);
        }
        while (setTargetKey.consumeClick()) {
            handleSetBlock(mc, true);
        }
        while (setReplacementKey.consumeClick()) {
            handleSetBlock(mc, false);
        }

        if (particleCooldown-- <= 0) {
            spawnSelectionParticles(mc);
            particleCooldown = PARTICLE_INTERVAL;
        }

        if (working) {
            tickMiner(mc);
        } else if (replacing) {
            tickReplacer(mc);
        } else if (combatEnabled && mc.player != null && mc.screen == null) {
            tickCombat(mc, mc.player);
        }
    }

    private void handleSetBlock(Minecraft mc, boolean isTarget) {
        if (mc.player == null) return;
        ItemStack held = mc.player.getMainHandItem();
        if (held.isEmpty() || !(held.getItem() instanceof BlockItem blockItem)) {
            showOverlay("§cВ руке должен быть блок");
            return;
        }
        Block block = blockItem.getBlock();
        if (isTarget) {
            targetBlock = block;
            showOverlay("§aЗаменяемый блок: " + nameOf(block));
        } else {
            replaceBlock = block;
            showOverlay("§aБлок для замены: " + nameOf(block));
        }
    }

    private boolean sharedGuards(Minecraft mc, LocalPlayer player, Runnable stop) {
        if (mc.screen != null) {
            releaseKeys(mc);
            return false;
        }
        if (player.getHealth() < MIN_HEALTH) {
            stop.run();
            return false;
        }
        if (isInventoryFull(player)) {
            releaseKeys(mc);
            if (!fullNotified) {
                player.sendSystemMessage(Component.literal(
                        "§cМесто в инвентаре закончилось! Освободите слоты — работа продолжится сама."));
                fullNotified = true;
            }
            if (fullMessageCooldown-- <= 0) {
                showOverlay("§c§lМесто в инвентаре закончилось!");
                fullMessageCooldown = FULL_MESSAGE_INTERVAL;
            }
            return false;
        }
        fullNotified = false;
        fullMessageCooldown = 0;
        return true;
    }

    private void tickMiner(Minecraft mc) {
        LocalPlayer player = mc.player;
        Level level = mc.level;
        if (player == null || level == null || mc.gameMode == null) { working = false; return; }

        if (combatEnabled && tickCombat(mc, player)) return;
        if (!sharedGuards(mc, player, () -> stopMining(mc, "§cАвтокопание остановлено: мало здоровья"))) return;

        if (++cellTicks > MAX_CELL_TICKS) {
            stopMining(mc, "§cАвтокопание остановлено: персонаж застрял");
            return;
        }

        if (phase == Phase.APPROACH) tickMineApproach(mc, player);
        else tickMineAdvance(mc, player, level);
    }

    private void tickReplacer(Minecraft mc) {
        LocalPlayer player = mc.player;
        Level level = mc.level;
        if (player == null || level == null || mc.gameMode == null) { replacing = false; return; }

        if (combatEnabled && tickCombat(mc, player)) return;
        if (!sharedGuards(mc, player, () -> stopReplacing(mc, "§cЗамена остановлена: мало здоровья"))) return;

        if (++cellTicks > MAX_CELL_TICKS) {
            stopReplacing(mc, "§cЗамена остановлена: персонаж застрял");
            return;
        }

        if (rPhase == Phase.APPROACH) tickReplaceApproach(mc, player);
        else tickReplaceAdvance(mc, player, level);
    }

    // =====================================================================
    //  Копание
    // =====================================================================

    private void tickMineApproach(Minecraft mc, LocalPlayer player) {
        int[] c = columns.get(index);
        double tx = c[0] + 0.5, tz = c[1] + 0.5;
        if (isCentered(player, tx, tz)) {
            releaseKeys(mc);
            phase = Phase.ADVANCE;
            clearY = maxY;
            cellTicks = 0;
            return;
        }
        walkToward(mc, player, tx, tz);
    }

    private void tickMineAdvance(Minecraft mc, LocalPlayer player, Level level) {
        if (index >= columns.size()) { stopMining(mc, "§aГотово! Область полностью выкопана."); return; }
        int[] c = columns.get(index);

        if (clearY >= minY) {
            BlockPos target = new BlockPos(c[0], clearY, c[1]);
            if (lavaNear(level, target)) { stopMining(mc, "§cАвтокопание остановлено: обнаружена лава!"); return; }
            if (needsDig(level, target)) { digBlock(mc, player, level, target, null); return; }
            clearY--;
            cellTicks = 0;
            return;
        }

        BlockPos floorPos = new BlockPos(c[0], minY - 1, c[1]);
        BlockState floor = level.getBlockState(floorPos);
        if (floor.isAir() || !floor.getFluidState().isEmpty()) {
            setKey(mc.options.keyUp, false);
            if (tryFillHole(mc, player, level, floorPos)) cellTicks = 0;
            else stopMining(mc, "§cАвтокопание остановлено: под областью пустота, а в хотбаре нет блоков для мостика.");
            return;
        }

        double tx = c[0] + 0.5, tz = c[1] + 0.5;
        if (isCentered(player, tx, tz) && player.onGround()) {
            releaseKeys(mc);
            index++;
            clearY = maxY;
            cellTicks = 0;
            lastToolPos = null;
            return;
        }
        walkToward(mc, player, tx, tz);
    }

    // =====================================================================
    //  Замена блоков
    // =====================================================================

    private void tickReplaceApproach(Minecraft mc, LocalPlayer player) {
        int[] c = rColumns.get(rIndex);
        double tx = c[0] + 0.5, tz = c[1] + 0.5;
        if (isCentered(player, tx, tz)) {
            releaseKeys(mc);
            rPhase = Phase.ADVANCE;
            rClearY = rMaxY;
            pendingReplacePos = null;
            cellTicks = 0;
            return;
        }
        walkToward(mc, player, tx, tz);
    }

    private void tickReplaceAdvance(Minecraft mc, LocalPlayer player, Level level) {
        if (rIndex >= rColumns.size()) { stopReplacing(mc, "§aГотово! Замена в области завершена."); return; }
        int[] c = rColumns.get(rIndex);

        if (rClearY >= rMinY) {
            BlockPos pos = new BlockPos(c[0], rClearY, c[1]);

            if (pendingReplacePos != null) {
                // Блок сломан, ждём, пока станет воздухом, и ставим замену
                BlockState state = level.getBlockState(pos);
                if (!state.isAir()) {
                    digBlock(mc, player, level, pos, targetBlock);
                    return;
                }
                if (!placeBlockAt(mc, player, level, pos, replaceBlock.asItem())) {
                    stopReplacing(mc, "§cЗамена остановлена: нет опоры для установки или в хотбаре кончился блок "
                            + nameOf(replaceBlock) + ".");
                    return;
                }
                pendingReplacePos = null;
                rClearY--;
                cellTicks = 0;
                return;
            }

            BlockState state = level.getBlockState(pos);
            if (state.is(targetBlock)) {
                pendingReplacePos = pos;
                digBlock(mc, player, level, pos, targetBlock);
                return;
            }
            rClearY--;
            cellTicks = 0;
            return;
        }

        double tx = c[0] + 0.5, tz = c[1] + 0.5;
        if (isCentered(player, tx, tz)) {
            releaseKeys(mc);
            rIndex++;
            rClearY = rMaxY;
            cellTicks = 0;
            lastToolPos = null;
            return;
        }
        walkToward(mc, player, tx, tz);
    }

    // =====================================================================
    //  Самооборона
    // =====================================================================

    /** Возвращает true, если в этот тик бот занят боем (основную работу нужно пропустить). */
    private boolean tickCombat(Minecraft mc, LocalPlayer player) {
        if (mc.level == null) return false;
        List<Monster> monsters = mc.level.getEntitiesOfClass(Monster.class, player.getBoundingBox().inflate(COMBAT_RADIUS));
        Monster nearest = null;
        double bestDist = Double.MAX_VALUE;
        for (Monster m : monsters) {
            if (!m.isAlive()) continue;
            double d = m.distanceToSqr(player);
            if (d < bestDist) { bestDist = d; nearest = m; }
        }
        if (nearest == null) return false;

        setKey(mc.options.keyUp, false);
        player.lookAt(EntityAnchorArgument.Anchor.EYES,
                nearest.position().add(0, nearest.getBbHeight() * 0.5, 0));
        HitResult hit = mc.hitResult;
        boolean canHit = hit instanceof net.minecraft.world.phys.EntityHitResult ehr && ehr.getEntity() == nearest;
        setKey(mc.options.keyAttack, canHit);
        return true;
    }

    // =====================================================================
    //  Действия персонажа
    // =====================================================================

    private void walkToward(Minecraft mc, LocalPlayer player, double tx, double tz) {
        player.lookAt(EntityAnchorArgument.Anchor.EYES, new Vec3(tx, player.getEyeY(), tz));
        setKey(mc.options.keyAttack, false);
        setKey(mc.options.keyUp, true);
    }

    /** Смотрит на блок и держит атаку, пока прицел действительно на нём. requiredBlock == null — любой блок. */
    private void digBlock(Minecraft mc, LocalPlayer player, Level level, BlockPos target, Block requiredBlock) {
        setKey(mc.options.keyUp, false);
        player.lookAt(EntityAnchorArgument.Anchor.EYES, Vec3.atCenterOf(target));

        boolean aimed = false;
        HitResult hit = mc.hitResult;
        if (hit instanceof BlockHitResult blockHit && blockHit.getType() == HitResult.Type.BLOCK) {
            BlockPos hitPos = blockHit.getBlockPos();
            if (hitPos.equals(target)) {
                BlockState state = level.getBlockState(hitPos);
                boolean matches = requiredBlock == null ? needsDig(level, hitPos) : state.is(requiredBlock);
                if (matches) {
                    aimed = true;
                    selectToolFor(player, level, hitPos);
                }
            }
        }
        setKey(mc.options.keyAttack, aimed);
    }

    /** Ставит блок из хотбара, "приклеиваясь" к любой ближайшей твёрдой соседней грани. */
    private boolean placeBlockAt(Minecraft mc, LocalPlayer player, Level level, BlockPos target, Item blockItem) {
        int slot = findItemSlot(player, blockItem);
        if (slot < 0) return false;

        for (Direction faceTowardTarget : PLACEMENT_FACES) {
            BlockPos support = target.relative(faceTowardTarget.getOpposite());
            BlockState s = level.getBlockState(support);
            if (s.isAir() || !s.getFluidState().isEmpty()) continue;

            Vec3 center = Vec3.atCenterOf(support);
            Vec3 hitVec = center.add(
                    faceTowardTarget.getStepX() * 0.5,
                    faceTowardTarget.getStepY() * 0.5,
                    faceTowardTarget.getStepZ() * 0.5);
            BlockHitResult hit = new BlockHitResult(hitVec, faceTowardTarget, support, false);

            Inventory inventory = player.getInventory();
            int original = inventory.getSelectedSlot();
            boolean swap = slot != original;
            if (swap) inventory.setSelectedSlot(slot);

            player.lookAt(EntityAnchorArgument.Anchor.EYES, hitVec);
            mc.gameMode.useItemOn(player, InteractionHand.MAIN_HAND, hit);

            if (swap) inventory.setSelectedSlot(original);
            return true;
        }
        return false;
    }

    private boolean tryFillHole(Minecraft mc, LocalPlayer player, Level level, BlockPos floorPos) {
        for (Item filler : FILLER_PRIORITY) {
            if (findItemSlot(player, filler) >= 0) {
                return placeBlockAt(mc, player, level, floorPos, filler);
            }
        }
        return false;
    }

    private static int findItemSlot(LocalPlayer player, Item item) {
        Inventory inventory = player.getInventory();
        for (int i = 0; i < 9; i++) {
            if (inventory.getItem(i).is(item)) return i;
        }
        return -1;
    }

    private void selectToolFor(LocalPlayer player, Level level, BlockPos pos) {
        if (pos.equals(lastToolPos)) return;
        lastToolPos = pos;

        BlockState state = level.getBlockState(pos);
        Inventory inventory = player.getInventory();
        int best = inventory.getSelectedSlot();
        float bestSpeed = toolSpeed(inventory.getItem(best), state);
        for (int i = 0; i < 9; i++) {
            float speed = toolSpeed(inventory.getItem(i), state);
            if (speed > bestSpeed + 0.01F) { best = i; bestSpeed = speed; }
        }
        if (best != inventory.getSelectedSlot()) inventory.setSelectedSlot(best);
    }

    private static float toolSpeed(ItemStack stack, BlockState state) {
        return stack.getItem().getDestroySpeed(stack, state);
    }

    private void releaseKeys(Minecraft mc) {
        setKey(mc.options.keyUp, false);
        setKey(mc.options.keyAttack, false);
    }

    private static void setKey(KeyMapping mapping, boolean down) {
        KeyMapping.set(KeyMappingHelper.getBoundKeyOf(mapping), down);
    }

    // =====================================================================
    //  Вспомогательные проверки
    // =====================================================================

    private static boolean isCentered(LocalPlayer player, double tx, double tz) {
        return Math.abs(player.getX() - tx) < 0.3 && Math.abs(player.getZ() - tz) < 0.3;
    }

    private static boolean needsDig(Level level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        return !state.isAir() && state.getFluidState().isEmpty() && state.getDestroySpeed(level, pos) >= 0.0F;
    }

    private static boolean lavaNear(Level level, BlockPos pos) {
        if (level.getBlockState(pos).is(Blocks.LAVA)) return true;
        for (Direction dir : Direction.values()) {
            if (level.getBlockState(pos.relative(dir)).is(Blocks.LAVA)) return true;
        }
        return false;
    }

    private static boolean isInventoryFull(LocalPlayer player) {
        Inventory inventory = player.getInventory();
        for (int i = 0; i < 36; i++) {
            if (inventory.getItem(i).isEmpty()) return false;
        }
        return true;
    }

    private static void showOverlay(String text) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.gui != null) {
            mc.gui.setOverlayMessage(Component.literal(text), false);
        }
    }
}
