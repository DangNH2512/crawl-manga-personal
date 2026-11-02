$OutputEncoding = [System.Text.Encoding]::UTF8
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8

$mangaDir = "C:\Users\OS\Documents\Mangas"
$allDirs = Get-ChildItem $mangaDir -Directory

$targetDir = $null
foreach ($d in $allDirs) {
    if ($d.Name -like "*lam tho*" -or $d.Name -like "*làm thơ*" -or $d.Name -like "*nang lam*") {
        $targetDir = $d.FullName
        break
    }
}

if (-not $targetDir) {
    Write-Host "ERROR: Not found. Listing all dirs:"
    foreach ($d in $allDirs) { Write-Host "  $($d.Name)" }
    exit 1
}

Write-Host "Target: $targetDir"
$chapters = Get-ChildItem $targetDir -Directory
Write-Host "Found $($chapters.Count) folders"

$count = 0
foreach ($ch in $chapters) {
    if ($ch.Name -match '^Chapter\s+(\d+(\.\d+)?)\s+(.+)$') {
        $num = $matches[1]
        $name = $matches[3]
        $newName = "${num}_${name}"
        Write-Host "Rename: $($ch.Name) -> $newName"
        Rename-Item $ch.FullName -NewName $newName
        $count++
    } else {
        Write-Host "SKIP: $($ch.Name)"
    }
}
Write-Host "Done! Renamed $count folders."

Get-ChildItem $targetDir -Directory | Sort-Object { if ($_.Name -match '^(\d+(\.\d+)?)_') { [double]$matches[1] } else { 999 } } | ForEach-Object { $_.Name } | Out-File "d:\self project\Fetch_Manga\tho_after.txt" -Encoding UTF8
