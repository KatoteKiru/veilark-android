param([Parameter(Mandatory = $true)][string]$ClassesJar)
$ErrorActionPreference = 'Stop'
$service = (& javap -classpath $ClassesJar -c -p com.adguard.trusttunnel.VpnService) -join "`n"
if ($LASTEXITCODE -ne 0) { throw 'javap failed while checking metering policy.' }
$method = [regex]::Match($service, '(?s)private final android\.os\.ParcelFileDescriptor createTunInterface\(.*?(?=\n  private final void applyApplicationRouting\()').Value
if ([string]::IsNullOrWhiteSpace($method)) { throw 'TUN builder bytecode is missing.' }
$guard = 'Build\$VERSION.SDK_INT:I\s+\d+: bipush\s+29\s+\d+: if_icmplt\s+\d+\s+\d+: aload(?:_\d|\s+\d+)\s+\d+: iconst_0\s+\d+: invokevirtual\s+#\d+\s+// Method android/net/VpnService\$Builder.setMetered:\(Z\)'
if ($method -notmatch $guard) { throw 'API29+ guarded setMetered(false) is missing.' }
if ($method.IndexOf('Builder.setMetered') -gt $method.IndexOf('Builder.establish')) {
    throw 'Metering policy must be applied before establish.'
}
if ($service -match 'setUnderlyingNetworks') {
    throw 'Unexpected explicit underlying-network override: review handover policy.'
}
Write-Host 'Metering bytecode gate passed: Q+ inherit metering, default underlying-network tracking preserved.'
