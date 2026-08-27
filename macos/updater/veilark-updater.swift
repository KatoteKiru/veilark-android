#!/usr/bin/env swift
import CryptoKit
import Darwin
import Foundation

private struct Options {
    let dmg: URL
    let expectedSHA256: String
    let expectedVersion: String
    let expectedBuild: Int
    let architecture: String
    let sourceURL: String
    let notes: String
    let manifestSignature: Data
    let currentApp: URL
    let parentPID: pid_t
    let relaunch: Bool
}

private let fileManager = FileManager.default
private let expectedBundleIdentifier = "app.veilark.macos"
private let otaPublicKeyRaw = Data(base64Encoded: "jH5WvKQdCpseXcpMcj4h3EQSLugd7L+CHobYdJy1R6Q=")!
#if VEILARK_REQUIRE_GATEKEEPER
private let requireGatekeeper = true
#else
private let requireGatekeeper = false
#endif
private let logURL: URL = {
    let base = fileManager.homeDirectoryForCurrentUser
        .appendingPathComponent("Library/Logs/Veilark", isDirectory: true)
    try? fileManager.createDirectory(at: base, withIntermediateDirectories: true)
    return base.appendingPathComponent("updater.log")
}()

private func log(_ message: String) {
    let line = "\(ISO8601DateFormatter().string(from: Date())) \(message)\n"
    let data = Data(line.utf8)
    if !fileManager.fileExists(atPath: logURL.path) {
        fileManager.createFile(
            atPath: logURL.path,
            contents: data,
            attributes: [.posixPermissions: 0o600]
        )
        return
    }
    guard let handle = try? FileHandle(forWritingTo: logURL) else { return }
    defer { try? handle.close() }
    try? handle.seekToEnd()
    try? handle.write(contentsOf: data)
}

private func fail(_ message: String) -> Never {
    log("ERROR \(message)")
    fputs(message + "\n", stderr)
    exit(1)
}

private func parseOptions() -> Options {
    let arguments = Array(CommandLine.arguments.dropFirst())
    guard arguments.count % 2 == 0 else { fail("invalid updater arguments") }
    var values: [String: String] = [:]
    var index = 0
    while index < arguments.count {
        let key = arguments[index]
        guard key.hasPrefix("--"), values[key] == nil else { fail("invalid updater option") }
        values[key] = arguments[index + 1]
        index += 2
    }
    guard
        let dmg = values["--dmg"],
        let sha256 = values["--sha256"]?.lowercased(),
        sha256.range(of: "^[0-9a-f]{64}$", options: .regularExpression) != nil,
        let version = values["--version"],
        version.range(of: "^\\d+\\.\\d+\\.\\d+$", options: .regularExpression) != nil,
        let rawBuild = values["--build"],
        let build = Int(rawBuild), build > 0,
        let architecture = values["--architecture"],
        ["arm64", "amd64", "universal"].contains(architecture),
        let sourceURL = values["--url"],
        let parsedURL = URL(string: sourceURL), parsedURL.scheme?.lowercased() == "https", parsedURL.host != nil,
        let notes = values["--notes"], notes.utf8.count <= 4_000,
        let rawSignature = values["--signature"],
        let signature = Data(base64Encoded: rawSignature), signature.count == 64,
        let currentApp = values["--current-app"],
        let rawPID = values["--pid"],
        let pid = Int32(rawPID), pid > 1,
        let rawRelaunch = values["--relaunch"],
        ["true", "false"].contains(rawRelaunch)
    else {
        fail("missing or invalid updater option")
    }
    return Options(
        dmg: URL(fileURLWithPath: dmg).standardizedFileURL,
        expectedSHA256: sha256,
        expectedVersion: version,
        expectedBuild: build,
        architecture: architecture,
        sourceURL: sourceURL,
        notes: notes,
        manifestSignature: signature,
        currentApp: URL(fileURLWithPath: currentApp).standardizedFileURL,
        parentPID: pid,
        relaunch: rawRelaunch == "true"
    )
}

@discardableResult
private func run(_ executable: String, _ arguments: [String]) -> (status: Int32, output: String) {
    let process = Process()
    let output = Pipe()
    process.executableURL = URL(fileURLWithPath: executable)
    process.arguments = arguments
    process.standardOutput = output
    process.standardError = output
    do {
        try process.run()
    } catch {
        return (1, "\(error)")
    }
    let data = output.fileHandleForReading.readDataToEndOfFile()
    process.waitUntilExit()
    return (process.terminationStatus, String(decoding: data, as: UTF8.self))
}

