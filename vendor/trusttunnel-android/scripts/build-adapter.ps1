[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [string]$OutputAar,

    [Parameter(Mandatory = $true)]
    [string]$WorkDirectory,

    [string]$AndroidSdk = "$env:LOCALAPPDATA\Android\Sdk",
    [string]$JavaHome = $env:JAVA_HOME,
    [string]$PythonLauncher = 'py',
    [string]$ConanExecutable = "$env:APPDATA\Python\Python312\Scripts\conan.exe",
    [string]$GitBash = 'C:\Program Files\Git\bin\bash.exe',
    [string]$RustToolchain = '1.95-x86_64-pc-windows-gnu',
    [string]$HostManifest
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

$UpstreamRepository = 'https://github.com/TrustTunnel/TrustTunnelClient.git'
$UpstreamCommit = '170609c24ca865819fed68437b01c013049bc3fa'
$DnsLibsRepository = 'https://github.com/AdguardTeam/DnsLibs.git'
$DnsLibsCommit = '0c6e855b12eee2f696e7cc30719532fda4fdd512'
$NativeLibsRepository = 'https://github.com/AdguardTeam/NativeLibsCommon.git'
$NativeLibsCommit = '58cef252031e2cc1f540ecaec2952f5f32afa3a1'
$CMakeVersion = '3.31.6'
$ExpectedAarHash = 'FCB2B2E8980E500E461CA973CA22630415380D84163BB2DDCAC113D603BAC52F'
$ExpectedClassesHash = 'B0871E397287DE7CEF1904645492950D9807361ABBA1E36418BCFCF5B2A7F034'
$ExpectedContentTreeHash = 'D4C18D68B86785BE099AFD39205CD73B46EB552E171398B3CFC1EFCEC821FE31'
$PatchPaths = @(
    (Resolve-Path (Join-Path $PSScriptRoot '..\patches\0001-android-per-app-routing.patch')).Path
    (Resolve-Path (Join-Path $PSScriptRoot '..\patches\0002-android-lifecycle-hardening.patch')).Path
    (Resolve-Path (Join-Path $PSScriptRoot '..\patches\0003-post-close-terminal-fence.patch')).Path
    (Resolve-Path (Join-Path $PSScriptRoot '..\patches\0004-http2-flow-control.patch')).Path
    (Resolve-Path (Join-Path $PSScriptRoot '..\patches\0005-android-metering-inheritance.patch')).Path
)

$ExpectedPayloadHashes = [ordered]@{
    'AndroidManifest.xml' = 'E2B8620B22BF37D9860165BBA5DF9B1B4FFF41CBDFDD240D7038628A077941BA'
    'R.txt' = 'E3B0C44298FC1C149AFBF4C8996FB92427AE41E4649B934CA495991B7852B855'
    'proguard.txt' = '6F171F5DC85E4A7DDBF78238C12B17F8B8CB4EAC5B3243B2E1BD6EC726ECA7E1'
    'assets/logback.xml' = '855E8C942F1D4198F0BECDD9E2FC9ADD6744710EB04607D188D61F3B512A5083'
    'META-INF/com/android/build/gradle/aar-metadata.properties' = '9CC8517BBDF06D879F57A2CFD6F8C6914E48800D443421CD850971945F98E7B2'
    'jni/arm64-v8a/libtrusttunnel_android.so' = 'BF9DB6CC9300B921C5B7BB5007D6E5AD27B0B44BC5E2075F4C4BDA214B42C94A'
    'jni/armeabi-v7a/libtrusttunnel_android.so' = 'EE5E193CB8F1C12315A77999F589E6347DA82CB6E9346DACE6060D9FA04959E7'
}

function Get-Sha256([string]$Path) {
    (Get-FileHash -LiteralPath $Path -Algorithm SHA256).Hash.ToUpperInvariant()
}

function Get-ContentTreeHash([string]$Root) {
    $lines = Get-ChildItem -LiteralPath $Root -Recurse -File |
        Sort-Object FullName |
        ForEach-Object {
            $relative = $_.FullName.Substring($Root.Length + 1).Replace('\', '/')
            "$relative`t$(Get-Sha256 $_.FullName)"
        }
    $payload = ($lines -join "`n") + "`n"
    $bytes = [System.Text.UTF8Encoding]::new($false).GetBytes($payload)
    [Convert]::ToHexString([System.Security.Cryptography.SHA256]::HashData($bytes))
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
    & (Join-Path $PSScriptRoot '..\tests\check-metering-bytecode.ps1') -ClassesJar $ClassesJar
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
    $client = (& javap -classpath $ClassesJar -c -p com.adguard.trusttunnel.VpnClient) -join "`n"
    if ($LASTEXITCODE -ne 0 -or $client -notmatch 'updateExclusionsNative') {
        throw 'Runtime exclusions are missing.'
    }
    $companion = (& javap -classpath $ClassesJar -c -p 'com.adguard.trusttunnel.VpnService$Companion') -join "`n"
    if ($LASTEXITCODE -ne 0 -or
        $companion -notmatch 'public final boolean start\(android\.content\.Context, java\.lang\.String, long\)' -or
        $companion -notmatch 'public final boolean stop\(android\.content\.Context, long\)' -or
        $companion -notmatch 'public final void stopNetworkManager\(' -or
        $companion -notmatch 'public final void initialize\(android\.content\.Context\)' -or
        $companion -notmatch 'public final java\.util\.List<java\.lang\.String> exportLogs\(android\.content\.Context\)' -or
        $companion -notmatch 'public final void clearLogs\(\)') {
        throw 'Session-fenced service start/stop, cleanup, or TrustTunnel logging API is missing.'
    }
    if ($service -notmatch 'notifyDisconnectedOnce' -or
        $service -notmatch 'AtomicLong.compareAndSet' -or
        $service -notmatch 'shutdownNow') {
        throw 'Exactly-once terminal notification or executor shutdown is missing.'
    }
    $notifier = (& javap -classpath $ClassesJar -p com.adguard.trusttunnel.AppNotifier) -join "`n"
    if ($LASTEXITCODE -ne 0 -or
        $notifier -notmatch 'onStateChanged\(int, long\)') {
        throw 'Session ID is missing from TrustTunnel state callbacks.'
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
    foreach ($requiredPermission in @(
        'android.permission.ACCESS_NETWORK_STATE',
        'android.permission.FOREGROUND_SERVICE'
    )) {
        $node = $manifest.SelectSingleNode(
            "/manifest/uses-permission[@android:name='$requiredPermission']",
            $namespaces
        )
        if ($null -eq $node) {
            throw "Host manifest is missing $requiredPermission."
        }
    }
}

$AndroidSdk = (Resolve-Path -LiteralPath $AndroidSdk).Path
$JavaHome = (Resolve-Path -LiteralPath $JavaHome).Path
$ConanExecutable = (Resolve-Path -LiteralPath $ConanExecutable).Path
$GitBash = (Resolve-Path -LiteralPath $GitBash).Path
$cmakeBin = Join-Path $AndroidSdk "cmake\$CMakeVersion\bin"
if (-not (Test-Path -LiteralPath (Join-Path $cmakeBin 'cmake.exe') -PathType Leaf)) {
    throw "Android SDK CMake $CMakeVersion is required."
}
if (-not (Test-Path -LiteralPath (Join-Path $AndroidSdk 'ndk\29.0.14206865\source.properties') -PathType Leaf)) {
    throw 'Android NDK 29.0.14206865 is required.'
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
$conanBin = Split-Path -Parent $ConanExecutable
$env:PATH = "$cmakeBin;$pythonScripts;$conanBin;$JavaHome\bin;$env:USERPROFILE\.cargo\bin;$env:PATH"
$env:ANDROID_HOME = $AndroidSdk
$env:ANDROID_SDK_ROOT = $AndroidSdk
$env:JAVA_HOME = $JavaHome
$env:RUSTUP_TOOLCHAIN = $RustToolchain

Invoke-Checked $ConanExecutable @('--version') $work
Invoke-Checked 'rustup' @('run', $RustToolchain, 'rustc', '--version') $work
Invoke-Checked 'cargo' @('ndk', '--version') $work

Invoke-Checked 'git' @('clone', '--filter=blob:none', '--no-checkout', $UpstreamRepository, $source) $work
Invoke-Checked 'git' @('config', 'core.autocrlf', 'false') $source
Invoke-Checked 'git' @('checkout', '--detach', $UpstreamCommit) $source
$actualCommit = (& git -C $source rev-parse HEAD).Trim()
if ($actualCommit -ne $UpstreamCommit) {
    throw "Unexpected upstream commit: $actualCommit"
}

Invoke-Checked 'git' @('clone', $DnsLibsRepository, $dnsLibs) $work
Invoke-Checked 'git' @('checkout', '--detach', $DnsLibsCommit) $dnsLibs

Invoke-Checked 'git' @('clone', $NativeLibsRepository, $nativeLibs) $work
Invoke-Checked 'git' @('checkout', '--detach', $NativeLibsCommit) $nativeLibs
Invoke-Checked $GitBash @('scripts/export_conan.sh') $nativeLibs
Invoke-Checked $GitBash @('scripts/export_conan.sh') $dnsLibs

foreach ($patchPath in $PatchPaths) {
    # Git for Windows checks out LF blobs as CRLF even with core.autocrlf=false;
    # ignore whitespace-only line-ending differences without permitting offsets/fuzz.
    Invoke-Checked 'git' @('apply', '--check', '--ignore-space-change', $patchPath) $source
    Invoke-Checked 'git' @('apply', '--ignore-space-change', $patchPath) $source
}

# Upstream uses __FILE__ in loadable strings, so an absolute checkout path
# changes .rodata, code layout and the ELF build ID. Remap the entire isolated
# source checkout at compile time for reproducible binaries without post-link edits.
$sourceForward = $source.Replace('\', '/')
$stableWorkRoot = '/work/veilark-trusttunnel'
$clangRemap = @(
    "-ffile-prefix-map=$sourceForward=$stableWorkRoot"
    "-fmacro-prefix-map=$sourceForward=$stableWorkRoot"
) -join ' '
$rustRemap = "--remap-path-prefix=$sourceForward=$stableWorkRoot"
$existingCFlags = [Environment]::GetEnvironmentVariable('CFLAGS', 'Process')
$existingCxxFlags = [Environment]::GetEnvironmentVariable('CXXFLAGS', 'Process')
$existingRustFlags = [Environment]::GetEnvironmentVariable('RUSTFLAGS', 'Process')
$env:CFLAGS = (@($existingCFlags, $clangRemap) | Where-Object { -not [string]::IsNullOrWhiteSpace($_) }) -join ' '
$env:CXXFLAGS = (@($existingCxxFlags, $clangRemap) | Where-Object { -not [string]::IsNullOrWhiteSpace($_) }) -join ' '
$env:VEILARK_REPRO_CFLAGS = $clangRemap
$env:VEILARK_REPRO_LINKER_FLAGS = '-Wl,--build-id=none'
$env:RUSTFLAGS = (@($existingRustFlags, $rustRemap) | Where-Object { -not [string]::IsNullOrWhiteSpace($_) }) -join ' '
$env:TT_CLIENT_VERSION = '1.1.7'
Write-Host "Clang reproducibility flags: $clangRemap"
Write-Host "Rust reproducibility flags: $rustRemap"
Write-Host "Linker reproducibility flags: $env:VEILARK_REPRO_LINKER_FLAGS"

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

Invoke-Checked 'jar' @('--extract', '--file', $resolvedOutput) $inspect
Assert-Hash (Join-Path $inspect 'classes.jar') $ExpectedClassesHash
foreach ($entry in $ExpectedPayloadHashes.GetEnumerator()) {
    Assert-Hash (Join-Path $inspect $entry.Key) $entry.Value
}
$contentTreeHash = Get-ContentTreeHash $inspect
if ($contentTreeHash -ne $ExpectedContentTreeHash) {
    throw "AAR content-tree SHA-256 mismatch. Expected $ExpectedContentTreeHash, got $contentTreeHash"
}
Assert-TrustAdapterBytecode (Join-Path $inspect 'classes.jar')
Assert-Hash $resolvedOutput $ExpectedAarHash

Write-Host "Built and verified TrustTunnel 1.1.7 Android AAR: $resolvedOutput"
Write-Host "SHA-256: $ExpectedAarHash"
