#!/usr/bin/env python3
"""可控延迟的 LLM 桩——**仅供确定性延迟演练（取延迟数字时用），不作为功能验收工具**。

为什么需要它：P5 的验收要对比"提交时同步等 LLM"与"异步 triage"的延迟，而真实 LLM 需要 key 且延迟不可控。
本桩提供**可复现的同口径基线**：延迟由环境变量固定，响应体与真实 OpenAI 兼容接口一致。

⚠ **根因已定位并修复（2026-09-25）**：此前把 Java 客户端读不通本桩记作"连接层抖动
（WSAECONNABORTED / 连接被中止）"。真实原因是**本桩只按 `Content-Length` 读请求体，而 Java/Spring 的 POST
用 `Transfer-Encoding: chunked`**（实测请求头：`"Transfer-Encoding": "chunked"`，**没有** `Content-Length`）。
于是桩读到 0 字节 → 解析失败 → 判成"模型名不在白名单" → **返回 400**；
并且它在客户端**还在发送请求体时**就写回了响应，客户端因此报"连接被中止"。
**后果很重**：过去用本桩测出的"改造前（同步等 LLM）"数字实际上是**"慢的 400"**——
延迟量级仍由桩的固定 delay 决定（所以结论方向不变），但**一次真正的 LLM 往返都没走通**，
响应体 `type` 会是兜底的 `OTHER`（这正是 `TRIAGE_MODE` + type 分布要拦住的那类假数字）。
现状：已支持 chunked 请求体（见 `_read_body`），Java 客户端可正常拿到 200 + 分类结果（本轮实测）。

因此本桩的定位收窄为一条（**保留**）：
  · ✅ 可以用它：**取延迟数字**（延迟由环境变量固定，口径可复现、可比）；
  · ❌ **不**用它评测模型质量/分类准确率（它返回的是写死的 type/priority）。
  原"Java 读不通本桩"的限制**已消失**（修复后实测：启动自检能正确区分 200 / 400 / 401）。

⚠ **第二处修复（同轮）**：30 并发下约 18% 的请求报
`I/O error on POST … Connection refused: getsockopt`（应用端降级为兜底值，压测行被污染）。
根因是 `socketserver` 的**监听队列默认只有 5**：并发一上来，内核队列满，Windows 直接拒绝新连接
（不是超时、不是重置，是"连接被拒"）。现已把 `request_queue_size` 提到 128（见 `StubServer`）。

⚠ **Windows 上的一个坑**（排查时踩到）：`allow_reuse_address=True`（http.server 默认）在 Windows 上
**允许两个进程同时绑同一端口**，于是"重启桩"可能只是又起了一个进程，请求仍打到旧的（未修复的）那个。
换端口/重启前先确认旧进程已退出（本机实测：4 个 python 进程同时绑 18080，请求落到了旧进程上）。
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

    def _read_body(self):
        """读请求体：**必须同时支持 chunked**。

        Java/Spring 的 RestTemplate 用 `Transfer-Encoding: chunked` 发 POST（没有 Content-Length），
        只按 Content-Length 读会读到 0 字节 → 解析失败 → 被白名单判成"模型名不对" → 返回 400
        （客户端还会因为桩提前写响应而报"连接被中止"）。这是本桩 2026-09-25 之前所有
        "改造前"数字失真的根因。
        """
        te = (self.headers.get("Transfer-Encoding") or "").lower()
        if "chunked" in te:
            chunks = []
            while True:
                line = self.rfile.readline()
                if not line:
                    break
                size_field = line.strip().split(b";")[0]
                if not size_field:
                    continue
                try:
                    size = int(size_field, 16)
                except ValueError:
                    break
                if size == 0:                      # 结束块：吞掉 trailer 直到空行
                    while True:
                        trailer = self.rfile.readline()
                        if trailer in (b"\r\n", b"\n", b""):
                            break
                    break
                chunks.append(self.rfile.read(size))
                self.rfile.read(2)                 # 每个块后面的 CRLF
            return b"".join(chunks)
        length = int(self.headers.get("Content-Length") or "0")
        return self.rfile.read(length) if length > 0 else b""

    def do_POST(self):  # noqa: N802 (http.server 的命名约定)
        try:
            raw = self._read_body()
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


class StubServer(ThreadingHTTPServer):
    """监听队列 5 → 128。

    默认的 `request_queue_size = 5` 在 30 并发压测下会被打满，Windows 上表现为客户端拿到
    `Connection refused: getsockopt`（应用侧降级成兜底值，于是"改造前"那一行被污染成
    "部分走通、部分兜底"）。这不是被测系统的问题，是桩自己的容量问题——压测工具的容量必须显著高于被测系统。
    """

    request_queue_size = 128
    daemon_threads = True


if __name__ == "__main__":
    port = int(os.sys.argv[1]) if len(os.sys.argv) > 1 else 18080
    print(f"stub-llm listening on :{port} delay={DELAY_MS}ms type={TYPE} priority={PRIORITY}", flush=True)
    StubServer(("127.0.0.1", port), Handler).serve_forever()
