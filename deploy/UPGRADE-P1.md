# 服务器升级操作清单（0988ad6 → 当前，P1 步骤 6）

> 用途：服务器上的代码停在 `0988ad6`、库也是**当时建的**——**没有 `t_event_outbox` 这张表**。
> 本文每一步都可以**逐条粘贴执行**。总耗时约 20–30 分钟（含镜像构建与 5 容器起来）。
> 凡是"判据"都要看到预期输出才算通过；**不要把真实口令贴回项目对话**。

## 0. 结论先行：这次要跑哪些 SQL（以及为什么只有这些）

| 库的状态 | 要跑的脚本 | 说明 |
| --- | --- | --- |
| 任何库（P4 之前都**没有** `t_consume_record`） | ③ `sql/hotfix-p4-consume-record.sql` | 消费端幂等表。**必须在重启后端之前跑**：表不存在时消费者会 INSERT 失败 → 消息被"ACK + ERROR 日志"吃掉（有留痕但业务没执行） |
| 任何库（P4 步骤 2 之前都**没有** `t_message_retry`） | ④ `sql/hotfix-p4-message-retry.sql` | 消费失败的重试账本。同样**必须在重启后端之前跑**：表不存在时"失败 → 落重试账本"会失败，消息既没账本又被 ACK 掉 = 静默丢失 |
| 0988ad6 建的库（**没有** `t_event_outbox`） | ① `sql/hotfix-p1-outbox-init.sql`；② `sql/hotfix-outbox-sending-state.sql` | ① 用最终形态 `CREATE TABLE IF NOT EXISTS` 建表；② 幂等，会自己打印"已应用，跳过" |
| 只有类型枚举还是旧的（P5/P11 显示 `REPAIR/LEAVE/REIMBURSE`） | `sql/hotfix-p0b-order-type.sql` | **本服务器不需要**：0988ad6 的种子数据与当前 `init.sql` 的 30 条 INSERT **逐条一致**（含 SLA 8 行新类型），已实测 |
| 只有权限绑定缺失（P8/P1 报错） | `sql/hotfix-role-permissions.sql` | 同上不需要；`INSERT IGNORE`，需要时可安全补跑 |

**这条路径已本地实测过**（D41 规定的判定方式）：三种库版本各建临时库 ——
A1 = `0988ad6` 的 `init.sql`、A2 = `c926472` 的 `init.sql`（outbox 是"步骤 2 形态"）、B = 当前 `init.sql`；
按上表处理后用 `information_schema` 比对**列（含注释文本）与索引**：

```
wo_upg_a1 vs wo_upg_b : True      # 71 列逐项一致
wo_upg_a2 vs wo_upg_b : True      # 71 列逐项一致
两个脚本各重跑一遍 → "已应用，跳过（影响 0 行）"，结构不变

# P4 步骤 1 追加后（t_consume_record）：
wo_cr_a（HEAD 的 init.sql + hotfix-p4-consume-record.sql） vs wo_cr_b（当前 init.sql） : True   # 75 列
wo_mr_a（HEAD 的 init.sql + hotfix-p4-message-retry.sql） vs wo_mr_b（当前 init.sql） : True   # 84 列
```

## 1. 拉代码

```bash
cd /opt/work-order-system
git pull                    # deploy key 只读即可（只拉不推）
git log --oneline -1        # 判据：应等于 origin/master 最新提交
```

## 2. 备份（别省这一步）

```bash
cd /opt/work-order-system/deploy
set -a; . ./.env; set +a                      # 把 .env 里的口令读进当前 shell
docker exec workorder-mysql mysqldump -uroot -p"$MYSQL_ROOT_PASSWORD" \
  --single-transaction --default-character-set=utf8mb4 work_order > ~/work_order-before-p1.sql
ls -l ~/work_order-before-p1.sql              # 判据：文件非空（几 MB 量级）
```

## 3. 跑迁移（**顺序不能反**）

