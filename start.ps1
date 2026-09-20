# ============================================================
# 一键启动:RAG 知识库问答系统
#
# 做三件事:启动 Docker Desktop -> 等后端就绪 -> 启动前端并打开浏览器
#
# 为什么要写成脚本:手动启动要等 Docker、等容器、等 Elasticsearch,
# 顺序错了就会看到"后端连不上"或者"某个依赖 DOWN",很容易误判成代码问题。
# ============================================================

$ErrorActionPreference = 'Continue'
$dockerExe = 'C:\Users\xunqian\AppData\Local\Programs\DockerDesktop\Docker Desktop.exe'

Write-Host ''
Write-Host '=== RAG 知识库问答 · 启动 ===' -ForegroundColor Cyan
Write-Host ''

# ---------- 1. Docker Desktop ----------
if (Get-Process 'Docker Desktop' -ErrorAction SilentlyContinue) {
    Write-Host '[1/3] Docker Desktop 已在运行'
} else {
    Write-Host '[1/3] 正在启动 Docker Desktop…'
    if (-not (Test-Path -LiteralPath $dockerExe)) {
        Write-Host "      找不到 $dockerExe" -ForegroundColor Red
        Read-Host '按回车退出'
        exit 1
    }
    Start-Process -FilePath $dockerExe
}

# ---------- 2. 等后端 ----------
Write-Host '[2/3] 等待后端就绪(首次启动或刚重启过可能要 2~3 分钟)…'
$ready = $false
for ($i = 1; $i -le 40; $i++) {
    Start-Sleep -Seconds 5
    try {
        $health = Invoke-RestMethod 'http://localhost:8080/api/system/health' -TimeoutSec 5
        if ($health.status -eq 'UP') { $ready = $true; break }
        $down = ($health.dependencies | Where-Object { $_.status -eq 'DOWN' } | ForEach-Object { $_.name }) -join '、'
        Write-Host "       已等待 $($i*5)s,还在等:$down"
    } catch {
        if ($i % 4 -eq 0) { Write-Host "       已等待 $($i*5)s,后端还没起来…" }
    }
}

if ($ready) {
    Write-Host '       后端已就绪,所有依赖 UP' -ForegroundColor Green
} else {
    Write-Host '       后端仍未就绪。请打开 Docker Desktop 窗口看看有没有报错。' -ForegroundColor Yellow
    Write-Host '       (常见原因:Elasticsearch 启动慢,再等一两分钟即可)'
}

# ---------- 3. 前端 ----------
Write-Host '[3/3] 启动前端…'
$webDir = Join-Path $PSScriptRoot 'web'
if (-not (Test-Path -LiteralPath (Join-Path $webDir 'node_modules'))) {
    Write-Host '       首次运行,正在安装前端依赖…'
    Push-Location $webDir
    npm.cmd install --no-fund --no-audit
    Pop-Location
}

# 开一个独立的窗口跑 dev server —— 不用隐藏窗口,
# 因为用户需要能看到它的输出,也需要能 Ctrl+C 把它停掉。
Start-Process powershell -ArgumentList '-NoExit', '-Command', "Set-Location '$webDir'; npm run dev"
Start-Sleep -Seconds 8

Start-Process 'http://localhost:5173'
Write-Host ''
Write-Host '完成!浏览器应该已经打开 http://localhost:5173' -ForegroundColor Green
Write-Host '前端服务在另一个窗口里跑着,关掉那个窗口就是停止前端。'
Write-Host ''
