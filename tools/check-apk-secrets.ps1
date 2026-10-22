# ============================================================
# 发布前安全检查：解压 APK，扫所有 dex 里的密钥特征。
#
# 为什么需要这个脚本：
#   2026-10-22 的 v1.0.0 事故里，buildConfigField 把 local.properties 的
#   API Key 编进了 BuildConfig，最终出现在 classes.dex 里，
#   反编译就能 grep 到明文 sk-…。带毒的包一度发布到 GitHub Release。
#
#   此后构建层已强制 release 的 Key 为空（见 app/build.gradle.kts），
#   但「构建层改了」不等于「产物一定干净」—— 依赖升级、插件变更都可能
#   重新引入泄露。所以发布前必须跑一次，亲眼看到 0 命中。
#
# 用法：
#   powershell -NoProfile -ExecutionPolicy Bypass -File tools\check-apk-secrets.ps1
#   powershell -NoProfile -ExecutionPolicy Bypass -File tools\check-apk-secrets.ps1 -Apk path\to.apk
#
# 退出码：0 = 干净；1 = 发现可疑内容或出错
#
# ⚠️ 兼容 Windows PowerShell 5.1（这台机器没有 pwsh 7）：
#    不要在 try 块里 exit —— 5.1 的解析器会报 "Try statement is missing
#    its Catch or Finally block"。所有退出都放在 try/finally 之后。
# ============================================================

param(
    [string]$Apk = "app/build/outputs/apk/release/app-release.apk"
)

$ErrorActionPreference = "Stop"

if (-not (Test-Path $Apk)) {
    Write-Host "找不到 APK: $Apk" -ForegroundColor Red
    Write-Host "先跑 ./gradlew assembleRelease" -ForegroundColor Yellow
    exit 1
}

Write-Host "扫描: $Apk" -ForegroundColor Cyan
$sizeMB = [math]::Round((Get-Item $Apk).Length / 1MB, 2)
Write-Host "大小: $sizeMB MB"

$tmp = Join-Path $env:TEMP ("apk_secret_scan_" + [guid]::NewGuid().ToString("N"))
$total = 0

try {
    New-Item -ItemType Directory -Force -Path $tmp | Out-Null
    Copy-Item $Apk (Join-Path $tmp "a.zip")
    Expand-Archive (Join-Path $tmp "a.zip") -DestinationPath (Join-Path $tmp "x") -Force

    $dexFiles = @(Get-ChildItem (Join-Path $tmp "x") -Filter "*.dex" -ErrorAction SilentlyContinue)
    Write-Host "dex 文件数: $($dexFiles.Count)"

    if ($dexFiles.Count -eq 0) {
        Write-Host "APK 里没有 dex 文件？" -ForegroundColor Red
        $total = -1
    }
    else {
        $patterns = @(
            @{ Name = "DeepSeek/OpenAI 风格"; Regex = 'sk-[A-Za-z0-9_\-]{16,}' },
            @{ Name = "Google API Key";       Regex = 'AIza[0-9A-Za-z_\-]{30,}' },
            @{ Name = "GitHub Token";         Regex = 'gh[pousr]_[0-9A-Za-z]{30,}' },
            @{ Name = "AWS Access Key";       Regex = 'AKIA[0-9A-Z]{16}' },
            @{ Name = "PEM 私钥";             Regex = '-----BEGIN [A-Z ]*PRIVATE KEY-----' }
        )

        foreach ($dex in $dexFiles) {
            $text = [System.Text.Encoding]::ASCII.GetString([System.IO.File]::ReadAllBytes($dex.FullName))
            foreach ($p in $patterns) {
                $ms = [regex]::Matches($text, $p.Regex)
                if ($ms.Count -gt 0) {
                    $total += $ms.Count
                    Write-Host ""
                    Write-Host "!! $($dex.Name) 命中 $($p.Name) x$($ms.Count)" -ForegroundColor Red
                    foreach ($m in @($ms | Select-Object -First 3)) {
                        $v = $m.Value
                        if ($v.Length -gt 12) { $shown = $v.Substring(0, 12) + "..." } else { $shown = $v }
                        Write-Host "     $shown (长度 $($v.Length))" -ForegroundColor Red
                    }
                }
            }
        }
    }
}
finally {
    Remove-Item $tmp -Recurse -Force -ErrorAction SilentlyContinue
}

Write-Host ""
if ($total -eq 0) {
    Write-Host "通过：0 命中，APK 里没有发现密钥。" -ForegroundColor Green
    exit 0
}

Write-Host "失败：共 $total 处命中。这个包绝对不能发布！" -ForegroundColor Red
Write-Host ""
Write-Host "处理步骤：" -ForegroundColor Yellow
Write-Host "  1. 删除这个 APK，不要上传到任何地方"
Write-Host "  2. 若密钥已外泄，立刻去对应平台作废该密钥"
Write-Host "  3. 检查 app/build.gradle.kts 的 release 是否仍覆盖 DEEPSEEK_API_KEY 为空"
Write-Host "  4. 重新 assembleRelease 后再跑本脚本"
exit 1
