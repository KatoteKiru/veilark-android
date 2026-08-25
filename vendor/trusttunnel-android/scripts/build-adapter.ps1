[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [string]$OutputAar,

    [Parameter(Mandatory = $true)]
    [string]$WorkDirectory,

    [string]$AndroidSdk = "$env:LOCALAPPDATA\Android\Sdk",
    [string]$JavaHome = $env:JAVA_HOME,
    [string]$PythonLauncher = 'py',
    [string]$RustToolchain = '1.95-x86_64-pc-windows-gnu',
    [string]$HostManifest
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

$UpstreamRepository = 'https://github.com/TrustTunnel/TrustTunnelClient.git'
$UpstreamCommit = '7da863b1b947d22a3131d94dcc7c80b0240b6e97'
$DnsLibsRepository = 'https://github.com/AdguardTeam/DnsLibs.git'
$DnsLibsBootstrapTag = 'v2.8.52'
$DnsLibsPackageVersion = '2.8.51'
$NativeLibsRepository = 'https://github.com/AdguardTeam/NativeLibsCommon.git'
$NativeLibsTag = 'v8.1.28'
$CMakeVersion = '3.31.6'
$ExpectedAarHash = '3BC3B2D39915305B8F18AD3D33E805054EB3C21FFBD2B0D554CCD48A08DB40D8'
$ExpectedClassesHash = 'B0904AE6B4513D6AEA17012827108754E5A35C8A5BA05D2B66A16066D02F5F7C'
$PatchPath = (Resolve-Path (Join-Path $PSScriptRoot '..\patches\0001-android-per-app-routing.patch')).Path

$ExpectedPayloadHashes = [ordered]@{
    'AndroidManifest.xml' = 'E2B8620B22BF37D9860165BBA5DF9B1B4FFF41CBDFDD240D7038628A077941BA'
    'R.txt' = 'E3B0C44298FC1C149AFBF4C8996FB92427AE41E4649B934CA495991B7852B855'
    'proguard.txt' = '59A5016DC2777C4A21B3C88D1D2BA7BDF36999EFF08CECB301E00E2EAD478510'
    'assets/logback.xml' = '7AC50F5A58E5FB5DFA8648CB459E4FB8B45DF3D6C50AEEC4DC1AFA64321D77C4'
    'META-INF/com/android/build/gradle/aar-metadata.properties' = '9CC8517BBDF06D879F57A2CFD6F8C6914E48800D443421CD850971945F98E7B2'
    'jni/arm64-v8a/libtrusttunnel_android.so' = 'D698806D46779B7A9D508ACBF9BA0C015ACC910704BD1A04B101A0FA04B8B60B'
    'jni/armeabi-v7a/libtrusttunnel_android.so' = 'E5F28B07FC348728FCFB018BE42D6BDFFEA9AF29FD37D886A8A53104E08FAB9E'
}

function Get-Sha256([string]$Path) {
    (Get-FileHash -LiteralPath $Path -Algorithm SHA256).Hash.ToUpperInvariant()
}

function Assert-Hash([string]$Path, [string]$Expected) {
    $actual = Get-Sha256 $Path
    if ($actual -ne $Expected) {
        throw "SHA-256 mismatch for $Path. Expected $Expected, got $actual"
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

function Assert-TrustAdapterBytecode([string]$ClassesJar) {
    $service = (& javap -classpath $ClassesJar -c -p com.adguard.trusttunnel.VpnService) -join "`n"
    if ($LASTEXITCODE -ne 0) {
        throw 'javap failed while verifying VpnService.'
    }
    if ($service -notmatch 'int 1073741824' -or $service -match 'sipush\s+1024') {
        throw 'Android 14+ SPECIAL_USE foreground-service bytecode is missing or invalid.'
    }
    if ($service -notmatch 'applyApplicationRouting') {
        throw 'Application routing hook is missing from VpnService.'
    }
    if ($service -notmatch 'stopping the foreground service') {
        throw 'Early-failure foreground-service cleanup is missing.'
    }

    $sink = (& javap -classpath $ClassesJar -c -p 'com.adguard.trusttunnel.VpnService$BuilderApplicationRuleSink') -join "`n"
    if ($LASTEXITCODE -ne 0 -or
        $sink -notmatch 'addAllowedApplication' -or
        $sink -notmatch 'addDisallowedApplication' -or
        $sink -notmatch 'NameNotFoundException') {
        throw 'Per-application routing bytecode is incomplete.'
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

$AndroidSdk = (Resolve-Path -LiteralPath $AndroidSdk).Path
$JavaHome = (Resolve-Path -LiteralPath $JavaHome).Path
$cmakeBin = Join-Path $AndroidSdk "cmake\$CMakeVersion\bin"
if (-not (Test-Path -LiteralPath (Join-Path $cmakeBin 'cmake.exe') -PathType Leaf)) {
    throw "Android SDK CMake $CMakeVersion is required."
}
if (-not (Test-Path -LiteralPath (Join-Path $AndroidSdk 'ndk\28.1.13356709\source.properties') -PathType Leaf)) {
    throw 'Android NDK 28.1.13356709 is required.'
}

if ([string]::IsNullOrWhiteSpace($HostManifest)) {
    $HostManifest = Join-Path $PSScriptRoot '..\..\..\app\src\main\AndroidManifest.xml'
}
$HostManifest = (Resolve-Path -LiteralPath $HostManifest).Path
Assert-HostManifest $HostManifest

$work = [System.IO.Path]::GetFullPath($WorkDirectory)
if (Test-Path -LiteralPath $work) {
    if ((Get-ChildItem -Force -LiteralPath $work | Measure-Object).Count -ne 0) {
        throw "WorkDirectory must be empty: $work"
    }
} else {
    New-Item -ItemType Directory -Path $work | Out-Null
}

$source = Join-Path $work 'source'
$dnsLibs = Join-Path $work 'dns-libs'
$nativeLibs = Join-Path $work 'native-libs-common'
$inspect = Join-Path $work 'aar-inspect'
$resolvedOutput = [System.IO.Path]::GetFullPath($OutputAar)
New-Item -ItemType Directory -Force -Path (Split-Path -Parent $resolvedOutput), $inspect | Out-Null

$pythonScripts = (& $PythonLauncher -c 'import sysconfig; print(sysconfig.get_path("scripts"))').Trim()
if ($LASTEXITCODE -ne 0 -or [string]::IsNullOrWhiteSpace($pythonScripts)) {
    throw 'Unable to locate the Python scripts directory.'
}
$env:PATH = "$cmakeBin;$pythonScripts;$JavaHome\bin;$env:USERPROFILE\.cargo\bin;$env:PATH"
$env:ANDROID_HOME = $AndroidSdk
$env:ANDROID_SDK_ROOT = $AndroidSdk
$env:JAVA_HOME = $JavaHome
$env:RUSTUP_TOOLCHAIN = $RustToolchain

Invoke-Checked $PythonLauncher @('-m', 'conan', '--version') $work
Invoke-Checked 'rustup' @('run', $RustToolchain, 'rustc', '--version') $work
Invoke-Checked 'cargo' @('ndk', '--version') $work

Invoke-Checked 'git' @('clone', '--filter=blob:none', '--no-checkout', $UpstreamRepository, $source) $work
Invoke-Checked 'git' @('checkout', '--detach', $UpstreamCommit) $source
$actualCommit = (& git -C $source rev-parse HEAD).Trim()
if ($actualCommit -ne $UpstreamCommit) {
    throw "Unexpected upstream commit: $actualCommit"
}

Invoke-Checked 'git' @('clone', $DnsLibsRepository, $dnsLibs) $work
Invoke-Checked 'git' @('checkout', $DnsLibsBootstrapTag) $dnsLibs
Invoke-Checked $PythonLauncher @('scripts/export_conan.py', $DnsLibsPackageVersion) $dnsLibs

Invoke-Checked 'git' @('clone', $NativeLibsRepository, $nativeLibs) $work
Invoke-Checked 'git' @('checkout', $NativeLibsTag) $nativeLibs
Invoke-Checked $PythonLauncher @('scripts/export_conan.py', '8.1.28') $nativeLibs

Invoke-Checked 'git' @('apply', '--check', $PatchPath) $source
Invoke-Checked 'git' @('apply', $PatchPath) $source

$escapedSdk = $AndroidSdk.Replace('\', '\\').Replace(':', '\:')
[System.IO.File]::WriteAllText(
    (Join-Path $source 'platform\android\local.properties'),
    "sdk.dir=$escapedSdk`n",
    [System.Text.UTF8Encoding]::new($false)
)

$androidProject = Join-Path $source 'platform\android'
Invoke-Checked (Join-Path $androidProject 'gradlew.bat') `
    @(':lib:assembleRelease', ':lib:testDebugUnitTest', '--no-daemon', '--stacktrace') `
    $androidProject

$builtAar = Join-Path $androidProject 'lib\build\outputs\aar\lib-release.aar'
Copy-Item -LiteralPath $builtAar -Destination $resolvedOutput -Force
Assert-Hash $resolvedOutput $ExpectedAarHash

Invoke-Checked 'jar' @('--extract', '--file', $resolvedOutput) $inspect
Assert-Hash (Join-Path $inspect 'classes.jar') $ExpectedClassesHash
foreach ($entry in $ExpectedPayloadHashes.GetEnumerator()) {
    Assert-Hash (Join-Path $inspect $entry.Key) $entry.Value
}
Assert-TrustAdapterBytecode (Join-Path $inspect 'classes.jar')

Write-Host "Built and verified TrustTunnel 1.1.4 Android AAR: $resolvedOutput"
Write-Host "SHA-256: $ExpectedAarHash"
