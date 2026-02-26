package top.miragedge.abcq;

import java.sql.*;
import java.util.logging.Logger;

public class DatabaseManager {
    private Connection connection;
    private final String dbPath;
    private final Logger logger;
    private final Object lock = new Object(); // 添加锁对象用于同步

    public DatabaseManager(String dbPath, Logger logger) {
        this.dbPath = dbPath;
        this.logger = logger;
    }

    public void connect() throws SQLException {
        synchronized (lock) {
            try {
                connection = DriverManager.getConnection("jdbc:sqlite:" + dbPath);
                createTables();
            } catch (SQLException e) {
                logger.severe("无法连接到数据库: " + e.getMessage());
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
            
            // 检查玩家是否已存在
            String checkSQL = "SELECT COUNT(*) FROM player_stats WHERE uuid = ?";
            boolean exists;
            
            try (PreparedStatement checkStmt = connection.prepareStatement(checkSQL)) {
                checkStmt.setString(1, uuid);
                try (ResultSet rs = checkStmt.executeQuery()) {
                    exists = rs.next() && rs.getInt(1) > 0;
                }
            }
            
            if (exists) {
                // 更新现有记录
                String updateSQL = """
                    UPDATE player_stats 
                    SET name = ?, 
                        correct_answers = correct_answers + ?, 
                        total_attempts = total_attempts + 1, 
                        updated_at = CURRENT_TIMESTAMP 
                    WHERE uuid = ?
                    """;
                
                try (PreparedStatement updateStmt = connection.prepareStatement(updateSQL)) {
                    updateStmt.setString(1, name != null ? name : uuid);
                    updateStmt.setInt(2, isCorrect ? 1 : 0);
                    updateStmt.setString(3, uuid);
                    updateStmt.executeUpdate();
                }
            } else {
                // 插入新记录
                String insertSQL = """
                    INSERT INTO player_stats (uuid, name, correct_answers, total_attempts) 
                    VALUES (?, ?, ?, ?)
                    """;
                
                try (PreparedStatement insertStmt = connection.prepareStatement(insertSQL)) {
                    insertStmt.setString(1, uuid);
                    insertStmt.setString(2, name != null ? name : uuid);
                    insertStmt.setInt(3, isCorrect ? 1 : 0);
                    insertStmt.setInt(4, 1);
                    insertStmt.executeUpdate();
                }
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
    public static class PlayerStats {
        public final String uuid;
        public final String name;
        public final int correctAnswers;
        public final int totalAttempts;
        
        public PlayerStats(String uuid, String name, int correctAnswers, int totalAttempts) {
            this.uuid = uuid != null ? uuid : "";
            this.name = name;
            this.correctAnswers = Math.max(0, correctAnswers);
            this.totalAttempts = Math.max(0, totalAttempts);
        }
        
        public double getAccuracyRate() {
            if (totalAttempts == 0) return 0.0;
            return (double) correctAnswers / totalAttempts * 100;
        }
    }
}