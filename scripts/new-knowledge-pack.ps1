<#
.SYNOPSIS
  Scaffold a screenshot knowledge pack for Vicky Assist (docs + static shots).

.DESCRIPTION
  Creates folders and starter catalog/howto files. Capture screenshots yourself
  (or with Cursor browser) into the pack's shot-NN.png slots — never commit credentials.

.PARAMETER PackId
  Folder id, e.g. dms-shore-v43 or my-app-v1

.PARAMETER Title
  Human title used inside starter docs

.EXAMPLE
  .\scripts\new-knowledge-pack.ps1 -PackId "fleet-app-v1" -Title "Fleet App"
#>
param(
    [Parameter(Mandatory = $true)]
    [string] $PackId,

    [Parameter(Mandatory = $true)]
    [string] $Title
)

$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $PSScriptRoot
$shots = Join-Path $root "backend\src\main\resources\static\screenshots\$PackId"
$docs = Join-Path $root "backend\src\main\resources\docs"

New-Item -ItemType Directory -Force -Path $shots | Out-Null

$catalogName = "50-$PackId-screenshots.txt"
$howtoName = "51-$PackId-visual-howtos.txt"
$catalogPath = Join-Path $docs $catalogName
$howtoPath = Join-Path $docs $howtoName

@"
$Title — Screenshot catalog (use these exact paths)

When explaining UI steps, include markdown images:
![short label](/screenshots/$PackId/shot-XX.png)

Catalog (add labels as you capture):
shot-01.png — (capture: home / landing)
shot-02.png — (capture: key module 1)
shot-03.png — (capture: key module 2)

Rules
- Always use markdown image syntax.
- For how-to questions, include 1–2 screenshots when a match exists.
- Do not invent filenames that are not listed here.
"@ | Set-Content -Path $catalogPath -Encoding UTF8

@"
$Title — Visual how-tos (steps + screenshots)

How-to: Open home and orient
1) Open the app URL and sign in with your assigned account.
2) Land on the home screen; note primary navigation.
3) Open the module you need from the main menu.

![Home](/screenshots/$PackId/shot-01.png)

Knowledge-pack builder notes
- Drop PNGs into: backend/src/main/resources/static/screenshots/$PackId/
- Update the catalog labels after each capture session.
- Never commit passwords or .env files used for login.
- Redeploy the API so classpath docs + static shots ship together.
"@ | Set-Content -Path $howtoPath -Encoding UTF8

Write-Host "Created:"
Write-Host "  $shots"
Write-Host "  $catalogPath"
Write-Host "  $howtoPath"
Write-Host ""
Write-Host "Next: log in to the target app (test account), capture shot-01.png ..."
Write-Host "Then update catalog labels and add more how-to blocks."
