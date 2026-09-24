# RabbitMQ 自建镜像（延迟插件版）

## 一、这个目录是什么

`deploy/docker-compose.yml` 里的 `rabbitmq` 服务不用官方镜像，而是用本目录构建的镜像。
原因只有一个：**官方镜像不含延迟插件**，而"接单后到点检查释放"这条链路依赖
`x-delayed-message` 交换机（决策见 `docs/DECISIONS.md` D32 / `ASYNC-SCHEDULING-PLAN.md` §5.3）。

实测（2026-09-24，官方 `rabbitmq:3.13-management`，broker 3.13.7）：

```
$ rabbitmq-plugins enable rabbitmq_delayed_message_exchange
Error: {:plugins_not_found, [:rabbitmq_delayed_message_exchange]}
（退出码 70）
```

插件是官方维护但**不随镜像分发**的社区插件，必须以 `.ez` 文件放进 `/opt/rabbitmq/plugins/`。

## 二、插件包的来源与校验

| 项 | 值 |
| --- | --- |
| 文件 | `rabbitmq_delayed_message_exchange-3.13.0.ez` |
| 大小 | 45,228 字节 |
| 来源 | `https://github.com/rabbitmq/rabbitmq-delayed-message-exchange/releases/download/v3.13.0/rabbitmq_delayed_message_exchange-3.13.0.ez` |
| SHA256 | `3479180DF03FA830FD59CCF638A60EEE8F0E8261524A8616685522D7B8D9A9EE` |

校验（Windows PowerShell）：

```powershell
(Get-FileHash deploy/rabbitmq/rabbitmq_delayed_message_exchange-3.13.0.ez -Algorithm SHA256).Hash
# 应等于上表 SHA256
```

**为什么把 45 KB 的二进制入库**：目标部署机在境内，GitHub release 资产的下载常被限速或阻断。
若要"构建期下载插件"，部署就变成看网络运气；随仓库入库后，服务器 `git clone` 即可离线构建。

**版本约束**：插件 minor 必须与 broker 匹配（3.13.x ↔ 插件 3.13.0）。升级 broker 时同步换包。

## 三、怎么观察延迟消息（A 方案的关键代价）

**延迟消息在到达投递时间之前，存在于交换机内部，不在任何队列里。**
所以 `list_queues messages` 在延迟窗口内看到的是 **0**，这不代表消息丢了。

| 想看什么 | 命令 | 说明 |
| --- | --- | --- |
| 消息是否还在交换机里等 | 管理台 `Exchanges → 选中交换机`，或 `GET /api/exchanges/%2f/<exchange>` | 该交换机下 `message_stats.publish` 有计数、队列深度仍为 0 |
| 消息是否已到点进队 | `docker exec workorder-rabbitmq rabbitmqctl list_queues name messages` | 到点后目标队列 `messages` 由 0 变正数 |
| 交换机类型是否真的是延迟型 | `docker exec workorder-rabbitmq rabbitmqctl list_exchanges name type` | 延迟交换机类型显示为 `x-delayed-message` |
| 插件是否启用 | `docker exec workorder-rabbitmq rabbitmq-plugins list -e \| findstr delayed` | 出现 `rabbitmq_delayed_message_exchange` |
| 业务侧的"该何时投递" | `SELECT event_id, status, deliver_at, retry_count, next_retry_at FROM t_event_outbox ORDER BY id DESC LIMIT 10;` | `deliver_at` 是**唯一真相来源**，交换机里的延迟值由它算出 |

**演示时的口径**：消息在延迟窗口内"看不见"是这套方案的设计代价（D32 已记），
不是故障。要证明它没丢，用上面第一行的交换机统计 + 到点后队列深度从 0 变正数。

## 四、重新构建

```bash
cd deploy
docker compose up -d --build rabbitmq      # 只重建 rabbitmq
docker compose logs rabbitmq | tail -20     # 确认 "Server startup complete"
docker exec workorder-rabbitmq rabbitmq-plugins list -e | grep delayed
```
