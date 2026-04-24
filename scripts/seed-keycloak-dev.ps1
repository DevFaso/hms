$ErrorActionPreference = 'Stop'
$BASE = 'http://localhost:8081'
$REALM = 'hms'

$body = @{grant_type='password'; client_id='admin-cli'; username='admin'; password='admin'}
$token = (Invoke-RestMethod -Method Post -Uri "$BASE/realms/master/protocol/openid-connect/token" -Body $body -ContentType 'application/x-www-form-urlencoded').access_token
$H = @{Authorization="Bearer $token"; 'Content-Type'='application/json'}
Write-Host "Got admin token"

# ---------- 1. Built-in client scopes (profile/email/roles/web-origins) ----------
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

# Re-fetch ids
$scopes = Invoke-RestMethod -Uri "$BASE/admin/realms/$REALM/client-scopes" -Headers $H
$scopeByName = @{}; foreach ($x in $scopes) { $scopeByName[$x.name] = $x.id }

# Attach built-ins as default scopes on hms-portal
$client = (Invoke-RestMethod -Uri "$BASE/admin/realms/$REALM/clients?clientId=hms-portal" -Headers $H)[0]
$cid = $client.id
$currentDefaults = (Invoke-RestMethod -Uri "$BASE/admin/realms/$REALM/clients/$cid/default-client-scopes" -Headers $H).name
foreach ($n in @('profile','email','roles','web-origins')) {
  if ($currentDefaults -contains $n) { Write-Host "hms-portal already has default scope: $n"; continue }
  $sid = $scopeByName[$n]
  Invoke-RestMethod -Method Put -Uri "$BASE/admin/realms/$REALM/clients/$cid/default-client-scopes/$sid" -Headers $H | Out-Null
  Write-Host "hms-portal default scope added: $n"
}

# ---------- 2. Seed dev.doctor ----------
$user = @{
  username='dev.doctor'; email='dev.doctor@hms.local'; enabled=$true; emailVerified=$true
  firstName='Dev'; lastName='Doctor'
  attributes=@{hospital_id=@('11111111-1111-1111-1111-111111111111'); role_assignments=@('["DOCTOR@11111111-1111-1111-1111-111111111111"]')}
  credentials=@(@{type='password'; value='DevDoctor#2026'; temporary=$false})
} | ConvertTo-Json -Depth 8
try { Invoke-RestMethod -Method Post -Uri "$BASE/admin/realms/$REALM/users" -Headers $H -Body $user | Out-Null; Write-Host "user created: dev.doctor" }
catch { Write-Host "user exists or failed: $($_.Exception.Message)" }

$u = Invoke-RestMethod -Uri "$BASE/admin/realms/$REALM/users?username=dev.doctor&exact=true" -Headers $H
$uid = $u[0].id
Write-Host "dev.doctor uid=$uid"

$allRoles = Invoke-RestMethod -Uri "$BASE/admin/realms/$REALM/roles?briefRepresentation=false&max=200" -Headers $H
$picked = @($allRoles | Where-Object { $_.name -eq 'ROLE_DOCTOR' -or $_.name -eq 'ROLE_STAFF' })
$json = ConvertTo-Json -InputObject $picked -Depth 5
Invoke-RestMethod -Method Post -Uri "$BASE/admin/realms/$REALM/users/$uid/role-mappings/realm" -Headers $H -Body $json | Out-Null
Write-Host "assigned roles: $($picked.name -join ', ')"

Write-Host "`nSeed complete."