```bash
cd /opt/work-order-system
docker exec -i workorder-mysql mysql -uroot -p"$MYSQL_ROOT_PASSWORD" --default-character-set=utf8mb4 \
  work_order < sql/hotfix-p1-outbox-init.sql
docker exec -i workorder-mysql mysql -uroot -p"$MYSQL_ROOT_PASSWORD" --default-character-set=utf8mb4 \
  work_order < sql/hotfix-outbox-sending-state.sql
docker exec -i workorder-mysql mysql -uroot -p"$MYSQL_ROOT_PASSWORD" --default-character-set=utf8mb4 \
  work_order < sql/hotfix-p4-consume-record.sql        # P4 步骤 1：消费端幂等表
docker exec -i workorder-mysql mysql -uroot -p"$MYSQL_ROOT_PASSWORD" --default-character-set=utf8mb4 \
  work_order < sql/hotfix-p4-message-retry.sql         # P4 步骤 2：消费失败重试账本
```

> ③④ 两条都必须在**重启后端之前**跑完：这两张表是消费端失败/幂等路径要写的，
> 表不存在时消费者会 INSERT 失败，而失败消息会被"ACK + 日志"吃掉（有痕迹但业务没执行）。

判据：

```bash
# ① 表建出来了，且列齐全（应看到 owner / claimed_at）
docker exec workorder-mysql mysql -uroot -p"$MYSQL_ROOT_PASSWORD" -e "SHOW COLUMNS FROM work_order.t_event_outbox;"
# ② 索引形状正确：idx_dispatch = (status, next_retry_at)
docker exec workorder-mysql mysql -uroot -p"$MYSQL_ROOT_PASSWORD" -e "SHOW INDEX FROM work_order.t_event_outbox;"
# ③ 第二条脚本应打印：hotfix-outbox-sending-state: 已应用，跳过（影响 0 行）
# ④ 幂等表的唯一约束真的在（不要只看 DDL 文件）：应看到 UNIQUE KEY `uk_event_consumer` (`event_id`,`consumer`)
docker exec workorder-mysql mysql -uroot -p"$MYSQL_ROOT_PASSWORD" -e "SHOW CREATE TABLE work_order.t_consume_record\G"
# ⑤ 重试账本同判据：应看到 UNIQUE KEY `uk_event_consumer` (`event_id`,`consumer`) + KEY `idx_retry_dispatch` (`status`,`next_retry_at`)
docker exec workorder-mysql mysql -uroot -p"$MYSQL_ROOT_PASSWORD" -e "SHOW CREATE TABLE work_order.t_message_retry\G"
# ⑥ 重投任务的开关与参数（与投递/消费共用一个开关，没有新增配置）：
docker logs workorder-backend 2>&1 | grep '\[retry\]'
#    期望：重投任务已启用：批上限=100 租约=300s 确认超时=5000ms（阶梯 1m/5m/15m/1h/6h 见 MessageRetryService）
```

## 4. 补 `.env`（这份 .env 建于这两个键存在之前）

```bash
cd /opt/work-order-system/deploy
# 4.1 先确认位置（必须与 compose 文件同级；放仓库根目录 compose 读不到，见 D47）
ls -l .env 2>/dev/null || { echo "deploy/.env 不存在"; ls -l ../.env 2>/dev/null && echo "→ 它在仓库根目录：执行 mv ../.env .env"; }
# 4.2 补两个键（服务器上自己填强口令）
grep -E '^RABBITMQ_' .env || echo 'RABBITMQ_* 缺失，下面补'
cat >> .env <<'EOF'
RABBITMQ_USER=workorder
RABBITMQ_PASS=换成你自己的强口令
EOF
# 4.3 判据：三个键都有值；HOST/PORT 刻意不写（容器内要用服务名 rabbitmq:5672，写 localhost 会连自己）
grep -E '^(MYSQL_ROOT_PASSWORD|RABBITMQ_USER|RABBITMQ_PASS)=' .env | sed 's/=.*/=***/'
```

## 5. 重建 5 容器（含自建延迟镜像）

```bash
cd /opt/work-order-system/deploy
docker compose up -d --build          # 构建 backend / frontend / rabbitmq（deploy/rabbitmq 自建镜像）
docker compose ps                     # 判据：5 个容器 Up；mysql/redis/rabbitmq 显示 healthy
```

**判据三连**（缺一个都说明没起对）：

```bash
# ① 延迟插件真的在（官方镜像没有它 → 应显示 [E*] rabbitmq_delayed_message_exchange）
docker exec workorder-rabbitmq rabbitmq-plugins list -e | grep delayed
# ② 交换机被应用声明出来（类型必须是 x-delayed-message）
docker exec workorder-rabbitmq rabbitmqctl list_exchanges name type | grep workorder
# ③ 投递/消费开关在生产是打开的（默认 false 会导致"投递与监听都不工作"）
docker compose config | grep -A2 OUTBOX_DISPATCH_ENABLED
docker logs workorder-backend 2>&1 | grep '\[outbox\]'
#   期望两行：投递任务已启用…（exchange=workorder.delay.exchange） / 延迟交换机校验通过：类型=x-delayed-message
```

