$ErrorActionPreference = "Stop"
$env:JAVA_HOME = "C:\Program Files\Eclipse Adoptium\jdk-17.0.19.10-hotspot"
$env:ANDROID_HOME = "$env:LOCALAPPDATA\Android\Sdk"
$env:ANDROID_NDK_HOME = "$env:LOCALAPPDATA\Android\Sdk\ndk\28.0.13004108"
$env:PATH = "$env:JAVA_HOME\bin;C:\Program Files\Go\bin;$HOME\go\bin;$env:PATH"

$source = "C:\AI-Agent\scratch\veilark-upstreams\sing-box"
$artifact = "C:\AI-Agent\artifacts\libbox-1.13.14-arm-arm64.aar"
New-Item -ItemType Directory -Path (Split-Path $artifact) -Force | Out-Null
Push-Location $source
try {
  & "C:\Program Files\Go\bin\go.exe" run ./cmd/internal/build_libbox -platform "android/arm,android/arm64"
  if ($LASTEXITCODE -ne 0) { throw "libbox build failed: $LASTEXITCODE" }
  Copy-Item "$source\libbox.aar" $artifact -Force
  Get-FileHash $artifact -Algorithm SHA256
} finally {
  Pop-Location
}
