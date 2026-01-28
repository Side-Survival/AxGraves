package com.artillexstudios.axgraves.gui;

import com.artillexstudios.axapi.utils.StringUtils;
import com.artillexstudios.axgraves.grave.Grave;
import com.artillexstudios.axgraves.grave.SpawnedGraves;
import com.artillexstudios.axgraves.utils.LimitUtils;
import net.milkbowl.vault.economy.Economy;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.ItemFlag;
import org.jetbrains.annotations.NotNull;
import xyz.xenondevs.invui.gui.Gui;
import xyz.xenondevs.invui.gui.PagedGui;
import xyz.xenondevs.invui.gui.structure.Markers;
import xyz.xenondevs.invui.item.Item;
import xyz.xenondevs.invui.item.ItemProvider;
import xyz.xenondevs.invui.item.builder.ItemBuilder;
import xyz.xenondevs.invui.item.impl.AbstractItem;
import xyz.xenondevs.invui.item.impl.SimpleItem;
import xyz.xenondevs.invui.item.impl.controlitem.PageItem;
import xyz.xenondevs.invui.window.Window;

import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;

import static com.artillexstudios.axgraves.AxGraves.CONFIG;
import static com.artillexstudios.axgraves.AxGraves.EXECUTOR;
import static com.artillexstudios.axgraves.AxGraves.MESSAGEUTILS;

public final class GravesGui {

    private static final Item BORDER = new SimpleItem(new ItemBuilder(Material.BLACK_STAINED_GLASS_PANE).setDisplayName(" "));

    private static Economy economy;

    private static final Map<UUID, PendingTeleport> PENDING_TELEPORTS = new java.util.concurrent.ConcurrentHashMap<>();

    private GravesGui() {
    }

    public static void open(@NotNull Player player) {
        open(player, 0);
    }

    public static void open(@NotNull Player player, int page) {
        List<Grave> graves = SpawnedGraves.getGraves().stream()
            .filter(grave -> Objects.equals(grave.getPlayer().getUniqueId(), player.getUniqueId()))
            .sorted(Comparator.comparingLong(Grave::getSpawned).reversed())
            .collect(Collectors.toList());

        int limit = LimitUtils.getGraveLimit(player);

        List<Item> content = graves.stream()
            .map(grave -> (Item) new GraveItem(grave, limit))
            .collect(Collectors.toList());

        Gui gui = PagedGui.items()
            .setStructure(
                "# # # # # # # # #",
                "# x x x x x x x #",
                "# # # < # > # # #"
            )
            .addIngredient('#', BORDER)
            .addIngredient('x', Markers.CONTENT_LIST_SLOT_HORIZONTAL)
            .addIngredient('<', new BackItem())
            .addIngredient('>', new ForwardItem())
            .setContent(content)
            .build();

        Window.single()
            .setViewer(player)
            .setTitle("§0Your Graves")
            .setGui(gui)
            .build()
            .open();

        if (gui instanceof PagedGui<?> pagedGui && page > 0) {
            pagedGui.setPage(page);
        }
    }

    public static class ForwardItem extends PageItem {

        public ForwardItem() {
            super(true);
        }

        @Override
        public ItemProvider getItemProvider(PagedGui<?> gui) {
            return new ItemBuilder(Material.ARROW)
                .setDisplayName("§7Next")
                .addLoreLines(gui.hasNextPage()
                    ? "Page " + (gui.getCurrentPage() + 2) + "/" + gui.getPageAmount()
                    : "No more pages");
        }
    }

    public static class BackItem extends PageItem {

        public BackItem() {
            super(false);
        }

        @Override
        public ItemProvider getItemProvider(PagedGui<?> gui) {
            return new ItemBuilder(Material.ARROW)
                .setDisplayName("§7Previous")
                .addLoreLines(gui.hasPreviousPage()
                    ? "Page " + (gui.getCurrentPage() + 1) + "/" + gui.getPageAmount()
                    : "No previous page");
        }
    }

    private static final class GraveItem extends AbstractItem {
        private final Grave grave;
        private final int limit;

        private GraveItem(@NotNull Grave grave, int limit) {
            this.grave = grave;
            this.limit = limit;
        }

