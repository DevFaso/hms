# scripts/verify-keycloak-admin.ps1
# Verify master-realm admin login on uat / prod Keycloak Railway services.
#
# Usage:
#   $env:KC_UAT_ADMIN_USER  = 'kc-admin'
#   $env:KC_UAT_ADMIN_PASS  = '<from Railway hms-keycloak-uat KC_BOOTSTRAP_ADMIN_PASSWORD>'
#   $env:KC_PROD_ADMIN_USER = 'kc-admin'
#   $env:KC_PROD_ADMIN_PASS = '<from Railway hms-keycloak-prod KC_BOOTSTRAP_ADMIN_PASSWORD>'
#   pwsh -File scripts/verify-keycloak-admin.ps1
#
# Reports GREEN (with token type + expiry) or the HTTP status + Keycloak
# error body for each env. dev intentionally omitted — admin user there is
# `tiego`, not `kc-admin`; verify dev manually via the browser.

$ErrorActionPreference = 'SilentlyContinue'

$envs = @(
  @{
    name = 'uat'
    base = 'https://hms-keycloak-uat-uat.up.railway.app'
    user = $env:KC_UAT_ADMIN_USER
    pass = $env:KC_UAT_ADMIN_PASS
  },
  @{
    name = 'prod'
    base = 'https://hms-keycloak-prod-prod.up.railway.app'
    user = $env:KC_PROD_ADMIN_USER
    pass = $env:KC_PROD_ADMIN_PASS
  }
)

$anyMissing = $false
foreach ($e in $envs) {
  if (-not $e.user -or -not $e.pass) {
    Write-Output "[$($e.name)] SKIPPED  set KC_$($e.name.ToUpper())_ADMIN_USER and KC_$($e.name.ToUpper())_ADMIN_PASS env vars"
    $anyMissing = $true
    continue
  }
  Write-Output ""
  Write-Output "=== $($e.name): $($e.base) ==="
  try {
    $tok = Invoke-RestMethod -Uri "$($e.base)/realms/master/protocol/openid-connect/token" `
      -Method POST -ContentType 'application/x-www-form-urlencoded' `
      -Body @{ grant_type = 'password'; client_id = 'admin-cli'; username = $e.user; password = $e.pass } `
      -ErrorAction Stop
    Write-Output "  GREEN  token_type=$($tok.token_type) expires_in=$($tok.expires_in)s"
  } catch {
    $code = if ($_.Exception.Response) { [int]$_.Exception.Response.StatusCode } else { 'ERR' }
    $body = if ($_.ErrorDetails) { $_.ErrorDetails.Message } else { $_.Exception.Message }
    Write-Output "  HTTP $code  $body"
  }
}

if ($anyMissing) { exit 2 }
