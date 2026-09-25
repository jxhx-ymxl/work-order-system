# ============================================================
# 工单提交压测（Windows 等价实现，语义与 scripts/loadtest.sh 对齐）
#
# 为什么单独有一份：本机（Windows）没有可用的 bash，而 P5 的"改造前后 P99 对照"必须在同一台机器、
# 同一套语义下取。三条语义与 shell 版一致：
#   1) **校验业务 code**：HTTP 200 不等于成功，脚本解析响应体里的 "code"，非 200 计为业务失败；
#   2) **预热分离**：WARMUP_WAVES 波数据只报告不统计。
#   3) **每请求计时**：每个样本记"它自己完成"的时刻（按完成顺序收割任务），
#      **不是**"整波最慢值"。⚠ 2026-09-25 之前本脚本写成了 `WaitAll` 之后统一 `Stop()`，
#      于是同一波里所有样本都等于该波的耗时——把 P50 从 3.1s 抬到 6.2s（实测），
#      也与 bash 版（curl 的 `%{time_total}`）口径不一致（这正是 CLAUDE.md §5 那条规则要防的事）。
#
# 用法（全部配置走环境变量，与 shell 版同名）：
#   $env:BASE_URL='http://127.0.0.1:9000'; $env:OMIT_TYPE='1'; $env:CONCURRENCY='30'
#   $env:WARMUP_WAVES='1'; $env:DURATION_SEC='20'; pwsh -File scripts/loadtest.ps1
#
# ⚠ 账号变量名与 shell 版不同：这里用 API_USER，**不能用 USERNAME**——
#   PowerShell 里 $env:USERNAME 是 Windows 自动设置的当前用户名，会被当成登录账号（实测踩到）。
# ============================================================
param()
$ErrorActionPreference = 'Stop'

$baseUrl     = if ($env:BASE_URL) { $env:BASE_URL } else { 'http://127.0.0.1:9000' }
$username    = if ($env:API_USER) { $env:API_USER } else { 'admin' }
$password    = if ($env:PASSWORD) { $env:PASSWORD } else { 'admin123' }
$concurrency = [int]($env:CONCURRENCY ?? 10)
$warmupWaves = [int]($env:WARMUP_WAVES ?? 1)
$durationSec = [int]($env:DURATION_SEC ?? 20)
$omitType    = ($env:OMIT_TYPE ?? '0') -eq '1'
# TRIAGE_MODE：sync（默认）= 改造前同步形态（OTHER 视为降级样本，剔除出延迟统计）；
#               async = P5 之后异步形态（OTHER 必然正确，计入 ok 与延迟统计，末尾提示核对 triage_status）
$triageMode  = if ($env:TRIAGE_MODE) { $env:TRIAGE_MODE } else { 'sync' }
$outDir      = if ($env:OUT_DIR) { $env:OUT_DIR } else { './loadtest-out' }
New-Item -ItemType Directory -Force -Path $outDir | Out-Null

$client = [System.Net.Http.HttpClient]::new()
$client.Timeout = [TimeSpan]::FromSeconds(120)

# ── 登录 ──
$loginBody = (@{ username = $username; password = $password } | ConvertTo-Json -Compress)
$login = $client.PostAsync("$baseUrl/api/login",
    [System.Net.Http.StringContent]::new($loginBody, [Text.Encoding]::UTF8, 'application/json')).Result
$loginText = $login.Content.ReadAsStringAsync().Result
$token = ([regex]'"token"\s*:\s*"([^"]+)"').Match($loginText).Groups[1].Value
if (-not $token) { throw "登录失败：$loginText" }
Write-Output "登录成功（token 长度 $($token.Length)）"

$types = @('NETWORK', 'UTILITY', 'DORM', 'OTHER')
$warmup = [System.Collections.Generic.List[double]]::new()
$measured = [System.Collections.Generic.List[double]]::new()
$okCount = 0; $failCount = 0; $warmupOk = 0; $warmupFail = 0
$fallbackCount = 0; $warmupFallback = 0     # triage 未生效（OMIT_TYPE=1 且响应 type 仍是兜底值）
$typeDist = @{}                             # 响应体 type 分布（供人工核对）
$deadline = (Get-Date).AddSeconds($durationSec)
$wave = 0
$sw = [System.Diagnostics.Stopwatch]::new()

