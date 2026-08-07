# Run Angular UI (expects backend on http://localhost:8080)
$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $MyInvocation.MyCommand.Path
Set-Location (Join-Path $root "frontend")

if (-not (Test-Path "node_modules")) {
    npm install
}
Write-Host "Starting Angular UI on http://localhost:4200"
Write-Host "API calls go through proxy -> http://localhost:8080"
Write-Host "Keep the Spring Boot backend running first."
npm start
