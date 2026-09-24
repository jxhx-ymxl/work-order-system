#!/usr/bin/env bash
# ============================================================
# 工单提交压测脚本（可复现的验证工具）
#
# 为什么有这个脚本：它承载两条已经用血换来的规则——
#   1) 判定必须落在**业务语义**上：HTTP 200 不等于成功，脚本会解析响应体里的 `"code"`，
#      只看 HTTP 状态会把 "200 + body code=500" 计成成功（本项目真实踩过）。
#   2) 压测必须**分离预热阶段**：预热期数据（JIT/类加载/连接池建连/首次 GC）不计入统计，
#      否则长尾数字既不能判断性能也不能定位瓶颈。
#
# 用法：
#   BASE_URL=http://localhost:19000 USERNAME=admin PASSWORD=admin123 ./scripts/loadtest.sh
# 参数（全部可用环境变量覆盖）：
#   BASE_URL    目标地址（默认 http://localhost:19000）
#   USERNAME    登录账号（默认 admin）
#   PASSWORD    登录口令（默认 admin123，仅本机演示用）
#   WARMUP_SEC  预热秒数（默认 120，此阶段数据只报告不统计）
#   DURATION_SEC 统计秒数（默认 300）
#   RATE_PER_MIN 目标速率：每分钟提交数（默认 50）
#   CONCURRENCY 并发度（默认 1；>1 时用后台任务并发推进）
#   OUT_DIR     结果输出目录（默认 ./loadtest-out）
#   OMIT_TYPE   1 = 提交时不带 type/priority（**触发 triage 路径**；默认 0 = 带类型提交）
#   TRIAGE_MODE sync（默认）= 改造前的同步 triage 形态：响应 type=OTHER 视为**降级样本**，
#                 记为 triagefallback、**排除出 P50/P95/P99**（降级样本会让延迟看起来更快，必须剔除）；
#               async = P5 之后的异步 triage 形态：响应 type=OTHER 是**必然且正确**的（兜底落库），
#                 因此正常计入 ok 并**计入延迟统计**；"triage 是否真的执行"改为按脚本末尾打印的命令核对。
#
# ⚠ OMIT_TYPE=1 时的**额外判据**（P5 收口新增）：
#   除了 `code=200`，还会解析响应体里的 `type`：
#     · type != OTHER  → 记为"业务成功（triage 生效）"
#     · type == OTHER  → 记为 **triage 未生效**（单独计数，**不混进业务成功**）
#     · 并额外打印 **type 分布**供人工核对（真模型确实判成 OTHER 时，只能靠人看分布）
#   为什么必须加：**压测的通过判据必须覆盖"被测的那条路径真的执行了"**——否则测出来的延迟与目标路径无关
#   （静默降级会让延迟看起来"变快"，把假数字当真数字）。同类教训：HTTP 200 ≠ 业务成功。
#   ⚠ **异步 triage 形态下的读法**（P5 步骤 1 之后）：提交响应里的 type **必然**是兜底值（分诊在事务之外异步做），
#     所以这一列此时只能证明"没有同步分诊"，不能证明"triage 执行了"。异步形态要证明 triage 真正跑通，
#     请看 `triage_status` 与消费端日志：
#       mysql> SELECT triage_status, COUNT(*) FROM t_work_order WHERE title LIKE '压测-triage-%' GROUP BY triage_status;
#       docker compose logs backend | grep '\[triage-listener\] 分诊写回成功'
#
# 依赖：bash、curl。不需要 jq（响应解析用 sed）。
#
# ⚠ 本脚本**不使用命令行参数**：所有配置一律通过下面的环境变量传入。
#   传入位置参数（如 `--url http://x`、`./loadtest.sh 100`）会在下方直接报错退出——
#   历史教训：曾按 `--url/--warmup/--duration` 传参，脚本静默忽略、按默认值跑，
#   得到的是"另一个负载场景"的数据却被当成本次结果。
# ============================================================
set -uo pipefail

if [ "$#" -gt 0 ]; then
  echo "错误：本脚本不接受位置参数，请用环境变量传入配置。" >&2
  echo "收到：$*" >&2
  echo "用法示例：BASE_URL=http://host:9000 WARMUP_SEC=120 DURATION_SEC=300 RATE_PER_MIN=50 CONCURRENCY=2 $0" >&2
  echo "可用变量：BASE_URL / USERNAME / PASSWORD / WARMUP_SEC / DURATION_SEC / RATE_PER_MIN / CONCURRENCY / OUT_DIR / OMIT_TYPE" >&2
  exit 2
