param(
    [string]$ApkPath = "",
    [string]$ExpectedPackage = "hu.bordasm.excelfingerprintunlock",
    [int]$ExpectedVersionCode = 3,
    [string]$ExpectedVersionName = "1.0.2",
    [string]$ExpectedSignerSha256 = "",
    [switch]$AllowDebuggable
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

$projectRoot = Split-Path -Parent $PSScriptRoot
$vaultRoot = Join-Path $projectRoot "vault"
$manifestPath = Join-Path $vaultRoot "src\main\AndroidManifest.xml"
$sourceRoot = Join-Path $vaultRoot "src"
$buildTools = Join-Path $projectRoot ".tools\android-sdk\build-tools\36.0.0"

function Assert-True {
    param(
        [bool]$Condition,
        [string]$Message
    )
    if (-not $Condition) {
        throw $Message
    }
}

function Normalize-Sha256 {
    param([string]$Value)
    return ($Value -replace "[^0-9A-Fa-f]", "").ToUpperInvariant()
}

Assert-True (Test-Path -LiteralPath $manifestPath -PathType Leaf) `
    "Vault manifest not found: $manifestPath"

$manifestText = Get-Content -LiteralPath $manifestPath -Raw
[xml]$manifestXml = $manifestText
$androidNamespace = "http://schemas.android.com/apk/res/android"
$toolsNamespace = "http://schemas.android.com/tools"
$namespaceManager = New-Object System.Xml.XmlNamespaceManager($manifestXml.NameTable)
$namespaceManager.AddNamespace("android", $androidNamespace)

$permissionNodes = @($manifestXml.SelectNodes("/manifest/uses-permission", $namespaceManager))
$activePermissionNodes = @($permissionNodes | Where-Object {
    $_.GetAttribute("node", $toolsNamespace) -ne "remove"
})
$removedPermissionNodes = @($permissionNodes | Where-Object {
    $_.GetAttribute("node", $toolsNamespace) -eq "remove"
})
$sourcePermissions = @($activePermissionNodes | ForEach-Object {
    $_.GetAttribute("name", $androidNamespace)
})
$allowedSourcePermissions = @("android.permission.USE_BIOMETRIC")
foreach ($permission in $sourcePermissions) {
    Assert-True ($allowedSourcePermissions -contains $permission) `
        "Unexpected source permission: $permission"
}
Assert-True ($sourcePermissions.Count -eq 1) `
    "The vault manifest must declare exactly USE_BIOMETRIC."
Assert-True ($sourcePermissions[0] -eq "android.permission.USE_BIOMETRIC") `
    "USE_BIOMETRIC is missing from the vault manifest."
Assert-True ($removedPermissionNodes.Count -eq 1) `
    "Exactly one transitive permission removal is required."
Assert-True ($removedPermissionNodes[0].GetAttribute(
    "name", $androidNamespace) -eq "android.permission.USE_FINGERPRINT") `
    "Only the obsolete transitive USE_FINGERPRINT permission may be removed."

$application = $manifestXml.SelectSingleNode("/manifest/application", $namespaceManager)
Assert-True ($null -ne $application) "Application node is missing."
Assert-True ($application.GetAttribute("allowBackup", $androidNamespace) -eq "false") `
    "android:allowBackup must be false."
Assert-True ($application.GetAttribute("fullBackupContent", $androidNamespace) -eq "false") `
    "android:fullBackupContent must be false."
Assert-True ($application.GetAttribute("usesCleartextTraffic", $androidNamespace) -eq "false") `
    "android:usesCleartextTraffic must be false."
Assert-True (-not [string]::IsNullOrWhiteSpace(
    $application.GetAttribute("dataExtractionRules", $androidNamespace))) `
    "android:dataExtractionRules must be configured."

