#!/usr/bin/env swift
import Darwin
import Foundation

let installedEngines = "/Library/PrivilegedHelperTools/VeilarkEngines"
let pidFile = "/Library/Application Support/Veilark/runtime/engine.pid"
let logFile = "/Library/Application Support/Veilark/runtime/engine.log"

func fail(_ message: String) -> Never {
    fputs(message + "\n", stderr)
    exit(1)
}

func becomeRoot() {
    if setgid(0) != 0 || seteuid(0) != 0 || setuid(0) != 0 || geteuid() != 0 {
        fail("helper is not running as root; reinstall helper")
    }
}

func engineLogText() -> String {
    (try? String(contentsOfFile: logFile, encoding: .utf8)) ?? ""
}

func failFromEngineLog(fallback: String) -> Never {
    let logText = engineLogText()
    if logText.contains("permission denied") || logText.contains("SIOCAIFADDR") {
        fail("engine missing root (reinstall helper)")
    }
    if logText.contains("empty direct outbound") {
        fail("sing-box dns rejected")
    }
    let routeFailures = [
        "Failed to create listener",
        "Unable to setup routes",
        "Unable to setup routes for mactun session",
        "Failed to initialize tunnel",
        "make_tun_listener",
    ]
    if routeFailures.contains(where: { logText.contains($0) }) {
        fail("tunnel routes failed")
    }
    fail(fallback)
}

func invokingUserHome() -> String? {
    let uid = getuid()
    guard uid != 0, let pw = getpwuid(uid) else { return nil }
    return String(cString: pw.pointee.pw_dir)
}

func configPathAllowed(_ configPath: String, callerHome: String?) -> Bool {
    let suffix = "/Library/Application Support/Veilark/"
    var prefixes = [
        "/Library/Application Support/Veilark/",
        "/System/Volumes/Data/Library/Application Support/Veilark/",
    ]
    if let home = callerHome, !home.isEmpty {
        prefixes += [home + suffix, "/System/Volumes/Data" + home + suffix]
    }
    if prefixes.contains(where: { configPath.hasPrefix($0) }) {
        return true
    }
    return configPath.range(
        of: #"^(/System/Volumes/Data)?/Users/[^/]+/Library/Application Support/Veilark/"#,
        options: .regularExpression,
    ) != nil
}

func realpathOrFail(_ path: String) -> String {
    guard let resolved = URL(fileURLWithPath: path).resolvingSymlinksInPath().path as String? else {
        fail("invalid path")
    }
    return resolved
}

func allowedEngine(_ name: String) -> String {
    switch name {
    case "sing-box":
        return installedEngines + "/sing-box"
    case "trusttunnel":
        return installedEngines + "/trusttunnel_client"
    default:
        fail("engine not allowed")
    }
}

func stopEngine() {
    becomeRoot()
    guard FileManager.default.fileExists(atPath: pidFile),
          let text = try? String(contentsOfFile: pidFile, encoding: .utf8),
          let pid = Int32(text.trimmingCharacters(in: .whitespacesAndNewlines)), pid > 1 else {
        return
    }
    kill(pid, SIGTERM)
    usleep(1_500_000)
    kill(pid, SIGKILL)
    usleep(200_000)
    try? FileManager.default.removeItem(atPath: pidFile)
}

func startEngine(name: String, config: String) {
    let callerHome = invokingUserHome()
    becomeRoot()
    let engine = realpathOrFail(allowedEngine(name))
    let configPath = realpathOrFail(config)
    guard configPathAllowed(configPath, callerHome: callerHome) else {
        fail("config path not allowed")
    }
    guard FileManager.default.isExecutableFile(atPath: engine) else {
        fail("engine binary missing")
    }
    stopEngine()
    let runtimeDir = URL(fileURLWithPath: logFile).deletingLastPathComponent().path
    try? FileManager.default.createDirectory(atPath: runtimeDir, withIntermediateDirectories: true)
    try? FileManager.default.removeItem(atPath: logFile)
    FileManager.default.createFile(
        atPath: logFile,
        contents: Data(),
        attributes: [.posixPermissions: 0o644]
    )
    guard let log = FileHandle(forWritingAtPath: logFile) else {
        fail("failed to open engine log")
    }
    let process = Process()
    process.executableURL = URL(fileURLWithPath: engine)
    process.arguments = name == "sing-box"
        ? ["run", "-c", configPath]
        : ["-c", configPath]
    process.standardOutput = log
    process.standardError = log
    do {
        try process.run()
    } catch {
        fail("failed to start engine")
    }
    let pid = process.processIdentifier
    try? String(pid).write(toFile: pidFile, atomically: true, encoding: .utf8)
    usleep(1_200_000)
    if kill(pid, 0) != 0 {
        failFromEngineLog(fallback: "engine exited immediately")
    }
    let logText = engineLogText()
    let failures = [
        "Failed to create listener",
        "Unable to setup routes",
        "Unable to setup routes for mactun session",
        "Failed to initialize tunnel",
        "make_tun_listener",
        "permission denied",
        "SIOCAIFADDR",
    ]
    if failures.contains(where: { logText.contains($0) }) {
        stopEngine()
        failFromEngineLog(fallback: "tunnel routes failed")
    }
    print("connected")
}

func status() {
    guard FileManager.default.fileExists(atPath: pidFile),
          let text = try? String(contentsOfFile: pidFile, encoding: .utf8),
          let pid = Int32(text.trimmingCharacters(in: .whitespacesAndNewlines)) else {
        print("disconnected")
        return
    }
    if kill(pid, 0) == 0 {
        print("connected")
    } else {
        print("disconnected")
    }
}

let args = Array(CommandLine.arguments.dropFirst())
guard let command = args.first else { fail("usage: veilark-helper start|stop|status") }
switch command {
case "start":
    guard args.count == 3 else { fail("usage: veilark-helper start sing-box|trusttunnel <config>") }
    startEngine(name: args[1], config: args[2])
case "stop":
    stopEngine()
    print("disconnected")
case "status":
    status()
default:
    fail("unknown command")
}
