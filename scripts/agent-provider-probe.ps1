#Requires -Version 7.0
<#
.SYNOPSIS
  调查助手：**供应商兼容性探测**（可复跑）。

.DESCRIPTION
  用来验证 `HttpAgentModel` 的两条协议假设在本机真 key 下是否成立，并复现 §11-1 的代价：
    ① /models 可用，且目标模型在列表里；
    ② 工具调用形状与实现假设一致（tool_calls[].function.arguments 是 **JSON 字符串**）；
    ③ **带 `reasoning_content` 原样回填 → 200**；
    ④ 去掉它 → 400，且错误信息含 `reasoning_content`；
    ⑤ assistant 的 `tool_calls` 缺少配对 `tool` 消息 → 400（§10 那条修复的必要性）。

  ⚠ **这类规则按模型而异，不能按供应商推广**：同一家换个模型、或同模型换个版本，
  ③ / ④ 的结论都可能翻转。本脚本存在的意义就是**换模型/供应商时能重跑一遍**，
  而不是把某次的结论写死进代码——代码里只放"有复现证据"的 denylist（当前为空集）。
  **结论只对本次运行的模型 + 模式成立；换模型必须重跑，不得沿用上次的 PASS/FAIL。**

  📌 **通则：负向判据必须用"系统不可能认得的输入"。**
  ④ 最初用**真实 tool_call id** 去掉 `reasoning_content`，结果拿到 200、被误报成 FAIL——
  因为服务端似乎**按 id 缓存了 thinking 内容**，"删字段"在有缓存时不会暴露问题；
  换成**合成 id**（`call_probe_1`，服务端不可能认得）后稳定 400。
  同族的三次事故（都不是"实现错"，而是"负向判据的输入不干净"）：
    ① 2026-10-06 本脚本 ④：真实 id 命中服务端缓存 → 负向路径被正向吃掉；
    ② 2026-10-06 夹具自检：用 `contains("RELEASE")` 判 DEV-04 的**否定式描述**
       "空（无 ACCEPT/ASSIGN/RELEASE/MANAGE）" → 自检自己误报"描述含 RELEASE"；
    ③ 同日日志物化：用 `contains("ACCEPT")` 判同一句否定式描述 → "从未接单"的单被物化成有接单记录。
  共同点：输入里**已经含有**关键字/标识，于是"该失败的路径"被当成"该成功的路径"。

  key 从 `deploy/.env`（`.gitignore` 已覆盖）读，**脚本不打印 key**，也不把 key 写进任何文件。
  真正的实测记录（模型名 + 日期 + 结论）写在 `docs/AGENT-PLAN.md` §6.1 与 `docs/DECISIONS.md` D78 的追加引用块里。

.PARAMETER ExtraJson
  追加进请求体的 JSON 片段（字符串）。例如某些供应商的 thinking 模式需要：
  `-ExtraJson '{"thinking":{"type":"enabled"}}'`

.EXAMPLE
  pwsh -File scripts/agent-provider-probe.ps1
  pwsh -File scripts/agent-provider-probe.ps1 -ExtraJson '{"thinking":{"type":"enabled"}}'
#>
param(
    [string]$EnvFile = (Join-Path (Split-Path $PSScriptRoot -Parent) 'deploy\.env'),
    [string]$Model = '',
    [string]$ExtraJson = '',
    [int]$TimeoutSec = 60
)

$ErrorActionPreference = 'Stop'

function Read-DotEnv([string]$Path) {
    $map = @{}
    if (-not (Test-Path -LiteralPath $Path)) { return $map }
    foreach ($line in Get-Content -LiteralPath $Path) {
        $trimmed = $line.Trim()
        if ($trimmed.Length -eq 0 -or $trimmed.StartsWith('#')) { continue }
        $idx = $trimmed.IndexOf('=')
        if ($idx -lt 1) { continue }
        $map[$trimmed.Substring(0, $idx).Trim()] = $trimmed.Substring($idx + 1).Trim()
    }
    return $map
}

$envMap = Read-DotEnv $EnvFile
$apiUrl = $envMap['LLM_API_URL']
$apiKey = $envMap['LLM_API_KEY']
$targetModel = if ($Model) { $Model } else { $envMap['LLM_MODEL'] }

if ([string]::IsNullOrWhiteSpace($apiUrl) -or [string]::IsNullOrWhiteSpace($apiKey) -or [string]::IsNullOrWhiteSpace($targetModel)) {
    Write-Host "缺 LLM_API_URL / LLM_API_KEY / LLM_MODEL（应在 $EnvFile）。**未发起任何请求。**" -ForegroundColor Yellow
    Write-Host "把三项写进 deploy/.env（该文件已被 .gitignore 覆盖）后重跑本脚本。" -ForegroundColor Yellow
    exit 2
}

