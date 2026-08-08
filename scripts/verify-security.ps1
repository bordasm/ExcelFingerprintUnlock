param(
    [string]$ApkPath = ""
)

$ErrorActionPreference = "Stop"
$projectRoot = Split-Path -Parent $PSScriptRoot
$manifestPath = Join-Path $projectRoot "app\src\main\AndroidManifest.xml"

$manifest = Get-Content -LiteralPath $manifestPath -Raw
if ($manifest -match "<uses-permission") {
    throw "Tiltott manifest elem: a próba APK nem kérhet uses-permission engedélyt."
}

$forbiddenServicePatterns = @(
    "\.getText\s*\(",
    "getAutofillValue\s*\(",
    "\.getHint\s*\(",
    "getContentDescription\s*\(",
    "\.getExtras\s*\(",
    "getHtmlInfo\s*\(",
    "android\.util\.Log",
    "Log\.[vdiew]\s*\("
)

$javaFiles = Get-ChildItem -LiteralPath (Join-Path $projectRoot "app\src\main\java") -Recurse -File -Filter "*.java"
foreach ($file in $javaFiles) {
    $content = Get-Content -LiteralPath $file.FullName -Raw
    foreach ($pattern in $forbiddenServicePatterns) {
        if ($content -match $pattern) {
            throw "Tiltott adatolvasó vagy naplózó minta a Java-forrásban: $pattern ($($file.FullName))"
        }
    }
}

$sourceFiles = Get-ChildItem -LiteralPath (Join-Path $projectRoot "app\src") -Recurse -File
$forbiddenProjectPatterns = @(
    "android\.permission\.INTERNET",
    "android\.permission\.READ_EXTERNAL_STORAGE",
    "android\.permission\.WRITE_EXTERNAL_STORAGE",
    "android\.permission\.MANAGE_EXTERNAL_STORAGE",
    "android\.accessibilityservice",
    "ClipboardManager",
    "System\.out\.print",
    "printStackTrace"
)

foreach ($file in $sourceFiles) {
    $content = Get-Content -LiteralPath $file.FullName -Raw
    foreach ($pattern in $forbiddenProjectPatterns) {
        if ($content -match $pattern) {
            throw "Tiltott projektminta: $pattern ($($file.FullName))"
        }
    }
}

if ($ApkPath) {
    $resolvedApk = (Resolve-Path -LiteralPath $ApkPath).Path
    $aapt2 = Join-Path $projectRoot ".tools\android-sdk\build-tools\36.0.0\aapt2.exe"
    if (-not (Test-Path -LiteralPath $aapt2)) {
        throw "Az APK-audithoz nem található az aapt2: $aapt2"
    }
    $permissionDump = & $aapt2 dump permissions $resolvedApk 2>&1 | Out-String
    if ($LASTEXITCODE -ne 0) {
        throw "Az aapt2 APK-audit hibával leállt: $permissionDump"
    }
    if ($permissionDump -match "uses-permission") {
        throw "Az elkészült APK váratlan uses-permission elemet tartalmaz:`n$permissionDump"
    }
}

Write-Host "OK: nincs uses-permission, tiltott mezőérték-olvasás vagy kockázatos API."
