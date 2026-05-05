package top.miragedge.abcq;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.FireworkEffect;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Firework;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.inventory.meta.FireworkMeta;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

/**
 * FE_ABCQ - 知识问答插件
 * 支持 Paper 1.18+ 至最新版本及 Folia 服务端
 */
@SuppressWarnings("deprecation")
public final class FE_ABCQ extends JavaPlugin implements Listener {

    private static final int FIREWORK_REMOVE_DELAY_TICKS = 60;

    private FileConfiguration messagesConfig;

    // 调度器适配器 - 兼容 Folia 和 Paper
    private SchedulerAdapter schedulerAdapter;

    private volatile String currentQuestion;
    // 使用 ConcurrentHashMap 替代 HashSet 以提高并发性能
    private volatile ConcurrentHashMap<String, String> correctAnswers; // answer -> originalAnswer
    private volatile boolean isQuestionActive = false;
    private volatile Player lastCorrectPlayer = null;

    private MiniMessage miniMessage;
    private DatabaseManager databaseManager;

    private List<String> cachedQuestions = null;

    @Override
    public void onEnable() {
        miniMessage = MiniMessage.miniMessage();

        // 初始化调度器适配器（始终需要）
        schedulerAdapter = new SchedulerAdapter(this, getLogger());

        try {
            initializeDatabase();
            saveDefaultConfig();
            loadMessages();
            cacheQuestions();

            getServer().getPluginManager().registerEvents(this, this);
        } catch (Exception e) {
            getLogger().severe("插件配置加载失败: " + e.getMessage());
            for (StackTraceElement element : e.getStackTrace()) {
                getLogger().severe(element.toString());
            }
            // 使用默认配置
            messagesConfig = new YamlConfiguration();
            cacheQuestions();
        }

        startQuestionTask();

        Objects.requireNonNull(getCommand("feabcq")).setExecutor(this);
        Objects.requireNonNull(getCommand("feabcq")).setTabCompleter(this);

        @SuppressWarnings("deprecation")
        String version = getDescription().getVersion();
        getLogger().info("知识问答插件已启用 (v" + version + ")，加载了 " + cachedQuestions.size() + " 个问题");
        getLogger().info("服务端类型: " + (schedulerAdapter.isFolia() ? "Folia" : "Paper/Spigot"));
    }

    private void cacheQuestions() {
        List<String> questions = getConfig().getStringList("options.questions");
        List<String> validQuestions = new java.util.ArrayList<>();

        for (String q : questions) {
            if (q != null && !q.trim().isEmpty()) {
                String[] parts = q.split("\\|");
                if (parts.length >= 2) {
                    boolean hasValidAnswer = false;
                    for (int i = 1; i < parts.length; i++) {
                        if (parts[i] != null && !parts[i].trim().isEmpty()) {
                            hasValidAnswer = true;
                            break;
                        }
                    }
                    if (hasValidAnswer) {
                        validQuestions.add(q);
                    }
                }
            }
        }

        if (validQuestions.isEmpty()) {
            validQuestions.addAll(Arrays.asList(
                    "地球的卫星是什么？|月球|月亮",
                    "水的化学式是什么？|H2O|h2o",
                    "中国的首都是哪里？|北京",
                    "太阳系中最大的行星是？|木星",
                    "《红楼梦》的作者是谁？|曹雪芹"
            ));
        }
        cachedQuestions = validQuestions;
    }

    @Override
    public void onDisable() {
        if (schedulerAdapter != null) {
            schedulerAdapter.cancelAllTasks();
        }
        if (databaseManager != null) {
            databaseManager.disconnect();
        }
        getLogger().info("知识问答插件已禁用");
    }

    private void initializeDatabase() {
        try {
            File dataFolder = getDataFolder();
            if (!dataFolder.exists()) {
                boolean created = dataFolder.mkdirs();
                if (!created) {
                    getLogger().warning("无法创建数据文件夹: " + dataFolder.getAbsolutePath());
                }
            }
            File dbFile = new File(dataFolder, "player_stats.db");
            databaseManager = new DatabaseManager(dbFile.getAbsolutePath(), getLogger());
            databaseManager.connect();
            getLogger().info("数据库连接成功");
        } catch (SQLException e) {
            getLogger().severe("无法连接到数据库: " + e.getMessage());
            for (StackTraceElement element : e.getStackTrace()) {
                getLogger().severe(element.toString());
            }
        }
    }

    private void loadMessages() {
        File messagesFile = new File(getDataFolder(), "messages.yml");
        if (!messagesFile.exists()) {
            saveResource("messages.yml", false);
        }
        messagesConfig = YamlConfiguration.loadConfiguration(messagesFile);
    }

