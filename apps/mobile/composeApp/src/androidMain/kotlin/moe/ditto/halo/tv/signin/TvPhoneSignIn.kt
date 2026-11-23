package moe.ditto.halo.tv.signin

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import moe.ditto.halo.auth.DeviceSession
import moe.ditto.halo.auth.DeviceSignInOffer
import moe.ditto.halo.auth.DeviceSignInPresenter
import moe.ditto.halo.auth.DeviceSignInState
import moe.ditto.halo.auth.EpochClock
import moe.ditto.halo.auth.SystemEpochClock
import moe.ditto.halo.tv.kit.TvButton
import moe.ditto.halo.tv.kit.TvButtonStyle
import moe.ditto.halo.tv.kit.TvType
import moe.ditto.halo.ui.HaloColors

/**
 * Signing in with a phone: a QR code of the address, the address and code to
 * type for a phone that cannot scan, and a countdown. Approval on the phone
 * hands the session to [onSession]; a refusal or a code that ran out offers a
 * new code, which then has focus.
 *
 * Polls only while on screen.
 */
@Composable
internal fun TvPhoneSignIn(
    offer: DeviceSignInOffer,
    serverUrl: String,
    onSession: (DeviceSession) -> Unit,
    modifier: Modifier = Modifier,
    clock: EpochClock = SystemEpochClock,
) {
    val scope = rememberCoroutineScope()
    val currentOnSession by rememberUpdatedState(onSession)
    val presenter = remember(offer, serverUrl) {
        DeviceSignInPresenter(
            signIn = offer.signIn,
            serverUrl = serverUrl,
            config = offer.config,
            scope = scope,
            clock = clock,
            onApproved = { currentOnSession(it) },
        )
    }
    DisposableEffect(presenter) {
        presenter.start()
        onDispose { presenter.cancel() }
    }
    val state by presenter.state.collectAsState()

    Row(modifier, horizontalArrangement = Arrangement.spacedBy(28.dp), verticalAlignment = Alignment.CenterVertically) {
        when (val shown = state) {
            is DeviceSignInState.Showing -> {
                TvQrCode(shown.qrUrl, size = QrSize)
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Scan with your phone's camera, or go to", style = TvType.Body)
                    Text(shown.verificationUrl.removePrefix("https://"), style = TvType.Label)
                    Text("and enter", style = TvType.Body, modifier = Modifier.padding(top = 4.dp))
                    Text(
                        shown.userCode,
                        color = HaloColors.Text,
                        fontSize = 34.sp,
                        fontWeight = FontWeight.ExtraBold,
                        fontFamily = FontFamily.Monospace,
                        letterSpacing = 2.sp,
                    )
                    Text("Code expires in ${formatCountdown(shown.secondsLeft)}", style = TvType.Caption)
                }
            }
            DeviceSignInState.Starting -> Text("Getting a code…", style = TvType.Body, modifier = Modifier.size(QrSize))
            DeviceSignInState.Approved -> Text("Signed in. Opening Halo…", style = TvType.Body)
            DeviceSignInState.Expired -> Retry("This code has expired.", presenter::start)
            DeviceSignInState.Denied -> Retry("Sign-in was declined on the phone.", presenter::start)
            is DeviceSignInState.Failed -> Retry(shown.message, presenter::start)
        }
    }
}

@Composable
private fun Retry(message: String, onNewCode: () -> Unit) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(message) { runCatching { focus.requestFocus() } }
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(message, style = TvType.Body, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
        TvButton("Get a new code", onClick = onNewCode, style = TvButtonStyle.Primary, modifier = Modifier.focusRequester(focus))
    }
}

/** "9:42". */
private fun formatCountdown(seconds: Int): String = "${seconds / 60}:${(seconds % 60).toString().padStart(2, '0')}"

private val QrSize = 248.dp
