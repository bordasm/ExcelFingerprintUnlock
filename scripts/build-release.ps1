param(
    [string]$KeystorePath = "",
    [string]$KeyAlias = "excel-fingerprint-unlock",
    [Alias("SkipClean")]
    [switch]$ReplaceExistingRelease
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

$projectRoot = Split-Path -Parent $PSScriptRoot
$javaHome = Join-Path $projectRoot ".tools\jdk-17.0.20+8"
$androidHome = Join-Path $projectRoot ".tools\android-sdk"
$androidUserHome = Join-Path $projectRoot ".tools\android-user-home"
$gradleUserHome = Join-Path $projectRoot ".tools\gradle-user-home"
$wrapperJar = Join-Path $projectRoot "gradle\wrapper\gradle-wrapper.jar"
$keytool = Join-Path $javaHome "bin\keytool.exe"
$expectedWrapperHash = "55243ef57851f12b070ad14f7f5bb8302daceeebc5bce5ece5fa6edb23e1145c"
$releasePinPath = Join-Path $projectRoot "release-signing-cert.sha256"
$expectedVersionCode = 3
$expectedVersionName = "1.0.2"

$usingDefaultKeystorePath = [string]::IsNullOrWhiteSpace($KeystorePath)
if ($usingDefaultKeystorePath) {
    $privateRoot = Join-Path (Split-Path -Parent $projectRoot) `
        "ExcelFingerprintUnlock-private"
    $KeystorePath = Join-Path $privateRoot "release-signing.p12"
}
if (-not [System.IO.Path]::IsPathRooted($KeystorePath)) {
    throw "KeystorePath must be an absolute path outside the project."
}
if ($KeyAlias -notmatch '^[A-Za-z0-9._-]+$') {
    throw "KeyAlias contains unsupported characters."
}

$projectFull = [System.IO.Path]::GetFullPath($projectRoot).TrimEnd('\', '/')
$keystoreFull = [System.IO.Path]::GetFullPath($KeystorePath)
if ($keystoreFull.Equals($projectFull, [System.StringComparison]::OrdinalIgnoreCase) -or
    $keystoreFull.StartsWith(
        $projectFull + [System.IO.Path]::DirectorySeparatorChar,
        [System.StringComparison]::OrdinalIgnoreCase
    )) {
    throw "The release keystore must be outside the project tree: $keystoreFull"
}

$requiredFiles = @(
    (Join-Path $javaHome "bin\java.exe"),
    $keytool,
    (Join-Path $androidHome "platforms\android-36\android.jar"),
    (Join-Path $androidHome "build-tools\36.0.0\aapt2.exe"),
    (Join-Path $androidHome "build-tools\36.0.0\apksigner.bat"),
    (Join-Path $projectRoot "gradlew.bat"),
    $wrapperJar,
    (Join-Path $projectRoot "vault\build.gradle"),
    $releasePinPath
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

$buildFilePath = Join-Path $projectRoot "vault\build.gradle"
$buildFileText = Get-Content -LiteralPath $buildFilePath -Raw
$versionCodeMatch = [regex]::Match($buildFileText, 'versionCode\s*=\s*([0-9]+)')
$versionNameMatch = [regex]::Match($buildFileText, 'versionName\s*=\s*"([^"]+)"')
if (-not $versionCodeMatch.Success -or -not $versionNameMatch.Success) {
    throw "Could not read the vault version from build.gradle."
}
$versionCode = [int]$versionCodeMatch.Groups[1].Value
$versionName = $versionNameMatch.Groups[1].Value
if ($versionCode -ne $expectedVersionCode -or $versionName -ne $expectedVersionName) {
    throw "Unexpected vault version: code=$versionCode name=$versionName"
}

function ConvertTo-PlainText {
    param([Security.SecureString]$SecureValue)
    $pointer = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($SecureValue)
    try {
        return [Runtime.InteropServices.Marshal]::PtrToStringBSTR($pointer)
    } finally {
        [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($pointer)
    }
}

function Protect-PrivateDirectory {
    param([string]$DirectoryPath)
    $currentUser = [Security.Principal.WindowsIdentity]::GetCurrent().Name
    & icacls.exe $DirectoryPath `
        "/inheritance:r" `
        "/grant:r" `
        "${currentUser}:(OI)(CI)F" `
        "*S-1-5-18:(OI)(CI)F" `
        "*S-1-5-32-544:(OI)(CI)F" | Out-Null
    if ($LASTEXITCODE -ne 0) {
        throw "Could not restrict release signing directory ACL."
    }
}

function Protect-PrivateFile {
    param([string]$FilePath)
    $currentUser = [Security.Principal.WindowsIdentity]::GetCurrent().Name
    & icacls.exe $FilePath `
        "/inheritance:r" `
        "/grant:r" `
        "${currentUser}:F" `
        "*S-1-5-18:F" `
        "*S-1-5-32-544:F" | Out-Null
    if ($LASTEXITCODE -ne 0) {
        throw "Could not restrict release signing file ACL."
    }
}

$environmentNames = @(
    "EXCEL_FPU_KEYSTORE_PATH",
    "EXCEL_FPU_STORE_PASSWORD",
    "EXCEL_FPU_KEY_ALIAS",
    "EXCEL_FPU_KEY_PASSWORD"
)
$previousEnvironment = @{}
foreach ($name in $environmentNames) {
    $previousEnvironment[$name] = [Environment]::GetEnvironmentVariable(
        $name, [EnvironmentVariableTarget]::Process
    )
}

$securePassword = $null
$confirmation = $null
$confirmationText = $null
$passwordText = $null
$temporaryCertificate = $null
try {
    $creatingKey = -not (Test-Path -LiteralPath $keystoreFull)
    if (-not $creatingKey -and -not (Test-Path -LiteralPath $keystoreFull -PathType Leaf)) {
        throw "Keystore path is not a file: $keystoreFull"
    }

    $keystoreDirectory = Split-Path -Parent $keystoreFull
    if ($creatingKey) {
        New-Item -ItemType Directory -Path $keystoreDirectory -Force | Out-Null
    }
    if ($usingDefaultKeystorePath) {
        Protect-PrivateDirectory $keystoreDirectory
    }
    if (-not $creatingKey) {
        Protect-PrivateFile $keystoreFull
    }

    Write-Host "Release keystore: $keystoreFull"
    if ($creatingKey) {
        Write-Host "The keystore does not exist; a PKCS12 RSA-4096 release key will be created."
    }

    $securePassword = Read-Host "Release keystore password" -AsSecureString
    $passwordText = ConvertTo-PlainText $securePassword
    if ([string]::IsNullOrEmpty($passwordText)) {
        throw "The release keystore password cannot be empty."
    }

    if ($creatingKey) {
        if ($passwordText.Length -lt 12) {
            throw "Use a release keystore password of at least 12 characters."
        }
        $confirmation = Read-Host "Repeat release keystore password" -AsSecureString
        $confirmationText = ConvertTo-PlainText $confirmation
        if ($passwordText -cne $confirmationText) {
            throw "The two release keystore passwords do not match."
        }
        $confirmationText = $null
    }

    [Environment]::SetEnvironmentVariable(
        "EXCEL_FPU_KEYSTORE_PATH", $keystoreFull, [EnvironmentVariableTarget]::Process
    )
    [Environment]::SetEnvironmentVariable(
        "EXCEL_FPU_STORE_PASSWORD", $passwordText, [EnvironmentVariableTarget]::Process
    )
    [Environment]::SetEnvironmentVariable(
        "EXCEL_FPU_KEY_ALIAS", $KeyAlias, [EnvironmentVariableTarget]::Process
    )
    [Environment]::SetEnvironmentVariable(
        "EXCEL_FPU_KEY_PASSWORD", $passwordText, [EnvironmentVariableTarget]::Process
    )

    if ($creatingKey) {
        $keytoolArguments = @(
            "-genkeypair",
            "-noprompt",
            "-alias", $KeyAlias,
            "-keyalg", "RSA",
            "-keysize", "4096",
            "-sigalg", "SHA256withRSA",
            "-validity", "10000",
            "-storetype", "PKCS12",
            "-keystore", $keystoreFull,
            "-dname", "CN=Excel Fingerprint Unlock, O=Private, C=HU",
            "-storepass:env", "EXCEL_FPU_STORE_PASSWORD",
            "-keypass:env", "EXCEL_FPU_KEY_PASSWORD"
        )
        & $keytool @keytoolArguments
        if ($LASTEXITCODE -ne 0) {
            throw "Release key creation failed (exit code: $LASTEXITCODE)."
        }
        Protect-PrivateFile $keystoreFull
        Write-Host "Release key created. Back up the .p12 file and its password separately."
    } else {
        & $keytool -list -alias $KeyAlias -keystore $keystoreFull `
            -storepass:env EXCEL_FPU_STORE_PASSWORD | Out-Null
        if ($LASTEXITCODE -ne 0) {
            throw "Could not open the release keystore or find alias '$KeyAlias'."
        }
    }

    $env:JAVA_HOME = $javaHome
    $env:ANDROID_HOME = $androidHome
    $env:ANDROID_USER_HOME = $androidUserHome
    $env:GRADLE_USER_HOME = $gradleUserHome
    $env:PATH = "$javaHome\bin;$androidHome\platform-tools;$env:PATH"

    $temporaryDirectory = Join-Path $projectRoot ".tools\temporary-certificates"
    New-Item -ItemType Directory -Path $temporaryDirectory -Force | Out-Null
    $temporaryCertificate = Join-Path $temporaryDirectory `
        (([System.IO.Path]::GetRandomFileName()) + ".der")
    & $keytool -exportcert -alias $KeyAlias -keystore $keystoreFull `
        -storepass:env EXCEL_FPU_STORE_PASSWORD -file $temporaryCertificate
    if ($LASTEXITCODE -ne 0) {
        throw "Could not export the public release certificate."
    }
    $signerHash = (Get-FileHash -LiteralPath $temporaryCertificate -Algorithm SHA256).Hash.ToUpperInvariant()
    $pinnedSignerHash = ((Get-Content -LiteralPath $releasePinPath -Raw) `
        -replace "[^0-9A-Fa-f]", "").ToUpperInvariant()
    if ($pinnedSignerHash.Length -ne 64 -or $signerHash -ne $pinnedSignerHash) {
        throw "Release signing certificate does not match the pinned fingerprint."
    }

    & powershell.exe -NoProfile -ExecutionPolicy Bypass -File `
        (Join-Path $PSScriptRoot "verify-vault-security.ps1") `
        -ExpectedVersionCode $expectedVersionCode `
        -ExpectedVersionName $expectedVersionName
    if ($LASTEXITCODE -ne 0) {
        throw "Vault source audit failed."
    }

    $gradleArguments = @(
        "--no-daemon",
        "--no-configuration-cache",
        "--no-build-cache",
        "clean",
        ":vault:lintRelease",
        ":vault:assembleRelease"
    )

    Push-Location $projectRoot
    try {
        & (Join-Path $projectRoot "gradlew.bat") @gradleArguments
        if ($LASTEXITCODE -ne 0) {
            throw "Vault release build failed (exit code: $LASTEXITCODE)."
        }
    } finally {
        Pop-Location
    }

    $apk = Join-Path $projectRoot "vault\build\outputs\apk\release\vault-release.apk"
    if (-not (Test-Path -LiteralPath $apk -PathType Leaf)) {
        throw "Release APK was not produced: $apk"
    }

    $metadataPath = Join-Path $projectRoot `
        "vault\build\outputs\apk\release\output-metadata.json"
    if (-not (Test-Path -LiteralPath $metadataPath -PathType Leaf)) {
        throw "Release output metadata was not produced: $metadataPath"
    }
    $metadata = Get-Content -LiteralPath $metadataPath -Raw | ConvertFrom-Json
    if ($metadata.applicationId -ne "hu.bordasm.excelfingerprintunlock" -or
        $metadata.variantName -ne "release" -or $metadata.elements.Count -ne 1) {
        throw "Unexpected release output metadata."
    }
    $metadataElement = $metadata.elements[0]
    if ($metadataElement.type -ne "SINGLE" -or $metadataElement.filters.Count -ne 0 -or
        [int]$metadataElement.versionCode -ne $versionCode -or
        $metadataElement.versionName -ne $versionName -or
        $metadataElement.outputFile -ne "vault-release.apk") {
        throw "Release output metadata does not match the expected artifact."
    }

    & powershell.exe -NoProfile -ExecutionPolicy Bypass -File `
        (Join-Path $PSScriptRoot "verify-vault-security.ps1") `
        -ApkPath $apk `
        -ExpectedPackage "hu.bordasm.excelfingerprintunlock" `
        -ExpectedVersionCode $expectedVersionCode `
        -ExpectedVersionName $expectedVersionName `
        -ExpectedSignerSha256 $signerHash
    if ($LASTEXITCODE -ne 0) {
        throw "Vault release APK audit failed."
    }

    $dist = Join-Path $projectRoot "dist"
    New-Item -ItemType Directory -Path $dist -Force | Out-Null
    $outputApk = Join-Path $dist "ExcelFingerprintUnlock-$versionName-release.apk"
    $mappingSource = Join-Path $projectRoot `
        "vault\build\outputs\mapping\release\mapping.txt"
    if (-not (Test-Path -LiteralPath $mappingSource -PathType Leaf) -or
        (Get-Item -LiteralPath $mappingSource).Length -eq 0) {
        throw "R8 mapping was not produced: $mappingSource"
    }
    $mappingOutput = Join-Path $dist `
        "ExcelFingerprintUnlock-$versionName-release-mapping.txt"
    $auditRecord = Join-Path $dist "ExcelFingerprintUnlock-$versionName-release.sha256.txt"
    $existingFinals = @(
        @($outputApk, $mappingOutput, $auditRecord) | Where-Object {
            Test-Path -LiteralPath $_
        }
    )
    if ($existingFinals.Count -gt 0 -and -not $ReplaceExistingRelease) {
        throw "Release artifacts for version $versionName already exist. " +
            "Use -ReplaceExistingRelease to archive and replace them."
    }

    $distFull = [System.IO.Path]::GetFullPath($dist).TrimEnd('\', '/')
    $publicationDirectory = Join-Path $dist `
        (".publishing-" + $versionName + "-" + [Guid]::NewGuid().ToString("N"))
    $publicationFull = [System.IO.Path]::GetFullPath($publicationDirectory)
    if (-not $publicationFull.StartsWith(
        $distFull + [System.IO.Path]::DirectorySeparatorChar,
        [System.StringComparison]::OrdinalIgnoreCase
    ) -or ([System.IO.Path]::GetFileName($publicationFull) -notlike ".publishing-*") ) {
        throw "Unexpected publication staging directory: $publicationFull"
    }

    New-Item -ItemType Directory -Path $publicationDirectory | Out-Null
    try {
        $stagedApk = Join-Path $publicationDirectory `
            "ExcelFingerprintUnlock-$versionName-release.apk"
        $stagedMapping = Join-Path $publicationDirectory `
            "ExcelFingerprintUnlock-$versionName-release-mapping.txt"
        $stagedAudit = Join-Path $publicationDirectory `
            "ExcelFingerprintUnlock-$versionName-release.sha256.txt"
        Copy-Item -LiteralPath $apk -Destination $stagedApk
        Copy-Item -LiteralPath $mappingSource -Destination $stagedMapping

        $buildApkHash = (Get-FileHash -LiteralPath $apk -Algorithm SHA256).Hash.ToUpperInvariant()
        $apkHash = (Get-FileHash -LiteralPath $stagedApk -Algorithm SHA256).Hash.ToUpperInvariant()
        if ($apkHash -ne $buildApkHash) {
            throw "The staged APK differs from the audited build output."
        }
        $mappingHash = (Get-FileHash -LiteralPath $stagedMapping -Algorithm SHA256).Hash.ToUpperInvariant()

        & powershell.exe -NoProfile -ExecutionPolicy Bypass -File `
            (Join-Path $PSScriptRoot "verify-vault-security.ps1") `
            -ApkPath $stagedApk `
            -ExpectedPackage "hu.bordasm.excelfingerprintunlock" `
            -ExpectedVersionCode $expectedVersionCode `
            -ExpectedVersionName $expectedVersionName `
            -ExpectedSignerSha256 $signerHash
        if ($LASTEXITCODE -ne 0) {
            throw "Staged release APK audit failed."
        }

        @(
            "APK SHA-256: $apkHash",
            "R8 mapping SHA-256: $mappingHash",
            "Signer certificate SHA-256: $signerHash",
            "Application ID: hu.bordasm.excelfingerprintunlock",
            "Version Code: $versionCode",
            "Version: $versionName"
        ) | Set-Content -LiteralPath $stagedAudit -Encoding Ascii

        if ($existingFinals.Count -gt 0) {
            $supersededDirectory = Join-Path $dist (
                "superseded-" + $versionName + "-" +
                (Get-Date).ToUniversalTime().ToString("yyyyMMddTHHmmssZ") + "-" +
                [Guid]::NewGuid().ToString("N")
            )
            $supersededFull = [System.IO.Path]::GetFullPath($supersededDirectory)
            if (-not $supersededFull.StartsWith(
                $distFull + [System.IO.Path]::DirectorySeparatorChar,
                [System.StringComparison]::OrdinalIgnoreCase
            ) -or ([System.IO.Path]::GetFileName($supersededFull) -notlike "superseded-*") ) {
                throw "Unexpected superseded-release directory: $supersededFull"
            }
            New-Item -ItemType Directory -Path $supersededDirectory | Out-Null
            foreach ($oldPath in @($auditRecord, $outputApk, $mappingOutput)) {
                if (Test-Path -LiteralPath $oldPath -PathType Leaf) {
                    Move-Item -LiteralPath $oldPath -Destination (
                        Join-Path $supersededDirectory ([System.IO.Path]::GetFileName($oldPath))
                    )
                }
            }
            Write-Host "Previous same-version release archived: $supersededDirectory"
        }

        Move-Item -LiteralPath $stagedApk -Destination $outputApk
        Move-Item -LiteralPath $stagedMapping -Destination $mappingOutput
        Move-Item -LiteralPath $stagedAudit -Destination $auditRecord
    } finally {
        if (Test-Path -LiteralPath $publicationDirectory) {
            Remove-Item -LiteralPath $publicationDirectory -Recurse -Force
        }
    }

    if ((Get-FileHash -LiteralPath $outputApk -Algorithm SHA256).Hash.ToUpperInvariant() `
            -ne $apkHash -or
        (Get-FileHash -LiteralPath $mappingOutput -Algorithm SHA256).Hash.ToUpperInvariant() `
            -ne $mappingHash) {
        throw "Published release artifact hash mismatch."
    }

    Write-Host "Release APK: $outputApk"
    Write-Host "APK SHA-256: $apkHash"
    Write-Host "R8 mapping: $mappingOutput"
    Write-Host "R8 mapping SHA-256: $mappingHash"
    Write-Host "Signer certificate SHA-256: $signerHash"
    Write-Host "Audit record: $auditRecord"
} finally {
    if ($null -ne $temporaryCertificate -and
        (Test-Path -LiteralPath $temporaryCertificate -PathType Leaf)) {
        Remove-Item -LiteralPath $temporaryCertificate -Force
    }
    foreach ($name in $environmentNames) {
        [Environment]::SetEnvironmentVariable(
            $name, $previousEnvironment[$name], [EnvironmentVariableTarget]::Process
        )
    }
    $passwordText = $null
    $confirmationText = $null
    if ($null -ne $securePassword) {
        $securePassword.Dispose()
    }
    if ($null -ne $confirmation) {
        $confirmation.Dispose()
    }
}
