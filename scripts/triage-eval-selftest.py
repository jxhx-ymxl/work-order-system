#!/usr/bin/env python3
"""`triage-eval.py` 的**离线自检**（不需要后端、不需要 Redis、不写任何库）。

守的是一条口径（`docs/AGENT-PLAN.md` §2.3 第 2 项 / `triage-eval.py` 文件头的"对外口径"）：
**信息不足组的保守率分母必须保持整组**——"未判定"（提交失败 / 超时未完成 / 分诊 FAILED）
一律按"不保守"计，不许从分母里消失。三条失败路径必须**同构**：同一条用例，
无论它以哪种方式没跑出来，分母都不许少。

为什么需要它：`main()` 靠 `submit` / `wait_triage` / `login` 三个模块级函数与后端交互，
把它们打桩就能在完全离线的情况下跑完整条计数逻辑并抓 stdout——这正是本脚本的做法。
（`login` 也要打桩：不打桩它会重试 3 次、每次间隔 2s，属于"离线跑不了"的路径。）

用法：
    python scripts/triage-eval-selftest.py        # 退出码 0=全绿 / 1=有失败
"""
import contextlib
import importlib.util
import io
import json
import re
import sys
from pathlib import Path

HERE = Path(__file__).resolve().parent
EVAL_SCRIPT = HERE / "triage-eval.py"
CASES = json.loads((HERE / "triage-eval-cases.json").read_text(encoding="utf-8"))["cases"]

# 整组条数 = 分母的应然值（用真实用例文件算，不写死 6）
INSUFFICIENT_IDS = [c["id"] for c in CASES if c["insufficient"]]
INSUFFICIENT_TOTAL = len(INSUFFICIENT_IDS)
FAIL_CASE_ID = INSUFFICIENT_IDS[0]

CONSERVATIVE_RE = re.compile(r"信息不足组的保守率：(\d+)/(\d+)")
THREE_COUNTS_RE = re.compile(r"三档计数：判错 (\d+) 条 / 超时未完成 (\d+) 条 / 分诊失败 (\d+) 条")


def load_eval_module():
    """按路径加载 `triage-eval.py`（文件名带连字符，不能直接 import）。"""
    spec = importlib.util.spec_from_file_location("triage_eval_under_test", EVAL_SCRIPT)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def make_stubs(mode, others="normal"):
    """造三个打桩函数：只让 `FAIL_CASE_ID` 以 `mode` 指定的方式失败，其余用例的行为由 `others` 决定。

    mode:   submit（提交失败） / timeout（超时未完成） / failed（分诊 FAILED）
    others: normal（信息不足组返回 OTHER/0 = 保守） / timeout（其余也一律未判定）
    """
    id_to_case = {}
    seq = {"n": 900}

    def submit(base, token, case):
        if case["id"] == FAIL_CASE_ID and mode == "submit":
            return None, {"code": 500, "message": "stub: 提交失败"}
        seq["n"] += 1
        id_to_case[seq["n"]] = case
        return seq["n"], {"code": 200, "data": {"id": seq["n"]}}

    def wait_triage(base, token, order_id, timeout_sec):
        case = id_to_case[order_id]
        if case["id"] == FAIL_CASE_ID and mode == "timeout":
            return None, float(timeout_sec)
        if case["id"] == FAIL_CASE_ID and mode == "failed":
            return {"triageStatus": "FAILED", "type": None, "priority": None}, 3.0
        if others == "timeout":
            return None, float(timeout_sec)
        if case["insufficient"]:
            return {"triageStatus": "DONE", "type": "OTHER", "priority": 0}, 2.0
        expected_type = (case.get("expect_types") or ["OTHER"])[0]
        return {"triageStatus": "DONE", "type": expected_type,
                "priority": case.get("expect_priority") or 0}, 2.0

    return submit, wait_triage