$baseUrl = $apiUrl -replace '/chat/completions/?$', ''
$headers = @{ Authorization = "Bearer $apiKey" }   # 注意：任何输出里都不得出现 $apiKey
$extra = @{}
if ($ExtraJson) {
    (ConvertFrom-Json $ExtraJson).PSObject.Properties | ForEach-Object { $extra[$_.Name] = $_.Value }
}

function Invoke-Probe([string]$Method, [string]$Uri, $Body) {
    $params = @{ Method = $Method; Uri = $Uri; Headers = $headers; TimeoutSec = $TimeoutSec; SkipHttpErrorCheck = $true }
    if ($null -ne $Body) {
        $params['ContentType'] = 'application/json'
        $params['Body'] = ($Body | ConvertTo-Json -Depth 12 -Compress)
    }
    $resp = Invoke-WebRequest @params
    [pscustomobject]@{ Status = [int]$resp.StatusCode; Body = $resp.Content }
}

function New-ChatBody([array]$Messages) {
    $body = [ordered]@{
        model       = $targetModel
        messages    = $Messages
        tools       = @(@{
                type     = 'function'
                function = @{
                    name        = 'get_order_facts'
                    description = '按工单编号取事实'
                    parameters  = @{
                        type       = 'object'
                        properties = @{ orderNo = @{ type = 'string' } }
                        required   = @('orderNo')
                    }
                }
            })
        tool_choice = 'auto'
        temperature = 0
    }
    foreach ($k in $extra.Keys) { $body[$k] = $extra[$k] }
    return $body
}

$results = [System.Collections.Generic.List[object]]::new()
function Add-Result([string]$Name, [string]$Expect, $Actual, [bool]$Pass, [string]$Note = '', [string]$Status = '') {
    $verdict = if ($Status) { $Status } elseif ($Pass) { 'PASS' } else { 'FAIL' }
    $results.Add([pscustomobject]@{ 探测项 = $Name; 期望 = $Expect; 实际 = $Actual; 结果 = $verdict; 备注 = $Note })
}

Write-Host "探测目标：$baseUrl（模型 $targetModel）；key 来自 $EnvFile，**不回显**。" -ForegroundColor Cyan

# ① /models
try {
    $models = Invoke-Probe 'GET' "$baseUrl/models" $null
    $ids = @()
    if ($models.Status -eq 200) { $ids = @((ConvertFrom-Json $models.Body).data | ForEach-Object { $_.id }) }
    $listed = $ids -contains $targetModel
    Add-Result '① /models' '200 且含目标模型' ("HTTP " + $models.Status + "；命中=" + $listed + "；共 " + $ids.Count + " 个模型") ($models.Status -eq 200 -and $listed)
} catch {
    Add-Result '① /models' '200 且含目标模型' ("异常：" + $_.Exception.Message) $false
}

# ② 工具调用形状
$turn1 = $null
try {
    $turn1 = Invoke-Probe 'POST' $apiUrl (New-ChatBody @(
            @{ role = 'system'; content = '你是工单调查助手，只能基于工具返回的事实作答。' },
            @{ role = 'user'; content = '工单 WO-20260607-00001 现在到哪一步了？' }))
    $msg = (ConvertFrom-Json $turn1.Body).choices[0].message
    $call = $msg.tool_calls[0]
    $argsIsString = $call.function.arguments -is [string]
    $parsedOk = $false
    if ($argsIsString) { try { $null = ConvertFrom-Json $call.function.arguments; $parsedOk = $true } catch { } }
    Add-Result '② tool_calls 形状' '200 且 arguments 是 JSON 字符串' ("HTTP " + $turn1.Status + "；arguments 类型=" + $(if ($null -ne $call) { $call.function.arguments.GetType().Name } else { '无 tool_call' }) + "；可解析=" + $parsedOk) ($turn1.Status -eq 200 -and $argsIsString -and $parsedOk)
} catch {
    Add-Result '② tool_calls 形状' '200 且 arguments 是 JSON 字符串' ("异常：" + $_.Exception.Message) $false
}

