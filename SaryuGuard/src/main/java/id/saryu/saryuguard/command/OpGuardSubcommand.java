package id.saryu.saryuguard.command;

import id.saryu.libcore.command.CommandFramework;
import id.saryu.libcore.ui.Paginator;
import id.saryu.saryuguard.SaryuGuardPlugin;
import id.saryu.saryuguard.mfa.MfaState;
import id.saryu.saryuguard.mfa.TwoFactorModule;
import id.saryu.saryuguard.module.SaryuGuardModule;
import id.saryu.saryuguard.opguard.NameResolver;
import id.saryu.saryuguard.opguard.OpGuardEntry;
import id.saryu.saryuguard.opguard.OpGuardModule;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

/**
 * {@code /sg opguard [info|reload|add|remove|list|import]} – OpGuard module management.
 */
public class OpGuardSubcommand implements CommandFramework.SubCommand {

    private static final DateTimeFormatter TIME_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault());
    private static final int LIST_PAGE_SIZE = 7;

    private final SaryuGuardPlugin plugin;

    public OpGuardSubcommand(@NotNull SaryuGuardPlugin plugin) {
        this.plugin = plugin;
    }

    @Override public @NotNull String name() { return "opguard"; }
    @Override public @NotNull String permission() { return "saryuguard.admin"; }
    @Override public String description() { return "Kelola modul OpGuard"; }
    @Override public String usage() { return "/sg opguard [info|reload|add|remove|list|import]"; }
    @Override public boolean playerOnly() { return false; }
    @Override public int cooldownSeconds() { return 3; }

    @Override
    public void execute(CommandSender sender, Command cmd, String label, String[] args) {
        OpGuardModule og = getOpGuard();
        if (og == null) {
            sender.sendMessage(Component.text("[SaryuGuard] Modul OpGuard tidak tersedia.", NamedTextColor.RED));
            return;
        }

        String sub = args.length > 0 ? args[0].toLowerCase(Locale.ROOT) : "info";
        switch (sub) {
            case "info" -> handleInfo(sender, og);
            case "reload" -> handleReload(sender, og);
            case "add" -> handleAdd(sender, og, args);
            case "remove" -> handleRemove(sender, og, args);
            case "list" -> handleList(sender, og, args);
            case "import" -> handleImport(sender, og, args);
            default -> sender.sendMessage(Component.text(
                    "Penggunaan: /sg opguard [info|reload|add|remove|list|import]", NamedTextColor.YELLOW));
        }
    }

    // -------------------------------------------------------------------------
    // info
    // -------------------------------------------------------------------------

    private void handleInfo(CommandSender sender, OpGuardModule og) {
        sender.sendMessage(Component.text("═══ OpGuard Status ═══", NamedTextColor.GOLD));
        sender.sendMessage(Component.text("Status: " + og.getStatus(), NamedTextColor.YELLOW));
        sender.sendMessage(Component.text("Mode: " + og.getMode(), NamedTextColor.YELLOW));
        sender.sendMessage(Component.text("OP sah: " + og.getAllowedOps().size(), NamedTextColor.YELLOW));
        sender.sendMessage(Component.text("Online-mode: " + og.isOnlineMode(), NamedTextColor.YELLOW));
        sender.sendMessage(Component.text("Detail: " + og.getStatusMessage(), NamedTextColor.GRAY));
        if (!og.isOnlineMode()) {
            sender.sendMessage(Component.text(
                    "⚠ Server offline-mode: UUID diturunkan dari nama, daftar OP tidak melindungi dari "
                            + "peniruan nama. Gunakan AuthMe + 2FA.",
                    NamedTextColor.RED).decorate(TextDecoration.BOLD));
        }
    }

    // -------------------------------------------------------------------------
    // reload
    // -------------------------------------------------------------------------

    private void handleReload(CommandSender sender, OpGuardModule og) {
        og.disable();
        try {
            og.enable();
            sender.sendMessage(Component.text("[OpGuard] Reload berhasil.", NamedTextColor.GREEN));
        } catch (Exception e) {
            sender.sendMessage(Component.text("[OpGuard] Reload gagal: " + e.getMessage(), NamedTextColor.RED));
        }
    }

    // -------------------------------------------------------------------------
    // Authorization check for sensitive ops (add/remove/import)
    // -------------------------------------------------------------------------

    /**
     * Check if the sender is authorized for sensitive manage operations.
     * Rules:
     * - Default: ONLY from console (opguard.manage-from-game: false).
     * - If enabled from game: needs permission saryuguard.opguard.manage (default false, NOT op)
     *   AND 2FA status AUTHENTICATED.
     *
     * @return true if authorized, false if denied (feedback message already sent)
     */
    public boolean checkManageAuth(CommandSender sender, OpGuardModule og) {
        // Console always allowed
        if (sender instanceof ConsoleCommandSender) return true;

        // In-game management disabled by default
        if (!og.isManageFromGame()) {
            sender.sendMessage(Component.text(
                    "[OpGuard] Pengelolaan OpGuard hanya diizinkan dari console. "
                            + "Set opguard.manage-from-game: true di config.yml untuk mengizinkan dari dalam game.",
                    NamedTextColor.RED));
            return false;
        }

        // Requires specific permission: saryuguard.opguard.manage (default false, NOT op)
        if (!sender.hasPermission("saryuguard.opguard.manage")) {
            sender.sendMessage(Component.text(
                    "[OpGuard] Anda tidak memiliki izin saryuguard.opguard.manage untuk mengelola OpGuard.",
                    NamedTextColor.RED));
            return false;
        }

        // Requires 2FA AUTHENTICATED status
        if (sender instanceof Player player) {
            TwoFactorModule tfa = getTwoFactor();
            if (tfa != null && tfa.isEnabled()) {
                MfaState state = tfa.getPlayerState(player.getUniqueId());
                if (state != MfaState.AUTHENTICATED) {
                    sender.sendMessage(Component.text(
                            "[OpGuard] Anda harus menyelesaikan autentikasi 2FA sebelum mengelola OpGuard.",
                            NamedTextColor.RED));
                    return false;
                }
            }
        }

        return true;
    }

    // -------------------------------------------------------------------------
    // add
    // -------------------------------------------------------------------------

    private void handleAdd(CommandSender sender, OpGuardModule og, String[] args) {
        if (!checkManageAuth(sender, og)) return;

        if (args.length < 2) {
            sender.sendMessage(Component.text("Penggunaan: /sg opguard add <nama|uuid> [--confirm]", NamedTextColor.YELLOW));
            return;
        }

        String input = args[1];
        boolean confirm = hasFlag(args, "--confirm");

        // 1. Direct UUID input
        UUID directUuid = NameResolver.tryParseUuid(input);
        if (directUuid != null) {
            // Rule: Pemain tidak boleh menambahkan dirinya sendiri
            if (sender instanceof Player player && player.getUniqueId().equals(directUuid)) {
                sender.sendMessage(Component.text(
                        "[OpGuard] Anda tidak boleh menambahkan diri sendiri ke daftar OP sah.",
                        NamedTextColor.RED));
                return;
            }

            if (!confirm) {
                sender.sendMessage(Component.text("═══ Konfirmasi Penambahan OP Sah ═══", NamedTextColor.GOLD));
                sender.sendMessage(Component.text("UUID: ").color(NamedTextColor.YELLOW)
                        .append(Component.text(directUuid.toString(), NamedTextColor.WHITE)));
                sender.sendMessage(Component.text("Sumber: langsung (UUID)", NamedTextColor.GRAY));
                showOfflineModeWarning(sender, og);
                Component confirmBtn = Component.text("[Klik untuk Konfirmasi]", NamedTextColor.GREEN)
                        .decorate(TextDecoration.BOLD)
                        .clickEvent(ClickEvent.runCommand("/sg opguard add " + directUuid + " --confirm"))
                        .hoverEvent(HoverEvent.showText(Component.text("Klik untuk menyimpan ke daftar OP sah")));
                sender.sendMessage(confirmBtn);
                return;
            }

            doAdd(sender, og, directUuid, null, "manual UUID");
            return;
        }

        // 2. Name input
        NameResolver resolver = og.getNameResolver();
        if (resolver == null) {
            sender.sendMessage(Component.text("[OpGuard] NameResolver belum siap.", NamedTextColor.RED));
            return;
        }

        String validationError = resolver.validateName(input);
        if (validationError != null) {
            sender.sendMessage(Component.text("[OpGuard] Nama tidak valid: " + validationError, NamedTextColor.RED));
            return;
        }

        // Rule: Pencarian async, hasil dikirim balik lewat scheduler ke pengirim
        sender.sendMessage(Component.text("[OpGuard] Mencari UUID untuk '", NamedTextColor.GRAY)
                .append(Component.text(input, NamedTextColor.WHITE))
                .append(Component.text("' di sumber lokal...", NamedTextColor.GRAY)));

        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            NameResolver.ResolveResult result = resolver.resolve(input);

            Bukkit.getScheduler().runTask(plugin, () -> {
                switch (result.status()) {
                    case OK -> {
                        UUID uuid = result.uuid();
                        // Rule: Pemain tidak boleh menambahkan dirinya sendiri
                        if (sender instanceof Player player && player.getUniqueId().equals(uuid)) {
                            sender.sendMessage(Component.text(
                                    "[OpGuard] Anda tidak boleh menambahkan diri sendiri ke daftar OP sah.",
                                    NamedTextColor.RED));
                            return;
                        }

                        // Rule: Sebelum disimpan, tampilkan nama, UUID, dan sumber, lalu minta konfirmasi (--confirm)
                        if (!confirm) {
                            sender.sendMessage(Component.text("═══ Konfirmasi Penambahan OP Sah ═══", NamedTextColor.GOLD));
                            // Hasil pencarian tidak pernah diparse sebagai format
                            sender.sendMessage(Component.text("Nama: ").color(NamedTextColor.YELLOW)
                                    .append(Component.text(input, NamedTextColor.WHITE)));
                            sender.sendMessage(Component.text("UUID: ").color(NamedTextColor.YELLOW)
                                    .append(Component.text(uuid.toString(), NamedTextColor.WHITE)));

                            StringBuilder sources = new StringBuilder();
                            result.sourceResults().forEach((src, id) ->
                                    sources.append(src).append(" → ").append(shortUuid(id)).append(", "));
                            String srcSummary = sources.length() > 2 ? sources.substring(0, sources.length() - 2) : "tidak ada";
                            sender.sendMessage(Component.text("Sumber: " + srcSummary, NamedTextColor.GRAY));

                            if (result.message() != null && !result.message().isEmpty()) {
                                sender.sendMessage(Component.text("Catatan: " + result.message(), NamedTextColor.AQUA));
                            }
                            showOfflineModeWarning(sender, og);

                            Component confirmBtn = Component.text("[Klik untuk Konfirmasi]", NamedTextColor.GREEN)
                                    .decorate(TextDecoration.BOLD)
                                    .clickEvent(ClickEvent.runCommand("/sg opguard add " + uuid + " --confirm"))
                                    .hoverEvent(HoverEvent.showText(Component.text("Klik untuk mengonfirmasi penambahan ke daftar OP sah")));
                            sender.sendMessage(confirmBtn);
                        } else {
                            doAdd(sender, og, uuid, input, sourceSummary(result.sourceResults()));
                        }
                    }
                    case CONFLICT -> {
                        // Rule: Hasil berbeda antar sumber = TOLAK dan tampilkan konfliknya. Jangan memilih diam-diam.
                        sender.sendMessage(Component.text(
                                "[OpGuard] DITOLAK – Terjadi konflik UUID antar sumber untuk '" + input + "':",
                                NamedTextColor.RED).decorate(TextDecoration.BOLD));
                        result.sourceResults().forEach((src, id) ->
                                sender.sendMessage(Component.text("  • " + src + ": ", NamedTextColor.YELLOW)
                                        .append(Component.text(id.toString(), NamedTextColor.WHITE))));
                        sender.sendMessage(Component.text(
                                "Gunakan /sg opguard add <uuid> untuk menambahkan UUID yang benar secara manual.",
                                NamedTextColor.GRAY));
                    }
                    case NOT_FOUND -> {
                        // Rule: Tidak ketemu di sumber mana pun: sarankan add <uuid> dan jangan menebak.
                        sender.sendMessage(Component.text(
                                "[OpGuard] Nama '" + input + "' tidak ditemukan di sumber lokal mana pun.",
                                NamedTextColor.RED));
                        sender.sendMessage(Component.text(
                                "Sarankan: gunakan /sg opguard add <uuid> untuk menambahkan langsung tanpa menebak.",
                                NamedTextColor.YELLOW));
                    }
                    case ERROR -> {
                        // Rule: Gagal membaca sumber (ops.json hilang/rusak) = status ERROR
                        sender.sendMessage(Component.text(
                                "[OpGuard] ERROR: Gagal membaca sumber resolusi: " + result.message(),
                                NamedTextColor.RED));
                    }
                }
            });
        });
    }

    private void doAdd(CommandSender sender, OpGuardModule og, UUID uuid,
                       String name, String notes) {
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                String approver = sender instanceof Player p ? p.getName() : "CONSOLE";
                boolean isNew = og.addAllowedOp(uuid, name, approver, notes);

                Bukkit.getScheduler().runTask(plugin, () -> {
                    if (isNew) {
                        Component msg = Component.text("[OpGuard] ✓ UUID " + shortUuid(uuid), NamedTextColor.GREEN);
                        if (name != null) {
                            msg = msg.append(Component.text(" (" + name + ")", NamedTextColor.WHITE));
                        }
                        msg = msg.append(Component.text(" berhasil ditambahkan ke daftar OP sah.", NamedTextColor.GREEN));
                        sender.sendMessage(msg);
                    } else {
                        sender.sendMessage(Component.text(
                                "[OpGuard] UUID " + shortUuid(uuid) + " sudah ada di daftar OP sah (data nama/sumber diperbarui).",
                                NamedTextColor.YELLOW));
                    }
                    // Rule: `add` HANYA memasukkan UUID ke daftar OP sah. Tidak pernah memberi OP.
                    sender.sendMessage(Component.text(
                            "Pengingat: perintah ini HANYA mendaftarkan UUID ke daftar OP sah, tidak pernah memberi OP.",
                            NamedTextColor.GRAY));
                });
            } catch (Exception e) {
                Bukkit.getScheduler().runTask(plugin, () ->
                        sender.sendMessage(Component.text(
                                "[OpGuard] Gagal menyimpan data OP sah: " + e.getMessage(), NamedTextColor.RED)));
            }
        });
    }

    // -------------------------------------------------------------------------
    // remove
    // -------------------------------------------------------------------------

    private void handleRemove(CommandSender sender, OpGuardModule og, String[] args) {
        if (!checkManageAuth(sender, og)) return;

        if (args.length < 2) {
            sender.sendMessage(Component.text("Penggunaan: /sg opguard remove <nama|uuid> [--confirm]", NamedTextColor.YELLOW));
            return;
        }

        String input = args[1];
        boolean confirm = hasFlag(args, "--confirm");

        // Rule: remove dalam mode enforce akan deop pemain tersebut. Wajib --confirm, di main thread.
        if (!confirm) {
            sender.sendMessage(Component.text("═══ Konfirmasi Penghapusan OP Sah ═══", NamedTextColor.GOLD));
            sender.sendMessage(Component.text("Target: ").color(NamedTextColor.YELLOW)
                    .append(Component.text(input, NamedTextColor.WHITE)));
            if ("enforce".equalsIgnoreCase(og.getMode())) {
                sender.sendMessage(Component.text(
                        "⚠ Peringatan mode enforce: Pemain akan langsung dide-op di server jika saat ini berstatus OP!",
                        NamedTextColor.RED));
            }
            Component confirmBtn = Component.text("[Klik untuk Konfirmasi Penghapusan]", NamedTextColor.RED)
                    .decorate(TextDecoration.BOLD)
                    .clickEvent(ClickEvent.runCommand("/sg opguard remove " + input + " --confirm"))
                    .hoverEvent(HoverEvent.showText(Component.text("Klik untuk menghapus dari daftar OP sah")));
            sender.sendMessage(confirmBtn);
            return;
        }

        // 1. Direct UUID
        UUID directUuid = NameResolver.tryParseUuid(input);
        if (directUuid != null) {
            doRemove(sender, og, directUuid);
            return;
        }

        // 2. Name lookup from current database entries first
        for (OpGuardEntry entry : og.getAllEntries()) {
            if (input.equalsIgnoreCase(entry.lastKnownName())) {
                doRemove(sender, og, entry.uuid());
                return;
            }
        }

        // 3. Fallback name resolution
        NameResolver resolver = og.getNameResolver();
        if (resolver == null) {
            sender.sendMessage(Component.text("[OpGuard] NameResolver belum siap.", NamedTextColor.RED));
            return;
        }

        sender.sendMessage(Component.text("[OpGuard] Mencari UUID untuk '" + input + "'...", NamedTextColor.GRAY));
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            NameResolver.ResolveResult result = resolver.resolve(input);
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (result.status() == NameResolver.ResolveResult.Status.OK && result.uuid() != null) {
                    doRemove(sender, og, result.uuid());
                } else {
                    sender.sendMessage(Component.text(
                            "[OpGuard] Tidak dapat menemukan UUID untuk '" + input + "'. "
                                    + "Gunakan /sg opguard remove <uuid> --confirm.",
                            NamedTextColor.RED));
                }
            });
        });
    }

    private void doRemove(CommandSender sender, OpGuardModule og, UUID uuid) {
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                String approver = sender instanceof Player p ? p.getName() : "CONSOLE";
                boolean removed = og.removeAllowedOp(uuid, approver);

                Bukkit.getScheduler().runTask(plugin, () -> {
                    if (removed) {
                        sender.sendMessage(Component.text(
                                "[OpGuard] ✓ UUID " + shortUuid(uuid) + " berhasil dihapus dari daftar OP sah.",
                                NamedTextColor.GREEN));
                        if ("enforce".equalsIgnoreCase(og.getMode())) {
                            sender.sendMessage(Component.text(
                                    "Pemain telah dide-op dari server jika sedang memiliki status OP.",
                                    NamedTextColor.GRAY));
                        }
                    } else {
                        sender.sendMessage(Component.text(
                                "[OpGuard] UUID " + shortUuid(uuid) + " tidak ditemukan di daftar OP sah.",
                                NamedTextColor.YELLOW));
                    }
                });
            } catch (Exception e) {
                Bukkit.getScheduler().runTask(plugin, () ->
                        sender.sendMessage(Component.text(
                                "[OpGuard] Gagal menghapus data: " + e.getMessage(), NamedTextColor.RED)));
            }
        });
    }

    // -------------------------------------------------------------------------
    // list
    // -------------------------------------------------------------------------

    private void handleList(CommandSender sender, OpGuardModule og, String[] args) {
        int page = 1;
        if (args.length >= 2) {
            try {
                page = Integer.parseInt(args[1]);
            } catch (NumberFormatException ignored) {}
        }

        List<OpGuardEntry> entries = og.getAllEntries();
        Set<UUID> currentOps = new HashSet<>();
        for (org.bukkit.OfflinePlayer op : Bukkit.getOperators()) {
            currentOps.add(op.getUniqueId());
        }

        Paginator<OpGuardEntry> paginator = new Paginator<>(entries, LIST_PAGE_SIZE);
        Paginator.Language lang = plugin.getLocaleManager().getCurrentLocale().equalsIgnoreCase("en")
                ? Paginator.Language.ENGLISH
                : Paginator.Language.INDONESIAN;

        int safePage = paginator.clampPage(page);
        List<OpGuardEntry> pageItems = paginator.getPageItems(safePage);

        sender.sendMessage(Component.text("═══ Daftar OP Sah OpGuard (" + entries.size() + " terdaftar) ═══",
                NamedTextColor.GOLD));

        if (pageItems.isEmpty()) {
            sender.sendMessage(Component.text("  Belum ada OP sah yang terdaftar di database.", NamedTextColor.GRAY));
            sender.sendMessage(Component.text("  Gunakan /sg opguard add <nama|uuid> atau /sg opguard import.",
                    NamedTextColor.GRAY));
        } else {
            for (OpGuardEntry entry : pageItems) {
                boolean isCurrentlyOp = currentOps.contains(entry.uuid());
                String sourceDisplay = entry.notes() != null && !entry.notes().isEmpty() ? entry.notes() : "manual";

                // Item: nama, UUID singkat, sumber, penambah, tanggal, penanda "sedang OP"
                Component line = Component.text("  • ", NamedTextColor.GRAY)
                        .append(Component.text(
                                entry.lastKnownName() != null ? entry.lastKnownName() : "???",
                                NamedTextColor.WHITE))
                        .append(Component.text(" [" + shortUuid(entry.uuid()) + "]", NamedTextColor.DARK_GRAY))
                        .append(Component.text(" | src: " + sourceDisplay, NamedTextColor.GRAY))
                        .append(Component.text(" oleh ", NamedTextColor.DARK_GRAY))
                        .append(Component.text(entry.approver(), NamedTextColor.AQUA))
                        .append(Component.text(" (" + TIME_FORMAT.format(Instant.ofEpochMilli(entry.approvedAt())) + ")",
                                NamedTextColor.DARK_GRAY))
                        .append(isCurrentlyOp
                                ? Component.text(" [● SEDANG OP]", NamedTextColor.GREEN)
                                : Component.text(" [○ BUKAN OP]", NamedTextColor.DARK_GRAY))
                        .hoverEvent(HoverEvent.showText(
                                Component.text("UUID: " + entry.uuid()
                                        + "\nNama Terakhir: " + (entry.lastKnownName() != null ? entry.lastKnownName() : "-")
                                        + "\nPenambah: " + entry.approver()
                                        + "\nTanggal Disetujui: " + TIME_FORMAT.format(Instant.ofEpochMilli(entry.approvedAt()))
                                        + "\nSumber/Catatan: " + sourceDisplay
                                        + "\nSedang OP Server: " + (isCurrentlyOp ? "Ya (aktif)" : "Tidak"),
                                        NamedTextColor.GRAY)));

                sender.sendMessage(line);
            }
        }

        // Paginator interactive clickable footer
        sender.sendMessage(paginator.buildFooter(safePage, "/sg opguard list {n}", false, lang));
    }

    // -------------------------------------------------------------------------
    // import
    // -------------------------------------------------------------------------

    private void handleImport(CommandSender sender, OpGuardModule og, String[] args) {
        if (!checkManageAuth(sender, og)) return;

        boolean confirm = hasFlag(args, "--confirm");
        if (!confirm) {
            sender.sendMessage(Component.text("═══ Konfirmasi Import OP Baseline ═══", NamedTextColor.GOLD));
            sender.sendMessage(Component.text(
                    "[OpGuard] Perintah import akan menyalin semua entri OP yang saat ini terdaftar di ops.json "
                            + "ke daftar OP sah SaryuGuard.",
                    NamedTextColor.YELLOW));
            sender.sendMessage(Component.text(
                    "Gunakan: /sg opguard import --confirm", NamedTextColor.YELLOW));
            showOfflineModeWarning(sender, og);

            Component confirmBtn = Component.text("[Klik untuk Konfirmasi Import]", NamedTextColor.GREEN)
                    .decorate(TextDecoration.BOLD)
                    .clickEvent(ClickEvent.runCommand("/sg opguard import --confirm"))
                    .hoverEvent(HoverEvent.showText(Component.text("Klik untuk mengimpor daftar dari ops.json")));
            sender.sendMessage(confirmBtn);
            return;
        }

        sender.sendMessage(Component.text("[OpGuard] Membaca dan mengimpor dari ops.json...", NamedTextColor.GRAY));
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                String approver = sender instanceof Player p ? p.getName() : "CONSOLE";
                int imported = og.importFromOpsJson(approver);

                Bukkit.getScheduler().runTask(plugin, () ->
                        sender.sendMessage(Component.text(
                                "[OpGuard] ✓ Import berhasil. " + imported + " entri OP baru ditambahkan ke daftar sah.",
                                NamedTextColor.GREEN)));
            } catch (Exception e) {
                Bukkit.getScheduler().runTask(plugin, () ->
                        sender.sendMessage(Component.text(
                                "[OpGuard] Gagal mengimpor dari ops.json: " + e.getMessage(), NamedTextColor.RED)));
            }
        });
    }

    // -------------------------------------------------------------------------
    // Tab complete
    // -------------------------------------------------------------------------

    @Override
    public List<String> tabComplete(CommandSender sender, String[] args) {
        if (args.length == 1) {
            String prefix = args[0].toLowerCase(Locale.ROOT);
            return List.of("info", "reload", "add", "remove", "list", "import").stream()
                    .filter(s -> s.startsWith(prefix))
                    .toList();
        }

        if (args.length == 2) {
            String sub = args[0].toLowerCase(Locale.ROOT);
            String prefix = args[1].toLowerCase(Locale.ROOT);

            if ("add".equals(sub) || "remove".equals(sub)) {
                Set<String> suggestions = new LinkedHashSet<>();

                // Rule: Tab-complete dari pemain online + nama di ops.json
                for (Player p : Bukkit.getOnlinePlayers()) {
                    if (p.getName().toLowerCase(Locale.ROOT).startsWith(prefix)) {
                        suggestions.add(p.getName());
                    }
                }

                OpGuardModule og = getOpGuard();
                if (og != null && og.getNameResolver() != null) {
                    try {
                        for (String name : og.getNameResolver().getOpsJsonNames()) {
                            if (name.toLowerCase(Locale.ROOT).startsWith(prefix)) {
                                suggestions.add(name);
                            }
                        }
                    } catch (Throwable ignored) {}
                }

                // For remove, also suggest last known names from existing entries
                if ("remove".equals(sub) && og != null) {
                    for (OpGuardEntry entry : og.getAllEntries()) {
                        if (entry.lastKnownName() != null
                                && entry.lastKnownName().toLowerCase(Locale.ROOT).startsWith(prefix)) {
                            suggestions.add(entry.lastKnownName());
                        }
                    }
                }

                return new ArrayList<>(suggestions);
            }

            if ("list".equals(sub)) {
                return List.of("1", "2", "3");
            }
        }

        if (args.length == 3) {
            String sub = args[0].toLowerCase(Locale.ROOT);
            if ("remove".equals(sub) || "import".equals(sub) || "add".equals(sub)) {
                String prefix = args[2].toLowerCase(Locale.ROOT);
                if ("--confirm".startsWith(prefix)) {
                    return List.of("--confirm");
                }
            }
        }

        return List.of();
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private OpGuardModule getOpGuard() {
        SaryuGuardModule m = plugin.getModule("opguard");
        return m instanceof OpGuardModule ? (OpGuardModule) m : null;
    }

    private TwoFactorModule getTwoFactor() {
        SaryuGuardModule m = plugin.getModule("2fa");
        return m instanceof TwoFactorModule ? (TwoFactorModule) m : null;
    }

    private static boolean hasFlag(String[] args, String flag) {
        for (String a : args) {
            if (flag.equalsIgnoreCase(a)) return true;
        }
        return false;
    }

    public static String shortUuid(UUID uuid) {
        if (uuid == null) return "???";
        String s = uuid.toString();
        return s.substring(0, 8) + "…";
    }

    private void showOfflineModeWarning(CommandSender sender, OpGuardModule og) {
        if (!og.isOnlineMode()) {
            sender.sendMessage(Component.text(
                    "⚠ PERINGATAN: Server dalam mode offline (online-mode=false). "
                            + "UUID diturunkan dari nama, sehingga daftar OP tidak melindungi dari peniruan nama. "
                            + "Sangat disarankan mengaktifkan AuthMe + 2FA.",
                    NamedTextColor.RED));
        }
    }

    private static String sourceSummary(Map<String, UUID> sources) {
        if (sources == null || sources.isEmpty()) return "manual";
        return String.join(", ", sources.keySet());
    }
}
