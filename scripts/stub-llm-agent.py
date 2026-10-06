#!/usr/bin/env python3
"""**调查助手用的模型桩**（OpenAI 兼容的 chat/completions + tool_calls）。

**为什么不能直接用 `scripts/stub-llm.py`**：那个桩是给**分诊**链路用的，返回的是
`{"type": ..., "priority": ...}` 这种业务结果；而调查链路的 `HttpAgentModel` 解析的是
`choices[0].message.tool_calls[].function.arguments`——两者协议不同。2026-10-07 S5 实测：
拿分诊桩当调查端点，每一次调查都会得到 `MODEL_PROTOCOL_ERROR`（HTTP 层 `code=500`）。

**它是无状态的**：按请求体里的 transcript 决定返回什么——
还没有 `role=tool` 应答 → 回一个 `get_order_facts` 的 tool_call；已经有 → 回 `finish_report`。
所以可以同时接多个并发调查（不需要按连接记状态）。

用法：
    STUB_DELAY_MS=3000 python scripts/stub-llm-agent.py 18080
    LLM_API_URL=http://127.0.0.1:18080/v1/chat/completions <启动后端>
"""

import json
import os
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

DELAY_MS = int(os.environ.get("STUB_DELAY_MS", "0"))


class Handler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def log_message(self, *args):
        pass

    def do_POST(self):
        length = int(self.headers.get("Content-Length", "0"))
        body = json.loads(self.rfile.read(length) or b"{}")
        messages = body.get("messages", [])
        seen_tool_answer = any(m.get("role") == "tool" for m in messages)
        order_no = "WO-20261007-90001"
        for m in messages:
            content = m.get("content") or ""
            if isinstance(content, str) and "WO-" in content:
                for token in content.split():
                    if token.startswith("WO-"):
                        order_no = token.strip("？?，,")
                        break

        if seen_tool_answer:
            message = {
                "role": "assistant",
                "content": None,
                "reasoning_content": "桩：证据够了，交报告",
                "tool_calls": [{
                    "id": "call_finish",
                    "type": "function",
                    "function": {
                        "name": "finish_report",
                        "arguments": json.dumps({
                            "problemType": "ORDER_STATUS",
                            "evidenceIds": ["E1", "E2", "E3"],
                            "suggestionIds": ["CONTACT_ASSIGNEE"],
                        }, ensure_ascii=False),
                    },
                }],
            }
        else:
            message = {
                "role": "assistant",
                "content": None,
                "reasoning_content": "桩：先读主工单",
                "tool_calls": [{
                    "id": "call_facts",
                    "type": "function",
                    "function": {
                        "name": "get_order_facts",
                        "arguments": json.dumps({"orderNo": order_no}),
                    },
                }],
            }

        if DELAY_MS:
            time.sleep(DELAY_MS / 1000.0)
        payload = json.dumps({"choices": [{"message": message}]}, ensure_ascii=False).encode("utf-8")
        self.send_response(200)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(payload)))
        self.end_headers()
        self.wfile.write(payload)


if __name__ == "__main__":
    import sys

    port = int(sys.argv[1]) if len(sys.argv) > 1 else 18080
    print(f"stub-llm-agent listening on :{port} delay={DELAY_MS}ms", flush=True)
    ThreadingHTTPServer(("127.0.0.1", port), Handler).serve_forever()