private func waitForParentToExit(_ pid: pid_t) {
    for _ in 0..<600 {
        if kill(pid, 0) != 0 && errno != EPERM { return }
        usleep(100_000)
    }
    fail("Veilark did not exit before update timeout")
}

private func sha256(_ url: URL) -> String {
    guard let handle = try? FileHandle(forReadingFrom: url) else { fail("cannot read update image") }
    defer { try? handle.close() }
    var digest = SHA256()
    while true {
        guard let data = try? handle.read(upToCount: 1024 * 1024), let data, !data.isEmpty else { break }
        digest.update(data: data)
    }
    return digest.finalize().map { String(format: "%02x", $0) }.joined()
}

private func canonicalPayload(_ options: Options) -> Data {
    let fields = [
        "1",
        "macos",
        options.expectedVersion,
        String(options.expectedBuild),
        options.architecture,
        options.sourceURL,
        options.expectedSHA256,
        options.notes,
    ]
    return Data(fields.map { "\(Data($0.utf8).count):\($0)" }.joined(separator: "\n").utf8)
}

private func verifyManifestSignature(_ options: Options) {
    do {
        let publicKey = try Curve25519.Signing.PublicKey(rawRepresentation: otaPublicKeyRaw)
        guard publicKey.isValidSignature(options.manifestSignature, for: canonicalPayload(options)) else {
            fail("OTA manifest signature is invalid")
        }
    } catch {
        fail("OTA public key is invalid: \(error)")
    }
}

private func semanticVersion(_ value: String) -> [Int]? {
    let parts = value.split(separator: ".")
    guard parts.count == 3 else { return nil }
    let numbers = parts.compactMap { Int($0) }
    return numbers.count == 3 ? numbers : nil
}

private func versionIsGreater(_ candidate: String, than current: String) -> Bool {
    guard let left = semanticVersion(candidate), let right = semanticVersion(current) else { return false }
    for index in 0..<3 where left[index] != right[index] {
        return left[index] > right[index]
    }
    return false
}

private func validateOwnedRegularFile(_ url: URL) {
    var info = stat()
    guard lstat(url.path, &info) == 0 else { fail("update image is missing") }
    guard (info.st_mode & S_IFMT) == S_IFREG else { fail("update image is not a regular file") }
    guard info.st_uid == getuid() else { fail("update image owner mismatch") }
    guard info.st_size > 0 && info.st_size <= 750 * 1024 * 1024 else { fail("update image size is invalid") }
}

private func mount(_ dmg: URL) -> URL {
    let result = run("/usr/bin/hdiutil", ["attach", dmg.path, "-nobrowse", "-readonly", "-plist"])
    guard result.status == 0, let data = result.output.data(using: .utf8) else {
        fail("could not mount update image: \(result.output)")
    }
    guard
        let plist = try? PropertyListSerialization.propertyList(from: data, format: nil),
        let dictionary = plist as? [String: Any],
        let entities = dictionary["system-entities"] as? [[String: Any]],
        let mountPoint = entities.compactMap({ $0["mount-point"] as? String }).last
    else {
        fail("update image did not expose a mount point")
    }
    return URL(fileURLWithPath: mountPoint, isDirectory: true)
}

private func verifyBundle(_ app: URL, expectedVersion: String, currentApp: URL) {
    guard app.pathExtension == "app", let bundle = Bundle(url: app) else { fail("update app bundle is invalid") }
    guard bundle.bundleIdentifier == expectedBundleIdentifier else { fail("update bundle identifier mismatch") }
    guard bundle.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String == expectedVersion else {
        fail("update bundle version mismatch")
    }
    guard
        let currentVersion = Bundle(url: currentApp)?.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String,
        versionIsGreater(expectedVersion, than: currentVersion)
    else {
        fail("update version is not newer than the installed version")
    }
    guard requireGatekeeper else { return }
    let signature = run("/usr/bin/codesign", ["--verify", "--deep", "--strict", "--verbose=2", app.path])
    guard signature.status == 0 else { fail("update code signature failed: \(signature.output)") }
    let gatekeeper = run("/usr/sbin/spctl", ["--assess", "--type", "execute", "--verbose=2", app.path])
    guard gatekeeper.status == 0 else { fail("update app failed Gatekeeper: \(gatekeeper.output)") }
    let currentTeam = teamIdentifier(currentApp)
    let updateTeam = teamIdentifier(app)
    guard !currentTeam.isEmpty, currentTeam == updateTeam else { fail("update signing team mismatch") }
}

