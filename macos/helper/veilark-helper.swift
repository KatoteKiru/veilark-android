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

// A separate bounded sink survives the short-lived start command. Engine output
// goes through its pipe, not an unbounded file descriptor inherited by the core.
func logSink() {
    becomeRoot()
    let input = FileHandle.standardInput
    let limit = 1_048_576
    var size = 0
    var output: FileHandle?
    func reopen() {
        try? output?.close()
        output = nil
        size = 0
        try? FileManager.default.removeItem(atPath: logFile + ".1")
        if FileManager.default.fileExists(atPath: logFile) {
            try? FileManager.default.moveItem(atPath: logFile, toPath: logFile + ".1")
        }
        guard FileManager.default.createFile(atPath: logFile, contents: Data(),
            attributes: [.posixPermissions: 0o600]),
            let handle = FileHandle(forWritingAtPath: logFile) else { return }
        output = handle
        size = 0
    }
    reopen()
    defer { try? output?.close() }
    while let data = try? input.read(upToCount: 16_384), !data.isEmpty {
        if size + data.count > limit { reopen() }
        do { try output?.write(contentsOf: data) } catch {
            // A full disk must not break the core's stdout pipe and kill a VPN.
            try? output?.close()
            output = nil
        }
        size += data.count
    }
}

func diagnostics() {
    becomeRoot()
    // Export only known technical failure markers. Never return raw log lines,
    // endpoints, imported configuration, credentials or customer destinations.
    let text = engineLogText()
    let markers = ["Failed to create listener", "Unable to setup routes",
        "Failed to initialize tunnel", "make_tun_listener", "permission denied",
        "SIOCAIFADDR", "empty direct outbound", "empty result"]
    for marker in markers where text.contains(marker) { print(marker) }
    if text.range(of: "outbound connection to :[0-9]+", options: .regularExpression) != nil {
        print("outbound connection to :0")
    }
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

func configPathAllowed(_ configPath: String, callerHome: String?, callerUid: uid_t) -> Bool {
    guard let home = callerHome, !home.isEmpty else { return false }
    let expected = home + "/Library/Application Support/Veilark/runtime/"
    let dataExpected = "/System/Volumes/Data" + expected
    guard configPath.hasPrefix(expected) || configPath.hasPrefix(dataExpected) else { return false }
    guard configPath.hasSuffix("/sing-box.json") || configPath.hasSuffix("/trusttunnel.toml") else {
        return false
    }
    var fileInfo = stat()
    guard lstat(configPath, &fileInfo) == 0 else { return false }
    guard (fileInfo.st_mode & S_IFMT) == S_IFREG else { return false }
    guard fileInfo.st_uid == callerUid else { return false }
    guard (fileInfo.st_mode & (S_IWGRP | S_IWOTH)) == 0 else { return false }
    return fileInfo.st_size > 0 && fileInfo.st_size <= 4 * 1024 * 1024
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

func managedProcessPath(_ pid: pid_t) -> String? {
    // PROC_PIDPATHINFO_MAXSIZE is a C macro that Swift cannot import on all SDKs.
    // Darwin defines it as 4 * MAXPATHLEN (4096 bytes).
    var buffer = [CChar](repeating: 0, count: 4096)
    let length = proc_pidpath(pid, &buffer, UInt32(buffer.count))
    guard length > 0 else { return nil }
    return String(cString: buffer)
}

func isManagedProcess(_ pid: pid_t) -> Bool {
    guard let path = managedProcessPath(pid) else { return false }
    return path == installedEngines + "/sing-box" ||
        path == installedEngines + "/trusttunnel_client"
}

func clearPidFile() {
    guard FileManager.default.fileExists(atPath: pidFile) else { return }
    do {
        try FileManager.default.removeItem(atPath: pidFile)
    } catch {
        fail("failed to clear engine pid")
    }
}

func stopEngine() {
    becomeRoot()
    guard FileManager.default.fileExists(atPath: pidFile) else {
        return
    }
    guard let text = try? String(contentsOfFile: pidFile, encoding: .utf8),
          let pid = Int32(text.trimmingCharacters(in: .whitespacesAndNewlines)), pid > 1 else {
        clearPidFile()
        return
    }
    guard isManagedProcess(pid) else {
        clearPidFile()
        return
    }
    guard kill(pid, SIGTERM) == 0 || errno == ESRCH else {
        fail("failed to signal managed engine")
    }
    for _ in 0..<30 {
        if kill(pid, 0) != 0 || !isManagedProcess(pid) {
            clearPidFile()
            return
        }
        usleep(100_000)
    }
    guard isManagedProcess(pid) else {
        clearPidFile()
        return
    }
    guard kill(pid, SIGKILL) == 0 || errno == ESRCH else {
        fail("failed to kill managed engine")
    }
    for _ in 0..<20 {
        if kill(pid, 0) != 0 || !isManagedProcess(pid) {
            clearPidFile()
            return
        }
        usleep(100_000)
    }
    fail("managed engine did not stop")
}

func startEngine(name: String, config: String) {
    let callerUid = getuid()
    let callerHome = invokingUserHome()
    let engine = realpathOrFail(allowedEngine(name))
    let configPath = realpathOrFail(config)
    guard configPathAllowed(configPath, callerHome: callerHome, callerUid: callerUid) else {
        fail("config path not allowed")
    }
    guard let configData = try? Data(contentsOf: URL(fileURLWithPath: configPath)),
          !configData.isEmpty,
          configData.count <= 4 * 1024 * 1024 else {
        fail("config could not be read safely")
    }
    becomeRoot()
    guard FileManager.default.isExecutableFile(atPath: engine) else {
        fail("engine binary missing")
    }
    stopEngine()
    let runtimeDir = URL(fileURLWithPath: logFile).deletingLastPathComponent().path
    try? FileManager.default.createDirectory(atPath: runtimeDir, withIntermediateDirectories: true)
    let privilegedConfig = runtimeDir + (name == "sing-box" ? "/active-sing-box.json" : "/active-trusttunnel.toml")
    do {
        try configData.write(to: URL(fileURLWithPath: privilegedConfig), options: .atomic)
        try FileManager.default.setAttributes([.posixPermissions: 0o600], ofItemAtPath: privilegedConfig)
    } catch {
        fail("failed to stage config")
    }
    let logPipe = Pipe()
    // Do not let startup diagnostics accidentally read the previous session.
    try? FileManager.default.removeItem(atPath: logFile)
    let logger = Process()
    logger.executableURL = URL(fileURLWithPath: "/Library/PrivilegedHelperTools/veilark-helper")
    logger.arguments = ["log-sink"]
    logger.standardInput = logPipe
    logger.standardOutput = FileHandle.nullDevice
    logger.standardError = FileHandle.nullDevice
    do { try logger.run() } catch { fail("failed to start log sink") }
    let process = Process()
    process.executableURL = URL(fileURLWithPath: engine)
    process.arguments = name == "sing-box"
        ? ["run", "-c", privilegedConfig]
        : ["-c", privilegedConfig]
    process.standardOutput = logPipe
    process.standardError = logPipe
    do {
        try process.run()
    } catch {
        logger.terminate()
        fail("failed to start engine")
    }
    try? logPipe.fileHandleForReading.close()
    try? logPipe.fileHandleForWriting.close()
    let pid = process.processIdentifier
    do {
        try String(pid).write(toFile: pidFile, atomically: true, encoding: .utf8)
        try FileManager.default.setAttributes([.posixPermissions: 0o600], ofItemAtPath: pidFile)
    } catch {
        process.terminate()
        usleep(500_000)
        if process.isRunning {
            kill(pid, SIGKILL)
        }
        try? FileManager.default.removeItem(atPath: pidFile)
        fail("failed to persist engine pid")
    }
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
    if kill(pid, 0) == 0 && isManagedProcess(pid) {
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
case "diagnostics":
    guard args.count == 1 else { fail("invalid diagnostics arguments") }
    diagnostics()
case "log-sink":
    guard args.count == 1 else { fail("invalid log sink arguments") }
    logSink()
default:
    fail("unknown command")
}