def run_eval(mode, others="normal"):
    """跑一遍 main()，返回它打印的全部 stdout。"""
    module = load_eval_module()
    module.login = lambda base, user, password: "stub-token"
    module.submit, module.wait_triage = make_stubs(mode, others)

    saved_argv = sys.argv
    sys.argv = ["triage-eval.py"]          # argparse 会读 sys.argv：不隔离会把测试运行器的参数喂给它
    buffer = io.StringIO()
    try:
        with contextlib.redirect_stdout(buffer):
            module.main()
    finally:
        sys.argv = saved_argv
    return buffer.getvalue()


def parse(output):
    conservative = CONSERVATIVE_RE.search(output)
    three = THREE_COUNTS_RE.search(output)
    if not conservative or not three:
        raise AssertionError("输出里找不到汇总行——脚本的输出格式变了？\n" + output[-800:])
    return {
        "numerator": int(conservative.group(1)),
        "denominator": int(conservative.group(2)),
        "three_counts": tuple(int(g) for g in three.groups()),
    }


SCENARIOS = [
    # 复现（口径来自 §2.3 第 2 项）：一条信息不足用例提交失败，其余全部未判定 → 0/整组
    {"name": "提交失败（其余未判定）", "mode": "submit", "others": "timeout",
     "denominator": INSUFFICIENT_TOTAL, "numerator": 0, "three_counts": (1, len(CASES) - 1, 0)},
    # 三条失败路径必须同构：同一条用例、三种失败方式，分母都必须保持整组，且另外五条都判为保守
    {"name": "提交失败", "mode": "submit", "others": "normal",
     "denominator": INSUFFICIENT_TOTAL, "numerator": INSUFFICIENT_TOTAL - 1, "three_counts": (1, 0, 0)},
    {"name": "超时未完成", "mode": "timeout", "others": "normal",
     "denominator": INSUFFICIENT_TOTAL, "numerator": INSUFFICIENT_TOTAL - 1, "three_counts": (0, 1, 0)},
    {"name": "分诊 FAILED", "mode": "failed", "others": "normal",
     "denominator": INSUFFICIENT_TOTAL, "numerator": INSUFFICIENT_TOTAL - 1, "three_counts": (0, 0, 1)},
]


def main():
    try:
        sys.stdout.reconfigure(encoding="utf-8", errors="replace")
    except Exception:
        pass

    print(f"信息不足组共 {INSUFFICIENT_TOTAL} 条（{','.join(INSUFFICIENT_IDS)}）；"
          f"被弄失败的用例：{FAIL_CASE_ID}")
    failures = []
    denominators = []
    for scenario in SCENARIOS:
        got = parse(run_eval(scenario["mode"], scenario["others"]))
        denominators.append(got["denominator"])
        checks = [
            (f"分母 == {scenario['denominator']}", got["denominator"] == scenario["denominator"]),
            (f"分子 == {scenario['numerator']}", got["numerator"] == scenario["numerator"]),
            (f"三档计数 == {scenario['three_counts']}", got["three_counts"] == scenario["three_counts"]),
        ]
        bad = [name for name, ok in checks if not ok]
        status = "PASS" if not bad else "FAIL"
        print(f"  [{status}] {scenario['name']:<18} 保守率 {got['numerator']}/{got['denominator']}"
              f"，三档计数 {got['three_counts']}" + ("" if not bad else f"  ← 未通过：{'; '.join(bad)}"))
        if bad:
            failures.append(f"{scenario['name']}：{'; '.join(bad)}")

    same = len(set(denominators)) == 1
    print(f"  [{'PASS' if same else 'FAIL'}] 三条失败路径的分母一致：{denominators}")
    if not same:
        failures.append(f"三条失败路径的分母不一致：{denominators}")

    if failures:
        print("\n自检失败：")
        for f in failures:
            print("  - " + f)
        return 1
    print("\n自检通过：三条失败路径同构，信息不足组的分母恒为整组。")
    return 0


if __name__ == "__main__":
    sys.exit(main())
