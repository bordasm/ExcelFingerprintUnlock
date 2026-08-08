param(
    [switch]$SkipClean
)

$ErrorActionPreference = "Stop"
$projectRoot = Split-Path -Parent $PSScriptRoot
$env:JAVA_HOME = Join-Path $projectRoot ".tools\jdk-17.0.20+8"
$env:ANDROID_HOME = Join-Path $projectRoot ".tools\android-sdk"
$env:ANDROID_USER_HOME = Join-Path $projectRoot ".tools\android-user-home"
$env:GRADLE_USER_HOME = Join-Path $projectRoot ".tools\gradle-user-home"
$env:PATH = "$env:JAVA_HOME\bin;$env:ANDROID_HOME\platform-tools;$env:PATH"

$requiredFiles = @(
    (Join-Path $env:JAVA_HOME "bin\java.exe"),
    (Join-Path $env:ANDROID_HOME "platforms\android-36\android.jar"),
    (Join-Path $env:ANDROID_HOME "build-tools\36.0.0\aapt2.exe"),
    (Join-Path $projectRoot "gradlew.bat"),
    (Join-Path $projectRoot "gradle\wrapper\gradle-wrapper.jar")
)
foreach ($file in $requiredFiles) {
    if (-not (Test-Path -LiteralPath $file)) {
        throw "Hiányzó buildösszetevő: $file"
    }
}

$expectedWrapperHash = "55243ef57851f12b070ad14f7f5bb8302daceeebc5bce5ece5fa6edb23e1145c"
$wrapperJar = Join-Path $projectRoot "gradle\wrapper\gradle-wrapper.jar"
$actualWrapperHash = (Get-FileHash -LiteralPath $wrapperJar -Algorithm SHA256).Hash.ToLowerInvariant()
if ($actualWrapperHash -ne $expectedWrapperHash) {
    throw "A Gradle Wrapper JAR ellenőrzőösszege hibás: $actualWrapperHash"
}

& powershell.exe -NoProfile -ExecutionPolicy Bypass -File (Join-Path $PSScriptRoot "verify-security.ps1")
if ($LASTEXITCODE -ne 0) {
    throw "A forrás biztonsági ellenőrzése sikertelen."
}

$gradleArguments = @("--no-daemon")
if (-not $SkipClean) {
    $gradleArguments += "clean"
}
$gradleArguments += @(':app:lintDebug', ':app:assembleDebug')

Push-Location $projectRoot
try {
    & (Join-Path $projectRoot "gradlew.bat") @gradleArguments
    if ($LASTEXITCODE -ne 0) {
        throw "A Gradle build sikertelen (exit code: $LASTEXITCODE)."
    }
} finally {
    Pop-Location
}

$apk = Join-Path $projectRoot "app\build\outputs\apk\debug\app-debug.apk"
& powershell.exe -NoProfile -ExecutionPolicy Bypass -File (Join-Path $PSScriptRoot "verify-security.ps1") -ApkPath $apk
if ($LASTEXITCODE -ne 0) {
    throw "Az elkészült APK biztonsági ellenőrzése sikertelen."
}

$apkHash = (Get-FileHash -LiteralPath $apk -Algorithm SHA256).Hash.ToLowerInvariant()
Write-Host "APK: $apk"
Write-Host "SHA-256: $apkHash"