**若 `rabbitmq` 构建时解析镜像源失败**（本地实测遇到过 `failed to fetch anonymous token … auth.docker.io`）：

```bash
docker images | grep rabbitmq         # ① 基础镜像若已在本地，可离线构建：
docker build -t workorder-rabbitmq:3.13-delayed deploy/rabbitmq/
# ② 或先配国内 registry-mirror（/etc/docker/daemon.json 的 registry-mirrors）再重试 compose build
# ③ 或在能连 Docker Hub 的机器上构建后搬运：
#    docker save workorder-rabbitmq:3.13-delayed | gzip > rmq.tgz
#    scp rmq.tgz <server>:~ && ssh <server> 'docker load < ~/rmq.tgz'
```

## 6. 冒烟 + 全量探针（P1–P16）

```bash
cd /opt/work-order-system
# 6.1 全量探针（P7/P10 是代码侧/人工项，见 README「探针用法」；P9 要求应用在运行）
docker exec -i workorder-mysql mysql -uroot -p"$MYSQL_ROOT_PASSWORD" --default-character-set=utf8mb4 \
  work_order < sql/probes.sql
#   判据：除 P7/P10 外不应出现 FAIL；INFO/SKIP 可接受（P15a-fresh 只在"刚提交工单"时有判定力）

# 6.2 登录冒烟（P12a 的口径：admin 存在且启用）
curl -s -X POST http://localhost:9000/api/login -H 'Content-Type: application/json' \
  -d '{"username":"admin","password":"admin123"}' | head -c 120

# 6.3 时区：提交一张工单后 **5 秒内** 跑 P15a-fresh（期望 0–5 秒；接近 28800 立刻停下来报告）
docker exec -i workorder-mysql mysql -uroot -p"$MYSQL_ROOT_PASSWORD" --default-character-set=utf8mb4 \
  work_order < sql/probes.sql | grep -E 'P15a-fresh|P15b'

# 6.4 LLM 变量是否真的进了容器（P5 收口新增，与 P12a 登录、P15 时区并列的冒烟项）
#     —— 为空时 triage 会**静默降级**成 OTHER/普通：这是"声明了但没接上"的典型故障（见 D57）
docker exec workorder-backend sh -c 'echo "LLM_API_URL=${LLM_API_URL:-（空）}"; echo "LLM_API_KEY=${LLM_API_KEY:+已设置}"'
#     判据：URL 非"（空）"且 KEY 显示"已设置"；否则检查 deploy/.env 是否填了这两键，然后 docker compose up -d backend
docker logs workorder-backend 2>&1 | grep '未配置 LLM_API_URL' && echo '警告：triage 处于降级态' || echo 'LLM 已配置'
#     ⚠ **判据（必须勾这条）**：启动日志里必须出现 `[启动自检] LLM 探测通过（triage 可用）`。
#        出现 `HTTP 400` 就是**模型名与供应商不匹配**（例：给 DeepSeek 的 URL 配了 gpt-3.5-turbo），按 README 排障表处理；
#        出现 `未配置 LLM_MODEL` 就是漏填。原因：模型名配错的现象是"AI 全判 OTHER、日志无异常"，
#        而启动自检把它变成了**一条可勾选的冒烟判据**——这正是它存在的价值。
docker logs workorder-backend 2>&1 | grep -E '\[启动自检\] LLM 探测通过|LLM 探测失败'
```

## 7. 三个验证（P1 步骤 6 的核心）

**先把 `NETWORK/0` 的 `accept_minutes` 临时改成 2 分钟**，并在 `docs/PENDING-RESTORE.md` 的待还原区登记一行：

```bash
cd /opt/work-order-system/deploy; set -a; . ./.env; set +a
docker exec workorder-mysql mysql -uroot -p"$MYSQL_ROOT_PASSWORD" -e \
 "UPDATE work_order.t_sla_config SET accept_minutes=2 WHERE type='NETWORK' AND priority=0;
  SELECT type,priority,accept_minutes FROM work_order.t_sla_config WHERE type='NETWORK';"
# 登记内容：t_sla_config 的 NETWORK/0.accept_minutes  临时值=2  应然值=30  验证完必须还原
```