while ($true) {
    $wave++
    $isWarmup = $wave -le $warmupWaves
    if (-not $isWarmup -and (Get-Date) -gt $deadline) { break }

    $tasks = @()
    $starts = @()
    for ($i = 0; $i -lt $concurrency; $i++) {
        $idx = $wave * $concurrency + $i
        if ($omitType) {
            $payload = @{ title = "压测-triage-$idx"; content = 'loadtest，不带类型' } | ConvertTo-Json -Compress
        } else {
            $payload = @{ title = "压测-$idx"; content = 'loadtest'
                          type = $types[$idx % 4]; priority = ($idx % 2) } | ConvertTo-Json -Compress
        }
        $req = [System.Net.Http.HttpRequestMessage]::new('Post', "$baseUrl/api/orders")
        $req.Headers.Add('Authorization', $token)
        $req.Content = [System.Net.Http.StringContent]::new($payload, [Text.Encoding]::UTF8, 'application/json')
        $starts += [System.Diagnostics.Stopwatch]::StartNew()
        $tasks += $client.SendAsync($req)
    }
    # ── 按**完成顺序**收割任务：每个样本记的是"它自己完成"的时刻 ──
    # ⚠ 这里**不能**写成 `WaitAll` 之后再统一 `$starts[$i].Stop()`：那样每个样本拿到的是
    #   **整波最慢那个请求的耗时**（同一波里所有样本的 Stop 时刻几乎相同）。
    #   本机实测（2026-09-25，改造前构建 + 桩延迟 3s）：30 并发下全部样本都是 6.17s，
    #   而真实分布是"20 个 ≈3.1s（池容量 20）+ 10 个 ≈6.2s（排队等连接）"——
    #   错误的计时把 P50 从 3.1s 抬到 6.2s，也让"改造后"的 P50/P95/P99 变成"每波最慢值"。
    #   这正是 CLAUDE.md §5 那条"两版脚本必须同步改"要防的口径分叉：bash 版用 curl 的
    #   `%{time_total}`，本来就是**每请求**的真实耗时。
    $pending = [System.Collections.Generic.List[object]]::new()
    for ($i = 0; $i -lt $tasks.Count; $i++) {
        $pending.Add([pscustomobject]@{ Task = $tasks[$i]; Sw = $starts[$i] })
    }
    while ($pending.Count -gt 0) {
        $doneIdx = [System.Threading.Tasks.Task]::WaitAny(@($pending | ForEach-Object { $_.Task }))
        $item = $pending[$doneIdx]
        $item.Sw.Stop()
        $ms = $item.Sw.Elapsed.TotalMilliseconds
        $text = $item.Task.Result.Content.ReadAsStringAsync().Result
        $pending.RemoveAt($doneIdx)
        $code = ([regex]'"code"\s*:\s*(\d+)').Match($text).Groups[1].Value
        # P5 收口：OMIT_TYPE=1 时还要看响应体的 type——**压测的通过判据必须覆盖"被测的那条路径真的执行了"**，
        # 否则"triage 静默降级"会让延迟看起来更快，把假数字当真数字（与 HTTP 200 ≠ 业务成功同一家族）。
        $type = ([regex]'"type"\s*:\s*"([^"]*)"').Match($text).Groups[1].Value
        if (-not $type) { $type = 'unknown' }
        $typeDist[$type] = 1 + [int]($typeDist[$type] ?? 0)
        $businessOk = ($code -eq '200')
        $isFallback = ($businessOk -and $omitType -and $triageMode -eq 'sync' -and $type -eq 'OTHER')
        if ($isWarmup) {
            if ($isFallback) { $warmupFallback++ } elseif ($businessOk) { $warmupOk++ } else { $warmupFail++ }
        } else {
            if ($isFallback) { $fallbackCount++ }
            elseif ($businessOk) { $okCount++; $measured.Add($ms) }   # 只有 ok 样本进延迟统计
            else { $failCount++ }
        }
    }
    if ($isWarmup) { Write-Output "预热第 $wave 波完成（$concurrency 个请求，不计入统计）" }
}

function Percentile($list, $p) {
    if ($list.Count -eq 0) { return [double]::NaN }
    $sorted = $list | Sort-Object
    $rank = [Math]::Ceiling($p / 100.0 * $sorted.Count) - 1
    if ($rank -lt 0) { $rank = 0 }
    return [Math]::Round($sorted[$rank], 1)
}

Write-Output "== 预热（不计入统计）=="
Write-Output ("  请求数={0} 业务成功={1} 业务失败={2} P50={3}ms" -f $warmup.Count, $warmupOk, $warmupFail, (Percentile $warmup 50))
Write-Output "== 统计窗口 =="
Write-Output ("  并发={0} 请求数={1} 业务成功={2} 业务失败={3} **triage 未生效={4}**" -f $concurrency,
    ($okCount + $failCount + $fallbackCount), $okCount, $failCount, $fallbackCount)
$distText = ($typeDist.GetEnumerator() | Sort-Object Name | ForEach-Object { "$($_.Name)×$($_.Value)" }) -join '  '
Write-Output ("  type 分布：{0}（供人工核对：真模型确实判 OTHER 时只能靠人看）" -f $distText)
Write-Output ("  P50={0}ms P95={1}ms P99={2}ms max={3}ms" -f (Percentile $measured 50), (Percentile $measured 95),
    (Percentile $measured 99), [Math]::Round(($measured | Measure-Object -Maximum).Maximum, 1))
if ($triageMode -eq 'async') {
    Write-Output ""
    Write-Output "== TRIAGE_MODE=async：响应 type=OTHER 属正常（兜底落库），已计入 ok 与延迟统计 =="
    Write-Output "   「triage 是否真的执行」请核对下面两处（提交响应里判定不出来）："
    Write-Output "     mysql -uroot -p -e `"SELECT triage_status, COUNT(*) FROM work_order.t_work_order WHERE title LIKE '压测-triage-%' GROUP BY triage_status;`""
    Write-Output "     docker compose logs backend | grep '分诊写回成功'"
}
$measured | ForEach-Object { $_ } | Set-Content "$outDir/measured_latency_ps.txt"
