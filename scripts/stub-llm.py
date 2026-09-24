#!/usr/bin/env python3
"""可控延迟的 LLM 桩——**仅供确定性延迟演练（取延迟数字时用），不作为功能验收工具**。

为什么需要它：P5 的验收要对比"提交时同步等 LLM"与"异步 triage"的延迟，而真实 LLM 需要 key 且延迟不可控。
本桩提供**可复现的同口径基线**：延迟由环境变量固定，响应体与真实 OpenAI 兼容接口一致。

⚠ **已知限制（本机实测，2026-09-25）**：Java 客户端（RestTemplate）↔ 本脚本之间在本机存在**连接层抖动**
（服务端关闭连接，客户端报 WSAECONNABORTED："你的主机中的软件中止了一个已建立的连接"，
Python 端还会额外吐出默认 HTML 错误页）。因此：
  · ✅ 可以用它：**取延迟数字**（curl/压测客户端侧不受影响，同一延迟口径可比）；
  · ❌ **不能**用它判定"应用行为对不对"（例如"启动自检能不能分辨正确模型名与错误模型名"）——
    那种连接层失败会被自检如实分类成"不可达或超时"，与模型名/vendor 无关。
**功能验证请指向真模型**：本机能访问供应商接口（host 侧 curl 已验证），
把 LLM_API_URL/LLM_API_KEY/LLM_MODEL 指向真实供应商即可。

用法：
    STUB_DELAY_MS=3000 python scripts/stub-llm.py 18080          # 3 秒后返回
    LLM_API_URL=http://127.0.0.1:18080/v1/chat/completions <启动后端>

环境变量：
    STUB_DELAY_MS   每次响应前的人为延迟（毫秒，默认 0）
    STUB_TYPE       返回的工单类型（默认 NETWORK —— 必须是当前合法类型集合里的值）
    STUB_PRIORITY   返回的优先级（默认 1）
    STUB_HTTP_STATUS 非 200 时返回该状态码（演练启动自检的错误分类：401 key 无效 / 400 模型名不对）
    STUB_ALLOWED_MODEL 只接受该模型名，其它模型名一律返回 400（演练"模型名与供应商不匹配"）

注意：真实模型基线的数字**必须**在配好 key 的机器上重取（本脚本只能给出同口径的相对对照）。
"""
import json
import os
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

DELAY_MS = int(os.environ.get("STUB_DELAY_MS", "0"))
TYPE = os.environ.get("STUB_TYPE", "NETWORK")
PRIORITY = int(os.environ.get("STUB_PRIORITY", "1"))
HTTP_STATUS = int(os.environ.get("STUB_HTTP_STATUS", "200"))
ALLOWED_MODEL = os.environ.get("STUB_ALLOWED_MODEL", "")


class Handler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def do_POST(self):  # noqa: N802 (http.server 的命名约定)
        try:
            length = int(self.headers.get("Content-Length", "0"))
            raw = self.rfile.read(length)
            time.sleep(DELAY_MS / 1000.0)

            status = HTTP_STATUS
            if ALLOWED_MODEL:
                try:
                    requested = json.loads(raw or b"{}").get("model")
                except Exception:
                    requested = None
                if requested != ALLOWED_MODEL:
                    status = 400   # 只拒绝**不在白名单里**的模型名；正确模型必须返回 200

            if status == 200:
                content = json.dumps({"type": TYPE, "priority": PRIORITY})
                body = json.dumps({"choices": [{"message": {"role": "assistant", "content": content}}]}).encode()
            else:  # 演练错误分类用：返回 OpenAI 风格的错误体
                body = json.dumps({"error": {"message": f"stub error {status}", "type": "stub_error"}}).encode()

            self.send_response(status)
            self.send_header("Content-Type", "application/json")
            self.send_header("Content-Length", str(len(body)))
            # **必须显式关闭连接**：Java 的 HttpURLConnection 默认 keep-alive，会复用连接发后续请求；
            # 早期版本没关连接时，客户端会看到 "I/O error … 连接被中止"（Python 端随后还输出了默认错误页）。
            # 桩工具宁可每次新建连接，也不要让这种抖动干扰"自检能不能分辨对错"的验证。
            self.send_header("Connection", "close")
            self.close_connection = True
            self.end_headers()
            self.wfile.write(body)
            self.wfile.flush()
        except Exception as e:   # 不要落到 http.server 的默认错误页（那会把响应体污染成 HTML）
            print(f"stub-llm handler error: {e!r}", flush=True)

    def log_message(self, *args):  # 压测时不要刷屏
        pass


if __name__ == "__main__":
    port = int(os.sys.argv[1]) if len(os.sys.argv) > 1 else 18080
    print(f"stub-llm listening on :{port} delay={DELAY_MS}ms type={TYPE} priority={PRIORITY}", flush=True)
    ThreadingHTTPServer(("127.0.0.1", port), Handler).serve_forever()
