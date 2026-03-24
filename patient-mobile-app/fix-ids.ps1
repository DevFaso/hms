$base = "C:\Users\tiego\OneDrive\Desktop\HMS\hms\patient-mobile-app\android"
$files = Get-ChildItem -Path $base -Recurse -Include "*.kt" | Where-Object { $_.FullName -like "*\data\*" }
Write-Host "Files found: $($files.Count)"

$total = 0
foreach ($f in $files) {
    $c = [IO.File]::ReadAllText($f.FullName)
    $c2 = $c

    # val id: Int/Long variants
    $c2 = $c2 -replace 'val id: Int = 0', 'val id: String = ""'
    $c2 = $c2 -replace 'val id: Long = 0', 'val id: String = ""'
    $c2 = $c2 -replace 'val id: Int,', 'val id: String,'
    $c2 = $c2 -replace 'val id: Long,', 'val id: String,'
    $c2 = $c2 -replace 'val id: Long\b', 'val id: String'
    $c2 = $c2 -replace 'val id: Int\b', 'val id: String'

    # val *Id: Int/Long variants  
    $c2 = $c2 -replace '(val \w+Id): Int = 0', '$1: String = ""'
    $c2 = $c2 -replace '(val \w+Id): Long = 0', '$1: String = ""'
    $c2 = $c2 -replace '(val \w+Id): Int,', '$1: String,'
    $c2 = $c2 -replace '(val \w+Id): Long,', '$1: String,'
    $c2 = $c2 -replace '(val \w+Id): Int\b', '$1: String'
    $c2 = $c2 -replace '(val \w+Id): Long\b', '$1: String'

    # @Path and @Query params with ID names
    $c2 = $c2 -replace '(@Path\("[^"]+"\) \w+): Int\b', '$1: String'
    $c2 = $c2 -replace '(@Query\("[^"]*[Ii]d[^"]*"\) \w+): Int\b', '$1: String'

    if ($c -ne $c2) {
        [IO.File]::WriteAllText($f.FullName, $c2)
        Write-Host "  Fixed: $($f.Name)"
        $total++
    }
}
Write-Host "Done. $total files updated."

# ── iOS / Swift ──────────────────────────────────────────────────────────────
Write-Host ""
Write-Host "=== iOS Swift files ==="

$iosBase = "C:\Users\tiego\OneDrive\Desktop\HMS\hms\patient-mobile-app\ios"
$swiftFiles = Get-ChildItem -Path $iosBase -Recurse -Include "*.swift"
Write-Host "Swift files found: $($swiftFiles.Count)"

$iosTotal = 0
foreach ($f in $swiftFiles) {
    $c = [IO.File]::ReadAllText($f.FullName)
    $c2 = $c

    # let id: Int  (standalone 'id' field or computed var)
    $c2 = $c2 -replace '\blet id: Int\b', 'let id: String'
    $c2 = $c2 -replace '\bvar id: Int\b', 'var id: String'

    # let/var *Id: Int  and  let/var *Id: Int?
    # Matches any identifier ending in 'Id' (e.g. patientId, fromHospitalId)
    $c2 = [regex]::Replace($c2, '\b((?:let|var)\s+\w+Id): Int\?', '$1: String?')
    $c2 = [regex]::Replace($c2, '\b((?:let|var)\s+\w+Id): Int\b', '$1: String')

    # Enum case associated-value params whose label is 'id' or ends in 'Id'
    # e.g.  case foo(id: Int)  or  case foo(userId: Int, page: Int)
    # Replace only the id-like label: Int occurrences inside case params
    $c2 = [regex]::Replace($c2, '\b((?:id|[a-z]\w*Id|[a-z]\w*ID)): Int\b', '$1: String')

    # Computed var returning an Int derived from .id (e.g. var currentUserId: Int { ... ?? 0 })
    $c2 = [regex]::Replace($c2, '\bvar (\w*[Uu]ser[Ii]d|\w*[Cc]urrent[Ii]d|\w*[Pp]atient[Ii]d): Int\b', 'var $1: String')

    # Fallback ?? 0 for id-bearing computed properties / accesses
    # e.g.  currentUser?.id ?? 0   or   AuthManager...?.id ?? 0
    $c2 = [regex]::Replace($c2, '(\?\.id\s*\?\?)\s*0\b', '$1 ""')

    if ($c -ne $c2) {
        [IO.File]::WriteAllText($f.FullName, $c2)
        Write-Host "  Fixed: $($f.FullName.Replace($iosBase, ''))"
        $iosTotal++
    }
}
Write-Host "Done. $iosTotal Swift files updated."
