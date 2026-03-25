package top.miragedge.abcq;

import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.logging.Logger;

/**
 * 调度器适配器 - 兼容 Folia 和 Paper 服务端
 * 使用标准 Bukkit Scheduler 确保最大兼容性
 * Folia 也支持 Bukkit Scheduler，在 Folia 上会自动路由到合适的线程
 */
public class SchedulerAdapter {

    private final JavaPlugin plugin;
    private final Logger logger;
    private final boolean isFolia;

    // 调度任务引用
    private volatile BukkitTask questionTask;
    private volatile BukkitTask answerTimeoutTask;
    private volatile BukkitTask fireworkRemoveTask;

    public SchedulerAdapter(JavaPlugin plugin, Logger logger) {
        this.plugin = plugin;
        this.logger = logger;
        this.isFolia = detectFolia();
    }

    private boolean detectFolia() {
        String serverName = Bukkit.getServer().getName();
        boolean folia = serverName.toLowerCase().contains("folia");
        logger.info("检测到服务端: " + serverName + (folia ? " (Folia)" : " (Paper/Spigot)"));
        return folia;
    }

    public boolean isFolia() {
        return isFolia;
    }

    /**
     * 运行周期性任务
     * 使用 Bukkit Scheduler，兼容所有服务端
     */
    public void runQuestionTask(Runnable task, long intervalTicks) {
        cancelQuestionTask();
        questionTask = Bukkit.getScheduler().runTaskTimer(plugin, task, 20L * intervalTicks, 20L * intervalTicks);
    }

    /**
     * 运行延迟任务
     */
    public void runAnswerTimeoutTask(Runnable task, long delayTicks) {
        cancelAnswerTimeoutTask();
        answerTimeoutTask = Bukkit.getScheduler().runTaskLater(plugin, task, 20L * delayTicks);
    }

    /**
     * 运行烟花移除任务
     */
    public void runFireworkRemoveTask(Runnable task, long delayTicks) {
        fireworkRemoveTask = Bukkit.getScheduler().runTaskLater(plugin, task, delayTicks);
    }

    /**
     * 取消问题任务
     */
    public void cancelQuestionTask() {
        if (questionTask != null) {
            questionTask.cancel();
            questionTask = null;
        }
    }

    /**
     * 取消答案超时任务
     */
    public void cancelAnswerTimeoutTask() {
        if (answerTimeoutTask != null) {
            answerTimeoutTask.cancel();
            answerTimeoutTask = null;
        }
    }

    /**
     * 取消烟花移除任务
     */
    public void cancelFireworkRemoveTask() {
        if (fireworkRemoveTask != null) {
            fireworkRemoveTask.cancel();
            fireworkRemoveTask = null;
        }
    }

    /**
     * 取消所有任务
     */
    public void cancelAllTasks() {
        cancelQuestionTask();
        cancelAnswerTimeoutTask();
        cancelFireworkRemoveTask();
    }
}
