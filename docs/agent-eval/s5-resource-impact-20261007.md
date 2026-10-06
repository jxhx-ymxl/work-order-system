# S5：资源与主业务影响（2026-10-07，本机实测）

> **环境**：本机 Windows；后端 `mvn -o spring-boot:run`（9000）；模型端点 = **本地 HTTP 桩**
> `scripts/stub-llm-agent.py`（**固定 3s 延时**）；容器 `workorder-mysql/redis/rabbitmq` 跑在 **3307/6380/5673**；
> 专用库 **`work_order_s5`**（结构克隆自 `work_order_test`，**不碰**另一个项目、不碰 `work_order_test` 数据）。
> 调查开关 `agent.investigation.enabled=true`、`mode=agent`、`max-concurrent=3`（**临时值**，见下）。
>
> ⚠ **桩延时不是生产延迟**（手册 L139）：下面所有"耗时"都只反映**本机 + 桩**的相对关系，
> **不得**当作生产数字；能当判据的是**趋势与上限**（错误数、P99 增幅的相对量、资源是否单调增长）。

## 1. 环境与命令（可复现）

```powershell
# ① 夹具（建专用库 + 一张起点单；admin 变成 DEPT 9 的主管）
$env:MYSQL_PORT='3307'; mvn -o test "-Dtest=S5FixtureHarness"

# ② 模型桩（固定 3s 延时；注意：分诊用的 stub-llm.py 协议不同，调查链路用不了它）
$env:STUB_DELAY_MS='3000'; python scripts/stub-llm-agent.py 18080

# ③ 后端（DB_NAME 指向专用库；临时把并发位提到 3 以便制造并发）
$env:DB_NAME='work_order_s5'; $env:MYSQL_PORT='3307'; $env:REDIS_PORT='6380'; $env:MYSQL_PASSWORD='<deploy/.env>'
$env:LLM_API_URL='http://127.0.0.1:18080/v1/chat/completions'; $env:AGENT_INVESTIGATION_ENABLED='true'
$env:AGENT_INVESTIGATION_MODE='agent'; $env:AGENT_INVESTIGATION_MAX_CONCURRENT='3'
mvn -o spring-boot:run

# ④ 采样器（MySQL Threads_connected，每 2 秒）
$env:S5_SAMPLE_SECONDS='120'; mvn -o test "-Dtest=S5SamplerHarness"
```

## 2. 资源：基线 vs 调查进行中（3 并发）

| 口径 | A 空闲（3 次采样） | B 调查中（3 并发，4 次采样） | 结束后（3 次采样） | 判据 |
| --- | --- | --- | --- | --- |
| **进程 RSS** | 309 MB（稳定） | 309 → **310 MB** | 310 MB | **有界**（+1 MB），无单调增长 |
| **线程数** | 62 / 60 / 60 | 59 / 59 / 62 / 62 | 59 / 59 / 58 | **有界**（未随并发增长） |
| **堆 used** | 69 733 KB | 71 306 → 72 005 → 72 868 → **74 128 KB** | 74 215 KB（稳定） | 先升后**平**，无单调增长 |
| **堆（GC 后）** | — | — | **35 031 KB** | **低于基线** ⇒ 之前的"未回落"是**尚未 GC**，不是泄漏 |
| **MySQL `Threads_connected`** | 6 | **6** | **6** | **常数**：连接不随并发增长 |

**结论（不泄漏）**：线程数、`Threads_connected` **全程不增长**；RSS +1 MB；堆 used 上升 ~6 MB
但 **`jcmd GC.run` 后落到 35 MB（低于基线 68 MB）** ⇒ 那些是**可回收的临时对象**，不是泄漏。
（`GC.run` 是"这条是泄漏还是还没回收"的决定性判据——只看 used 会误判。）

## 3. 主业务：有/无调查并发的对照（同一环境、同一批 30 单）

| 批次 | n | min | p50 | **p99** | max | 业务 code |
| --- | --- | --- | --- | --- | --- | --- |
| **有调查并发**（3 个调查进行中） | 30 | 25 ms | 28 ms | **55 ms** | 55 ms | **200 : 30** |
| **无调查**（对照） | 30 | 17 ms | 20 ms | **31 ms** | 31 ms | **200 : 30** |

- **主业务错误 = 0**（按**业务 code** 判定：30/30 都是 `code=200`，不是看 HTTP 状态）。
- **P99 增幅 = +24 ms（31 → 55，约 +77%）**，**可归因**：3 个调查在同一台机器上并发跑，
  它们的 SQL 读取、JSON 序列化与线程调度与提交请求抢同一个 JVM 与同一个 MySQL 实例。
  绝对量很小（几十毫秒）且**没有错误**；但**这是本机 + 桩**的数字，**不能外推**到生产。

## 4. 调查侧（同一批）

3 个并发调查：**全部 `code=200` / `status=COMPLETED`**，耗时 6 225 / 6 240 / 6 261 ms
（= 2 次模型调用 × 3s 桩延时 + 少量开销）——**桩延时主导**，不代表真实模型耗时。

## 5. 未观测到的指标（如实登记，不糊）

| 指标 | 状态 | 卡在哪 / 差距 |
| --- | --- | --- |
| **Hikari active / idle / pending** | **未观测** | 应用没有 actuator、也没开 JMX 出口；本机也没有 mysql 客户端。**替代口径**：MySQL 侧 `Threads_connected`（= 应用实际占用的连接数），全程常数 6 |
| 生产环境的主业务影响 | **未做** | 需要服务器完整栈与真实流量；本轮只在本机 + 桩上做相对比较 |

## 6. 与 loadtest 脚本口径的关系

`scripts/loadtest.sh` 与 `scripts/loadtest.ps1` 是**两版一套口径**（CLAUDE §5）。
**本轮未改任何一版的判据**（只跑提交循环做对照），所以两版仍然一致。

## 7. 清理

- 专用库 `work_order_s5` 保留（供复跑）；**`work_order_test` 未被写入**（只读了它的结构与参考行）。
- 后端进程与两个桩进程在测量结束后停止；容器保持运行（它们是本项目自己的容器）。