        @Override
        public ItemProvider getItemProvider() {
            Location l = grave.getLocation();

            int dTime = CONFIG.getInt("despawn-time-seconds", 180);
            String timeLeft = StringUtils.formatTime(
                dTime != -1
                    ? (dTime * 1_000L - (System.currentTimeMillis() - grave.getSpawned()))
                    : System.currentTimeMillis() - grave.getSpawned()
            );

            int current = (int) SpawnedGraves.getGraves().stream()
                .filter(gr -> Objects.equals(gr.getPlayer().getUniqueId(), grave.getPlayer().getUniqueId()))
                .count();

            double price = CONFIG.getDouble("graves-gui.teleport-price", 0.0D);

            ItemBuilder builder = new ItemBuilder(Material.CHEST)
                .setDisplayName("§fGrave §7(" + l.getWorld().getName() + " " + l.getBlockX() + ", " + l.getBlockY() + ", " + l.getBlockZ() + ")")
                .addLoreLines(
                    "§7Owner: §e" + grave.getPlayerName(),
                    "§7Time left: §e" + timeLeft,
                    "§7Items: §e" + grave.countItems(),
                    "§7Graves: §e" + current + "§7/" + (limit == -1 ? "∞" : limit),
                    " ",
                    "§aClick to teleport",
                    "§7Price: §a" + String.format(Locale.US, "%.2f", price) + "⛁"
                )
                .addItemFlags(ItemFlag.HIDE_ATTRIBUTES);

            return builder;
        }

        @Override
        public void handleClick(@NotNull ClickType clickType, @NotNull Player player, @NotNull InventoryClickEvent event) {
            if (!clickType.isLeftClick() && !clickType.isRightClick()) {
                return;
            }

            GravesGui.handleTeleportClick(player, grave);
        }
    }

    private static void handleTeleportClick(@NotNull Player player, @NotNull Grave grave) {
        double price = CONFIG.getDouble("graves-gui.teleport-price", 0.0D);
        int warmup = CONFIG.getInt("graves-gui.teleport-warmup-seconds", 3);

        Location target = grave.getLocation().clone().add(0, 0.5, 0);
        boolean safe = isSafe(target);

        if (!safe && CONFIG.getBoolean("graves-gui.unsafe-teleport-requires-confirm", true)) {
            openConfirmTeleport(player, grave, price, warmup, target);
            return;
        }

        startWarmup(player, target, price, warmup);
    }

    private static boolean isSafe(@NotNull Location loc) {
        if (loc.getWorld() == null) return false;

        Block feet = loc.getBlock();
        Block head = loc.clone().add(0, 1, 0).getBlock();
        Block below = loc.clone().add(0, -1, 0).getBlock();

        if (!below.getType().isSolid()) return false;
        if (!feet.isPassable()) return false;
        if (!head.isPassable()) return false;

        return true;
    }

    private static void openConfirmTeleport(@NotNull Player player, @NotNull Grave grave, double price, int warmup, @NotNull Location target) {
        Item yes = new ClickItem(
            new ItemBuilder(Material.LIME_WOOL)
                .setDisplayName("§aTELEPORT")
                .addLoreLines(
                    "§7Location seems unsafe.",
                    "§7Teleport anyway for §a" + String.format(Locale.US, "%.2f", price) + "§7?",
                    "§7You must stand still for §e" + warmup + "s§7."
                ),
            p -> {
                startWarmup(p, target, price, warmup);
            }
        );

        Item no = new ClickItem(
            new ItemBuilder(Material.RED_WOOL)
                .setDisplayName("§cCANCEL"),
            GravesGui::open
        );

        Gui gui = Gui.normal()
            .setStructure(
                ". . Y . N . . ."
            )
            .addIngredient('.', new SimpleItem(new ItemBuilder(Material.AIR)))
            .addIngredient('Y', yes)
            .addIngredient('N', no)
            .build();

        Window.single()
            .setViewer(player)
            .setTitle("§8Unsafe Teleport?")
            .setGui(gui)
            .build()
            .open();
    }

    private static final class ClickItem extends AbstractItem {
        private final ItemProvider provider;
        private final java.util.function.Consumer<Player> onClick;

        private ClickItem(ItemBuilder builder, java.util.function.Consumer<Player> onClick) {
            this.provider = builder;
            this.onClick = onClick;
        }

