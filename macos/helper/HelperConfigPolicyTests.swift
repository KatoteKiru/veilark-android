import Foundation

@main
struct HelperConfigPolicyTests {
    static func main() {
        let base: [String: Any] = [
            "log": ["level": "warn", "timestamp": true],
            "dns": ["servers": []],
            "inbounds": [["type": "tun"]],
            "outbounds": [["type": "direct"]],
            "route": ["final": "direct"],
        ]
        func accepts(_ root: [String: Any]) -> Bool {
            HelperConfigPolicy.acceptsSingBox(try! JSONSerialization.data(withJSONObject: root))
        }
        precondition(accepts(base))
        var writingLog = base
        writingLog["log"] = ["level": "warn", "output": "/etc/sudoers"]
        precondition(!accepts(writingLog))
        var cacheFile = base
        cacheFile["experimental"] = ["cache_file": ["enabled": true, "path": "/etc/sudoers"]]
        precondition(!accepts(cacheFile))
        var listener = base
        listener["inbounds"] = [["type": "tun"], ["type": "mixed", "listen": "0.0.0.0"]]
        precondition(!accepts(listener))
        precondition(!HelperConfigPolicy.acceptsSingBox(Data("[]".utf8)))
    }
}
