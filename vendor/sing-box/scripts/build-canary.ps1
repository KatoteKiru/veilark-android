[CmdletBinding()]
param(
  [Parameter(Mandatory = $true)]
  [string] $GoRoot,

  [string] $JavaHome = $env:JAVA_HOME,
  [string] $AndroidSdk = "$env:LOCALAPPDATA\Android\Sdk",
  [string] $WorkRoot = (Join-Path $env:TEMP "veilark-sing-box-1.13.21"),
  [string] $OutputDirectory = (Join-Path $PSScriptRoot "..\artifacts")
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

$tag = "v1.13.21"
$commit = "628cb31ffa79cffffd34c2f9cde6cae044e4fc12"
$goVersion = "go1.25.12"
$gomobileVersion = "v0.1.12"
$ndkVersion = "28.0.13004108"
$repository = "https://github.com/SagerNet/sing-box.git"
$artifactName = "libbox-1.13.21-android-arm-arm64.aar"

$go = Join-Path $GoRoot "bin\go.exe"
$java = Join-Path $JavaHome "bin\java.exe"
$ndk = Join-Path $AndroidSdk "ndk\$ndkVersion"
$source = Join-Path $WorkRoot "source"
$gopath = Join-Path $WorkRoot "gopath"
$gocache = Join-Path $WorkRoot "gocache"

if (-not (Test-Path -LiteralPath $go -PathType Leaf)) {
  throw "Go executable not found: $go"
}
if (-not (Test-Path -LiteralPath $java -PathType Leaf)) {
  throw "Java executable not found: $java"
}
if (-not (Test-Path -LiteralPath (Join-Path $ndk "source.properties") -PathType Leaf)) {
  throw "Android NDK $ndkVersion not found below $AndroidSdk"
}
if (Test-Path -LiteralPath $source) {
  throw "Refusing to reuse an existing source directory: $source"
}

$actualGoVersion = (& $go version).Split(' ')[2]
if ($actualGoVersion -ne $goVersion) {
  throw "Expected $goVersion, got $actualGoVersion"
}
$javaVersion = (& $java --version 2>&1 | Select-Object -First 1)
if ($javaVersion -notmatch 'openjdk 17') {
  throw "OpenJDK 17 is required, got: $javaVersion"
}

New-Item -ItemType Directory -Path $WorkRoot, $gopath, $gocache, $OutputDirectory -Force | Out-Null
& git clone --depth 1 --branch $tag $repository $source
if ($LASTEXITCODE -ne 0) { throw "git clone failed: $LASTEXITCODE" }
$actualCommit = (& git -C $source rev-parse HEAD).Trim()
if ($actualCommit -ne $commit) {
  throw "Expected source commit $commit, got $actualCommit"
}

$env:GOROOT = $GoRoot
$env:GOPATH = $gopath
$env:GOBIN = Join-Path $gopath "bin"
$env:GOMODCACHE = Join-Path $gopath "pkg\mod"
$env:GOCACHE = $gocache
$env:GOTOOLCHAIN = "local"
$env:GOFLAGS = "-mod=readonly"
$env:JAVA_HOME = $JavaHome
$env:ANDROID_HOME = $AndroidSdk
$env:ANDROID_NDK_HOME = $ndk
$env:PATH = "$GoRoot\bin;$env:GOBIN;$JavaHome\bin;$env:PATH"

& $go install "github.com/sagernet/gomobile/cmd/gomobile@$gomobileVersion"
if ($LASTEXITCODE -ne 0) { throw "gomobile install failed: $LASTEXITCODE" }
& $go install "github.com/sagernet/gomobile/cmd/gobind@$gomobileVersion"
if ($LASTEXITCODE -ne 0) { throw "gobind install failed: $LASTEXITCODE" }

Push-Location $source
try {
  & $go run ./cmd/internal/build_libbox -target android -platform "android/arm,android/arm64"
  if ($LASTEXITCODE -ne 0) { throw "libbox build failed: $LASTEXITCODE" }
  $output = Join-Path $OutputDirectory $artifactName
  Copy-Item (Join-Path $source "libbox.aar") $output -Force
  $entries = @(tar.exe -tf $output | Where-Object { $_ -like "jni/*/libbox.so" } | Sort-Object)
  $expected = @("jni/arm64-v8a/libbox.so", "jni/armeabi-v7a/libbox.so")
  if (@(Compare-Object $expected $entries).Count -ne 0) {
    throw "Unexpected Android ABI set: $($entries -join ', ')"
  }
  Get-Item $output | Select-Object FullName, Length
  Get-FileHash $output -Algorithm SHA256
} finally {
  Pop-Location
}