取一个处理人账号的 token（把 `<handler账号>` / `<口令>` 换成你库里的真实账号）：

```bash
TOKEN=$(curl -s -X POST http://localhost:9000/api/login -H 'Content-Type: application/json' \
  -d '{"username":"<handler账号>","password":"<口令>"}' | sed -n 's/.*"token":"\([^"]*\)".*/\1/p')
echo ${TOKEN:0:12}    # 判据：非空
```

### 验证 1：停 broker 仍能释放（兜底路径按配置时限）

```bash
docker compose stop rabbitmq
OID=$(curl -s -X POST http://localhost:9000/api/orders -H "Authorization: $TOKEN" -H 'Content-Type: application/json' \
  -d '{"title":"P1步骤6-停broker仍能释放","content":"验证兜底扫描","type":"NETWORK","priority":0}' \
  | sed -n 's/.*"id":\([0-9]*\).*/\1/p')
echo "orderId=$OID"
curl -s -X POST http://localhost:9000/api/orders/$OID/accept -H "Authorization: $TOKEN"; echo
# 观察（期望：2–3 分钟内 status 由 ACCEPTED → RELEASED；若仍硬编码 30 分钟则要等 30 分钟）
for i in $(seq 1 8); do
  sleep 30
  docker exec workorder-mysql mysql -uroot -p"$MYSQL_ROOT_PASSWORD" -N -e \
   "SELECT CONCAT('t+$((i*30))s order: ', status, ' ver=', version, ' assignee=', IFNULL(assignee_id,'NULL')) FROM work_order.t_work_order WHERE id=$OID;
    SELECT CONCAT('        outbox: ', status, ' retry=', retry_count, ' sent=', IFNULL(sent_at,'NULL')) FROM work_order.t_event_outbox WHERE aggregate_id=$OID;"
done
docker compose logs --since 10m backend | grep '超时释放成功'
```

### 验证 2：后端强杀不丢（outbox 记录被补投）

```bash
docker compose stop rabbitmq                      # 保持 MQ 不可用
OID2=$(curl -s -X POST http://localhost:9000/api/orders -H "Authorization: $TOKEN" -H 'Content-Type: application/json' \
  -d '{"title":"P1步骤6-后端强杀补投","content":"验证 outbox 补投","type":"NETWORK","priority":0}' \
  | sed -n 's/.*"id":\([0-9]*\).*/\1/p')
curl -s -X POST http://localhost:9000/api/orders/$OID2/accept -H "Authorization: $TOKEN"; echo
sleep 10
docker exec workorder-mysql mysql -uroot -p"$MYSQL_ROOT_PASSWORD" -e \
 "SELECT status,retry_count,next_retry_at FROM work_order.t_event_outbox WHERE aggregate_id=$OID2;"
#   判据：PENDING（可能已 retry=1）—— 记录在库里，没有丢
docker kill -s KILL workorder-backend             # 硬杀（不是 docker restart）
docker compose start rabbitmq                     # 恢复 MQ
sleep 5; docker compose start backend             # 重启后端
#   ⚠ backend 有 restart: unless-stopped：被 kill 后 compose 可能已自动把它拉起来，两种都算正常
for i in $(seq 1 8); do
  sleep 30
  docker exec workorder-mysql mysql -uroot -p"$MYSQL_ROOT_PASSWORD" -N -e \
   "SELECT CONCAT('t+$((i*30))s outbox: ', status, ' retry=', retry_count, ' sent=', IFNULL(sent_at,'NULL')) FROM work_order.t_event_outbox WHERE aggregate_id=$OID2;
    SELECT CONCAT('        order: ', status, ' ver=', version) FROM work_order.t_work_order WHERE id=$OID2;"
done
#   判据：outbox 变 SENT（retry_count 不因"成功"而增加）→ 随后工单 RELEASED
docker compose logs --since 10m backend | grep -E 'release-listener|超时释放成功'
```

### 验证 3：重复投递只释放一次

