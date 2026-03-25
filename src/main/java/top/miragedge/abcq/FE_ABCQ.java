package top.miragedge.abcq;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.*;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerChatEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.sql.SQLException;
import java.util.*;
import java.util.concurrent.ThreadLocalRandom;

public final class FE_ABCQ extends JavaPlugin implements Listener {

    private FileConfiguration messagesConfig;
    private File messagesFile;
    private io.papermc.paper.threadedregions.scheduler.ScheduledTask questionTask;
    private io.papermc.paper.threadedregions.scheduler.ScheduledTask answerTimeoutTask;

    private volatile String currentQuestion;
    private volatile Set<String> correctAnswers;
    private volatile boolean isQuestionActive = false;
    private volatile Player lastCorrectPlayer = null;

    private MiniMessage miniMessage;
    private DatabaseManager databaseManager;

    private List<String> cachedQuestions = null;

    @Override
    public void onEnable() {
        miniMessage = MiniMessage.miniMessage();
        initializeDatabase();
        saveDefaultConfig();
        loadMessages();
        cacheQuestions();
        getServer().getPluginManager().registerEvents(this, this);
        startQuestionTask();
        Objects.requireNonNull(getCommand("feabcq")).setExecutor(this);
        Objects.requireNonNull(getCommand("feabcq")).setTabCompleter(this);
        getLogger().info("知识问答插件已启用，加载了 " + cachedQuestions.size() + " 个问题");
    }