$service = $manifestXml.SelectSingleNode(
    "/manifest/application/service[contains(@android:name, 'ExcelAutofillService')]",
    $namespaceManager
)
Assert-True ($null -ne $service) "ExcelAutofillService is missing from the manifest."
Assert-True ($service.GetAttribute("exported", $androidNamespace) -eq "true") `
    "ExcelAutofillService must be exported for the Android Autofill framework."
Assert-True ($service.GetAttribute("permission", $androidNamespace) -eq `
    "android.permission.BIND_AUTOFILL_SERVICE") `
    "ExcelAutofillService must be protected by BIND_AUTOFILL_SERVICE."

$unexpectedExported = @($manifestXml.SelectNodes(
    "/manifest/application/*[@android:exported='true']", $namespaceManager
) | Where-Object {
    $name = $_.GetAttribute("name", $androidNamespace)
    -not (($_.LocalName -eq "activity" -and $name -like "*MainActivity") -or
          ($_.LocalName -eq "service" -and $name -like "*ExcelAutofillService"))
})
Assert-True ($unexpectedExported.Count -eq 0) `
    "An unexpected exported component exists in the vault manifest."

$sourceFiles = @(Get-ChildItem -LiteralPath $sourceRoot -Recurse -File)
$forbiddenProjectPatterns = @(
    "android\.permission\.INTERNET",
    "android\.permission\.(READ|WRITE|MANAGE)_EXTERNAL_STORAGE",
    "android\.accessibilityservice",
    "ClipboardManager",
    "android\.util\.Log",
    "\bLog\.[vdiew]\s*\(",
    "System\.out\.print",
    "printStackTrace",
    "\.getLocalizedMessage\s*\(",
    "\.getMessage\s*\(",
    "\bWebView\b",
    "FirebaseCrashlytics",
    "Sentry\.init",
    "XLSX_AUTOFILL_PROBE",
    "PROBE_ONLY"
)
foreach ($file in $sourceFiles) {
    $content = Get-Content -LiteralPath $file.FullName -Raw
    $patternsForFile = @($forbiddenProjectPatterns)
    if ($file.Extension -eq ".java") {
        $patternsForFile += @(
            "https?://",
            "java\.net\.",
            "HttpURLConnection",
            "\bURLConnection\b",
            "\bOkHttpClient\b",
            "\bRetrofit\b"
        )
    }
    foreach ($pattern in $patternsForFile) {
        if ($content -match $pattern) {
            throw "Forbidden vault source pattern '$pattern' in $($file.FullName)"
        }
    }
}

$excelIdentityPath = Join-Path $vaultRoot `
    "src\main\java\hu\bordasm\excelfingerprintunlock\ExcelIdentity.java"
Assert-True (Test-Path -LiteralPath $excelIdentityPath -PathType Leaf) `
    "ExcelIdentity.java is missing."
$excelIdentitySource = Get-Content -LiteralPath $excelIdentityPath -Raw
Assert-True ($excelIdentitySource -notmatch "EXPECTED_VERSION_(CODE|NAME)") `
    "Excel version pinning must remain disabled in version 1.0.2."
Assert-True ($excelIdentitySource -match "hasSigningCertificate\s*\(") `
    "The Microsoft signing-certificate check is missing."

$structureReaders = @(
    (Join-Path $vaultRoot "src\main\java\hu\bordasm\excelfingerprintunlock\ExcelStructure.java"),
    (Join-Path $vaultRoot "src\main\java\hu\bordasm\excelfingerprintunlock\ExcelAutofillService.java"),
    (Join-Path $vaultRoot "src\main\java\hu\bordasm\excelfingerprintunlock\AutofillAuthActivity.java")
)
$forbiddenForeignFieldReads = @(
    "\.getText\s*\(",
    "getAutofillValue\s*\(",
    "\.getHint\s*\(",
    "getContentDescription\s*\(",
    "\.getExtras\s*\(",
    "getHtmlInfo\s*\("
)
foreach ($reader in $structureReaders) {
    if (-not (Test-Path -LiteralPath $reader -PathType Leaf)) {
        continue
    }
    $content = Get-Content -LiteralPath $reader -Raw
    foreach ($pattern in $forbiddenForeignFieldReads) {
        if ($content -match $pattern) {
            throw "Foreign field-value read '$pattern' in $reader"
        }
    }
}

