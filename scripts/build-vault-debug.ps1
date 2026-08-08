param(
    [switch]$SkipClean
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

$projectRoot = Split-Path -Parent $PSScriptRoot
$javaHome = Join-Path $projectRoot ".tools\jdk-17.0.20+8"
$androidHome = Join-Path $projectRoot ".tools\android-sdk"
$androidUserHome = Join-Path $projectRoot ".tools\android-user-home"
$gradleUserHome = Join-Path $projectRoot ".tools\gradle-user-home"
$wrapperJar = Join-Path $projectRoot "gradle\wrapper\gradle-wrapper.jar"
$expectedWrapperHash = "55243ef57851f12b070ad14f7f5bb8302daceeebc5bce5ece5fa6edb23e1145c"

$requiredFiles = @(
    (Join-Path $javaHome "bin\java.exe"),
    (Join-Path $androidHome "platforms\android-36\android.jar"),
    (Join-Path $androidHome "build-tools\36.0.0\aapt2.exe"),
    (Join-Path $androidHome "build-tools\36.0.0\apksigner.bat"),
    (Join-Path $projectRoot "gradlew.bat"),
    $wrapperJar,
    (Join-Path $projectRoot "vault\build.gradle")
)
foreach ($file in $requiredFiles) {
    if (-not (Test-Path -LiteralPath $file -PathType Leaf)) {
        throw "Required build component is missing: $file"
    }
}

$actualWrapperHash = (Get-FileHash -LiteralPath $wrapperJar -Algorithm SHA256).Hash.ToLowerInvariant()
if ($actualWrapperHash -ne $expectedWrapperHash) {
    throw "Gradle Wrapper JAR checksum mismatch: $actualWrapperHash"
}

$env:JAVA_HOME = $javaHome
$env:ANDROID_HOME = $androidHome
$env:ANDROID_USER_HOME = $androidUserHome
$env:GRADLE_USER_HOME = $gradleUserHome
$env:PATH = "$javaHome\bin;$androidHome\platform-tools;$env:PATH"

& powershell.exe -NoProfile -ExecutionPolicy Bypass -File `
    (Join-Path $PSScriptRoot "verify-vault-security.ps1")
if ($LASTEXITCODE -ne 0) {
    throw "Vault source audit failed."
}

$gradleArguments = @("--no-daemon", "--no-configuration-cache")
if (-not $SkipClean) {
    $gradleArguments += "clean"
}
$gradleArguments += @(":vault:lintDebug", ":vault:assembleDebug")

Push-Location $projectRoot
try {
    & (Join-Path $projectRoot "gradlew.bat") @gradleArguments
    if ($LASTEXITCODE -ne 0) {
        throw "Vault debug build failed (exit code: $LASTEXITCODE)."
    }
} finally {
    Pop-Location
}

$apk = Join-Path $projectRoot "vault\build\outputs\apk\debug\vault-debug.apk"
if (-not (Test-Path -LiteralPath $apk -PathType Leaf)) {
    throw "Debug APK was not produced: $apk"
}

& powershell.exe -NoProfile -ExecutionPolicy Bypass -File `
    (Join-Path $PSScriptRoot "verify-vault-security.ps1") `
    -ApkPath $apk `
    -ExpectedPackage "hu.bordasm.excelfingerprintunlock.debug" `
    -AllowDebuggable
if ($LASTEXITCODE -ne 0) {
    throw "Vault debug APK audit failed."
}

$buildFileText = Get-Content -LiteralPath (Join-Path $projectRoot "vault\build.gradle") -Raw
$versionMatch = [regex]::Match($buildFileText, 'versionName\s*=\s*"([^"]+)"')
$versionName = if ($versionMatch.Success) { $versionMatch.Groups[1].Value } else { "unknown" }
$dist = Join-Path $projectRoot "dist"
New-Item -ItemType Directory -Path $dist -Force | Out-Null
$outputApk = Join-Path $dist "ExcelFingerprintUnlock-$versionName-debug.apk"
Copy-Item -LiteralPath $apk -Destination $outputApk -Force

$apkHash = (Get-FileHash -LiteralPath $outputApk -Algorithm SHA256).Hash.ToLowerInvariant()
Write-Host "Debug APK: $outputApk"
Write-Host "SHA-256: $apkHash"
