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
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.sql.SQLException;
import java.util.*;

public final class FE_ABCQ extends JavaPlugin implements Listener {

    private FileConfiguration messagesConfig;
    private File messagesFile;
    private io.papermc.paper.threadedregions.scheduler.ScheduledTask questionTask;
    private String currentQuestion;
    private String correctAnswer;
    private Set<String> correctAnswers;
    private boolean isQuestionActive = false;
    private Player lastCorrectPlayer = null;
    private MiniMessage miniMessage;
    private DatabaseManager databaseManager;

    @Override
    public void onEnable() {
        // 初始化MiniMessage
        miniMessage = MiniMessage.miniMessage();

        // 初始化数据库
        initializeDatabase();
        
        // 保存默认配置文件
        saveDefaultConfig();
        loadMessages();

        // 注册监听器
        getServer().getPluginManager().registerEvents(this, this);

        // 启动周期提问任务
        startQuestionTask();

        // 注册命令
        Objects.requireNonNull(getCommand("feabcq")).setExecutor(this);
        Objects.requireNonNull(getCommand("feabcq")).setTabCompleter(this);
    }

    @Override
    public void onDisable() {
        // Plugin shutdown logic
        if (questionTask != null) {
            questionTask.cancel();
        }
        
        // 关闭数据库连接
        if (databaseManager != null) {
            databaseManager.disconnect();
        }
    }

    // 初始化数据库
    private void initializeDatabase() {
        try {
            // 确保数据文件夹存在
            File dataFolder = getDataFolder();
            if (!dataFolder.exists()) {
                dataFolder.mkdirs();
            }
            
            File dbFile = new File(dataFolder, "player_stats.db");
            databaseManager = new DatabaseManager(dbFile.getAbsolutePath(), getLogger());
            databaseManager.connect();
            getLogger().info("数据库连接成功");
        } catch (SQLException e) {
            getLogger().severe("无法连接到数据库: " + e.getMessage());
            e.printStackTrace();
        }
    }

    // 加载消息配置
    private void loadMessages() {
        messagesFile = new File(getDataFolder(), "messages.yml");
        if (!messagesFile.exists()) {
            saveResource("messages.yml", false);
        }
        messagesConfig = YamlConfiguration.loadConfiguration(messagesFile);
    }

    // 重载配置
    public void reloadConfigs() {
        reloadConfig();
        loadMessages();
        
        // 重启周期任务以应用新的问题配置和设置
        if (questionTask != null) {
            questionTask.cancel();
        }
        startQuestionTask();
    }

    // 启动周期提问任务
    private void startQuestionTask() {
        int interval = getConfig().getInt("question.interval", 300); // 默认5分钟
        
        questionTask = getServer().getGlobalRegionScheduler().runAtFixedRate(this, task -> {
            if (isQuestionActive) {
                return; // 如果当前已有问题活跃，则不发起新问题
            }
            
            int minPlayers = getConfig().getInt("question.min_players", 4);
            if (getServer().getOnlinePlayers().size() < minPlayers) {
                // 人数不足，不发送消息
                return;
            }
            
            askRandomQuestion();
        }, 20L * interval, 20L * interval); // 以ticks为单位，20 ticks = 1秒
    }

    // 随机提问
    private void askRandomQuestion() {
        List<String> questions = getConfig().getStringList("options.questions");
        if (questions.isEmpty()) {
            // 如果没有配置问题，使用默认问题
            questions = Arrays.asList(
                "地球的卫星是什么？|月球|月亮",
                "水的化学式是什么？|H2O|h2o",
                "中国的首都是哪里？|北京",
                "太阳系中最大的行星是？|木星",
                "《红楼梦》的作者是谁？|曹雪芹"
            );
        }
        
        if (questions.isEmpty()) return;
        
        String questionLine = questions.get(new Random().nextInt(questions.size()));
        String[] parts = questionLine.split("\\|");
        
        if (parts.length < 2) return;
        
        // 如果当前有活跃问题，需要先结束它（特别是在until_next模式下）
        if (isQuestionActive) {
            // 如果当前问题没有被答对，发送无人答对的消息
            if (lastCorrectPlayer == null) {
                Component noAnswerMsg = deserializeMessage("no_answer");
                
                for (Player player : getServer().getOnlinePlayers()) {
                    if (player != null) {
                        player.sendMessage(noAnswerMsg);
                    }
                }
            }
            
            // 重置当前问题状态，但要防止并发问题
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
                newCorrectAnswers.add(answer.trim());
            }
        }
        