    public void reloadConfigs() {
        reloadConfig();
        loadMessages();
        cacheQuestions();

        getLogger().info("配置文件重载完成，加载了 " + cachedQuestions.size() + " 个问题");

        if (schedulerAdapter != null) {
            schedulerAdapter.cancelQuestionTask();
        }
        startQuestionTask();
    }

    private void startQuestionTask() {
        int interval = Math.max(1, getConfig().getInt("question.interval", 300));

        schedulerAdapter.runQuestionTask(() -> {
            int minPlayers = getConfig().getInt("question.min_players", 4);
            if (Bukkit.getOnlinePlayers().size() < minPlayers) {
                return;
            }
            askRandomQuestion();
        }, interval);
    }

    private void askRandomQuestion() {
        if (cachedQuestions.isEmpty()) {
            getLogger().warning("没有可用的问题列表");
            return;
        }

        String questionLine = cachedQuestions.get(ThreadLocalRandom.current().nextInt(cachedQuestions.size()));
        String[] parts = questionLine.split("\\|");
        if (parts.length < 2) return;

        String answerMode = getConfig().getString("options.answer_mode", "timed");

        // 如果当前有活跃问题，需要先结束它
        if (isQuestionActive) {
            // 只有在 timed 模式下且无人答对时，才发送"无人回答"消息
            if ("timed".equals(answerMode) && lastCorrectPlayer == null) {
                Component noAnswerMsg = deserializeMessage("no_answer");
                broadcastMessage(noAnswerMsg);
            }
            // 取消之前的超时任务
            schedulerAdapter.cancelAnswerTimeoutTask();
            // 重置当前问题状态
            synchronized (this) {
                isQuestionActive = false;
                currentQuestion = null;
                correctAnswers = null;
                lastCorrectPlayer = null;
            }
        }

        String newQuestion = parts[0];
        ConcurrentHashMap<String, String> newCorrectAnswers = new ConcurrentHashMap<>();

        for (int i = 1; i < parts.length; i++) {
            String answer = parts[i];
            if (answer != null) {
                String trimmedAnswer = answer.trim();
                if (!trimmedAnswer.isEmpty()) {
                    boolean ignoreCase = getConfig().getBoolean("options.ignore_case", true);
                    String key = ignoreCase ? trimmedAnswer.toLowerCase() : trimmedAnswer;
                    newCorrectAnswers.put(key, trimmedAnswer);
                }
            }
        }
        if (newCorrectAnswers.isEmpty()) return;

        synchronized (this) {
            currentQuestion = newQuestion;
            correctAnswers = newCorrectAnswers;
            isQuestionActive = true;
            lastCorrectPlayer = null;
        }

        // 广播问题
        String questionToBroadcast = currentQuestion;
        if (questionToBroadcast != null) {
            Component msg = deserializeMessage("question_message",
                    Placeholder.parsed("question", questionToBroadcast));
            broadcastMessage(msg);
        }

        // 仅在 timed 模式下设置时间限制
        if ("timed".equals(answerMode)) {
            int timeLimit = Math.max(1, getConfig().getInt("options.answer_time_limit", 30));
            schedulerAdapter.runAnswerTimeoutTask(() -> {
                synchronized (FE_ABCQ.this) {
                    if (isQuestionActive) {
                        if (lastCorrectPlayer == null) {
                            Component noAnswerMsg = deserializeMessage("no_answer");
                            broadcastMessage(noAnswerMsg);
                        }
                        isQuestionActive = false;
                        currentQuestion = null;
                        correctAnswers = null;
                        lastCorrectPlayer = null;
                    }
                }
            }, timeLimit);
        }
    }

