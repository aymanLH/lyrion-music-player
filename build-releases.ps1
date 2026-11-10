# Builds Lyrion release APKs for armeabi-v7a (32-bit) and arm64-v8a (64-bit)
$ErrorActionPreference = "Stop"

$env:JAVA_HOME = "C:\Program Files\Java\jdk-17"
$env:Path = "$env:JAVA_HOME\bin;$env:Path"
# $env:ANDROID_HOME = "C:\Android"   # uncomment only if Gradle says "SDK location not found"

Set-Location $PSScriptRoot

$gradleText = Get-Content "app\build.gradle.kts" -Raw
$version = [regex]::Match($gradleText, 'versionName\s*=\s*"([^"]+)"').Groups[1].Value
if (-not $version) { throw "Could not read versionName from app\build.gradle.kts" }

# Output goes outside the repo so the APKs can't be committed by accident
$outDir = Join-Path (Split-Path $PSScriptRoot -Parent) "lyrion-releases"
New-Item -ItemType Directory -Force -Path $outDir | Out-Null

Add-Type -AssemblyName System.IO.Compression.FileSystem

foreach ($abi in @("armeabi-v7a", "arm64-v8a")) {
    Write-Host "=== Building $abi ===" -ForegroundColor Cyan

    & .\gradlew.bat clean assembleRelease "-PABI_FILTERS=$abi"
    if ($LASTEXITCODE -ne 0) { throw "Build failed for $abi" }

    $built = "app\build\outputs\apk\release\lyrion-$version-release.apk"
    if (-not (Test-Path $built)) { throw "APK not found: $built" }

    $dest = Join-Path $outDir "lyrion-$version-$abi.apk"
    Copy-Item $built $dest -Force

    # Sanity check: the APK must contain native libs for this ABI only
    $zip = [System.IO.Compression.ZipFile]::OpenRead((Resolve-Path $dest).Path)
    $found = $zip.Entries |
        Where-Object { $_.FullName -like "lib/*/*" } |
        ForEach-Object { $_.FullName.Split("/")[1] } |
        Sort-Object -Unique
    $zip.Dispose()
    if (($found -join ",") -ne $abi) { throw "Wrong ABIs in $dest : $($found -join ', ')" }

    $hash = (Get-FileHash $dest -Algorithm SHA256).Hash
    Write-Host "OK  $dest" -ForegroundColor Green
    Write-Host "    ABI: $($found -join ', ')   SHA256: $hash"
}

Write-Host "`nDone. APKs are in: $outDir" -ForegroundColor Cyan