if ($null -ne $turn1 -and $turn1.Status -eq 200) {
    $assistant = (ConvertFrom-Json $turn1.Body).choices[0].message
    $call = $assistant.tool_calls[0]
    $hasReasoning = ($assistant.PSObject.Properties.Name -contains 'reasoning_content')
    $toolContent = '{"tool":"get_order_facts","ok":true,"facts":{"order.exists":"true","order.status":"IN_PROGRESS"}}'
    $toolMsg = @{ role = 'tool'; tool_call_id = $call.id; content = $toolContent }
    $withReasoning = $assistant
    $withoutReasoning = $assistant | Select-Object * -ExcludeProperty reasoning_content

    if ($hasReasoning) {
        # ③ 带 reasoning_content 回填
        $t3 = Invoke-Probe 'POST' $apiUrl (New-ChatBody @(
                @{ role = 'system'; content = '你是工单调查助手。' },
                @{ role = 'user'; content = '工单 WO-20260607-00001 现在到哪一步了？' },
                $withReasoning, $toolMsg))
        Add-Result '③ 带 reasoning_content 回填' '200' ("HTTP " + $t3.Status) ($t3.Status -eq 200)

        # ④ 去掉它——**必须换合成 id**（真实 id 会命中服务端缓存，见文件头通则）
        $syntheticAssistant = ($assistant | ConvertTo-Json -Depth 12 | ConvertFrom-Json)
        $syntheticAssistant.tool_calls[0].id = 'call_probe_1'
        if ($syntheticAssistant.PSObject.Properties.Name -contains 'reasoning_content') {
            $syntheticAssistant.PSObject.Properties.Remove('reasoning_content')
        }
        $syntheticToolMsg = @{ role = 'tool'; tool_call_id = 'call_probe_1'; content = $toolContent }
        $t4 = Invoke-Probe 'POST' $apiUrl (New-ChatBody @(
                @{ role = 'system'; content = '你是工单调查助手。' },
                @{ role = 'user'; content = '工单 WO-20260607-00001 现在到哪一步了？' },
                $syntheticAssistant, $syntheticToolMsg))
        $mentions = ($t4.Body -like '*reasoning_content*')
        $t4StatusText = "HTTP " + $t4.Status + "（合成 id call_probe_1）；错误含 reasoning_content=" + $mentions
        Add-Result '④ 去掉 reasoning_content 回填（合成 id）' '400 且错误信息含 reasoning_content' $t4StatusText ($t4.Status -eq 400 -and $mentions)

        # 观察项（**不计 PASS/FAIL**）：真实 id + 去掉 reasoning —— 记录实际状态码，用来说明存在服务端缓存
        $t4obs = Invoke-Probe 'POST' $apiUrl (New-ChatBody @(
                @{ role = 'system'; content = '你是工单调查助手。' },
                @{ role = 'user'; content = '工单 WO-20260607-00001 现在到哪一步了？' },
                $withoutReasoning, $toolMsg))
        Add-Result '（观察）真实 id + 去掉 reasoning' '不判 PASS/FAIL，只记录' `
            ("HTTP " + $t4obs.Status + "（真实 id " + $call.id + "）") $true '服务端疑似按 id 缓存 thinking 内容：有缓存时删字段不会暴露问题' 'OBSERVE'
    } else {
        Add-Result '③ 带 reasoning_content 回填' '200' '本轮响应没有 reasoning_content（该模型/模式未开 thinking）' $false 'SKIP' 'SKIP'
        Add-Result '④ 去掉 reasoning_content 回填（合成 id）' '400' '同上（换 thinking 模式或加 -ExtraJson 后重跑）' $false 'SKIP' 'SKIP'
        Add-Result '（观察）真实 id + 去掉 reasoning' '不判 PASS/FAIL，只记录' '同上，未探测' $false 'SKIP' 'SKIP'
    }

    # ⑤ 缺配对的 tool 消息
    $t5 = Invoke-Probe 'POST' $apiUrl (New-ChatBody @(
            @{ role = 'system'; content = '你是工单调查助手。' },
            @{ role = 'user'; content = '工单 WO-20260607-00001 现在到哪一步了？' },
            $assistant))
    Add-Result '⑤ tool_calls 缺配对 tool 消息' '400' ("HTTP " + $t5.Status) ($t5.Status -eq 400)
} else {
    Add-Result '③ 带 reasoning_content 回填' '200' '前置 ② 未通过，未探测' $false 'BLOCKED' 'FAIL'
    Add-Result '④ 去掉 reasoning_content 回填（合成 id）' '400' '前置 ② 未通过，未探测' $false 'BLOCKED' 'FAIL'
    Add-Result '（观察）真实 id + 去掉 reasoning' '不判 PASS/FAIL，只记录' '同上，未探测' $false 'BLOCKED' 'FAIL'
    Add-Result '⑤ tool_calls 缺配对 tool 消息' '400' '前置 ② 未通过，未探测' $false 'BLOCKED' 'FAIL'
}

Write-Host ''
$results | Format-Table -AutoSize
$fail = @($results | Where-Object { $_.结果 -eq 'FAIL' }).Count
$skip = @($results | Where-Object { $_.备注 -eq 'SKIP' }).Count
$observe = @($results | Where-Object { $_.结果 -eq 'OBSERVE' }).Count
Write-Host ("汇总：PASS {0} / FAIL {1} / SKIP {2} / OBSERVE {3}（观察项不计 PASS/FAIL）" -f (@($results | Where-Object { $_.结果 -eq 'PASS' }).Count), $fail, $skip, $observe)
Write-Host "（结论只对本次模型/模式成立；换模型或版本必须重跑——本脚本不把任何结论写回代码。）"
exit $(if ($fail -gt 0) { 1 } else { 0 })