fi

BASE_URL="${BASE_URL:-http://localhost:19000}"
USERNAME="${USERNAME:-admin}"
PASSWORD="${PASSWORD:-admin123}"
WARMUP_SEC="${WARMUP_SEC:-120}"
DURATION_SEC="${DURATION_SEC:-300}"
RATE_PER_MIN="${RATE_PER_MIN:-50}"
CONCURRENCY="${CONCURRENCY:-1}"
OUT_DIR="${OUT_DIR:-./loadtest-out}"
OMIT_TYPE="${OMIT_TYPE:-0}"
TRIAGE_MODE="${TRIAGE_MODE:-sync}"

mkdir -p "$OUT_DIR"
WARMUP_FILE="$OUT_DIR/warmup_latency.txt"
MEASURED_FILE="$OUT_DIR/measured_latency.txt"
: > "$WARMUP_FILE"
: > "$MEASURED_FILE"

# 每次请求间隔（毫秒）；按 CONCURRENCY 摊薄，使总速率仍接近 RATE_PER_MIN
INTERVAL_MS=$(awk -v r="$RATE_PER_MIN" -v c="$CONCURRENCY" 'BEGIN{ if (r<=0) r=1; printf "%d", (60000.0*c)/r }')

echo "== 登录 =="
LOGIN_BODY=$(curl -sS -X POST "$BASE_URL/api/login" -H 'Content-Type: application/json' \
  -d "{\"username\":\"$USERNAME\",\"password\":\"$PASSWORD\"}" || true)
TOKEN=$(printf '%s' "$LOGIN_BODY" | sed -n 's/.*"token"[[:space:]]*:[[:space:]]*"\([^"]*\)".*/\1/p')
if [ -z "$TOKEN" ]; then
  echo "登录失败，无法继续。响应：$LOGIN_BODY" >&2
  exit 1
fi
echo "登录成功（token 长度 ${#TOKEN}）"

TYPES=(NETWORK UTILITY DORM OTHER)
submit_one() {
  local idx="$1" file="$2"
  local type="${TYPES[$((idx % 4))]}" priority=$((idx % 2))
  local payload
  if [ "$OMIT_TYPE" = "1" ]; then
    # P5 基线用：不带 type/priority → 走 triage（改造前会同步等 LLM）
    payload="{\"title\":\"压测-triage-$idx\",\"content\":\"loadtest，不带类型\"}"
  else
    payload="{\"title\":\"压测-$idx\",\"content\":\"loadtest\",\"type\":\"$type\",\"priority\":$priority}"
  fi
  # 只取业务 code 与耗时：%{time_total} 由 curl 输出，code 从响应体解析
  local raw
  raw=$(curl -sS -o "$OUT_DIR/last_body.json" -w '%{time_total}' -X POST "$BASE_URL/api/orders" \
        -H 'Content-Type: application/json' -H "Authorization: $TOKEN" -d "$payload" 2>/dev/null || echo "0")
  local biz_code
  biz_code=$(sed -n 's/.*"code"[[:space:]]*:[[:space:]]*\([0-9]*\).*/\1/p' "$OUT_DIR/last_body.json" 2>/dev/null | head -1)
  local secs="${raw:-0}"
  local ms
  ms=$(awk -v s="$secs" 'BEGIN{ printf "%.1f", s*1000 }')
  # 响应体里的 type（P5 收口新增）：用来判定"triage 是否真的生效"，见文件头说明
  local biz_type
  biz_type=$(sed -n 's/.*"type"[[:space:]]*:[[:space:]]*"\([^"]*\)".*/\1/p' "$OUT_DIR/last_body.json" 2>/dev/null | head -1)
  biz_type="${biz_type:-unknown}"
  # 判定：业务 code == 200 才算成功（HTTP 200 + code=500 记为失败）
  # 记录格式统一为三列：<耗时ms> <种类> <type>，便于 summarize 分类统计与打印 type 分布
  if [ "$biz_code" = "200" ]; then
    if [ "$OMIT_TYPE" = "1" ] && [ "$TRIAGE_MODE" = "sync" ] && [ "$biz_type" = "OTHER" ]; then
      # 请求没带 type，响应也是兜底值 → 同步形态下这条**不能**算"目标路径跑通"（triage 没生效或降级）
      printf '%s triagefallback %s\n' "$ms" "$biz_type" >> "$file"
    else
      printf '%s ok %s\n' "$ms" "$biz_type" >> "$file"
    fi
  else
    printf '%s bizfail(code=%s) %s\n' "$ms" "${biz_code:-none}" "$biz_type" >> "$file"
  fi
}

