package moe.ditto.halo.tv

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.zxing.BinaryBitmap
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.serialization.json.JsonObject
import moe.ditto.halo.auth.AddonSignedIn
import moe.ditto.halo.auth.DeviceChallenge
import moe.ditto.halo.auth.DevicePollResult
import moe.ditto.halo.auth.DeviceSession
import moe.ditto.halo.auth.DeviceSignIn
import moe.ditto.halo.auth.DeviceSignInOffer
import moe.ditto.halo.tv.signin.TvPhoneSignIn
import moe.ditto.halo.ui.HaloColors
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Signing in with a phone, against a stand-in server: the QR code on screen
 * reads back as the address, an approval hands over the session, and a
 * refusal offers a new code with the remote.
 */
@RunWith(AndroidJUnit4::class)
class TvPhoneSignInInstrumentedTest {
    private val launchIntent = Intent(ApplicationProvider.getApplicationContext<Context>(), TvActivity::class.java)
        .apply { putExtra("resetSession", true) }

    @get:Rule(order = 0)
    val composeRule = createEmptyComposeRule()

    @get:Rule(order = 1)
    val activityRule = ActivityScenarioRule<TvActivity>(launchIntent)

    private val remote = TvRemote(composeRule)

    /** Answers in order, then keeps saying "not yet". */
    private class FakeServer(answers: List<DevicePollResult>) : DeviceSignIn {
        private val queue = ArrayDeque(answers)
        var codes = 0

        override suspend fun start(serverUrl: String, config: JsonObject): DeviceChallenge {
            codes += 1
            return DeviceChallenge(
                deviceCode = "secret",
                userCode = "BCDF-GHJ$codes",
                verificationUrl = "https://account.example/tv",
                verificationUrlComplete = "https://account.example/tv?device=BCDFGHJ$codes",
                expiresInSeconds = 600,
                intervalSeconds = 1,
            )
        }

        override suspend fun poll(serverUrl: String, challenge: DeviceChallenge): DevicePollResult =
            queue.removeFirstOrNull() ?: DevicePollResult.Pending
    }

    private fun show(server: FakeServer, sessions: MutableList<DeviceSession>) {
        activityRule.scenario.onActivity { activity ->
            activity.setContent {
                Box(Modifier.fillMaxSize().background(HaloColors.Background)) {
                    TvPhoneSignIn(
                        offer = DeviceSignInOffer(server, JsonObject(emptyMap())),
                        serverUrl = "https://halo.example",
                        onSession = { sessions += it },
                    )
                }
            }
        }
        remote.leaveTouchMode()
    }

    @Test
    fun theQrCodeReadsAsTheAddressAndAnApprovalSignsIn() {
        val session = DeviceSession.Addon("example", AddonSignedIn("token"))
        val sessions = CopyOnWriteArrayList<DeviceSession>()
        show(FakeServer(listOf(DevicePollResult.Pending, DevicePollResult.Pending, DevicePollResult.Approved(session))), sessions)

        remote.waitForText("BCDF-GHJ1")
        remote.waitForText("account.example/tv")
        val qr = composeRule.onNode(hasContentDescription("QR code", substring = true)).captureToImage().asAndroidBitmap()
        assertEquals("https://account.example/tv?device=BCDFGHJ1", decode(qr))

        composeRule.waitUntil(10_000) { sessions.isNotEmpty() }
        assertEquals(session, sessions.single())
        remote.waitForText("Signed in")
    }

    @Test
    fun aRefusalOffersANewCode() {
        val server = FakeServer(listOf(DevicePollResult.Denied))
        show(server, CopyOnWriteArrayList())

        remote.waitForText("declined", 10_000)
        remote.waitForFocusOn("Get a new code")
        remote.select()
        remote.waitForText("BCDF-GHJ2")
        assertEquals(2, server.codes)
        composeRule.waitUntil(5_000) { composeRule.onAllNodes(hasText("declined", substring = true)).fetchSemanticsNodes().isEmpty() }
    }

    private fun decode(image: Bitmap): String {
        val pixels = IntArray(image.width * image.height)
        image.copy(Bitmap.Config.ARGB_8888, false).getPixels(pixels, 0, image.width, 0, 0, image.width, image.height)
        val source = RGBLuminanceSource(image.width, image.height, pixels)
        return QRCodeReader().decode(BinaryBitmap(HybridBinarizer(source))).text
    }
}