        if (newCorrectAnswers.isEmpty()) return; // 如果没有有效答案，则跳过此问题
        
        String newCorrectAnswer = parts[1].trim(); // 主答案
        
        // 设置问题状态
        synchronized(this) {
            currentQuestion = newQuestion;
            correctAnswers = newCorrectAnswers;
            correctAnswer = newCorrectAnswer;
            isQuestionActive = true;
        }
        
        // 广播问题
        String answerMode = getConfig().getString("options.answer_mode", "timed");
        Component msg = deserializeMessage("question_message",
            Placeholder.parsed("question", currentQuestion));
        
        for (Player player : getServer().getOnlinePlayers()) {
            if (player != null) {
                player.sendMessage(msg);
            }
        }
        
        // 仅在timed模式下设置时间限制
        if ("timed".equals(answerMode)) {
            int timeLimit = getConfig().getInt("options.answer_time_limit", 30);
            // 设置答题时间限制
            int finalTimeLimit = timeLimit;
            getServer().getGlobalRegionScheduler().runDelayed(this, task -> {
                // 使用同步块确保线程安全
                synchronized(FE_ABCQ.this) {
                    if (isQuestionActive) {
                        // 时间到，无人答对
                        // 如果已经有玩家答对，则不发送此消息
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
            }, 20L * finalTimeLimit); // 以ticks为单位
        }
    }

    // 检测回答
    @EventHandler
    public void onPlayerChat(AsyncPlayerChatEvent event) {
        if (!isQuestionActive || currentQuestion == null || correctAnswers == null) {
            return; // 没有活跃问题时不处理
        }

        Player player = event.getPlayer();
        if (player == null) {
            return; // 玩家为空，不处理
        }
        
        String message = event.getMessage();
        if (message == null || message.trim().isEmpty()) {
            return; // 消息为空，不处理
        }
        
        boolean ignoreCase = getConfig().getBoolean("options.ignore_case", true);
        String checkMessage = ignoreCase ? message.toLowerCase() : message;
        
        boolean isCorrect = false;
        String matchedAnswer = null;
        
        // 确保correctAnswers不为null
        if (correctAnswers != null) {
            for (String answer : correctAnswers) {
                if (answer == null) continue; // 跳过null答案
                
                String checkAnswer = ignoreCase ? answer.toLowerCase() : answer;
                if (checkMessage.contains(checkAnswer) || checkAnswer.contains(checkMessage)) {
                    isCorrect = true;
                    matchedAnswer = answer;
                    break;
                }
            }
        }
        
        if (isCorrect) {
            event.setCancelled(true); // 取消消息发送
            
            // 检查是否已经有玩家答对
            if (lastCorrectPlayer != null) {
                player.sendActionBar(deserializeMessage("wrong_answer_actionbar"));
                return; // 已有玩家答对，其他人答错
            }
            
            lastCorrectPlayer = player;
            isQuestionActive = false;
            
            // 更新玩家统计
            updatePlayerStats(player.getUniqueId().toString(), player.getName(), true);
            
            // 给予奖励
            giveRewards(player);
            
            // 显示效果
            showSuccessEffects(player);
            
            // 发送Action Bar消息
            sendActionBarStats(player);
            
            // 广播消息
            broadcastSuccessMessage(player, matchedAnswer);
            
            // 重置
            currentQuestion = null;
            correctAnswers = null;
        } else {
            // 答错 - 更新玩家统计（错误回答也算一次尝试）
            updatePlayerStats(player.getUniqueId().toString(), player.getName(), false);
        }
    }

    // 更新玩家统计
    private void updatePlayerStats(String uuid, String name, boolean isCorrect) {
        try {
            databaseManager.updatePlayerStats(uuid, name, isCorrect);
        } catch (SQLException e) {
            getLogger().severe("更新玩家统计失败: " + e.getMessage());
            e.printStackTrace();
        }
    }

    // 发送Action Bar统计信息
    private void sendActionBarStats(Player player) {
        try {
            DatabaseManager.PlayerStats stats = databaseManager.getPlayerStats(player.getUniqueId().toString());
            Component actionBarMsg = deserializeMessage("actionbar_stats",
                Placeholder.parsed("correct_count", String.valueOf(stats.correctAnswers)),
                Placeholder.parsed("accuracy_rate", String.format("%.1f", stats.getAccuracyRate())));
            
            player.sendActionBar(actionBarMsg);
        } catch (SQLException e) {
            getLogger().severe("获取玩家统计失败: " + e.getMessage());
            // 发送一个默认的Action Bar消息
            Component defaultMsg = deserializeMessage("actionbar_stats",
                Placeholder.parsed("correct_count", "0"),
                Placeholder.parsed("accuracy_rate", "0.0"));
            player.sendActionBar(defaultMsg);
        }
    }

    // 给予奖励
    private void giveRewards(Player player) {
        if (player == null || getConfig().getBoolean("rewards.disabled", false)) {
            return;
        }
        
        int exp = getConfig().getInt("rewards.experience", 10);
        if (exp > 0) {
            player.giveExp(exp);
        }
        
        List<String> commands = getConfig().getStringList("rewards.commands");
        if (commands.isEmpty()) return; // 如果没有命令，直接返回
        
        String playerName = player.getName(); // 缓存玩家名称
        String expStr = String.valueOf(exp); // 缓存经验字符串
        
        for (String cmd : commands) {
            if (cmd != null && !cmd.trim().isEmpty()) { // 检查命令是否有效
                String processedCmd = cmd.replace("{player}", playerName)
                                        .replace("{reward}", expStr);
                getServer().dispatchCommand(getServer().getConsoleSender(), processedCmd);
            }
        }
    }

    // 显示成功效果
    private void showSuccessEffects(Player player) {
        if (player == null || player.isDead() || !player.isOnline()) {
            return; // 确保玩家有效
        }
        
        if (getConfig().getBoolean("effects.particles", true)) {
            // 显示粒子效果
            Location loc = player.getLocation();
            World world = player.getWorld();
            if (world == null) return; // 确保世界有效
            
            // 预创建随机数生成器以提高性能
            Random random = new Random();
            
            // 发射多个粒子
            for (int i = 0; i < 25; i++) { // 减少粒子数量以提高性能
                double offsetX = (random.nextGaussian() - 0.5) * 2;
                double offsetY = Math.abs(random.nextGaussian()) * 2;
                double offsetZ = (random.nextGaussian() - 0.5) * 2;
                
                world.spawnParticle(Particle.HAPPY_VILLAGER, 
                    loc.getX() + offsetX, loc.getY() + offsetY, loc.getZ() + offsetZ,
                    1, 0, 0, 0, 0);
            }
        }
        
        if (getConfig().getBoolean("effects.sounds", true)) {
            // 播放声音
            player.playSound(player.getLocation(), "entity.player.levelup", 1.0f, 1.0f);
        }
        
        if (getConfig().getBoolean("effects.fireworks", true)) {
            // 发射烟花（无伤害）
            Location loc = player.getLocation();
            World world = player.getWorld();
            if (world == null) return; // 确保世界有效
            
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
            
            // 延迟删除烟花实体以避免伤害
            getServer().getGlobalRegionScheduler().runDelayed(this, task -> {
                if (firework.isValid() && !firework.isDead()) {
                    firework.remove();
                }
            }, 20L); // 1秒后删除
        }
    }
    // 广播成功消息
    private void broadcastSuccessMessage(Player player, String answer) {
        Component msg = deserializeMessage("correct_answer_broadcast",
            Placeholder.parsed("player", player.getName()));
        
        // 向除答题者外的所有在线玩家发送消息
        for (Player p : getServer().getOnlinePlayers()) {
            if (!p.getUniqueId().equals(player.getUniqueId())) {
                p.sendMessage(msg);
            }
        }
        
        // 也发送给答对的玩家特殊消息
        Component playerMsg = deserializeMessage("correct_answer_player",
            Placeholder.parsed("reward", String.valueOf(getConfig().getInt("rewards.experience", 10))));
        player.sendMessage(playerMsg);
    }

    // 反序列化消息
    private Component deserializeMessage(String key, TagResolver... placeholders) {
        String message = messagesConfig.getString(key, "<red>Message not found: " + key + "</red>");
        return miniMessage.deserialize(message, placeholders);
    }

    // 命令处理
    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (command.getName().equalsIgnoreCase("feabcq")) {
            if (args.length == 0) {
                // 显示帮助信息
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
                    e.printStackTrace();
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

    // Tab补全
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