run_phase() {
  local phase="$1" seconds="$2" file="$3"
  local total_ms=$(( seconds * 1000 ))
  local elapsed=0 idx=0
  echo "== $phase 阶段：${seconds}s，目标 ${RATE_PER_MIN} 单/分钟，并发 ${CONCURRENCY} =="
  while [ "$elapsed" -lt "$total_ms" ]; do
    local i=0
    while [ "$i" -lt "$CONCURRENCY" ]; do
      submit_one "$idx" "$file" &
      idx=$((idx + 1)); i=$((i + 1))
    done
    wait
    sleep "$(awk -v ms="$INTERVAL_MS" 'BEGIN{ printf "%.3f", ms/1000 }')"
    elapsed=$((elapsed + INTERVAL_MS))
  done
}

summarize() {
  local phase="$1" file="$2"
  local ok bizfail fallback total
  ok=$(awk '$2=="ok"' "$file" 2>/dev/null | wc -l); ok=${ok:-0}
  fallback=$(awk '$2=="triagefallback"' "$file" 2>/dev/null | wc -l); fallback=${fallback:-0}
  bizfail=$(awk '$2 ~ /^bizfail/' "$file" 2>/dev/null | wc -l); bizfail=${bizfail:-0}
  total=$((ok + fallback + bizfail))
  echo "---- $phase 阶段结果 ----"
  echo "请求数=$total  业务成功=$ok  业务失败=$bizfail  **triage 未生效=$fallback**  成功率=$(awk -v a="$ok" -v b="$total" 'BEGIN{ printf "%.1f%%", (b==0?0:100.0*a/b) }')"
  # type 分布（供人工核对）：真模型确实判成 OTHER 时，这一列是唯一能看出真相的地方
  local dist
  dist=$(awk 'NF>=3{print $3}' "$file" 2>/dev/null | sort | uniq -c | awk '{printf "%s×%s  ", $2, $1}')
  [ -n "$dist" ] && echo "  type 分布：$dist"
  if [ "$total" -gt 0 ]; then
    awk '$2=="ok"{print $1}' "$file" | sort -n > "$file.sorted"
    awk 'BEGIN{n=0}
         {v[n++]=$1}
         END{ if(n==0){print "  （无成功样本）"; exit}
              printf "  P50=%.1fms  P95=%.1fms  P99=%.1fms  max=%.1fms\n",
                     v[int(n*0.50)], v[int(n*0.95)], v[int(n*0.99)], v[n-1] }' "$file.sorted"
  fi
}

run_phase "预热（不计入统计）" "$WARMUP_SEC" "$WARMUP_FILE"
summarize "预热" "$WARMUP_FILE"

run_phase "统计" "$DURATION_SEC" "$MEASURED_FILE"
summarize "统计" "$MEASURED_FILE"

echo
echo "原始样本：$WARMUP_FILE（预热，不计入统计）与 $MEASURED_FILE（统计）"
echo "判定口径：① 只有在响应体里解析出 \"code\":200 才计成功（HTTP 200 + code=500 一律计失败）；"
echo "          ② OMIT_TYPE=1 时，响应体 type 仍是兜底值 OTHER 的**单列计数**为「triage 未生效」，不计入业务成功；"
echo "          ③ 并打印 type 分布供人工核对（真模型确实判 OTHER 的情况只能靠人看）。"
echo "          ④ 异步 triage 形态（P5 步骤 1 起）：响应里的 type 必然是兜底值，证明 triage 执行请看 triage_status 与消费端日志（见文件头）。"
if [ "$TRIAGE_MODE" = "async" ]; then
  echo
  echo "== 当前 TRIAGE_MODE=async：响应 type=OTHER 属正常（兜底落库），已计入 ok 与延迟统计 =="
  echo "   「triage 是否真的执行」请核对下面两处（脚本无法从提交响应里判定）："
  echo "     mysql -uroot -p -e \"SELECT triage_status, COUNT(*) FROM work_order.t_work_order WHERE title LIKE '压测-triage-%' GROUP BY triage_status;\""
  echo "     docker compose logs backend | grep '分诊写回成功'"
fi
