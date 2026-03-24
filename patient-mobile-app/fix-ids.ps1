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
