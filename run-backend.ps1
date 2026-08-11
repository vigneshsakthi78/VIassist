# Run Spring Boot backend with JDK 17 + Gemini API
$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $MyInvocation.MyCommand.Path
$tools = Join-Path $root ".tools"

$jdk = Get-ChildItem $tools -Directory -ErrorAction SilentlyContinue |
    Where-Object { $_.Name -like "jdk-17*" } |
    Select-Object -First 1
$mvnCmd = Join-Path $tools "apache-maven-3.9.9\bin\mvn.cmd"

if (-not $jdk) {
    Write-Error "JDK 17 not found under $tools. Install JDK 17 or restore the .tools folder."
}
if (-not (Test-Path $mvnCmd)) {
    Write-Error "Maven not found at $mvnCmd"
}

Get-NetTCPConnection -LocalPort 8080 -State Listen -ErrorAction SilentlyContinue |
    ForEach-Object {
        Write-Host "Stopping old process on 8080: PID $($_.OwningProcess)"
        Stop-Process -Id $_.OwningProcess -Force -ErrorAction SilentlyContinue
    }
Start-Sleep -Seconds 1

$env:JAVA_HOME = $jdk.FullName
$env:PATH = "$env:JAVA_HOME\bin;" + ($env:PATH -replace [regex]::Escape("$env:JAVA_HOME\bin;"), "")

Write-Host "JAVA_HOME=$env:JAVA_HOME"
& "$env:JAVA_HOME\bin\java.exe" -version
Write-Host ""
Write-Host "Starting Vicky Assist backend (Gemini) on http://localhost:8080"
Write-Host "Leave this window open. Do NOT press Ctrl+C while using the app."

if (-not $env:GEMINI_API_KEY) {
    Write-Host ""
    Write-Host "ERROR: GEMINI_API_KEY is not set."
    Write-Host "1) Create a key at https://aistudio.google.com/apikey"
    Write-Host "2) Then run:"
    Write-Host '   $env:GEMINI_API_KEY = "your-key-here"'
    Write-Host "3) Optional model override:"
    Write-Host '   $env:GEMINI_MODEL = "gemini-3.6-flash"'
    Write-Host "4) Start again: .\run-backend.ps1"
    exit 1
}

if (-not $env:GEMINI_MODEL) {
    $env:GEMINI_MODEL = "gemini-3.6-flash"
}
if (-not $env:GEMINI_API_KEY -or $env:GEMINI_API_KEY.Trim().Length -eq 0) {
    Write-Host "ERROR: GEMINI_API_KEY is empty. Set a real key before starting."
    exit 1
}
if (-not $env:VICKY_DATA_DIR) {
    $env:VICKY_DATA_DIR = Join-Path $root "data"
}
New-Item -ItemType Directory -Force -Path $env:VICKY_DATA_DIR | Out-Null
Write-Host "Using Gemini model: $($env:GEMINI_MODEL)"
Write-Host "Durable data dir: $($env:VICKY_DATA_DIR)"
Write-Host ""

Set-Location (Join-Path $root "backend")
& $mvnCmd spring-boot:run
