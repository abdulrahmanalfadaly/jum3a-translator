# One-time GitHub setup: creates the repo, uploads the signing key as Actions secrets, pushes main.
# Requires the GitHub CLI, logged in:
#   winget install --id GitHub.cli -e
#   gh auth login --web --git-protocol https
param(
    [string]$Repo = "abdulrahmanalfadaly/jum3a-translator"
)
$ErrorActionPreference = "Stop"
Set-Location (Split-Path $PSScriptRoot -Parent)

gh auth status *> $null
if ($LASTEXITCODE -ne 0) { throw "Run 'gh auth login --web --git-protocol https' first." }

# Read the local signing config (never committed).
$props = @{}
Get-Content keystore.properties | ForEach-Object {
    if ($_ -match '^\s*([^#=]+?)\s*=\s*(.*?)\s*$') { $props[$Matches[1]] = $Matches[2] }
}
$keystore = [Convert]::ToBase64String([IO.File]::ReadAllBytes((Resolve-Path $props['storeFile'])))

# Create the public repo if it doesn't exist yet.
gh repo view $Repo *> $null
if ($LASTEXITCODE -ne 0) {
    gh repo create $Repo --public --description "Live Malay to English translation for Android (khutbah and lectures)"
    if ($LASTEXITCODE -ne 0) { throw "Couldn't create $Repo" }
}

# --body avoids the trailing newline PowerShell adds when piping, which would break the passwords.
gh secret set KEYSTORE_BASE64 --repo $Repo --body $keystore
gh secret set KEYSTORE_PASSWORD --repo $Repo --body $props['storePassword']
gh secret set KEY_ALIAS --repo $Repo --body $props['keyAlias']
gh secret set KEY_PASSWORD --repo $Repo --body $props['keyPassword']

git remote get-url origin *> $null
if ($LASTEXITCODE -ne 0) { git remote add origin "https://github.com/$Repo.git" }
git push -u origin main
if ($LASTEXITCODE -ne 0) { throw "git push failed" }
Write-Host "Done. The first cloud build (build 2) will appear at https://github.com/$Repo/releases in ~5 minutes."