    /**
     * 广播消息给所有在线玩家 - 性能优化版本
     */
    private void broadcastMessage(Component message) {
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (player != null) {
                player.sendMessage(message);
            }
        }
    }

    /**
     * 异步聊天事件处理 - 兼容 Paper 1.19.4+
     * 注意: AsyncPlayerChatEvent 在 Paper 中已被标记为弃用，
     * 但它仍然向后兼容。在 Paper 1.19.4+ 中，聊天默认是异步的。
     * 这里使用同步处理以确保线程安全。
     */
    @EventHandler(priority = EventPriority.HIGH)
    @SuppressWarnings("deprecation")
    public void onPlayerChat(AsyncPlayerChatEvent event) {
        // 如果是异步事件，需要同步处理
        if (event.isAsynchronous()) {
            // 对于异步事件，我们需要在主线程中处理
            // 使用同步任务来确保线程安全
            Bukkit.getScheduler().runTask(this, () -> handleChatEvent(event));
        } else {
            handleChatEvent(event);
        }
    }

    /**
     * 实际处理聊天事件的逻辑
     * 注意：此方法在主线程调用（通过 runTask 调度）
     */
    @SuppressWarnings("deprecation")
    private void handleChatEvent(AsyncPlayerChatEvent event) {
        // 获取当前正确答案的快照用于检查和匹配
        ConcurrentHashMap<String, String> currentAnswers;
        boolean questionActive;

        synchronized (this) {
            questionActive = isQuestionActive;
            currentAnswers = correctAnswers;
        }

        if (!questionActive || currentAnswers == null) {
            return;
        }

        Player player = event.getPlayer();
        String message = event.getMessage();

        // event.getMessage() 返回的消息不会为空，但可能只包含空白字符
        if (message.trim().isEmpty()) {
            return;
        }

        boolean ignoreCase = getConfig().getBoolean("options.ignore_case", true);
        String checkMessage = ignoreCase ? message.toLowerCase() : message;

        // 使用当前答案快照进行查找（线程安全）
        String matchedAnswer = currentAnswers.get(checkMessage);
        boolean isCorrect = matchedAnswer != null;

        if (isCorrect) {
            event.setCancelled(true);

            // 只在需要时同步 lastCorrectPlayer
            boolean wasFirstToAnswer;
            synchronized (this) {
                wasFirstToAnswer = (lastCorrectPlayer == null);
                if (wasFirstToAnswer) {
                    lastCorrectPlayer = player;
                    isQuestionActive = false;
                    correctAnswers = null;
                }
            }

            if (wasFirstToAnswer) {
                updatePlayerStats(player.getUniqueId().toString(), player.getName(), true);
                giveRewards(player);
                showSuccessEffects(player);
                sendActionBarStats(player);
                broadcastSuccessMessage(player);
            } else {
                player.sendActionBar(deserializeMessage("wrong_answer_actionbar"));
            }
        } else {
            updatePlayerStats(player.getUniqueId().toString(), player.getName(), false);
        }
    }

    private void updatePlayerStats(String uuid, String name, boolean isCorrect) {
        try {
            databaseManager.updatePlayerStats(uuid, name, isCorrect);
        } catch (SQLException e) {
            getLogger().severe("更新玩家统计失败: " + e.getMessage());
        }
    }

    private void sendActionBarStats(Player player) {
        try {
            DatabaseManager.PlayerStats stats = databaseManager.getPlayerStats(player.getUniqueId().toString());
            Component actionBarMsg = deserializeMessage("actionbar_stats",
                    Placeholder.parsed("correct_count", String.valueOf(stats.correctAnswers())),
                    Placeholder.parsed("accuracy_rate", String.format("%.1f", stats.getAccuracyRate())));
            player.sendActionBar(actionBarMsg);
        } catch (SQLException e) {
            getLogger().severe("获取玩家统计失败: " + e.getMessage());
            Component defaultMsg = deserializeMessage("actionbar_stats",
                    Placeholder.parsed("correct_count", "0"),
                    Placeholder.parsed("accuracy_rate", "0.0"));
            player.sendActionBar(defaultMsg);
        }
    }

    private void giveRewards(Player player) {
        if (player == null || getConfig().getBoolean("rewards.disabled", false)) {
            return;
        }
        int exp = getConfig().getInt("rewards.experience", 10);
        if (exp > 0) {
            player.giveExp(exp);
        }
        List<String> commands = getConfig().getStringList("rewards.commands");
        if (commands.isEmpty()) return;

        String playerName = player.getName();
        String expStr = String.valueOf(exp);
        for (String cmd : commands) {
            if (cmd != null && !cmd.trim().isEmpty()) {
                try {
                    String processedCmd = cmd.replace("{player}", playerName)
                            .replace("{reward}", expStr);
                    Bukkit.dispatchCommand(Bukkit.getConsoleSender(), processedCmd);
                } catch (Exception e) {
                    getLogger().warning("执行奖励命令失败: " + cmd + " - " + e.getMessage());
                }
            }
        }
    }

    private void showSuccessEffects(Player player) {
        if (player == null || player.isDead() || !player.isOnline()) {
            return;
        }

        Location loc = player.getLocation();
        World world = loc.getWorld();
        if (world == null) {
            return;
        }

        try {
            // 粒子效果
            if (getConfig().getBoolean("effects.particles", true)) {
                double offsetX = (ThreadLocalRandom.current().nextGaussian() - 0.5) * 2;
                double offsetY = Math.abs(ThreadLocalRandom.current().nextGaussian()) * 2;
                double offsetZ = (ThreadLocalRandom.current().nextGaussian() - 0.5) * 2;
                world.spawnParticle(Particle.HAPPY_VILLAGER,
                        loc.getX() + offsetX, loc.getY() + offsetY, loc.getZ() + offsetZ,
                        25, 0, 0, 0, 0);
            }

            // 声音效果
            if (getConfig().getBoolean("effects.sounds", true)) {
                player.playSound(loc, Sound.ENTITY_PLAYER_LEVELUP, 1.0f, 1.0f);
            }

            // 烟花效果
            if (getConfig().getBoolean("effects.fireworks", true)) {
                // 检查玩家是否在户外（检查上方 10 格内是否有非空气方块）
                boolean isOutdoor = true;
                int maxHeight = world.getMaxHeight();
                int blockX = loc.getBlockX();
                int blockY = loc.getBlockY();
                int blockZ = loc.getBlockZ();

                for (int y = blockY + 1; y <= blockY + 10 && y < maxHeight; y++) {
                    try {
                        if (!world.getBlockAt(blockX, y, blockZ).getType().isAir()) {
                            isOutdoor = false;
                            break;
                        }
                    } catch (Exception e) {
                        // 世界加载问题时假设在户外
                        break;
                    }
                }

                if (!isOutdoor) {
                    return;
                }

                // 生成烟花
                Firework firework = (Firework) world.spawnEntity(loc, org.bukkit.entity.EntityType.FIREWORK_ROCKET);
                FireworkMeta fireworkMeta = firework.getFireworkMeta();

                FireworkEffect effect = FireworkEffect.builder()
                        .with(FireworkEffect.Type.STAR)
                        .withColor(Color.YELLOW, Color.RED, Color.BLUE)
                        .withFade(Color.fromRGB(0x9B59B6), Color.fromRGB(0x00CED1))
                        .flicker(true)
                        .trail(true)
                        .build();

                fireworkMeta.addEffect(effect);
                fireworkMeta.setPower(1);
                firework.setFireworkMeta(fireworkMeta);

                // 延迟删除烟花
                schedulerAdapter.runFireworkRemoveTask(() -> {
                    if (firework.isValid() && !firework.isDead()) {
                        firework.remove();
                    }
                }, FIREWORK_REMOVE_DELAY_TICKS);
            }
        } catch (Exception e) {
            getLogger().warning("显示成功效果时发生异常: " + e.getMessage());
        }
    }

    private void broadcastSuccessMessage(Player player) {
        Component msg = deserializeMessage("correct_answer_broadcast",
                Placeholder.parsed("player", player.getName()));
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (!p.getUniqueId().equals(player.getUniqueId())) {
                p.sendMessage(msg);
            }
        }
        Component playerMsg = deserializeMessage("correct_answer_player",
                Placeholder.parsed("reward", String.valueOf(getConfig().getInt("rewards.experience", 10))));
        player.sendMessage(playerMsg);
    }

    private Component deserializeMessage(String key, TagResolver... placeholders) {
        String message = messagesConfig.getString(key, "<red>Message not found: " + key + "</red>");
        return miniMessage.deserialize(message, placeholders);
    }

    @Override
    @SuppressWarnings("all")
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (command.getName().equalsIgnoreCase("feabcq")) {
            if (args.length == 0) {
                List<String> help = messagesConfig.getStringList("help_message");
                for (String line : help) {
                    sender.sendMessage(miniMessage.deserialize(line));
                }
                return true;
            }
            if ("reload".equalsIgnoreCase(args[0])) {
                if (!sender.hasPermission("feabcq.reload")) {
                    sender.sendMessage(deserializeMessage("no_permission"));
                    return true;
                }
                try {
                    reloadConfigs();
                    sender.sendMessage(deserializeMessage("reload_success"));
                } catch (Exception e) {
                    sender.sendMessage(deserializeMessage("reload_error"));
                    getLogger().severe("Error reloading configuration: " + e.getMessage());
                }
                return true;
            }
            if ("help".equalsIgnoreCase(args[0])) {
                List<String> help = messagesConfig.getStringList("help_message");
                for (String line : help) {
                    sender.sendMessage(miniMessage.deserialize(line));
                }
                return true;
            }
        }
        return false;
    }

    @Override
    @SuppressWarnings("all")
    public @Nullable List<String> onTabComplete(CommandSender sender, Command command, String label, String[] args) {
        if (command.getName().equalsIgnoreCase("feabcq")) {
            if (args.length == 1) {
                return Arrays.asList("reload", "help");
            }
        }
        return null;
    }
}