    private void cacheQuestions() {
        List<String> questions = getConfig().getStringList("options.questions");
        cachedQuestions = new ArrayList<>();
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
                        cachedQuestions.add(q);
                    }
                }
            }
        }
        if (cachedQuestions.isEmpty()) {
            cachedQuestions = Arrays.asList(
                    "地球的卫星是什么？|月球|月亮",
                    "水的化学式是什么？|H2O|h2o",
                    "中国的首都是哪里？|北京",
                    "太阳系中最大的行星是？|木星",
                    "《红楼梦》的作者是谁？|曹雪芹"
            );
        }
    }

    @Override
    public void onDisable() {
        if (questionTask != null) {
            questionTask.cancel();
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
        messagesFile = new File(getDataFolder(), "messages.yml");
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
        if (questionTask != null) {
            questionTask.cancel();
        }
        startQuestionTask();
    }

    private void startQuestionTask() {
        int interval = getConfig().getInt("question.interval", 300);
        questionTask = getServer().getGlobalRegionScheduler().runAtFixedRate(this, task -> {
            int minPlayers = getConfig().getInt("question.min_players", 4);
            if (getServer().getOnlinePlayers().size() < minPlayers) {
                return;
            }
            askRandomQuestion(); // 关键修改：不再检查 isQuestionActive
        }, 20L * interval, 20L * interval);
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
                for (Player player : getServer().getOnlinePlayers()) {
                    if (player != null) {
                        player.sendMessage(noAnswerMsg);
                    }
                }
            }
            // 取消之前的超时任务
            if (answerTimeoutTask != null) {
                answerTimeoutTask.cancel();
                answerTimeoutTask = null;
            }
            // 重置当前问题状态
            synchronized(this) {
                isQuestionActive = false;
                currentQuestion = null;
                correctAnswers = null;
                lastCorrectPlayer = null;
            }
        }

        String newQuestion = parts[0];
        Set<String> newCorrectAnswers = new HashSet<>();
        for (int i = 1; i < parts.length; i++) {
            String answer = parts[i];
            if (answer != null) {
                String trimmedAnswer = answer.trim();
                if (!trimmedAnswer.isEmpty()) {
                    newCorrectAnswers.add(trimmedAnswer);
                }
            }
        }
        if (newCorrectAnswers.isEmpty()) return;

        synchronized (this) {
            currentQuestion = newQuestion;
            correctAnswers = newCorrectAnswers;
            isQuestionActive = true;
            lastCorrectPlayer = null; // 重置上一个答对的玩家
        }

        // 广播问题
        String questionToBroadcast = currentQuestion;
        if (questionToBroadcast != null) {
            Component msg = deserializeMessage("question_message",
                    Placeholder.parsed("question", questionToBroadcast));
            for (Player player : getServer().getOnlinePlayers()) {
                if (player != null) {
                    player.sendMessage(msg);
                }
            }
        }

        // 仅在 timed 模式下设置时间限制
        if ("timed".equals(answerMode)) {
            int timeLimit = getConfig().getInt("options.answer_time_limit", 30);
            answerTimeoutTask = getServer().getGlobalRegionScheduler().runDelayed(this, task -> {
                synchronized (FE_ABCQ.this) {
                    if (isQuestionActive) {
                        if (lastCorrectPlayer == null) {
                            Component noAnswerMsg = deserializeMessage("no_answer");
                            for (Player p : getServer().getOnlinePlayers()) {
                                if (p != null) {
                                    p.sendMessage(noAnswerMsg);
                                }
                            }
                        }
                        isQuestionActive = false;
                        currentQuestion = null;
                        correctAnswers = null;
                        lastCorrectPlayer = null;
                    }
                }
                answerTimeoutTask = null;
            }, 20L * timeLimit);
        }
    }

    @EventHandler
    public void onPlayerChat(PlayerChatEvent event) {
        if (!isQuestionActive || currentQuestion == null || correctAnswers == null) {
            return;
        }

        Player player = event.getPlayer();
        String message = event.getMessage();
        if (message == null || message.trim().isEmpty()) {
            return;
        }

        boolean ignoreCase = getConfig().getBoolean("options.ignore_case", true);
        String checkMessage = ignoreCase ? message.toLowerCase() : message;

        boolean isCorrect = false;
        String matchedAnswer = null;

        Set<String> answers = correctAnswers;
        if (answers != null) {
            for (String answer : answers) {
                if (answer == null) continue;
                String checkAnswer = ignoreCase ? answer.toLowerCase() : answer;
                // 使用精确匹配：玩家输入的消息必须完整匹配某个答案
                if (checkMessage.equals(checkAnswer)) {
                    isCorrect = true;
                    matchedAnswer = answer;
                    break;
                }
            }
        }

        if (isCorrect) {
            event.setCancelled(true);

            boolean wasFirstToAnswer;
            synchronized (this) {
                wasFirstToAnswer = (lastCorrectPlayer == null);
                if (wasFirstToAnswer) {
                    lastCorrectPlayer = player;
                    isQuestionActive = false;
                    currentQuestion = null;
                    correctAnswers = null;
                }
            }

            if (wasFirstToAnswer) {
                // 只有第一个答对的玩家才获得奖励和统计
                updatePlayerStats(player.getUniqueId().toString(), player.getName(), true);
                giveRewards(player);
                showSuccessEffects(player);
                sendActionBarStats(player);
                broadcastSuccessMessage(player, matchedAnswer);
            } else {
                // 慢了一步的玩家收到提示，但不算作答错
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
            for (StackTraceElement element : e.getStackTrace()) {
                getLogger().severe(element.toString());
            }
        }
    }

    private void sendActionBarStats(Player player) {
        try {
            DatabaseManager.PlayerStats stats = databaseManager.getPlayerStats(player.getUniqueId().toString());
            Component actionBarMsg = deserializeMessage("actionbar_stats",
                    Placeholder.parsed("correct_count", String.valueOf(stats.correctAnswers)),
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
                String processedCmd = cmd.replace("{player}", playerName)
                        .replace("{reward}", expStr);
                getServer().dispatchCommand(getServer().getConsoleSender(), processedCmd);
            }
        }
    }

    private void showSuccessEffects(Player player) {
        if (player == null || player.isDead() || !player.isOnline()) {
            return;
        }

        Location loc = player.getLocation();
        World world = player.getWorld();
        if (world == null) return;

        try {
            // 粒子效果（始终播放）- 优化为单次调用
            if (getConfig().getBoolean("effects.particles", true)) {
                Random random = ThreadLocalRandom.current();
                double offsetX = (random.nextGaussian() - 0.5) * 2;
                double offsetY = Math.abs(random.nextGaussian()) * 2;
                double offsetZ = (random.nextGaussian() - 0.5) * 2;
                world.spawnParticle(Particle.HAPPY_VILLAGER,
                        loc.getX() + offsetX, loc.getY() + offsetY, loc.getZ() + offsetZ,
                        25, 0, 0, 0, 0);
            }

            // 声音效果（始终播放）
            if (getConfig().getBoolean("effects.sounds", true)) {
                player.playSound(loc, "entity.player.levelup", 1.0f, 1.0f);
            }

            // 烟花效果（仅在户外生成）
            if (getConfig().getBoolean("effects.fireworks", true)) {
                // 检查玩家是否在户外
                boolean isOutdoor = true;
                for (int y = loc.getBlockY() + 1; y <= loc.getBlockY() + 10 && y < world.getMaxHeight(); y++) {
                    if (!world.getBlockAt(loc.getBlockX(), y, loc.getBlockZ()).getType().isAir()) {
                        isOutdoor = false;
                        break;
                    }
                }

                if (!isOutdoor) {
                    // 室内：不生成烟花，只生成彩色粒子（可选，但您希望室内取消烟花，这里不添加额外粒子）
                    // 如果您希望室内也有粒子点缀，可以取消下面注释：
                    // world.spawnParticle(Particle.FIREWORK, loc, 50, 1.5, 1.5, 1.5, 0.1);
                    // world.spawnParticle(Particle.FLASH, loc, 5, 0.5, 0.5, 0.5, 0);
                    return; // 直接返回，不执行后续烟花生成
                }

                // 户外：生成烟花火箭
                org.bukkit.entity.Firework firework = (org.bukkit.entity.Firework) world.spawnEntity(loc, org.bukkit.entity.EntityType.FIREWORK_ROCKET);
                org.bukkit.inventory.meta.FireworkMeta fireworkMeta = firework.getFireworkMeta();

                FireworkEffect effect = FireworkEffect.builder()
                        .with(FireworkEffect.Type.STAR)
                        .withColor(Color.YELLOW, Color.RED, Color.BLUE)
                        .withFade(Color.PURPLE, Color.AQUA)
                        .flicker(true)
                        .trail(true)
                        .build();

                fireworkMeta.addEffect(effect);
                fireworkMeta.setPower(1);
                firework.setFireworkMeta(fireworkMeta);

                // 延迟删除烟花实体
                getServer().getGlobalRegionScheduler().runDelayed(this, task -> {
                    if (firework.isValid() && !firework.isDead()) {
                        firework.remove();
                    }
                }, 60L);
            }
        } catch (Exception e) {
            // 捕获所有异常，防止中断答对后续流程（消息发送等）
            getLogger().warning("显示成功效果时发生异常: " + e.getMessage());
            for (StackTraceElement element : e.getStackTrace()) {
                getLogger().warning(element.toString());
            }
        }
    }

    private void broadcastSuccessMessage(Player player, String answer) {
        Component msg = deserializeMessage("correct_answer_broadcast",
                Placeholder.parsed("player", player.getName()));
        for (Player p : getServer().getOnlinePlayers()) {
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
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (command.getName().equalsIgnoreCase("feabcq")) {
            if (args.length == 0) {
                List<String> help = messagesConfig.getStringList("help_message");
                for (String line : help) {
                    sender.sendMessage(miniMessage.deserialize(line));
                }
                return true;
            }
            if (args[0].equalsIgnoreCase("reload")) {
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
                    for (StackTraceElement element : e.getStackTrace()) {
                        getLogger().severe(element.toString());
                    }
                }
                return true;
            }
            if (args[0].equalsIgnoreCase("help")) {
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
    public java.util.List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (command.getName().equalsIgnoreCase("feabcq")) {
            if (args.length == 1) {
                return Arrays.asList("reload", "help");
            }
        }
        return null;
    }
}