private func teamIdentifier(_ app: URL) -> String {
    let result = run("/usr/bin/codesign", ["-dv", "--verbose=4", app.path])
    guard result.status == 0 else { return "" }
    for line in result.output.split(separator: "\n") where line.hasPrefix("TeamIdentifier=") {
        return String(line.dropFirst("TeamIdentifier=".count)).trimmingCharacters(in: .whitespacesAndNewlines)
    }
    return ""
}

private func shellQuote(_ value: String) -> String {
    "'" + value.replacingOccurrences(of: "'", with: "'\"'\"'") + "'"
}

private func appleScriptQuote(_ value: String) -> String {
    "\"" + value
        .replacingOccurrences(of: "\\", with: "\\\\")
        .replacingOccurrences(of: "\"", with: "\\\"")
        .replacingOccurrences(of: "\n", with: "\\n") + "\""
}

private func install(_ source: URL, over target: URL) {
    let parent = target.deletingLastPathComponent()
    let token = UUID().uuidString
    let staging = parent.appendingPathComponent(".Veilark-update-\(token).app")
    let backup = parent.appendingPathComponent(".Veilark-backup-\(token).app")
    if fileManager.isWritableFile(atPath: parent.path) {
        let copy = run("/usr/bin/ditto", ["--rsrc", "--extattr", "--acl", source.path, staging.path])
        guard copy.status == 0 else { fail("could not stage update: \(copy.output)") }
        do {
            if fileManager.fileExists(atPath: target.path) {
                try fileManager.moveItem(at: target, to: backup)
            }
            do {
                try fileManager.moveItem(at: staging, to: target)
                try? fileManager.removeItem(at: backup)
            } catch {
                if fileManager.fileExists(atPath: backup.path) {
                    try? fileManager.moveItem(at: backup, to: target)
                }
                throw error
            }
        } catch {
            try? fileManager.removeItem(at: staging)
            fail("could not replace Veilark: \(error)")
        }
        return
    }

    let script = """
    set -euo pipefail
    /bin/rm -rf -- \(shellQuote(staging.path)) \(shellQuote(backup.path))
    /usr/bin/ditto --rsrc --extattr --acl \(shellQuote(source.path)) \(shellQuote(staging.path))
    if [ -e \(shellQuote(target.path)) ]; then /bin/mv -- \(shellQuote(target.path)) \(shellQuote(backup.path)); fi
    if /bin/mv -- \(shellQuote(staging.path)) \(shellQuote(target.path)); then
      /bin/rm -rf -- \(shellQuote(backup.path))
    else
      if [ -e \(shellQuote(backup.path)) ]; then /bin/mv -- \(shellQuote(backup.path)) \(shellQuote(target.path)); fi
      exit 1
    fi
    """
    let elevated = run("/usr/bin/osascript", ["-e", "do shell script \(appleScriptQuote(script)) with administrator privileges"])
    guard elevated.status == 0 else { fail("administrator update failed: \(elevated.output)") }
}

let options = parseOptions()
log("START version=\(options.expectedVersion)")
verifyManifestSignature(options)
validateOwnedRegularFile(options.dmg)
guard sha256(options.dmg) == options.expectedSHA256 else { fail("update SHA-256 changed before installation") }
guard options.currentApp.pathExtension == "app", fileManager.fileExists(atPath: options.currentApp.path) else {
    fail("current Veilark app bundle is unavailable")
}
guard Bundle(url: options.currentApp)?.bundleIdentifier == expectedBundleIdentifier else {
    fail("current app bundle identifier mismatch")
}
waitForParentToExit(options.parentPID)
let mountPoint = mount(options.dmg)
defer { _ = run("/usr/bin/hdiutil", ["detach", mountPoint.path, "-quiet", "-force"]) }
let updateApp = mountPoint.appendingPathComponent("Veilark.app", isDirectory: true)
verifyBundle(updateApp, expectedVersion: options.expectedVersion, currentApp: options.currentApp)
install(updateApp, over: options.currentApp)
if options.relaunch {
    let launch = run("/usr/bin/open", ["-n", options.currentApp.path])
    guard launch.status == 0 else { fail("Veilark was updated but could not be relaunched") }
}
try? fileManager.removeItem(at: options.dmg)
log("SUCCESS version=\(options.expectedVersion)")