```bash
cd /opt/work-order-system/deploy; set -a; . ./.env; set +a
# 用管理 API 把同一条事件再投一次（x-delay=0 立即投；payload/header 与第一次完全一致）
curl -s -u "$RABBITMQ_USER:$RABBITMQ_PASS" -X POST \
  "http://127.0.0.1:15672/api/exchanges/%2f/workorder.delay.exchange/publish" \
  -H 'Content-Type: application/json' \
  -d "{\"properties\":{\"delivery_mode\":2,\"headers\":{\"x-delay\":0,\"x-event-id\":\"order:$OID2:v1:ORDER_RELEASE_CHECK\"}},\"routing_key\":\"order.release.check\",\"payload\":\"{\\\"orderId\\\":$OID2}\",\"payload_encoding\":\"string\"}"
sleep 5
docker exec workorder-mysql mysql -uroot -p"$MYSQL_ROOT_PASSWORD" -e \
 "SELECT id,status,version,assignee_id FROM work_order.t_work_order WHERE id=$OID2;
  SELECT event_id,status,retry_count FROM work_order.t_event_outbox WHERE aggregate_id=$OID2;"
#   判据：工单状态与 version **不变**
curl -s -u "$RABBITMQ_USER:$RABBITMQ_PASS" \
  "http://127.0.0.1:15672/api/queues/%2f/workorder.order.release.queue" | grep -o '"messages":[0-9]*'
docker compose logs --since 2m backend | grep 'release-listener'   # 期望 DEBUG 跳过（状态守卫未命中）
```

## 8. 5 容器实测采样（2–3 小时）

> 容器集合从 4/6 变成了 5（多了 `rabbitmq`），**旧采样脚本不要沿用**，用下面这个：

```bash
cat > ~/observe5.sh <<'EOF'
#!/usr/bin/env bash
set -u
OUT="$HOME/observe.csv"
[ -f "$OUT" ] || echo "ts,mysql,redis,rabbitmq,backend,frontend,total,mem_available" > "$OUT"
while true; do
  ts=$(date '+%F %T')
  vals=$(docker stats --no-stream --format '{{.Name}}|{{.MemUsage}}' \
           workorder-mysql workorder-redis workorder-rabbitmq workorder-backend workorder-frontend \
         | awk -F'[|/]' '{u=$2; gsub(/[A-Za-z ]/,"",u); v=u+0; if($2 ~ /GiB/) v*=1024; printf "%.1f,", v}')
  total=$(echo "$vals" | tr ',' ' ' | awk '{s+=$1} END {printf "%.1f", s}')
  ma=$(free -m | awk '/^Mem:/{print $7}')
  echo "$ts,${vals}${total},${ma}" >> "$OUT"
  sleep 300
done
EOF
chmod +x ~/observe5.sh
nohup ~/observe5.sh > ~/observe5.log 2>&1 & echo "observe5 started"
```

对照口径（**结论必须分三种口径写，别混**）：

| 口径 | 参照值 | 用途 |
| --- | --- | --- |
| 4 容器服务器实测（历史） | 稳态 653 MiB / 8h 后 673 MiB；`MemAvailable` ≈2290 MiB | 看"加入 RabbitMQ 的增量" |
| 5 容器本机 WSL2 预演 | ≈881 MiB（mysql 461.2 / rabbitmq 136.8 / backend 261.5 / frontend 16.3 / redis 5.2） | 只说明"RabbitMQ 增量有限"，**不能当容量规划** |
| 容量规划（应采用） | §1.6.2 上界口径 ≈2.7 GiB、余量 ≈1 GiB | 规划用这个 |

**2–3 小时能得出什么**：能得出"空载是否单调爬升"的**初步**结论；§1.6.4 的正式泄漏判定要求窗口 **≥8 小时且跨日切**
（判据：斜率 ≤ 5 MiB/h，且窗口内峰值 ≤70% `mem_limit`）。采样结束后把 `~/observe.csv` 贴回项目对话，由对话侧写进方案。

## 9. 清理 401 张压测工单（演示前必做）

压测工单特征：`t_work_order.title LIKE '压测-%'`（`scripts/loadtest.sh` 写入 `title=压测-N`、`content=loadtest`）。
**按 D19：先按最宽口径统计、留档，取最大值，再删。**

