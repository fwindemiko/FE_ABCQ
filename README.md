# FE_ABCQ 知识问答插件

一个功能丰富的 Minecraft 知识问答插件，支持 Paper 1.18+ 至最新版本及 Folia 服务端。

## 功能特性

- **定时问答**: 周期性向所有在线玩家广播问题
- **即时抢答**: 第一个答对的玩家获得奖励
- **多种答案**: 支持一个问题有多个正确答案
- **答题时限**: 可配置答题时间限制（timed 模式）或无限时抢答（until_next 模式）
- **丰富奖励**: 经验值 + 自定义命令奖励
- **炫酷特效**: 粒子效果、音效、户外烟花
- **数据统计**: SQLite 数据库记录玩家答题统计
- **完美兼容**: 支持 Paper 1.18+ 及 Folia 服务端
- **性能优化**: 使用 ConcurrentHashMap 和线程安全设计

## 支持版本

| 服务端 | 支持版本 |
|--------|---------|
| Paper | 1.18.x - 1.21.x |
| Folia | 所有版本 |

## 安装

1. 下载最新的插件 JAR 文件
2. 将 JAR 文件放入服务器的 `plugins` 文件夹
3. 重启服务器
4. 插件会自动生成配置文件

## 构建

```bash
# 默认构建 (Paper 1.21.x)
mvn clean package

# 指定版本构建
mvn clean package -Ppaper-1.18    # Paper 1.18.x
mvn clean package -Ppaper-1.19    # Paper 1.19.x
mvn clean package -Ppaper-1.20    # Paper 1.20.x
mvn clean package -Ppaper-1.21    # Paper 1.21.x
```

构建产物位于 `target/[F][知识问答]FE_ABCQ-{version}.jar`

## 配置说明

### 周期提问配置 (`config.yml`)

```yaml
question:
  enabled: true          # 是否启用周期提问
  interval: 897         # 提问间隔（秒）
  min_players: 4        # 最少在线人数触发提问
```

### 效果配置

```yaml
effects:
  particles: true       # 粒子效果
  fireworks: true       # 户外烟花效果
  sounds: true          # 声音效果
```

### 奖励配置

```yaml
rewards:
  experience: 10         # 经验奖励数量
  disabled: false       # 是否禁用奖励
  commands:             # 额外命令奖励
    - "eco give {player} 500"
    - "points give {player} 5"
```

占位符:
- `{player}` - 玩家名
- `{reward}` - 经验奖励数量

### 答题设置

```yaml
options:
  # 答题模式
  # timed: 在指定秒数内回答
  # until_next: 直到下一个问题出现前都可以回答
  answer_mode: 'until_next'

  answer_time_limit: 60  # 答题时间限制（秒，仅 timed 模式）

  ignore_case: true      # 答案是否忽略大小写

  # 问题列表格式: "问题|答案1|答案2|答案3"
  questions:
    - "地球的卫星是什么？|月球|月亮"
    - "水的化学式是什么？|H2O|h2o"
```

## 消息配置 (`messages.yml`)

所有消息支持 MiniMessage 格式和渐变色。

### 可用消息键

| 键 | 说明 |
|----|------|
| `question_message` | 提问消息（可用 `<question>` 占位） |
| `correct_answer_player` | 答对玩家收到的消息 |
| `correct_answer_broadcast` | 答对广播给其他玩家（可用 `<player>` 占位） |
| `wrong_answer_actionbar` | 答错提示（ActionBar） |
| `no_answer` | 超时无人答对消息 |
| `actionbar_stats` | ActionBar 统计消息 |
| `reload_success` | 重载成功消息 |
| `reload_error` | 重载失败消息 |
| `no_permission` | 无权限消息 |
| `help_message` | 帮助信息列表 |

### MiniMessage 示例

```yaml
question_message: "<gradient:#FF6B6B:#4ECDC4>[知识问答]</gradient> <white><question></white>"
correct_answer_player: "<gradient:#FF6B6B:#4ECDC4>[知识问答]</gradient> <gradient:#4ECDC4:#45B7D1>恭喜您回答正确！</gradient>"
```

## 命令

| 命令 | 权限 | 说明 |
|------|------|------|
| `/feabcq reload` | `feabcq.reload` | 重载配置文件 |
| `/feabcq help` | 无 | 显示帮助信息 |

## 权限

| 权限 | 默认 | 说明 |
|------|------|------|
| `feabcq.reload` | OP | 重载配置文件 |

## 数据库

玩家统计数据存储在 `plugins/FE_ABCQ/player_stats.db`（SQLite）

记录内容：
- UUID
- 玩家名
- 正确答题数
- 总答题数
- 正确率

## 性能特点

- **线程安全**: 使用 `volatile` 和 `synchronized` 保护共享状态
- **并发优化**: 使用 `ConcurrentHashMap` 存储答案
- **资源管理**: 正确的任务取消和数据库连接管理
- **内存安全**: 防止无效实体引用导致的问题

## 目录结构

```
plugins/FE_ABCQ/
├── config.yml         # 主配置文件
├── messages.yml       # 消息配置文件
└── player_stats.db     # 玩家数据（自动创建）
```

## 技术栈

- Java 21
- Paper API 1.18+ - 1.21+
- Adventure API (MiniMessage)
- SQLite JDBC
- Maven

## 作者

F.windEmiko

## 许可证

MIT License

## 更新日志

### v1.1.0
- 支持 Paper 1.18+ 至最新版本
- 支持 Folia 服务端
- 性能优化
- 线程安全改进
