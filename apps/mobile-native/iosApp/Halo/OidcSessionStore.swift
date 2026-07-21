import Foundation
import Security

/// The persisted OIDC session, entirely Swift-owned — Kotlin never sees a
/// token or parses OIDC JSON. Endpoints are captured at sign-in so restore and
/// refresh work without re-running discovery (and therefore offline).
struct OidcSession: Codable {
    var serverUrl: String
    var issuer: String
    var clientId: String
    /// Used verbatim on refresh — the trailing-slash preservation that
    /// motivated the hand-built token POST applies to the refresh grant too.
    var tokenEndpoint: String
    var revocationEndpoint: String?
    var accessToken: String
    /// Epoch seconds.
    var accessTokenExpiresAt: Double
    var refreshToken: String
}

/// One Keychain generic-password item for the whole session blob. Same service
/// and accessibility as the Kotlin `IosKeychainStorage` (so both arms live in
/// one namespace and `AfterFirstUnlock` keeps future background work able to
/// read), but a distinct account key — the arms must never share an item.
final class OidcSessionStore {
    private let service = "moe.ditto.halo"
    private let account = "halo.oidcSession"

    func load() -> OidcSession? {
        var query = baseQuery()
        query[kSecReturnData as String] = kCFBooleanTrue
        query[kSecMatchLimit as String] = kSecMatchLimitOne
        var result: CFTypeRef?
        guard SecItemCopyMatching(query as CFDictionary, &result) == errSecSuccess,
              let data = result as? Data
        else { return nil }
        // An undecodable blob is treated as signed out rather than crashing the
        // launch path; the next sign-in overwrites it.
        return try? JSONDecoder().decode(OidcSession.self, from: data)
    }

    /// Returns false when the Keychain write fails — the caller must surface
    /// that loudly, or a sign-in would appear to succeed and vanish on the
    /// next launch.
    func save(_ session: OidcSession) -> Bool {
        guard let data = try? JSONEncoder().encode(session) else { return false }

        let update: [String: Any] = [kSecValueData as String: data]
        let updateStatus = SecItemUpdate(baseQuery() as CFDictionary, update as CFDictionary)
        if updateStatus == errSecSuccess { return true }

        var add = baseQuery()
        add[kSecValueData as String] = data
        add[kSecAttrAccessible as String] = kSecAttrAccessibleAfterFirstUnlock
        return SecItemAdd(add as CFDictionary, nil) == errSecSuccess
    }

    func clear() {
        // Idempotent: errSecItemNotFound is as good as deleted.
        SecItemDelete(baseQuery() as CFDictionary)
    }

    private func baseQuery() -> [String: Any] {
        [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: account,
        ]
    }
}
