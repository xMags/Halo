package moe.ditto.halo.auth

import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.value
import platform.CoreFoundation.CFDictionaryAddValue
import platform.CoreFoundation.CFDictionaryCreateMutable
import platform.CoreFoundation.CFMutableDictionaryRef
import platform.CoreFoundation.CFRelease
import platform.CoreFoundation.CFTypeRefVar
import platform.CoreFoundation.kCFBooleanTrue
import platform.CoreFoundation.kCFTypeDictionaryKeyCallBacks
import platform.CoreFoundation.kCFTypeDictionaryValueCallBacks
import platform.Foundation.CFBridgingRelease
import platform.Foundation.CFBridgingRetain
import platform.Foundation.NSData
import platform.Foundation.NSString
import platform.Foundation.NSUTF8StringEncoding
import platform.Foundation.create
import platform.Foundation.dataUsingEncoding
import platform.Security.SecItemAdd
import platform.Security.SecItemCopyMatching
import platform.Security.SecItemDelete
import platform.Security.SecItemUpdate
import platform.Security.errSecSuccess
import platform.Security.kSecAttrAccessible
import platform.Security.kSecAttrAccessibleAfterFirstUnlock
import platform.Security.kSecAttrAccount
import platform.Security.kSecAttrService
import platform.Security.kSecClass
import platform.Security.kSecClassGenericPassword
import platform.Security.kSecMatchLimit
import platform.Security.kSecMatchLimitOne
import platform.Security.kSecReturnData
import platform.Security.kSecValueData

/**
 * Generic-password Keychain items, one per storage key, all under a single
 * service. `AfterFirstUnlock` accessibility so future background work
 * (downloads, refresh) can read the session while the device is locked.
 */
@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
class IosKeychainStorage(
    private val service: String = "moe.ditto.halo",
) : SecureStorage {

    override fun read(key: String): String? = memScoped {
        val result = alloc<CFTypeRefVar>()
        val status = withQuery(key, capacity = 6) { query ->
            CFDictionaryAddValue(query, kSecReturnData, kCFBooleanTrue)
            CFDictionaryAddValue(query, kSecMatchLimit, kSecMatchLimitOne)
            SecItemCopyMatching(query, result.ptr)
        }
        if (status != errSecSuccess) return null
        val data = CFBridgingRelease(result.value) as? NSData ?: return null
        // NSString is not a Kotlin String subtype in Kotlin/Native; toString()
        // (NSString.description) is the supported bridge back.
        NSString.create(data = data, encoding = NSUTF8StringEncoding)?.toString()
    }

    override fun write(key: String, value: String) {
        val data = NSString.create(string = value).dataUsingEncoding(NSUTF8StringEncoding)
            ?: error("Keychain write failed: value is not encodable as UTF-8")

        // Update in place when the item exists; add on first write.
        val updateStatus = withQuery(key, capacity = 3) { query ->
            withValueAttributes(data) { attributes -> SecItemUpdate(query, attributes) }
        }
        if (updateStatus == errSecSuccess) return

        val addStatus = withQuery(key, capacity = 6) { query ->
            val dataRef = CFBridgingRetain(data)
            try {
                CFDictionaryAddValue(query, kSecValueData, dataRef)
                CFDictionaryAddValue(query, kSecAttrAccessible, kSecAttrAccessibleAfterFirstUnlock)
                SecItemAdd(query, null)
            } finally {
                CFRelease(dataRef)
            }
        }
        // Failing to persist a session must be loud: a silent failure would
        // look like a successful sign-in that vanishes on the next launch.
        check(addStatus == errSecSuccess) { "Keychain write failed with OSStatus $addStatus" }
    }

    override fun delete(key: String) {
        // Idempotent: errSecItemNotFound is as good as deleted.
        withQuery(key, capacity = 3) { query -> SecItemDelete(query) }
    }

    /**
     * Builds the class/service/account base query, hands it to [block], and
     * releases every CF reference afterwards. The dictionary uses the CFType
     * callbacks, so it retains its contents and our local refs can be released
     * unconditionally.
     */
    private inline fun <T> withQuery(key: String, capacity: Long, block: (CFMutableDictionaryRef?) -> T): T {
        val query = CFDictionaryCreateMutable(
            null,
            capacity,
            kCFTypeDictionaryKeyCallBacks.ptr,
            kCFTypeDictionaryValueCallBacks.ptr,
        )
        val serviceRef = CFBridgingRetain(service)
        val accountRef = CFBridgingRetain(key)
        try {
            CFDictionaryAddValue(query, kSecClass, kSecClassGenericPassword)
            CFDictionaryAddValue(query, kSecAttrService, serviceRef)
            CFDictionaryAddValue(query, kSecAttrAccount, accountRef)
            return block(query)
        } finally {
            CFRelease(serviceRef)
            CFRelease(accountRef)
            CFRelease(query)
        }
    }

    private inline fun <T> withValueAttributes(data: NSData, block: (CFMutableDictionaryRef?) -> T): T {
        val attributes = CFDictionaryCreateMutable(
            null,
            1,
            kCFTypeDictionaryKeyCallBacks.ptr,
            kCFTypeDictionaryValueCallBacks.ptr,
        )
        val dataRef = CFBridgingRetain(data)
        try {
            CFDictionaryAddValue(attributes, kSecValueData, dataRef)
            return block(attributes)
        } finally {
            CFRelease(dataRef)
            CFRelease(attributes)
        }
    }
}
