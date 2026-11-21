# MCBot — 让 AI 替你玩 Minecraft

一个 **Minecraft 1.21.11 (Fabric)** 客户端 mod：在游戏内接入任意大模型 API（OpenAI / DeepSeek / 智谱 GLM / Ollama / Claude 等），把整个游戏交给 AI 当智能体操作——你说目标，它规划步骤、调用工具、自动执行。

## 功能

- **聊天派活**：聊天框输入 `@mcbot <目标>`，AI 自动分解任务并逐步执行
- **A* 自动寻路**（Baritone 风格）：绕障碍、跳1格上台、跳落3格、走斜角，卡死自动重规划
- **游戏内 GUI 配置**：按 `O` 或 `/mcbot` 打开，25+ 厂商预设（DeepSeek/GLM/Kimi/千问/OpenAI/Claude/Gemini...），每家带最新模型列表一键轮换
- **13 个游戏工具**：状态感知(含周围方块概览)、大范围找方块(96格)、寻路走位、挖矿、放方块、自动走到工作台合成、战斗、换装等
- **自动生存**：饥饿自动吃东西、死亡自动复活(可关)、挖矿自动装备工具
- **多步自主规划**：每步结果回传 AI 动态调整，直到 `done` 或 `abort`
- **本地保存配置**：`config/mcbot.json`，支持 HTTP 代理(proxyHost/proxyPort 字段)

## 安装

1. 安装 [Fabric Loader](https://fabricmc.net/use/installer/) **0.19.3+**（Minecraft 1.21.11）
2. 把 `build/libs/mcbot-1.0.0.jar` 放进 `.minecraft/mods/`
3. 同目录放入 [Fabric API](https://modrinth.com/mod/fabric-api)（0.141.x+1.21.11）
4. 启动游戏

## 使用

### 1. 配置 API（首次必做）

游戏内输入 `/mcbot` 打开配置界面：

| 项 | 说明 |
|---|---|
| 接口类型 | `OpenAI 兼容`（OpenAI/DeepSeek/GLM/Ollama 等全兼容）或 `Anthropic (Claude)` |
| Base URL | 留空=官方默认。如 DeepSeek: `https://api.deepseek.com/v1`，GLM: `https://open.bigmodel.cn/api/paas/v4`，Ollama: `http://localhost:11434/v1` |
| API Key | 你的密钥（仅存本地 `config/mcbot.json`） |
| 模型 | 如 `deepseek-chat` / `glm-4.6` / `gpt-4o-mini` / `claude-sonnet-4-5` |
| Temperature / 最大步数 | 采样温度、单次任务最多行动步数 |
| 自动选工具 | 挖矿时自动装备正确工具 |
| 显示思考 | 聊天框显示 AI 每步的思考 |

### 2. 派活

```
@mcbot 给我挖 20 个圆石再做一把石镐
@mcbot 附近哪有铁矿？去挖一些回来
@mcbot 晚上了，造一个简单庇护所躲一晚
/mcbot run 用木板把脚下垫高5格      (命令方式)
/mcbot stop                          (随时中止)
@mcbot 停止                          (聊天方式中止)
/mcbot status                        (查看状态)
```

### 3. 工作原理

```
你: @mcbot 挖点木头做个工作台
  → AgentManager 启动循环（后台线程）:
     1. 抓取游戏状态(get_status)发给 AI
     2. AI 回复 {"thought":"...","tool":"find_block","args":{"block":"log"}}
     3. ActionExecutor 在主线程执行该工具, 结果回传 AI
     4. 重复 2-3, 直到 AI 回复 tool=done
```

工具清单：`get_status` / `find_block` / `walk_to` / `look_at` / `dig` / `place` / `select` / `craft` / `attack` / `say` / `wait` / `done` / `abort`

## 开发

```powershell
.\gradlew.bat build      # 产物在 build/libs/mcbot-1.0.0.jar
.\gradlew.bat runClient  # 启动开发客户端测试
```

- 工具链：Gradle 9.5.1（wrapper 已配国内镜像）+ Fabric Loom 1.17.20 + Java 21+（本机 Java 25 直接可用）
- 映射：Mojang 官方映射（官方模板默认）
- 代码结构：

```
src/main/java/com/mcbot/            入口 + 配置
src/client/java/com/mcbot/client/
  ├─ MCBotClient.java               客户端入口: 注册 tick/聊天事件/命令
  ├─ ai/AgentManager.java           Agent 循环（多步规划）
  ├─ ai/OpenAiCompatProvider.java   OpenAI 兼容 API
  ├─ ai/AnthropicProvider.java      Claude API
  ├─ ai/Prompts.java                系统提示词 + 工具说明
  ├─ mc/ActionExecutor.java         游戏动作执行器(13个工具, tick状态机)
  └─ config/ConfigScreen.java       游戏内 GUI
```

## 已知边界

- 寻路为有界 A*（单次规划 ≤128 格，节点上限 6 万），超远距离 AI 会分段 walk_to；不破坏方块开路（AI 会用 dig 配合）
- 合成需背包材料齐全；3x3 配方自动走到 32 格内的工作台并打开界面，2x2 自动打开背包
- 纯客户端 mod，单人/联机皆可，服务端无需安装
- AI 质量取决于所用模型；建议用推理能力较强的模型（deepseek-v4-pro / gpt-5.6 / claude-opus-5 等）

## License

MIT
