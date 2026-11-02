<#
    Đóng gói Fetch Manga thành ứng dụng .exe chạy độc lập trên Windows.

    Cách dùng:  mở PowerShell tại thư mục Fetch_Manga rồi chạy
                  .\build-exe.ps1

    Kết quả:    dist\FetchManga\FetchManga.exe   (kèm sẵn Java runtime)
                dist\FetchManga-portable.zip     (bản nén để chép sang máy khác)

    Yêu cầu:    JDK 17 trở lên (đã có sẵn jpackage). Máy chạy file exe thì
                KHÔNG cần cài Java, vì runtime được nhúng luôn trong thư mục.
#>

$ErrorActionPreference = "Stop"
Set-Location $PSScriptRoot

$AppName = "FetchManga"
$MainClass = "org.example.fetch_manga.FetchMangaApplication"
$JarName = "fetch-manga.jar"
$Stage = "target\jpackage-input"
$DistDir = "dist"

function Step($number, $text) {
    Write-Host ""
    Write-Host "[$number/5] $text" -ForegroundColor Cyan
}

# --- 1. Kiểm tra công cụ -----------------------------------------------------
Step 1 "Kiem tra JDK va jpackage"
$jpackage = Get-Command jpackage -ErrorAction SilentlyContinue
if ($null -eq $jpackage) {
    throw "Khong tim thay 'jpackage'. Hay cai JDK 17+ va them thu muc bin vao PATH."
}
java -version
jpackage --version

# --- 2. Build jar gộp --------------------------------------------------------
Step 2 "Build jar gop (maven)"
& ".\mvnw.cmd" -B clean package "-DskipTests"
if ($LASTEXITCODE -ne 0) { throw "Maven build that bai." }
if (-not (Test-Path "target\$JarName")) { throw "Khong thay target\$JarName sau khi build." }

# --- 3. Tạo icon -------------------------------------------------------------
Step 3 "Tao icon ung dung"
java "tools\MakeIcon.java" "tools\app.ico"

# --- 4. Chuẩn bị thư mục đầu vào cho jpackage --------------------------------
# jpackage chép TOÀN BỘ nội dung --input vào ứng dụng, nên chỉ để đúng file jar.
Step 4 "Chuan bi thu muc dau vao"
if (Test-Path $Stage) { Remove-Item -Recurse -Force $Stage }
New-Item -ItemType Directory -Force -Path $Stage | Out-Null
Copy-Item "target\$JarName" -Destination $Stage

if (Test-Path "$DistDir\$AppName") { Remove-Item -Recurse -Force "$DistDir\$AppName" }
New-Item -ItemType Directory -Force -Path $DistDir | Out-Null

# --- 5. Đóng gói -------------------------------------------------------------
Step 5 "Dong goi bang jpackage"
$jpackageArgs = @(
    "--type", "app-image",
    "--name", $AppName,
    "--app-version", "1.0.0",
    "--vendor", "Personal",
    "--description", "Trinh tai chuong truyen tranh",
    "--input", $Stage,
    "--main-jar", $JarName,
    "--main-class", $MainClass,
    "--icon", "tools\app.ico",
    "--dest", $DistDir,
    "--java-options", "-Dfile.encoding=UTF-8",
    "--java-options", "-Dsun.java2d.uiScale.enabled=true"
)
& jpackage @jpackageArgs
if ($LASTEXITCODE -ne 0) { throw "jpackage that bai." }

$exe = Join-Path (Resolve-Path $DistDir) "$AppName\$AppName.exe"
if (-not (Test-Path $exe)) { throw "Khong thay file exe sau khi dong goi." }

# Bản nén để chép sang máy khác.
$zip = Join-Path (Resolve-Path $DistDir) "$AppName-portable.zip"
if (Test-Path $zip) { Remove-Item -Force $zip }
Compress-Archive -Path "$DistDir\$AppName" -DestinationPath $zip

$sizeMb = [math]::Round((Get-ChildItem "$DistDir\$AppName" -Recurse |
        Measure-Object -Property Length -Sum).Sum / 1MB, 1)

Write-Host ""
Write-Host "XONG." -ForegroundColor Green
Write-Host "  File chay : $exe"
Write-Host "  Ban nen   : $zip"
Write-Host "  Dung luong: $sizeMb MB (da nhung san Java runtime)"
Write-Host ""
Write-Host "Meo: tao shortcut cua FetchManga.exe ra Desktop de mo cho nhanh."
