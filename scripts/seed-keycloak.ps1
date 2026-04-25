<#
.SYNOPSIS
  Idempotent Keycloak bootstrap for the HMS realm.

.DESCRIPTION
  One script, many environments. For each target env it:
    1. Reads config + users-to-seed from scripts/seed-keycloak.<env>.json.
    2. Obtains an admin token from the master realm.
    3. Ensures the hms realm has unmanagedAttributePolicy=ENABLED
       (required for custom user attrs like hospital_id on KC 26+).
    4. Ensures the built-in OIDC client scopes (profile/email/roles/web-origins)
       exist and are attached as default scopes on hms-portal.
       (Our realm-export explicitly declares clientScopes, which suppresses
       KC's auto-bootstrap of those built-ins and causes invalid_scope on SSO.)
    5. Creates/updates each user: attributes, password, realm-role mappings.

.PARAMETER Environment
  local | dev | uat | prod. Selects scripts/seed-keycloak.<env>.json as the
  source of truth for baseUrl, realm, admin creds, hospitals, and users.

.PARAMETER BaseUrl
  Overrides baseUrl from the JSON (e.g. http://localhost:8081).

.PARAMETER AdminUser / -AdminPassword
  Override admin creds from the JSON. Also honoured via env vars
  KC_ADMIN_USER / KC_ADMIN_PASSWORD so secrets don't need to sit in the file.

.PARAMETER SeedFile
  Explicit path to a seed JSON. When provided, -Environment is ignored for
  file selection (but is still used for the prod safety check).

.PARAMETER Confirm
  Required when Environment=prod. Without it the script aborts before talking
  to any remote server.

.EXAMPLE
  # local (default)
  ./scripts/seed-keycloak.ps1

.EXAMPLE
  # dev, admin password from env
  $env:KC_ADMIN_PASSWORD = 'xxx'
  ./scripts/seed-keycloak.ps1 -Environment dev -AdminUser admin

.EXAMPLE
  # prod - explicit confirmation required
  ./scripts/seed-keycloak.ps1 -Environment prod -Confirm
#>
[CmdletBinding()]
param(
  [ValidateSet('local','dev','uat','prod')] [string] $Environment = 'local',
  [string] $BaseUrl,
  [string] $AdminUser,
  [string] $AdminPassword,
  [string] $SeedFile,
  [switch] $Confirm
)

$ErrorActionPreference = 'Stop'

# ---------- 0. Load config ----------
if (-not $SeedFile) {
  $SeedFile = Join-Path $PSScriptRoot "seed-keycloak.$Environment.json"
}
if (-not (Test-Path $SeedFile)) { throw "Seed file not found: $SeedFile" }

$cfg = Get-Content -Raw -Path $SeedFile | ConvertFrom-Json

$BASE          = if ($BaseUrl)                          { $BaseUrl }          else { $cfg.baseUrl }
$REALM         = $cfg.realm
$ADMIN_USER    = if ($AdminUser)                        { $AdminUser }        `
                 elseif ($env:KC_ADMIN_USER)            { $env:KC_ADMIN_USER } `
                 else                                   { $cfg.adminUser }
$ADMIN_PASS    = if ($AdminPassword)                    { $AdminPassword }    `
                 elseif ($env:KC_ADMIN_PASSWORD)        { $env:KC_ADMIN_PASSWORD } `
                 else                                   { $cfg.adminPassword }

foreach ($pair in @(@('baseUrl',$BASE), @('realm',$REALM), @('adminUser',$ADMIN_USER), @('adminPassword',$ADMIN_PASS))) {
  if (-not $pair[1] -or "$($pair[1])".StartsWith('TODO')) {
    throw "Missing or TODO value for '$($pair[0])'. Set it in $SeedFile or via params/env vars."
  }
}

Write-Host "Environment : $Environment"
Write-Host "Base URL    : $BASE"
Write-Host "Realm       : $REALM"
Write-Host "Admin user  : $ADMIN_USER"
Write-Host "Seed file   : $SeedFile"

# ---------- 0a. Prod safety ----------
if ($Environment -eq 'prod' -and -not $Confirm) {
  throw "Refusing to seed PROD without -Confirm. Re-run with -Confirm if this is intentional."
}
if ($BASE -notmatch '^https?://') {
  throw "baseUrl must start with http:// or https:// (got '$BASE')."
}
if ($Environment -in @('uat','prod') -and $BASE -notmatch '^https://') {
  throw "Refusing to use non-https baseUrl for '$Environment': $BASE"
}

# ---------- 1. Admin token ----------
$body  = @{grant_type='password'; client_id='admin-cli'; username=$ADMIN_USER; password=$ADMIN_PASS}
$token = (Invoke-RestMethod -Method Post -Uri "$BASE/realms/master/protocol/openid-connect/token" `
                            -Body $body -ContentType 'application/x-www-form-urlencoded').access_token
$H = @{Authorization="Bearer $token"; 'Content-Type'='application/json'}
Write-Host "Got admin token"

# ---------- 2. unmanagedAttributePolicy=ENABLED on hms realm ----------
# KC 26 rejects unknown user attributes by default; HMS relies on hospital_id
# and role_assignments attrs, so this MUST be ENABLED.
$profileUrl = "$BASE/admin/realms/$REALM/users/profile"
$raw = (Invoke-WebRequest -UseBasicParsing -Uri $profileUrl -Headers $H).Content
if ($raw -match '"unmanagedAttributePolicy"\s*:\s*"([^"]+)"') {
  if ($Matches[1] -ne 'ENABLED') {
    $patched = $raw -replace '"unmanagedAttributePolicy"\s*:\s*"[^"]*"', '"unmanagedAttributePolicy":"ENABLED"'
    Invoke-RestMethod -Method Put -Uri $profileUrl -Headers $H -Body $patched | Out-Null
    Write-Host "unmanagedAttributePolicy: changed from '$($Matches[1])' to ENABLED"
  } else {
    Write-Host "unmanagedAttributePolicy: already ENABLED"
  }
} else {
  $patched = $raw -replace '^\{', '{"unmanagedAttributePolicy":"ENABLED",'
  Invoke-RestMethod -Method Put -Uri $profileUrl -Headers $H -Body $patched | Out-Null
  Write-Host "unmanagedAttributePolicy: set to ENABLED (was unset)"
}

# ---------- 3. Built-in client scopes ----------
$builtInScopes = @(
  @{
    name='profile'; description='OpenID Connect built-in scope: profile'; protocol='openid-connect'
    attributes=@{'include.in.token.scope'='true'; 'display.on.consent.screen'='true'}
  },
  @{
    name='email'; description='OpenID Connect built-in scope: email'; protocol='openid-connect'
    attributes=@{'include.in.token.scope'='true'; 'display.on.consent.screen'='true'}
    protocolMappers=@(
      @{name='email'; protocol='openid-connect'; protocolMapper='oidc-usermodel-property-mapper'
        config=@{'user.attribute'='email'; 'claim.name'='email'; 'jsonType.label'='String'; 'id.token.claim'='true'; 'access.token.claim'='true'; 'userinfo.token.claim'='true'}},
      @{name='email verified'; protocol='openid-connect'; protocolMapper='oidc-usermodel-property-mapper'
        config=@{'user.attribute'='emailVerified'; 'claim.name'='email_verified'; 'jsonType.label'='boolean'; 'id.token.claim'='true'; 'access.token.claim'='true'; 'userinfo.token.claim'='true'}}
    )
  },
  @{
    name='roles'; description='OpenID Connect scope for realm and client roles'; protocol='openid-connect'
    attributes=@{'include.in.token.scope'='false'; 'display.on.consent.screen'='true'}
    protocolMappers=@(
      @{name='realm roles'; protocol='openid-connect'; protocolMapper='oidc-usermodel-realm-role-mapper'
        config=@{'claim.name'='realm_access.roles'; 'jsonType.label'='String'; 'multivalued'='true'; 'id.token.claim'='false'; 'access.token.claim'='true'; 'userinfo.token.claim'='false'}},
      @{name='audience resolve'; protocol='openid-connect'; protocolMapper='oidc-audience-resolve-mapper'; config=@{}}
    )
  },
  @{
    name='web-origins'; description='OpenID Connect scope for allowed Web Origins'; protocol='openid-connect'
    attributes=@{'include.in.token.scope'='false'; 'display.on.consent.screen'='false'}
    protocolMappers=@(
      @{name='allowed web origins'; protocol='openid-connect'; protocolMapper='oidc-allowed-origins-mapper'; config=@{}}
    )
  }
)

$existing = (Invoke-RestMethod -Uri "$BASE/admin/realms/$REALM/client-scopes" -Headers $H).name
foreach ($s in $builtInScopes) {
  if ($existing -contains $s.name) { Write-Host "scope already exists: $($s.name)"; continue }
  $json = $s | ConvertTo-Json -Depth 8
  Invoke-RestMethod -Method Post -Uri "$BASE/admin/realms/$REALM/client-scopes" -Headers $H -Body $json | Out-Null
  Write-Host "created scope: $($s.name)"
}

# ---------- 3b. hms-profile scope (aud=hms-backend + identity-link claims) ----------
if ($existing -notcontains 'hms-profile') {
  $hmsProfileBody = @{
    name='hms-profile'
    description='HMS identity-link claims (appUserId, staffId, patientId) and aud=hms-backend'
    protocol='openid-connect'
    attributes=@{'include.in.token.scope'='true'; 'display.on.consent.screen'='false'}
    protocolMappers=@(
      @{name='audience-hms-backend'; protocol='openid-connect'; protocolMapper='oidc-audience-mapper'
        consentRequired=$false
        config=@{'included.client.audience'='hms-backend'; 'id.token.claim'='false'; 'access.token.claim'='true'}},
      @{name='app_user_id'; protocol='openid-connect'; protocolMapper='oidc-usermodel-attribute-mapper'
        consentRequired=$false
        config=@{'user.attribute'='app_user_id'; 'claim.name'='appUserId'; 'jsonType.label'='String'
                 'id.token.claim'='true'; 'access.token.claim'='true'; 'userinfo.token.claim'='true'}},
      @{name='staff_id'; protocol='openid-connect'; protocolMapper='oidc-usermodel-attribute-mapper'
        consentRequired=$false
        config=@{'user.attribute'='staff_id'; 'claim.name'='staffId'; 'jsonType.label'='String'
                 'id.token.claim'='true'; 'access.token.claim'='true'; 'userinfo.token.claim'='true'}},
      @{name='patient_id'; protocol='openid-connect'; protocolMapper='oidc-usermodel-attribute-mapper'
        consentRequired=$false
        config=@{'user.attribute'='patient_id'; 'claim.name'='patientId'; 'jsonType.label'='String'
                 'id.token.claim'='true'; 'access.token.claim'='true'; 'userinfo.token.claim'='true'}}
    )
  } | ConvertTo-Json -Depth 8
  Invoke-RestMethod -Method Post -Uri "$BASE/admin/realms/$REALM/client-scopes" -Headers $H -Body $hmsProfileBody | Out-Null
  Write-Host "created scope: hms-profile"
} else {
  Write-Host "scope already exists: hms-profile"
}

$scopes = Invoke-RestMethod -Uri "$BASE/admin/realms/$REALM/client-scopes" -Headers $H
$scopeByName = @{}; foreach ($x in $scopes) { $scopeByName[$x.name] = $x.id }

$portal = (Invoke-RestMethod -Uri "$BASE/admin/realms/$REALM/clients?clientId=hms-portal" -Headers $H)
if ($portal.Count -gt 0) {
  $cid = $portal[0].id
  $currentDefaults = (Invoke-RestMethod -Uri "$BASE/admin/realms/$REALM/clients/$cid/default-client-scopes" -Headers $H).name
  foreach ($n in @('profile','email','roles','web-origins','hms-claims','hms-profile')) {
    if ($currentDefaults -contains $n) { Write-Host "hms-portal already has default scope: $n"; continue }
    $sid = $scopeByName[$n]
    if (-not $sid) { Write-Host "WARNING: scope '$n' not found in realm - skipping"; continue }
    Invoke-RestMethod -Method Put -Uri "$BASE/admin/realms/$REALM/clients/$cid/default-client-scopes/$sid" -Headers $H | Out-Null
    Write-Host "hms-portal default scope added: $n"
  }
} else {
  Write-Host "hms-portal client not found in realm $REALM - skipping default-scope attachment"
}

# ---------- 3c. hms-backend client roles (capability-level permissions) ----------
$hmsBackendRoleCatalog = @(
  @{name='pharmacy.dispense';      description='Dispense a prescription line'},
  @{name='pharmacy.stock.adjust';  description='Record stock adjustment with reason'},
  @{name='pharmacy.goods.receive'; description='Receive a goods delivery'},
  @{name='pharmacy.route.partner'; description='Route stock to a partner pharmacy'},
  @{name='pharmacy.claim.submit';  description='Submit an insurance claim'},
  @{name='pharmacy.claim.reverse'; description='Reverse a submitted claim'},
  @{name='billing.refund';         description='Issue a billing refund'},
  @{name='audit.view';             description='View audit log'}
)
$beClients = (Invoke-RestMethod -Uri "$BASE/admin/realms/$REALM/clients?clientId=hms-backend" -Headers $H)
if ($beClients.Count -gt 0) {
  $beId = $beClients[0].id
  $beRolesUrl = "$BASE/admin/realms/$REALM/clients/$beId/roles"
  $existingClientRoles = (Invoke-RestMethod -Uri $beRolesUrl -Headers $H).name
  foreach ($r in $hmsBackendRoleCatalog) {
    if ($existingClientRoles -contains $r.name) {
      Write-Host "hms-backend client role already exists: $($r.name)"
    } else {
      $body = @{name=$r.name; description=$r.description} | ConvertTo-Json
      Invoke-RestMethod -Method Post -Uri $beRolesUrl -Headers $H -Body $body | Out-Null
      Write-Host "hms-backend client role created: $($r.name)"
    }
  }
} else {
  Write-Host "hms-backend client not found - skipping client role bootstrap"
}

# ---------- 4. Users ----------
$rolesUrl = '{0}/admin/realms/{1}/roles?briefRepresentation=false&max=200' -f $BASE, $REALM
$allRoles = Invoke-RestMethod -Uri $rolesUrl -Headers $H

function Resolve-Template {
  param([string] $Value, $Hospitals)
  if (-not $Value) { return $Value }
  if (-not $Hospitals) { return $Value }
  foreach ($prop in $Hospitals.PSObject.Properties) {
    $Value = $Value -replace ('\{' + [regex]::Escape($prop.Name) + '\}'), $prop.Value
  }
  return $Value
}

function Ensure-HmsUser {
  param(
    [Parameter(Mandatory)] [string] $Username,
    [Parameter(Mandatory)] [string] $Email,
    [Parameter(Mandatory)] [string] $FirstName,
    [Parameter(Mandatory)] [string] $LastName,
    [Parameter(Mandatory)] [string] $Password,
    [Parameter(Mandatory)] [string[]] $Roles,
    [string]   $HospitalId,
    [string[]] $RoleAssignments,
    # hashtable: clientId -> string[] of role names e.g. @{'hms-backend'=@('audit.view')}
    [hashtable] $ClientRoles = @{}
  )

  $attrs = @{}
  if ($HospitalId) { $attrs['hospital_id'] = @($HospitalId) }
  if ($RoleAssignments -and $RoleAssignments.Count -gt 0) {
    # Force JSON-array literal (PS 5.1 unwraps single-element arrays in ConvertTo-Json).
    $raJson = '[' + (($RoleAssignments | ForEach-Object { '"' + ($_ -replace '"','\"') + '"' }) -join ',') + ']'
    $attrs['role_assignments'] = @($raJson)
  }

  $body = @{
    username=$Username; email=$Email; enabled=$true; emailVerified=$true
    firstName=$FirstName; lastName=$LastName
    attributes=$attrs
    credentials=@(@{type='password'; value=$Password; temporary=$false})
  } | ConvertTo-Json -Depth 8

  try {
    Invoke-RestMethod -Method Post -Uri "$BASE/admin/realms/$REALM/users" -Headers $H -Body $body | Out-Null
    Write-Host "user created: $Username"
  } catch {
    Write-Host "user exists (or failed): $Username - $($_.Exception.Message)"
  }

  $lookupUrl = '{0}/admin/realms/{1}/users?username={2}&exact=true' -f $BASE, $REALM, $Username
  $u = Invoke-RestMethod -Uri $lookupUrl -Headers $H
  if ($null -eq $u -or $u.Count -le 0) {
    throw "Unable to resolve Keycloak user '$Username' after create attempt. User creation may have failed; check the earlier error output for details."
  }
  $uid = $u[0].id
  Write-Host "$Username uid=$uid"

  # Always (re-)apply attributes so re-runs converge existing users.
  $update = @{ attributes=$attrs } | ConvertTo-Json -Depth 8
  Invoke-RestMethod -Method Put -Uri "$BASE/admin/realms/$REALM/users/$uid" -Headers $H -Body $update | Out-Null

  # Always (re-)set password so rotations in the seed file take effect.
  # Realm password policies (e.g. passwordHistory) can reject a re-set of the
  # same password - that's fine on re-runs, we just warn.
  $cred = @{type='password'; value=$Password; temporary=$false} | ConvertTo-Json -Depth 5
  try {
    Invoke-RestMethod -Method Put -Uri "$BASE/admin/realms/$REALM/users/$uid/reset-password" -Headers $H -Body $cred | Out-Null
  } catch {
    Write-Host "password reset skipped for ${Username}: $($_.Exception.Message)"
  }

  $picked = @($allRoles | Where-Object { $Roles -contains $_.name })
  if ($picked.Count -gt 0) {
    $json = ConvertTo-Json -InputObject $picked -Depth 5
    Invoke-RestMethod -Method Post -Uri "$BASE/admin/realms/$REALM/users/$uid/role-mappings/realm" -Headers $H -Body $json | Out-Null
    Write-Host "assigned roles to ${Username}: $($picked.name -join ', ')"
  }

  # Client role assignments
  foreach ($clientId in $ClientRoles.Keys) {
    $cData = (Invoke-RestMethod -Uri "$BASE/admin/realms/$REALM/clients?clientId=$clientId" -Headers $H)
    if (-not $cData -or $cData.Count -eq 0) { Write-Host "WARNING: client '$clientId' not found - skipping client roles"; continue }
    $cInternalId = $cData[0].id
    $available = Invoke-RestMethod -Uri "$BASE/admin/realms/$REALM/clients/$cInternalId/roles" -Headers $H
    $toAssign = @($available | Where-Object { $ClientRoles[$clientId] -contains $_.name })
    if ($toAssign.Count -gt 0) {
      # Build explicit JSON array to avoid PS 5.1 single-element array unwrapping
      $fragments = $toAssign | ForEach-Object {
        $composite = if ($_.composite) { 'true' } else { 'false' }
        $clientRole = if ($_.clientRole) { 'true' } else { 'false' }
        '{"id":"' + $_.id + '","name":"' + $_.name + '","composite":' + $composite + ',"clientRole":' + $clientRole + ',"containerId":"' + $_.containerId + '"}'
      }
      $body = '[' + ($fragments -join ',') + ']'
      $mapUrl = "$BASE/admin/realms/$REALM/users/$uid/role-mappings/clients/$cInternalId"
      Invoke-RestMethod -Method Post -Uri $mapUrl -Headers $H -Body $body | Out-Null
      Write-Host "assigned client roles to ${Username} ($clientId): $($toAssign.name -join ', ')"
    }
  }
}

foreach ($u in @($cfg.users)) {
  $hospitalId = $null
  if ($u.hospital) { $hospitalId = Resolve-Template -Value ('{' + $u.hospital + '}') -Hospitals $cfg.hospitals }

  $ra = @()
  if ($u.roleAssignments) {
    foreach ($item in @($u.roleAssignments)) {
      $ra += (Resolve-Template -Value $item -Hospitals $cfg.hospitals)
    }
  }

  $clientRolesHt = @{}
  if ($u.clientRoles) {
    foreach ($prop in $u.clientRoles.PSObject.Properties) {
      $clientRolesHt[$prop.Name] = @($prop.Value)
    }
  }

  Ensure-HmsUser `
    -Username $u.username -Email $u.email `
    -FirstName $u.firstName -LastName $u.lastName `
    -Password $u.password `
    -Roles ([string[]]@($u.roles)) `
    -HospitalId $hospitalId `
    -RoleAssignments ([string[]]$ra) `
    -ClientRoles $clientRolesHt
}

Write-Host "`nSeed complete ($Environment)."
