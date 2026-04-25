$ErrorActionPreference = 'Stop'
$t = (Invoke-RestMethod -Method Post -Uri 'http://localhost:8081/realms/master/protocol/openid-connect/token' -Body @{grant_type='password'; client_id='admin-cli'; username='admin'; password='admin'} -ContentType 'application/x-www-form-urlencoded').access_token
$H = @{Authorization="Bearer $t"}
Write-Host "---ALL REALM SCOPES---"
(Invoke-RestMethod -Uri 'http://localhost:8081/admin/realms/hms/client-scopes' -Headers $H).name -join ', ' | Write-Host
$c = Invoke-RestMethod -Uri 'http://localhost:8081/admin/realms/hms/clients?clientId=hms-portal' -Headers $H
$cid = $c[0].id
Write-Host "---hms-portal default scopes---"
(Invoke-RestMethod -Uri "http://localhost:8081/admin/realms/hms/clients/$cid/default-client-scopes" -Headers $H).name -join ', ' | Write-Host
Write-Host "---hms-portal optional scopes---"
(Invoke-RestMethod -Uri "http://localhost:8081/admin/realms/hms/clients/$cid/optional-client-scopes" -Headers $H).name -join ', ' | Write-Host
