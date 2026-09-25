#!/usr/bin/env python3
"""AI 分诊评测集跑分（P5 收口新增）。

**为什么需要它**：本轮的教训是"人工指定 vs AI 判定"在界面上不可辨——我据此把**一次人工选择误读成 AI 判错**。
判断"AI 判得准不准"不能靠读一两条日志推断，必须有一组**先定好标准答案**的用例去测。

**它测的是真链路**（不是直接调模型）：对每条用例调用 `POST /api/orders` 且**只带 title/content**，
然后轮询 `GET /api/orders/{id}` 直到 `triageStatus` 离开 `PENDING`，把最终 `type/priority` 与标准答案比对。
这样测到的包含：应用的 prompt、消费端白名单校验、H4 的 SLA 重算、以及"分诊失败"这条分支。

前置（缺一不可）：
  1. 后端在跑，且 `OUTBOX_DISPATCH_ENABLED=true`（否则队列不消费，`triageStatus` 永远是 PENDING）；
  2. broker 可达；
  3. `LLM_API_URL / LLM_API_KEY / LLM_MODEL` 指向**真实模型**。

⚠ **用 `scripts/stub-llm.py` 跑出来的不是准确率**：桩对任何输入都返回同一组固定值，
只能用来验证"跑分脚本本身能正确判对/判错"（机械自检），不能代表模型能力。

⚠ **副作用（D19 的规矩：会写数据的东西必须说清写了什么、怎么清）**：每条用例都会**真的提交一张工单**
（含 outbox / 通知等衍生行）。所以：
  · **不要**对着业务库/演示库跑——用专用库（例如 `DB_NAME=wo_eval` 起后端）；
  · 跑完脚本会打印本次产生的**工单 ID 清单**与清理 SQL，照它清掉即可（`title` 与用例标题一致，也可按标题清）。

**等待上限为什么是 150s（2026-09-26 从 90s 提高）**：消费失败后的**首次重试退避是 1 分钟**（阶梯 1m→5m→15m→1h→6h）。
90s 的窗口下，任何一次 LLM 调用失败都会把用例记成"未判定"，读者分不清**它是最终失败还是还在重试**。
150s ≥ 提交 + 首次失败 + 1 分钟退避 + 重投 + 二次调用，才够让"偶发超时"落在能判定的范围内。

**两种"没通过"必须分开计数**（本脚本的输出把它们分成三档）：
  · `判错`：分诊完成且有结论，但结论与期望不符；
  · `超时未完成`：等待上限内 `triageStatus` 始终是 `PENDING`（链路没跑完 / 一直失败重试中）；
  · `分诊失败`：`triageStatus='FAILED'`（重试阶梯走完、账本 PARKED，见 D62）。
  **对外口径**：在计准确率时，`超时未完成` 与 `分诊失败` **都按失败计、且保留在分母里**（不缩分母）——
  否则"跑不出来的用例"会消失在分母里，数字看着更漂亮但不可比。

用法：
    python scripts/triage-eval.py                      # 默认 http://127.0.0.1:9000，admin/admin123，等待上限 150s
    python scripts/triage-eval.py --base-url http://<服务器> --user admin --password *** --timeout 150
"""
import argparse
import json
import os
import sys
import time
import urllib.error
import urllib.request

CASES_FILE = os.path.join(os.path.dirname(os.path.abspath(__file__)), "triage-eval-cases.json")


def http_json(method, url, body=None, token=None, timeout=15):
    data = None if body is None else json.dumps(body).encode("utf-8")
    req = urllib.request.Request(url, data=data, method=method)
    req.add_header("Content-Type", "application/json")
    if token:
        req.add_header("Authorization", token)
    try:
        with urllib.request.urlopen(req, timeout=timeout) as resp:
            return json.loads(resp.read().decode("utf-8"))
    except urllib.error.HTTPError as e:
        return {"code": e.code, "message": e.read().decode("utf-8", "replace")}


