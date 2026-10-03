[CmdletBinding()]
param(
  [Parameter(Mandatory)][string]$BaseAar,
  [Parameter(Mandatory)][string]$ClassesJar,
  [Parameter(Mandatory)][string]$OutputAar
)
$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
Add-Type -AssemblyName System.IO.Compression.FileSystem

# Only a Java/Kotlin adapter change is allowed. Native libraries and every other
# payload entry must remain identical to the verified full-source 1.1.5 build.
$baseline = '069819F12B9F5D94D79569212D002DF27E6FF77E0B9FAC3BC5640BD01979B7CC'
if ((Get-FileHash -LiteralPath $BaseAar).Hash -ne $baseline) { throw 'Unexpected baseline AAR' }
$output = [IO.Path]::GetFullPath($OutputAar)
if (Test-Path -LiteralPath $output) { throw 'Output must not already exist' }
if (!(Test-Path -LiteralPath $ClassesJar -PathType Leaf)) { throw 'Classes JAR missing' }
$callback = (& javap -classpath $ClassesJar -c -p 'com.adguard.trusttunnel.VpnService$processStarting$proxyClientListener$1') -join "`n"
if ($LASTEXITCODE -ne 0 -or $callback -match 'notifyDisconnectedOnce' -or $callback -notmatch 'closeIfLast') {
  throw 'Adapter terminal callback must schedule close, never publish early completion'
}
$service = (& javap -classpath $ClassesJar -c -p 'com.adguard.trusttunnel.VpnService') -join "`n"
$startMethod = [regex]::Match($service, '(?s)public int onStartCommand\(.*?(?=\n  (?:public|private|protected))').Value
if ($LASTEXITCODE -ne 0 -or !$startMethod -or $startMethod -match 'notifyDisconnectedOnce') {
  throw 'Null-intent start must leave terminal notification to post-close destruction'
}

function EntryHash($entry) {
  $stream = $entry.Open()
  $sha = [Security.Cryptography.SHA256]::Create()
  try { [Convert]::ToHexString($sha.ComputeHash($stream)) }
  finally { $sha.Dispose(); $stream.Dispose() }
}

$source = [IO.Compression.ZipFile]::OpenRead([IO.Path]::GetFullPath($BaseAar))
try {
  $destination = [IO.Compression.ZipFile]::Open($output, [IO.Compression.ZipArchiveMode]::Create)
  try {
    foreach ($entry in $source.Entries) {
      $copy = $destination.CreateEntry($entry.FullName, [IO.Compression.CompressionLevel]::Optimal)
      $copy.LastWriteTime = $entry.LastWriteTime
      $input = if ($entry.FullName -eq 'classes.jar') { [IO.File]::OpenRead($ClassesJar) } else { $entry.Open() }
      $sink = $copy.Open()
      try { $input.CopyTo($sink) } finally { $input.Dispose(); $sink.Dispose() }
    }
  } finally { $destination.Dispose() }
  $result = [IO.Compression.ZipFile]::OpenRead($output)
  try {
    if ($result.Entries.Count -ne $source.Entries.Count) { throw 'AAR entry set changed' }
    foreach ($entry in $source.Entries) {
      if ($entry.FullName -eq 'classes.jar') { continue }
      if ((EntryHash $entry) -ne (EntryHash $result.GetEntry($entry.FullName))) {
        throw "Unexpected payload change: $($entry.FullName)"
      }
    }
    if ((EntryHash $result.GetEntry('classes.jar')) -ne (Get-FileHash -LiteralPath $ClassesJar).Hash) {
      throw 'Classes payload mismatch'
    }
  } finally { $result.Dispose() }
} finally { $source.Dispose() }
Write-Output 'All non-class payload entries, including both native ABIs, are byte-identical.'
Get-FileHash -LiteralPath $output