```bash
cd /opt/work-order-system/deploy; set -a; . ./.env; set +a
MYSQL="docker exec -i workorder-mysql mysql -uroot -p$MYSQL_ROOT_PASSWORD --default-character-set=utf8mb4"
# 9.1 最宽口径统计（日志表两个口径都算；通知表 ref_* 全 NULL，只能按 content 里的单号匹配）
eval $MYSQL work_order <<'SQL'
SELECT 'work_order'              AS scope, COUNT(*) FROM t_work_order WHERE title LIKE '压测-%';
SELECT 'log_by_order_id'         AS scope, COUNT(*) FROM t_work_order_log l JOIN t_work_order w ON w.id = l.order_id      WHERE w.title LIKE '压测-%';
SELECT 'log_by_order_no'         AS scope, COUNT(*) FROM t_work_order_log l JOIN t_work_order w ON w.order_no = l.order_no WHERE w.title LIKE '压测-%';
SELECT 'notification_by_content' AS scope, COUNT(*) FROM t_notification n JOIN t_work_order w ON n.content LIKE CONCAT('%', w.order_no, '%') WHERE w.title LIKE '压测-%';
SELECT 'outbox_by_aggregate'     AS scope, COUNT(*) FROM t_event_outbox o JOIN t_work_order w ON w.id = o.aggregate_id  WHERE w.title LIKE '压测-%';
SQL
# 判据：work_order = 401（51 + 350）；其它口径记下来，留档取最大值

# 9.2 留档（仓库外，勿入库）
docker exec workorder-mysql mysqldump -uroot -p"$MYSQL_ROOT_PASSWORD" --no-create-info --skip-comments \
  --single-transaction --default-character-set=utf8mb4 --where="title LIKE '压测-%'" \
  work_order t_work_order > ~/cleanup-loadtest-work_order.sql
docker exec workorder-mysql mysqldump -uroot -p"$MYSQL_ROOT_PASSWORD" --no-create-info --skip-comments \
  --single-transaction --default-character-set=utf8mb4 \
  --where="order_id IN (SELECT id FROM t_work_order WHERE title LIKE '压测-%')" \
  work_order t_work_order_log > ~/cleanup-loadtest-logs.sql
docker exec workorder-mysql mysqldump -uroot -p"$MYSQL_ROOT_PASSWORD" --no-create-info --skip-comments \
  --single-transaction --default-character-set=utf8mb4 \
  --where="content LIKE '%压测%' OR EXISTS (SELECT 1 FROM t_work_order w WHERE w.title LIKE '压测-%' AND t_notification.content LIKE CONCAT('%', w.order_no, '%'))" \
  work_order t_notification > ~/cleanup-loadtest-notifications.sql
ls -l ~/cleanup-loadtest-*.sql          # 判据：三个文件都在、都非空

# 9.3 删除（先子表后主表，一个事务）
eval $MYSQL work_order <<'SQL'
START TRANSACTION;
DELETE l FROM t_work_order_log l JOIN t_work_order w ON w.id = l.order_id   WHERE w.title LIKE '压测-%';
DELETE n FROM t_notification   n JOIN t_work_order w ON n.content LIKE CONCAT('%', w.order_no, '%') WHERE w.title LIKE '压测-%';
DELETE o FROM t_event_outbox   o JOIN t_work_order w ON w.id = o.aggregate_id WHERE w.title LIKE '压测-%';
DELETE FROM t_work_order WHERE title LIKE '压测-%';
COMMIT;
SQL

# 9.4 复跑探针确认归零（P4 未完结 NULL、P5 旧类型、P13 测试残留、P16 outbox 健康度）
docker exec -i workorder-mysql mysql -uroot -p"$MYSQL_ROOT_PASSWORD" --default-character-set=utf8mb4 \
  work_order < ../sql/probes.sql | grep -E 'P4|P5|P13|P16'
```

## 10. 收尾（务必做）

```bash
# 10.1 还原临时配置（验证用的 2 分钟）
docker exec workorder-mysql mysql -uroot -p"$MYSQL_ROOT_PASSWORD" -e \
 "UPDATE work_order.t_sla_config SET accept_minutes=30 WHERE type='NETWORK' AND priority=0;"
# 10.2 探针必须 PASS（P14c 就是钉这个应然值的）
docker exec -i workorder-mysql mysql -uroot -p"$MYSQL_ROOT_PASSWORD" --default-character-set=utf8mb4 \
  work_order < ../sql/probes.sql | grep -E 'P14c|P9'
# 10.3 把 ~/observe.csv 与三个验证的原始输出整段贴回项目对话；
#      对话侧据此写进方案（§1.6 新增 5 容器真机段）、标记 P1 完成、补 I11/P16，并在 PENDING-RESTORE 里划掉临时改动
```