if (-not [string]::IsNullOrWhiteSpace($ApkPath)) {
    $resolvedApk = (Resolve-Path -LiteralPath $ApkPath).Path
    $aapt2 = Join-Path $buildTools "aapt2.exe"
    $apksigner = Join-Path $buildTools "apksigner.bat"
    $apkanalyzer = Join-Path $projectRoot `
        ".tools\android-sdk\cmdline-tools\latest\bin\apkanalyzer.bat"
    $projectJavaHome = Join-Path $projectRoot ".tools\jdk-17.0.20+8"
    Assert-True (Test-Path -LiteralPath $aapt2 -PathType Leaf) "aapt2 not found: $aapt2"
    Assert-True (Test-Path -LiteralPath $apksigner -PathType Leaf) `
        "apksigner not found: $apksigner"
    Assert-True (Test-Path -LiteralPath $apkanalyzer -PathType Leaf) `
        "apkanalyzer not found: $apkanalyzer"
    Assert-True (Test-Path -LiteralPath (Join-Path $projectJavaHome "bin\java.exe") `
        -PathType Leaf) "Project JDK not found: $projectJavaHome"
    $env:JAVA_HOME = $projectJavaHome

    $permissionDump = (& $aapt2 dump permissions $resolvedApk 2>&1 | Out-String)
    Assert-True ($LASTEXITCODE -eq 0) "aapt2 permission audit failed: $permissionDump"
    $apkPermissions = @([regex]::Matches(
        $permissionDump,
        "uses-permission(?:-sdk-[0-9]+)?: name='([^']+)'"
    ) | ForEach-Object { $_.Groups[1].Value } | Sort-Object -Unique)
    $allowedApkPermissions = @("android.permission.USE_BIOMETRIC")
    foreach ($permission in $apkPermissions) {
        Assert-True ($allowedApkPermissions -contains $permission) `
            "Unexpected permission in APK: $permission"
    }
    Assert-True ($apkPermissions.Count -eq 1) `
        "The APK must contain exactly the USE_BIOMETRIC permission. Found: $($apkPermissions -join ', ')"

    $badging = (& $aapt2 dump badging $resolvedApk 2>&1 | Out-String)
    Assert-True ($LASTEXITCODE -eq 0) "aapt2 badging audit failed: $badging"
    $packageMatch = [regex]::Match($badging, "package: name='([^']+)'", `
        [System.Text.RegularExpressions.RegexOptions]::CultureInvariant)
    Assert-True $packageMatch.Success "Could not read APK package name."
    Assert-True ($packageMatch.Groups[1].Value -eq $ExpectedPackage) `
        "Unexpected APK package: $($packageMatch.Groups[1].Value); expected: $ExpectedPackage"
    Assert-True ($badging -match ("versionCode='" + $ExpectedVersionCode + "'")) `
        "Unexpected APK versionCode."
    $expectedVersionName = if ($AllowDebuggable) {
        $ExpectedVersionName + "-debug"
    } else {
        $ExpectedVersionName
    }
    Assert-True ($badging -match ("versionName='" + [regex]::Escape(
        $expectedVersionName) + "'")) "Unexpected APK versionName."
    Assert-True ($badging -match "minSdkVersion:'31'") `
        "Unexpected APK minSdkVersion."
    Assert-True ($badging -match "targetSdkVersion:'36'") `
        "Unexpected APK targetSdkVersion."
    Assert-True ($badging -match "compileSdkVersion='36'") `
        "Unexpected APK compileSdkVersion."
    if (-not $AllowDebuggable) {
        Assert-True ($badging -notmatch "application-debuggable") `
            "Release APK is debuggable."
    }

    $packagedManifestText = (& $apkanalyzer manifest print $resolvedApk | Out-String)
    Assert-True ($LASTEXITCODE -eq 0) "Packaged manifest audit failed."
    [xml]$packagedManifest = $packagedManifestText
    $packagedNamespaceManager = New-Object System.Xml.XmlNamespaceManager(
        $packagedManifest.NameTable
    )
    $packagedNamespaceManager.AddNamespace("android", $androidNamespace)
    $packagedApplication = $packagedManifest.SelectSingleNode(
        "/manifest/application", $packagedNamespaceManager
    )
    Assert-True ($null -ne $packagedApplication) `
        "Packaged application node is missing."
    Assert-True ($packagedApplication.GetAttribute(
        "allowBackup", $androidNamespace) -eq "false") `
        "Packaged android:allowBackup must be false."
    Assert-True ($packagedApplication.GetAttribute(
        "fullBackupContent", $androidNamespace) -eq "false") `
        "Packaged android:fullBackupContent must be false."
    Assert-True ($packagedApplication.GetAttribute(
        "usesCleartextTraffic", $androidNamespace) -eq "false") `
        "Packaged android:usesCleartextTraffic must be false."
    if (-not $AllowDebuggable) {
        Assert-True ($packagedApplication.GetAttribute(
            "debuggable", $androidNamespace) -ne "true") `
            "Packaged release application is debuggable."
    }

    $packagedService = $packagedManifest.SelectSingleNode(
        "/manifest/application/service[contains(@android:name, 'ExcelAutofillService')]",
        $packagedNamespaceManager
    )
    Assert-True ($null -ne $packagedService) `
        "Packaged ExcelAutofillService is missing."
    Assert-True ($packagedService.GetAttribute(
        "permission", $androidNamespace) -eq "android.permission.BIND_AUTOFILL_SERVICE") `
        "Packaged Autofill service lacks BIND_AUTOFILL_SERVICE protection."
    $packagedUnexpectedExported = @($packagedManifest.SelectNodes(
        "/manifest/application/*[@android:exported='true']",
        $packagedNamespaceManager
    ) | Where-Object {
        $name = $_.GetAttribute("name", $androidNamespace)
        -not (($_.LocalName -eq "activity" -and $name -like "*MainActivity") -or
              ($_.LocalName -eq "service" -and $name -like "*ExcelAutofillService"))
    })
    Assert-True ($packagedUnexpectedExported.Count -eq 0) `
        "An unexpected exported component exists in the packaged manifest."

    $signatureDump = (& $apksigner verify --verbose --print-certs $resolvedApk 2>&1 | Out-String)
    Assert-True ($LASTEXITCODE -eq 0) "APK signature verification failed: $signatureDump"
    $v2Verified = $signatureDump -match `
        "Verified using v2 scheme \(APK Signature Scheme v2\): true"
    $v3Verified = $signatureDump -match `
        "Verified using v3 scheme \(APK Signature Scheme v3\): true"
    if ($AllowDebuggable) {
        Assert-True ($v2Verified -or $v3Verified) `
            "Debug APK Signature Scheme v2/v3 verification did not succeed."
    } else {
        Assert-True $v3Verified `
            "Release APK Signature Scheme v3 verification did not succeed."
    }

    $signerLines = @($signatureDump -split "`r?`n" | Where-Object {
        $_ -match "certificate SHA-256 digest:"
    })
    Assert-True ($signerLines.Count -eq 1) `
        "Exactly one APK signer is required; reported signer count: $($signerLines.Count)."
    $signerLine = $signerLines[0]
    $actualSigner = Normalize-Sha256 (($signerLine -split ":", 2)[1])
    Assert-True ($actualSigner.Length -eq 64) "Invalid signer SHA-256: $actualSigner"
    if (-not [string]::IsNullOrWhiteSpace($ExpectedSignerSha256)) {
        $expectedSigner = Normalize-Sha256 $ExpectedSignerSha256
        Assert-True ($expectedSigner.Length -eq 64) `
            "Expected signer SHA-256 is invalid: $ExpectedSignerSha256"
        Assert-True ($actualSigner -eq $expectedSigner) `
            "APK signer mismatch. Actual: $actualSigner; expected: $expectedSigner"
    }

    Add-Type -AssemblyName System.IO.Compression.FileSystem
    $archive = [System.IO.Compression.ZipFile]::OpenRead($resolvedApk)
    try {
        $forbiddenEntries = @($archive.Entries | Where-Object {
            $_.FullName -match "(?i)(\.jks|\.keystore|\.p12|\.pfx|signing[^/]*\.properties|\.env)$"
        })
        Assert-True ($forbiddenEntries.Count -eq 0) `
            "Private signing material was packaged into the APK."
    } finally {
        $archive.Dispose()
    }

    $apkAscii = [System.Text.Encoding]::ASCII.GetString(
        [System.IO.File]::ReadAllBytes($resolvedApk)
    )
    Assert-True (-not $apkAscii.Contains("XLSX_AUTOFILL_PROBE")) `
        "Probe sentinel found in vault APK."
    Assert-True (-not $apkAscii.Contains("PROBE_ONLY")) `
        "Probe-only marker found in vault APK."

    Write-Host "OK: vault source and APK audit passed."
    Write-Host "Package: $ExpectedPackage"
    Write-Host "Signer SHA-256: $actualSigner"
} else {
    Write-Host "OK: vault source audit passed."
}
