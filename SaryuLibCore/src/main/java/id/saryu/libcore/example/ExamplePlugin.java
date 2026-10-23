package id.saryu.libcore.example;

import id.saryu.libcore.SaryuLib;
import id.saryu.libcore.command.CommandFramework;
import id.saryu.libcore.config.ConfigModule;
import id.saryu.libcore.database.DatabaseModule;
import id.saryu.libcore.language.LanguageManager;
import id.saryu.libcore.message.MessageModule;
import id.saryu.libcore.scheduler.SchedulerModule;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Minimal template plugin (< 100 lines) showing how to use SaryuLibCore:
 * config, language, messages, database, scheduler, and command framework.
 * Copy this to create a new Saryu plugin.
 */
public class ExamplePlugin extends JavaPlugin {

    private SaryuLib lib;
    private ConfigModule config;
    private LanguageManager lang;
    private MessageModule msg;
    private DatabaseModule db;
    private SchedulerModule sched;

    @Override
    public void onEnable() {
        // Require SaryuLibCore (declare depend: SaryuLibCore in plugin.yml).
        lib = SaryuLib.getInstanceSafe();
        if (lib == null) {
            getLogger().severe("SaryuLibCore is not loaded!");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        // Give this plugin its own config + language files (plugins/<name>/).
        config = new ConfigModule(this, "config.yml");
        lang = new LanguageManager(this, config);
        msg = lib.getMessageModule();
        db = lib.getDatabaseModule();
        sched = lib.getSchedulerModule();

        // Register a plugin-owned command root: /example ping
        CommandFramework.Root root = lib.getCommandFramework()
                .root("example", "example.admin", "Example plugin command");
        root.register(new CommandFramework.SubCommand() {
            @Override public String name() { return "ping"; }
            @Override public String permission() { return "example.ping"; }
            @Override public String description() { return "Example ping"; }
            @Override public String usage() { return "/example ping"; }
            @Override public boolean playerOnly() { return false; }
            @Override public int cooldownSeconds() { return 0; }
            @Override public void execute(org.bukkit.command.CommandSender sender,
                                          org.bukkit.command.Command command, String label, String[] args) {
                sender.sendMessage(msg.parse("&aPong! Config language=" + config.getString("language", "eng_US")));
            }
        });
        root.bind(this);

        // Async DB write example.
        sched.runAsync(() -> {
            db.asyncVoid(c -> {
                try {
                    try (var ps = c.prepareStatement("CREATE TABLE IF NOT EXISTS example_test (id INTEGER PRIMARY KEY)")) {
                        ps.execute();
                    }
                } catch (java.sql.SQLException ex) {
                    getLogger().warning("DB example failed: " + ex.getMessage());
                }
            }).exceptionally(ex -> { getLogger().warning("DB example failed: " + ex.getMessage()); return null; });
        });

        getLogger().info("ExamplePlugin enabled.");
    }

    @Override
    public void onDisable() {
        getLogger().info("ExamplePlugin disabled.");
    }
}