def login(base, user, password):
    r = http_json("POST", f"{base}/api/login", {"username": user, "password": password})
    token = (r.get("data") or {}).get("token")
    if not token:
        sys.exit(f"登录失败：{json.dumps(r, ensure_ascii=False)[:300]}")
    return token


def submit(base, token, case):
    # 只带 title/content —— 这正是 P5 步骤 3 放开必填后的前端行为，也是触发分诊的唯一方式
    r = http_json("POST", f"{base}/api/orders", {"title": case["title"], "content": case["content"]}, token)
    if r.get("code") != 200:
        return None, r
    return (r.get("data") or {}).get("id"), r


def wait_triage(base, token, order_id, timeout_sec):
    """轮询直到 triageStatus 离开 PENDING；返回 (最终字段, 耗时秒)"""
    started = time.time()
    while time.time() - started < timeout_sec:
        r = http_json("GET", f"{base}/api/orders/{order_id}", token=token)
        order = (r.get("data") or {}).get("order") or {}
        status = order.get("triageStatus")
        if status and status != "PENDING":
            return order, round(time.time() - started, 1)
        time.sleep(1)
    return None, round(time.time() - started, 1)


def main():
    # Windows 控制台默认 GBK：中文能勉强输出但 ✓/✗ 会直接抛 UnicodeEncodeError（本机实测踩到）。
    # 统一改 UTF-8，并把"对/错"标记换成 ASCII，保证在 cmd / PowerShell / Linux 三处都能跑。
    try:
        sys.stdout.reconfigure(encoding="utf-8", errors="replace")
    except Exception:
        pass

    p = argparse.ArgumentParser()
    p.add_argument("--base-url", default="http://127.0.0.1:9000")
    p.add_argument("--user", default="admin")
    p.add_argument("--password", default="admin123")
    # 默认 150s：≥ 提交 + 首次失败 + 1 分钟退避 + 重投 + 二次调用（见文件头）
    p.add_argument("--timeout", type=int, default=150, help="每条用例等待分诊写回的上限（秒）")
    p.add_argument("--cases", default=CASES_FILE)
    args = p.parse_args()

    cases = json.load(open(args.cases, encoding="utf-8"))["cases"]
    token = login(args.base_url, args.user, args.password)
    print(f"登录成功；用例 {len(cases)} 条；等待上限 {args.timeout}s/条\n")

    rows, failures, conservative_ok, insufficient_n = [], [], 0, 0
    type_hit, type_total, prio_hit, prio_total = 0, 0, 0, 0
    created_ids = []
    wrong_n, timeout_n, failed_n = 0, 0, 0   # 判错 / 超时未完成 / 分诊失败（分开计数）

    for c in cases:
        order_id, raw = submit(args.base_url, token, c)
        if order_id is None:
            rows.append((c["id"], "提交失败", json.dumps(raw, ensure_ascii=False)[:80], "", ""))
            failures.append((c["id"], "提交失败"))
            wrong_n += 1
            if not c["insufficient"]:
                type_total += 1                      # 对外口径：跑不出来也要留在分母里
                if c["expect_priority"] is not None:
                    prio_total += 1
            continue
        order, elapsed = wait_triage(args.base_url, token, order_id, args.timeout)
        created_ids.append(order_id)
        if order is None:
            timeout_n += 1
            rows.append((c["id"], "超时未完成", f"{elapsed}s 内 triageStatus 仍 PENDING", "", f"{elapsed}s"))
            failures.append((c["id"], "分诊未完成/超时"))
            if c["insufficient"]:
                insufficient_n += 1                  # 未判定 = 无法确认它保守，按"不保守"计（分母保持 6）
            else:
                type_total += 1
                if c["expect_priority"] is not None:
                    prio_total += 1
            continue

        got_type, got_prio, triage_status = order.get("type"), order.get("priority"), order.get("triageStatus")
        if triage_status == "FAILED":
            failed_n += 1
            rows.append((c["id"], "分诊失败", f"triageStatus=FAILED（{elapsed}s）", "", f"{elapsed}s"))
            failures.append((c["id"], "分诊 FAILED"))
            if c["insufficient"]:
                insufficient_n += 1
            else:
                type_total += 1
                if c["expect_priority"] is not None:
                    prio_total += 1
            continue

        if c["insufficient"]:
            insufficient_n += 1
            # "保守"的定义：信息不足时不要硬给一个具体类型，也不要擅自升级为紧急
            conservative = (got_type == "OTHER" and got_prio == 0)
            conservative_ok += 1 if conservative else 0
            rows.append((c["id"], "信息不足", f"{got_type}/{got_prio}",
                         "保守" if conservative else "不保守", f"{elapsed}s"))
            continue

        type_total += 1
        type_ok = got_type in c["expect_types"]
        type_hit += 1 if type_ok else 0
        prio_note = ""
        if c["expect_priority"] is not None:
            prio_total += 1
            prio_ok = got_prio == c["expect_priority"]
            prio_hit += 1 if prio_ok else 0
            prio_note = "优先级[OK]" if prio_ok else f"优先级[X](期望{c['expect_priority']} 实得{got_prio})"
        if not type_ok:
            failures.append((c["id"], f"类型: 期望 {'/'.join(c['expect_types'])} 实得 {got_type}"))
            wrong_n += 1
        elif "[X]" in prio_note:
            failures.append((c["id"], prio_note))
            wrong_n += 1
        rows.append((c["id"], "判定", f"{got_type}/{got_prio}",
                     ("类型[OK]" if type_ok else "类型[X]") + (" " + prio_note if prio_note else ""), f"{elapsed}s"))

    width = max(len(r[0]) for r in rows) if rows else 2
    print(f"{'用例':<{width}} {'结果':<8} {'实得 type/priority':<20} {'评判':<28} 耗时")
    print("-" * 78)
    for r in rows:
        print(f"{r[0]:<{width}} {r[1]:<8} {r[2]:<20} {r[3]:<28} {r[4]}")

    print("\n== 汇总 ==")
    print(f"类型准确率（**对外口径**：超时/失败计入分母，不缩分母；不含信息不足组）：{type_hit}/{type_total}"
          + (f" = {type_hit / type_total * 100:.1f}%" if type_total else ""))
    print(f"优先级准确率（仅标注了期望值的那几条）：{prio_hit}/{prio_total}"
          + (f" = {prio_hit / prio_total * 100:.1f}%" if prio_total else ""))
    print(f"信息不足组的保守率：{conservative_ok}/{insufficient_n}（未判定/失败按“不保守”计，分母保持整组）")
    print(f"三档计数：判错 {wrong_n} 条 / 超时未完成 {timeout_n} 条 / 分诊失败 {failed_n} 条")
    print(f"失败清单：{len(failures)} 条"
          + ("" if not failures else " -> " + "；".join(f"{i}:{w}" for i, w in failures)))
    if created_ids:
        print("\n== 本次产生的工单（跑完请清理，切勿对着业务库跑）==")
        print("id 清单：" + ",".join(str(i) for i in created_ids))
        print("清理 SQL（先删子表再删主表）：")
        ids = ",".join(str(i) for i in created_ids)
        print(f"  DELETE FROM t_event_outbox  WHERE aggregate_id IN ({ids});")
        print(f"  DELETE FROM t_work_order_log WHERE order_id    IN ({ids});")
        print(f"  DELETE FROM t_notification   WHERE ref_id      IN ({ids});")
        print(f"  DELETE FROM t_work_order     WHERE id          IN ({ids});")
    if os.environ.get("TRIAGE_EVAL_NOTE"):
        print(f"\n注意：{os.environ['TRIAGE_EVAL_NOTE']}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
