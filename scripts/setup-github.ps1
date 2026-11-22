# One-time GitHub setup: creates the repo, uploads the signing key as Actions secrets, pushes main.
# Requires the GitHub CLI, logged in:
#   winget install --id GitHub.cli -e
#   gh auth login --web --git-protocol https
param(
    [string]$Repo = "abdulrahmanalfadaly/jum3a-translator"
)
# Not "Stop": in Windows PowerShell 5.1 that turns any stderr line from gh/git into a crash.
# Every native call below is checked through $LASTEXITCODE instead.
$ErrorActionPreference = "Continue"
Set-Location (Split-Path $PSScriptRoot -Parent)

function Fail($msg) { Write-Host $msg -ForegroundColor Red; exit 1 }

gh auth status *> $null
if ($LASTEXITCODE -ne 0) { Fail "Run 'gh auth login --web --git-protocol https' first." }

# Read the local signing config (never committed).
if (-not (Test-Path keystore.properties)) { Fail "keystore.properties not found." }
$props = @{}
Get-Content keystore.properties | ForEach-Object {
    if ($_ -match '^\s*([^#=]+?)\s*=\s*(.*?)\s*$') { $props[$Matches[1]] = $Matches[2] }
}
$keystore = [Convert]::ToBase64String([IO.File]::ReadAllBytes((Resolve-Path $props['storeFile'])))

# Create the public repo if it doesn't exist yet.
gh repo view $Repo *> $null
if ($LASTEXITCODE -ne 0) {
    gh repo create $Repo --public --description "Live Malay to English translation for Android (khutbah and lectures)"
    if ($LASTEXITCODE -ne 0) { Fail "Couldn't create $Repo" }
}

# --body avoids the trailing newline PowerShell adds when piping, which would break the passwords.
$secrets = [ordered]@{
    KEYSTORE_BASE64   = $keystore
    KEYSTORE_PASSWORD = $props['storePassword']
    KEY_ALIAS         = $props['keyAlias']
    KEY_PASSWORD      = $props['keyPassword']
}
foreach ($name in $secrets.Keys) {
    gh secret set $name --repo $Repo --body $secrets[$name]
    if ($LASTEXITCODE -ne 0) { Fail "Couldn't set secret $name" }
}

git remote get-url origin *> $null
if ($LASTEXITCODE -ne 0) { git remote add origin "https://github.com/$Repo.git" }
git push -u origin main
if ($LASTEXITCODE -ne 0) { Fail "git push failed" }
Write-Host "Done. The first cloud build (build 2) will appear at https://github.com/$Repo/releases in ~5 minutes."
