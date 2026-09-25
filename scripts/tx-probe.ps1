# 一次性验证脚本（不入库）：量"LLM 调用期间是否占住数据库连接"与"20 单总耗时"。
# 用法：pwsh -File loadtest-out/txprobe.ps1 -Count 20 -Tag before@1 -Db wo_txprobe
param([int]$Count = 20, [int]$TimeoutSec = 240, [string]$Db = 'wo_txprobe', [string]$Tag = 'run')

$mysql = "E:\CS\tool\MySQL\MySQL Server 8.0\bin\mysql.exe"
$auth = @('--host=127.0.0.1', '--user=root', '--password=123456')

function Get-Conn {
  $line = (& $mysql @auth -N -B -e "SHOW STATUS LIKE 'Threads_connected';" 2>$null) -join ''
  return [int](($line -split "`t")[-1])
}

# **真正能判定"是否持有连接"的指标**：未提交事务数。
# Threads_connected 统计的是"打开的会话"（Hikari 池里的连接），池一涨就不会马上缩（idle-timeout 5min），
# 所以它对"LLM 期间是否占着连接"不敏感（本轮实测：修复前后都停在 8~11，看不出差别——见 D67）。
# 而"未提交事务" = 那条连接**正被事务持有**：LLM 调用在事务里时，整个调用期间事务都是开着的。
function Get-Trx {
  $line = (& $mysql @auth -N -B -e "SELECT COUNT(*) FROM information_schema.innodb_trx;" 2>$null) -join ''
  return [int]$line
}

# 基线（空载）：采样本身会占 1 条连接，before/after 同口径
$base = @()
1..3 | ForEach-Object { $base += (Get-Conn); Start-Sleep -Milliseconds 200 }

$lg = Invoke-WebRequest -Uri "http://127.0.0.1:9000/api/login" -Method Post `
  -Body '{"username":"admin","password":"admin123"}' -ContentType 'application/json' -UseBasicParsing
$token = ([regex]'"token"\s*:\s*"([^"]+)"').Match($lg.Content).Groups[1].Value

$client = [System.Net.Http.HttpClient]::new(); $client.Timeout = [TimeSpan]::FromSeconds(120)
$sw = [System.Diagnostics.Stopwatch]::StartNew()
$tasks = @()
$submitPeak = 0
for ($i = 0; $i -lt $Count; $i++) {
  $req = [System.Net.Http.HttpRequestMessage]::new('Post', "http://127.0.0.1:9000/api/orders")
  $req.Headers.Add('Authorization', $token)
  $req.Content = [System.Net.Http.StringContent]::new(
    (@{ title = "txprobe-$Tag-$i"; content = 'loadtest' } | ConvertTo-Json -Compress),
    [Text.Encoding]::UTF8, 'application/json')
  $tasks += $client.SendAsync($req)
  $c = Get-Conn; if ($c -gt $submitPeak) { $submitPeak = $c }   # 提交期（短事务）单独记，别和排空期混在一起
}
[System.Threading.Tasks.Task]::WaitAll($tasks)
$ids = @(); foreach ($t in $tasks) { $ids += ($t.Result.Content.ReadAsStringAsync().Result | ConvertFrom-Json).data.id }
$submitMs = [Math]::Round($sw.Elapsed.TotalMilliseconds, 0)

$samples = New-Object System.Collections.Generic.List[int]
$trxSamples = New-Object System.Collections.Generic.List[int]
$n = 0; $done = 0
$idsCsv = $ids -join ','
while ($sw.Elapsed.TotalSeconds -lt $TimeoutSec) {
  $c = Get-Conn
  $samples.Add($c)                                  # 排空期 = 消费端在处理（LLM 调用就在这里面）
  $trxSamples.Add((Get-Trx))
  $n++
  $done = [int](& $mysql @auth -N -B -e "SELECT COUNT(*) FROM $Db.t_work_order WHERE id IN ($idsCsv) AND triage_status <> 'PENDING';" 2>$null)
  if ($done -ge $Count) { break }
  Start-Sleep -Milliseconds 80
}
$sw.Stop()
# 只看峰值会被"5 个并发短事务恰好同时进行"顶起来；要判定"LLM 期间是否**持续**占连接"，
# 必须看排空期的**最小值与中位数**：若连接被 LLM 持续占住，最小值就会明显高于基线。
$sorted = $samples | Sort-Object
$min = $sorted[0]
$med = $sorted[[int]($sorted.Count / 2)]
$avg = [Math]::Round(($samples | Measure-Object -Average).Average, 1)
$high = ($samples | Where-Object { $_ -gt ($base[0] + 1) }).Count
$tSorted = $trxSamples | Sort-Object
$tMin = $tSorted[0]; $tMed = $tSorted[[int]($tSorted.Count / 2)]
$tHigh = ($trxSamples | Where-Object { $_ -ge 2 }).Count
Write-Output ("[{0}] 提交{1}单={2}ms  完成={3}/{1}  总耗时={4}s  基线={5}  提交期峰值={6}" -f `
  $Tag, $Count, $submitMs, $done, [Math]::Round($sw.Elapsed.TotalSeconds, 1), ($base -join '/'), $submitPeak)
Write-Output ("          排空期连接：min={0} median={1} avg={2} max={3}  高于基线+1的样本={4}/{5}" -f `
  $min, $med, $avg, $sorted[-1], $high, $samples.Count)
Write-Output ("          排空期**未提交事务**：min={0} median={1} max={2}  样本>=2个的占比={3}/{4}  ← 判定'LLM 期间是否持有连接'看这行" -f `
  $tMin, $tMed, $tSorted[-1], $tHigh, $trxSamples.Count)