        @Override
        public ItemProvider getItemProvider() {
            return provider;
        }

        @Override
        public void handleClick(@NotNull ClickType clickType, @NotNull Player player, @NotNull InventoryClickEvent event) {
            onClick.accept(player);
        }
    }

    private static boolean withdraw(@NotNull Player player, double amount) {
        if (amount <= 0.0D) {
            return true;
        }

        Economy eco = getEconomy();
        if (eco == null) {
            return true;
        }

        if (eco.getBalance(player) < amount) {
            MESSAGEUTILS.sendFormatted(player, "§cYou don't have enough money to teleport!");
            return false;
        }

        eco.withdrawPlayer(player, amount);

        String paidMsg = StringUtils.formatToString(
            "&#55FF55You paid &a" + String.format(Locale.US, "%.2f", amount) + " &#55FF55for grave teleport."
        );
        player.sendMessage(paidMsg);

        return true;
    }

    private static Economy getEconomy() {
        if (economy != null) return economy;

        if (Bukkit.getPluginManager().getPlugin("Vault") == null) {
            return null;
        }

        var rsp = Bukkit.getServicesManager().getRegistration(Economy.class);
        if (rsp == null) {
            return null;
        }

        economy = rsp.getProvider();
        return economy;
    }

    private static void startWarmup(@NotNull Player player, @NotNull Location target, double price, int warmupSeconds) {
        if (warmupSeconds <= 0) {
            if (!withdraw(player, price)) return;
            player.teleport(target);
            return;
        }

        UUID uuid = player.getUniqueId();
        // cancel existing warmup if any
        PendingTeleport old = PENDING_TELEPORTS.remove(uuid);
        if (old != null) {
            old.cancelled = true;
        }

        Location startLoc = player.getLocation().clone();
        PendingTeleport pending = new PendingTeleport(target, price, System.currentTimeMillis(), warmupSeconds, startLoc);
        PENDING_TELEPORTS.put(uuid, pending);

        EXECUTOR.execute(() -> {
            player.closeInventory();
            try {
                int lastAnnounced = warmupSeconds;
                String secondsText = StringUtils.formatToString("&#FFAAFFTeleporting in &f" + warmupSeconds + " &7seconds. Don't move!");
                player.sendMessage(secondsText);

                while (!pending.cancelled) {
                    long elapsed = (System.currentTimeMillis() - pending.startTime) / 1000L;
                    int remaining = pending.warmupSeconds - (int) elapsed;

                    if (remaining != lastAnnounced && remaining > 0) {
                        lastAnnounced = remaining;
                        String msg = StringUtils.formatToString("&#FFAAFFTeleporting in &f" + remaining + " &7seconds. Don't move!");
                        player.sendMessage(msg);
                    }

                    if (elapsed >= pending.warmupSeconds) {
                        if (!withdraw(player, price)) {
                            PENDING_TELEPORTS.remove(uuid);
                            return;
                        }
                        player.teleport(pending.target);
                        PENDING_TELEPORTS.remove(uuid);
                        return;
                    }

                    Location current = player.getLocation();
                    if (!isSameBlock(current, pending.startLocation)) {
                        pending.cancelled = true;
                        PENDING_TELEPORTS.remove(uuid);
                        player.sendMessage(StringUtils.formatToString("&#FF5555Teleport cancelled because you moved."));
                        return;
                    }

                    Thread.sleep(200L);
                }
            } catch (InterruptedException ignored) {
            }
        });
    }

    private static boolean isSameBlock(@NotNull Location a, @NotNull Location b) {
        if (!Objects.equals(a.getWorld(), b.getWorld())) return false;
        return a.getBlockX() == b.getBlockX()
            && a.getBlockY() == b.getBlockY()
            && a.getBlockZ() == b.getBlockZ();
    }

    private static final class PendingTeleport {
        private final Location target;
        private final double price;
        private final long startTime;
        private final int warmupSeconds;
        private final Location startLocation;
        private volatile boolean cancelled = false;

        private PendingTeleport(Location target, double price, long startTime, int warmupSeconds, Location startLocation) {
            this.target = target;
            this.price = price;
            this.startTime = startTime;
            this.warmupSeconds = warmupSeconds;
            this.startLocation = startLocation;
        }
    }
}

