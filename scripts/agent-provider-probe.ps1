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
    $toolMsg = @{ role = 'tool'; tool_call_id = $call.id; content = '{"tool":"get_order_facts","ok":true,"facts":{"order.exists":"true","order.status":"IN_PROGRESS"}}' }
    $withReasoning = $assistant
    $withoutReasoning = $assistant | Select-Object * -ExcludeProperty reasoning_content

    if ($hasReasoning) {
        # ③ 带 reasoning_content 回填
        $t3 = Invoke-Probe 'POST' $apiUrl (New-ChatBody @(
                @{ role = 'system'; content = '你是工单调查助手。' },
                @{ role = 'user'; content = '工单 WO-20260607-00001 现在到哪一步了？' },
                $withReasoning, $toolMsg))
        Add-Result '③ 带 reasoning_content 回填' '200' ("HTTP " + $t3.Status) ($t3.Status -eq 200)

        # ④ 去掉它
        $t4 = Invoke-Probe 'POST' $apiUrl (New-ChatBody @(
                @{ role = 'system'; content = '你是工单调查助手。' },
                @{ role = 'user'; content = '工单 WO-20260607-00001 现在到哪一步了？' },
                $withoutReasoning, $toolMsg))
        $mentions = ($t4.Body -like '*reasoning_content*')
        $t4StatusText = "HTTP " + $t4.Status + "；错误含 reasoning_content=" + $mentions
        Add-Result '④ 去掉 reasoning_content 回填' '400 且错误信息含 reasoning_content' $t4StatusText ($t4.Status -eq 400 -and $mentions)
    } else {
        Add-Result '③ 带 reasoning_content 回填' '200' '本轮响应没有 reasoning_content（该模型/模式未开 thinking）' $false 'SKIP' 'SKIP'
        Add-Result '④ 去掉 reasoning_content 回填' '400' '同上（换 thinking 模式或加 -ExtraJson 后重跑）' $false 'SKIP' 'SKIP'
    }

    # ⑤ 缺配对的 tool 消息
    $t5 = Invoke-Probe 'POST' $apiUrl (New-ChatBody @(
            @{ role = 'system'; content = '你是工单调查助手。' },
            @{ role = 'user'; content = '工单 WO-20260607-00001 现在到哪一步了？' },
            $assistant))
    Add-Result '⑤ tool_calls 缺配对 tool 消息' '400' ("HTTP " + $t5.Status) ($t5.Status -eq 400)
} else {
    Add-Result '③ 带 reasoning_content 回填' '200' '前置 ② 未通过，未探测' $false 'BLOCKED' 'FAIL'
    Add-Result '④ 去掉 reasoning_content 回填' '400' '前置 ② 未通过，未探测' $false 'BLOCKED' 'FAIL'
    Add-Result '⑤ tool_calls 缺配对 tool 消息' '400' '前置 ② 未通过，未探测' $false 'BLOCKED' 'FAIL'
}

Write-Host ''
$results | Format-Table -AutoSize
$fail = @($results | Where-Object { $_.结果 -eq 'FAIL' }).Count
$skip = @($results | Where-Object { $_.备注 -eq 'SKIP' }).Count
Write-Host ("汇总：PASS {0} / FAIL {1} / SKIP {2}" -f (@($results | Where-Object { $_.结果 -eq 'PASS' }).Count), $fail, $skip)
Write-Host "（结论只对本次模型/模式成立；换模型或版本必须重跑——本脚本不把任何结论写回代码。）"
exit $(if ($fail -gt 0) { 1 } else { 0 })
