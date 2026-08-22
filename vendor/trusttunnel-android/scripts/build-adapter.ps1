[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [string]$BaselineAar,

    [Parameter(Mandatory = $true)]
    [string]$OutputAar,

    [Parameter(Mandatory = $true)]
    [string]$WorkDirectory,

    [string]$AndroidSdk = $env:ANDROID_HOME,

    [string]$HostManifest
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

$UpstreamRepository = 'https://github.com/TrustTunnel/TrustTunnelClient.git'
$UpstreamCommit = 'be6596652d9722c3109164f505be8e7975a2daa5'
$BaselineAarHash = '8de1d62df1d2242b527c99ab28d172886326bbdeddcc931caa3d23251d6393b1'
$BaselineClassesHash = '89c744cc589c8547bb8796769c630ee06e2cdd0bde6a7ecafce9d9fab82b6386'
$PatchedClassesHash = '760c80213cc7c6074b9a124ec2cd730fbbf4960b5c03a0210fea56e8bb85c2c6'
$PatchedAarHash = '3257274bb06fa2ef7b13d91d40b0bbd81434e9a5b8d1ee2cda4a347ae6428d82'
$FixedTimestamp = '2026-04-09T09:55:00Z'
$PatchPath = (Resolve-Path (Join-Path $PSScriptRoot '..\patches\0001-android-per-app-routing.patch')).Path

$ExpectedPayloadHashes = [ordered]@{
    'AndroidManifest.xml' = 'e2b8620b22bf37d9860165bba5df9b1b4fff41cbdfdd240d7038628a077941ba'
    'R.txt' = 'e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855'
    'proguard.txt' = '59a5016dc2777c4a21b3c88d1d2ba7bdf36999eff08cecb301e00e2ead478510'
    'assets/logback.xml' = '7ac50f5a58e5fb5dfa8648cb459e4fb8b45df3d6c50aeec4dc1afa64321d77c4'
    'META-INF/com/android/build/gradle/aar-metadata.properties' = '9cc8517bbdf06d879f57a2cfd6f8c6914e48800d443421cd850971945f98e7b2'
    'jni/arm64-v8a/libtrusttunnel_android.so' = '26adfbb9780c11a51e2e10e561310be07977a1fa68d44e202598f01378dd4a73'
    'jni/armeabi-v7a/libtrusttunnel_android.so' = '16416cb7e567dbe90e9d52a2211e5d8ae27118197d83a209d5b9d7df7169813c'
}

function Get-Sha256([string]$Path) {
    (Get-FileHash -LiteralPath $Path -Algorithm SHA256).Hash.ToLowerInvariant()
}

function Assert-Hash([string]$Path, [string]$Expected) {
    $actual = Get-Sha256 $Path
    if ($actual -ne $Expected) {
        throw "SHA-256 mismatch for $Path. Expected $Expected, got $actual"
    }
}

function Assert-TrustAdapterBytecode([string]$ClassesJar) {
    $bytecodeLines = & javap -classpath $ClassesJar -c -p com.adguard.trusttunnel.VpnService
    if ($LASTEXITCODE -ne 0) {
        throw 'javap failed while verifying the patched VpnService.'
    }
    $bytecode = $bytecodeLines -join "`n"
    if ($bytecode -notmatch '(?s)bipush\s+34.{0,400}int 1073741824.{0,200}startForeground') {
        throw 'Android 34+ SPECIAL_USE foreground-service bytecode is missing.'
    }
    if ($bytecode -match 'sipush\s+1024') {
        throw 'SYSTEM_EXEMPTED foreground-service bytecode was reintroduced.'
    }

    $closeStart = $bytecode.IndexOf('private final boolean close(java.lang.Integer);')
    $closeEnd = $bytecode.IndexOf('static boolean close$default', $closeStart + 1)
    if ($closeStart -lt 0 -or $closeEnd -le $closeStart) {
        throw 'Could not locate VpnService.close bytecode.'
    }
    $closeBytecode = $bytecode.Substring($closeStart, $closeEnd - $closeStart)
    if ($closeBytecode -notmatch 'VPN service is not running, stopping the foreground service') {
        throw 'Inactive-service foreground cleanup log marker is missing.'
    }
    if (([regex]::Matches($closeBytecode, 'stopSelf:\(I\)V')).Count -lt 2 -or
        ([regex]::Matches($closeBytecode, 'stopSelf:\(\)V')).Count -lt 2) {
        throw 'Inactive-service close path no longer calls both stopSelf variants.'
    }
}

function Assert-HostManifest([string]$ManifestPath) {
    [xml]$manifest = Get-Content -Raw -LiteralPath $ManifestPath
    $androidNamespace = 'http://schemas.android.com/apk/res/android'
    $namespaces = [System.Xml.XmlNamespaceManager]::new($manifest.NameTable)
    $namespaces.AddNamespace('android', $androidNamespace)
    $service = $manifest.SelectSingleNode(
        "/manifest/application/service[@android:name='com.adguard.trusttunnel.VpnService']",
        $namespaces
    )
    if ($null -eq $service -or
        $service.GetAttribute('foregroundServiceType', $androidNamespace) -ne 'specialUse') {
        throw 'Host manifest must declare TrustTunnel VpnService foregroundServiceType="specialUse".'
    }
    $permission = $manifest.SelectSingleNode(
        "/manifest/uses-permission[@android:name='android.permission.FOREGROUND_SERVICE_SPECIAL_USE']",
        $namespaces
    )
    if ($null -eq $permission) {
        throw 'Host manifest is missing FOREGROUND_SERVICE_SPECIAL_USE.'
    }
}

function Invoke-Checked([string]$Executable, [string[]]$Arguments, [string]$WorkingDirectory) {
    Push-Location $WorkingDirectory
    try {
        & $Executable @Arguments
        if ($LASTEXITCODE -ne 0) {
            throw "$Executable failed with exit code $LASTEXITCODE"
        }
    } finally {
        Pop-Location
    }
}

$BaselineAar = (Resolve-Path -LiteralPath $BaselineAar).Path
Assert-Hash $BaselineAar $BaselineAarHash

if ([string]::IsNullOrWhiteSpace($HostManifest)) {
    $HostManifest = Join-Path $PSScriptRoot '..\..\..\app\src\main\AndroidManifest.xml'
}
$HostManifest = (Resolve-Path -LiteralPath $HostManifest).Path
Assert-HostManifest $HostManifest

if ([string]::IsNullOrWhiteSpace($AndroidSdk)) {
    throw 'AndroidSdk is required (pass -AndroidSdk or set ANDROID_HOME).'
}
$AndroidSdk = (Resolve-Path -LiteralPath $AndroidSdk).Path

$work = [System.IO.Path]::GetFullPath($WorkDirectory)
if (Test-Path -LiteralPath $work) {
    if ((Get-ChildItem -Force -LiteralPath $work | Measure-Object).Count -ne 0) {
        throw "WorkDirectory must be empty: $work"
    }
} else {
    New-Item -ItemType Directory -Path $work | Out-Null
}

$source = Join-Path $work 'source'
$package = Join-Path $work 'aar'
$compiledJar = Join-Path $work 'classes.jar'
$resolvedOutput = [System.IO.Path]::GetFullPath($OutputAar)
New-Item -ItemType Directory -Path $package | Out-Null
New-Item -ItemType Directory -Force -Path (Split-Path -Parent $resolvedOutput) | Out-Null

Invoke-Checked 'git' @('clone', '--filter=blob:none', '--no-checkout', $UpstreamRepository, $source) $work
Invoke-Checked 'git' @('checkout', '--detach', $UpstreamCommit) $source
$actualCommit = (& git -C $source rev-parse HEAD).Trim()
if ($LASTEXITCODE -ne 0 -or $actualCommit -ne $UpstreamCommit) {
    throw "Unexpected upstream commit: $actualCommit"
}
Invoke-Checked 'git' @('apply', '--check', $PatchPath) $source
Invoke-Checked 'git' @('apply', $PatchPath) $source

$escapedSdk = $AndroidSdk.Replace('\', '\\').Replace(':', '\:')
$localProperties = "sdk.dir=$escapedSdk`n"
[System.IO.File]::WriteAllText(
    (Join-Path $source 'platform\android\local.properties'),
    $localProperties,
    [System.Text.UTF8Encoding]::new($false)
)

$androidProject = Join-Path $source 'platform\android'
Invoke-Checked (Join-Path $androidProject 'gradlew.bat') `
    @(':lib:compileReleaseKotlin', ':lib:testDebugUnitTest', '--no-daemon', '--stacktrace') `
    $androidProject

$compiledClasses = Join-Path $androidProject 'lib\build\tmp\kotlin-classes\release'
if (-not (Test-Path -LiteralPath $compiledClasses)) {
    throw "Compiled Kotlin classes not found: $compiledClasses"
}
Invoke-Checked 'jar' `
    @('--create', '--no-manifest', '--file', $compiledJar, '--date', $FixedTimestamp, '-C', $compiledClasses, '.') `
    $work
Assert-Hash $compiledJar $PatchedClassesHash
Assert-TrustAdapterBytecode $compiledJar

Invoke-Checked 'jar' @('--extract', '--file', $BaselineAar) $package
Assert-Hash (Join-Path $package 'classes.jar') $BaselineClassesHash
foreach ($entry in $ExpectedPayloadHashes.GetEnumerator()) {
    Assert-Hash (Join-Path $package $entry.Key) $entry.Value
}

Copy-Item -LiteralPath $compiledJar -Destination (Join-Path $package 'classes.jar') -Force
Invoke-Checked 'jar' `
    @('--create', '--no-manifest', '--file', $resolvedOutput, '--date', $FixedTimestamp, '-C', $package, '.') `
    $work

Assert-Hash $resolvedOutput $PatchedAarHash
Write-Host "Built and verified TrustTunnel adapter: $resolvedOutput"
Write-Host "SHA-256: $PatchedAarHash"
