import Foundation

// The setuid helper must not pass an arbitrary user-authored sing-box document
// to a root process. Veilark only generates these top-level sections.
enum HelperConfigPolicy {
    static func acceptsSingBox(_ data: Data) -> Bool {
        guard let root = (try? JSONSerialization.jsonObject(with: data)) as? [String: Any],
              Set(root.keys) == Set(["log", "dns", "inbounds", "outbounds", "route"]),
              let log = root["log"] as? [String: Any],
              Set(log.keys).isSubset(of: ["level", "timestamp"]),
              let inbounds = root["inbounds"] as? [[String: Any]],
              inbounds.count == 1,
              inbounds[0]["type"] as? String == "tun",
              root["dns"] is [String: Any],
              root["outbounds"] is [[String: Any]],
              root["route"] is [String: Any] else {
            return false
        }
        return true
    }
}
