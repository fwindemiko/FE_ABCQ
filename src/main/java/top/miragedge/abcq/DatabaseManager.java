package top.miragedge.abcq;

import java.sql.*;
import java.util.logging.Logger;

public class DatabaseManager {
    private Connection connection;
    private final String dbPath;
    private final Logger logger;
    private final Object lock = new Object();

    public DatabaseManager(String dbPath, Logger logger) {
        if (dbPath == null || dbPath.isEmpty()) {
            throw new IllegalArgumentException("Database path cannot be null or empty");
        }
        this.dbPath = dbPath;
        this.logger = logger;
    }

    public void connect() throws SQLException {
        synchronized (lock) {
            if (isConnected()) {
                return;
            }
            try {
                // 使用标准化路径处理Windows路径问题
                String normalizedPath = dbPath.replace("\\", "/");
                connection = DriverManager.getConnection("jdbc:sqlite:" + normalizedPath);
                createTables();
            } catch (SQLException e) {
                logger.severe("无法连接到数据库: " + e.getMessage());
                connection = null;
                throw e;
            }
        }
    }

    private void createTables() throws SQLException {
        String createTableSQL = """
            CREATE TABLE IF NOT EXISTS player_stats (
                uuid TEXT PRIMARY KEY,
                name TEXT NOT NULL,
                correct_answers INTEGER DEFAULT 0,
                total_attempts INTEGER DEFAULT 0,
                created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
                updated_at DATETIME DEFAULT CURRENT_TIMESTAMP
            )
            """;

        try (Statement stmt = connection.createStatement()) {
            stmt.execute(createTableSQL);
        } catch (SQLException e) {
            logger.severe("创建数据表失败: " + e.getMessage());
            throw e;
        }
    }

    public void updatePlayerStats(String uuid, String name, boolean isCorrect) throws SQLException {
        if (uuid == null || uuid.trim().isEmpty()) {
            logger.warning("试图使用空UUID更新玩家统计");
            return;
        }

        synchronized (lock) {
            if (!isConnected()) {
                logger.warning("数据库未连接，无法更新玩家统计");
                return;
            }

            // 使用 SQLite UPSERT 语法 (INSERT ... ON CONFLICT DO UPDATE)
            // 这是原子操作，比先 SELECT 再 UPDATE/INSERT 更高效
            String upsertSQL = """
                INSERT INTO player_stats (uuid, name, correct_answers, total_attempts)
                VALUES (?, ?, ?, ?)
                ON CONFLICT(uuid) DO UPDATE SET
                    name = excluded.name,
                    correct_answers = correct_answers + excluded.correct_answers,
                    total_attempts = total_attempts + 1,
                    updated_at = CURRENT_TIMESTAMP
                """;

            try (PreparedStatement stmt = connection.prepareStatement(upsertSQL)) {
                stmt.setString(1, uuid);
                stmt.setString(2, name != null ? name : uuid);
                stmt.setInt(3, isCorrect ? 1 : 0);
                stmt.setInt(4, 1);
                stmt.executeUpdate();
            } catch (SQLException e) {
                logger.severe("更新玩家统计失败: " + e.getMessage());
                throw e;
            }
        }
    }

    public PlayerStats getPlayerStats(String uuid) throws SQLException {
        if (uuid == null || uuid.trim().isEmpty()) {
            logger.warning("试图使用空UUID获取玩家统计");
            return new PlayerStats(uuid, null, 0, 0);
        }
        
        synchronized (lock) {
            if (!isConnected()) {
                logger.warning("数据库未连接，无法获取玩家统计");
                return new PlayerStats(uuid, null, 0, 0);
            }
            
            String sql = "SELECT name, correct_answers, total_attempts FROM player_stats WHERE uuid = ?";
            
            try (PreparedStatement stmt = connection.prepareStatement(sql)) {
                stmt.setString(1, uuid);
                
                try (ResultSet rs = stmt.executeQuery()) {
                    if (rs.next()) {
                        String name = rs.getString("name");
                        int correctAnswers = rs.getInt("correct_answers");
                        int totalAttempts = rs.getInt("total_attempts");
                        
                        return new PlayerStats(uuid, name, correctAnswers, totalAttempts);
                    }
                }
            }
        }
        
        // 如果玩家没有记录，返回默认值
        return new PlayerStats(uuid, null, 0, 0);
    }

    public void disconnect() {
        synchronized (lock) {
            try {
                if (connection != null && !connection.isClosed()) {
                    connection.close();
                }
            } catch (SQLException e) {
                logger.severe("关闭数据库连接时出错: " + e.getMessage());
            }
        }
    }

    public boolean isConnected() {
        try {
            return connection != null && !connection.isClosed();
        } catch (SQLException e) {
            return false;
        }
    }

    // 玩家统计数据类
    public record PlayerStats(String uuid, String name, int correctAnswers, int totalAttempts) {
        // Compact 构造函数用于验证和规范化
        public PlayerStats {
            uuid = (uuid != null ? uuid : "");
            correctAnswers = Math.max(0, correctAnswers);
            totalAttempts = Math.max(0, totalAttempts);
        }

        public double getAccuracyRate() {
            if (totalAttempts == 0) return 0.0;
            return (double) correctAnswers / totalAttempts * 100;
        }
    }
}