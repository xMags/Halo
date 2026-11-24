package moe.ditto.halo.tv.signin

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.ktor.client.HttpClient
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import moe.ditto.halo.PlatformDependencies
import moe.ditto.halo.auth.DeviceSession
import moe.ditto.halo.auth.DeviceSignInOffer
import moe.ditto.halo.auth.KtorLocalDeviceSignIn
import moe.ditto.halo.auth.LocalAuthenticator
import moe.ditto.halo.auth.deviceSignInOffer
import moe.ditto.halo.auth.LoginPresenter
import moe.ditto.halo.auth.PasswordSignInOutcome
import moe.ditto.halo.auth.signInWithPassword
import moe.ditto.halo.tv.kit.TvButton
import moe.ditto.halo.tv.kit.TvButtonStyle
import moe.ditto.halo.tv.kit.TvMetrics
import moe.ditto.halo.tv.kit.TvRowSurface
import moe.ditto.halo.tv.kit.TvTextField
import moe.ditto.halo.tv.kit.TvType
import moe.ditto.halo.ui.HaloColors

/**
 * What a TV says when the typed server signs in through a web page: a TV has
 * no browser to open, so it cannot finish that sign-in.
 */
internal const val TvWebSignInMessage =
    "This server signs in through a web page, which a TV cannot open. " +
        "Continue without an account, or use a server that has its own usernames and passwords."

/**
 * The TV sign-in. When the server lets a phone approve the TV, that leads:
 * a QR code and a short code, with a password and no account as the other
 * choices. Otherwise: a password on a server that has its own accounts, or
 * continue without an account.
 *
 * Signing in hands the session to [localAuthenticator] or [onDeviceSession]
 * (both the session controller), whose state change moves the app to the
 * shell, exactly as on the phone.
 */
@Composable
internal fun TvSignInScreen(
    dependencies: PlatformDependencies,
    localAuthenticator: LocalAuthenticator,
    /** Prefilled in the password form: a debug override, the last server used, or this build's own. */
    serverUrl: String,
    notice: String?,
    onDeviceSession: (serverUrl: String, session: DeviceSession) -> Unit,
    onContinueWithoutAccount: () -> Unit,
) {
    val presenter = remember {
        LoginPresenter(
            authConfigSource = dependencies.authConfigSource,
            nativeHostRequests = dependencies.nativeHostRequests,
            localAuthenticator = localAuthenticator,
        )
    }
    val scope = rememberCoroutineScope()
    var showForm by remember { mutableStateOf(false) }
    var server by remember { mutableStateOf(serverUrl) }
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    // What the built-in or remembered server offers for signing in with a
    // phone; null while asking, when it offers nothing, or when it cannot be
    // reached (the password form then says why).
    var phoneOffer by remember { mutableStateOf<DeviceSignInOffer?>(null) }
    // The choices wait for the server's answer (briefly), so they do not
    // rearrange under the remote once it arrives.
    var offerChecked by remember { mutableStateOf(serverUrl.isBlank()) }
    val phoneLayout = !showForm && offerChecked && phoneOffer != null
    val localDeviceSignIn = remember { KtorLocalDeviceSignIn(HttpClient()) }
    LaunchedEffect(serverUrl) {
        if (serverUrl.isBlank()) return@LaunchedEffect
        phoneOffer = withTimeoutOrNull(OfferWaitMs) {
            runCatching { dependencies.authConfigSource.fetch(serverUrl).deviceSignInOffer(localDeviceSignIn) }.getOrNull()
        }
        offerChecked = true
    }

    fun submit() {
        if (busy) return
        if (server.isBlank() || username.isBlank() || password.isEmpty()) {
            error = "Enter the server address, your username and your password."
            return
        }
        busy = true
        error = null
        scope.launch {
            when (val outcome = presenter.signInWithPassword(server, username, password)) {
                // The session controller moves the app on; nothing to do here.
                PasswordSignInOutcome.SignedIn -> Unit
                is PasswordSignInOutcome.Failed -> error = outcome.message
                PasswordSignInOutcome.NotPasswordServer -> error = TvWebSignInMessage
            }
            busy = false
        }
    }

    BackHandler(enabled = showForm) {
        showForm = false
        error = null
    }

    Box(Modifier.fillMaxSize().background(HaloColors.Background)) {
        Row(
            Modifier
                .fillMaxSize()
                .padding(horizontal = TvMetrics.SideMargin + 24.dp, vertical = TvMetrics.VerticalMargin + 16.dp),
            horizontalArrangement = Arrangement.spacedBy(if (phoneLayout) 48.dp else 64.dp),
        ) {
            // Narrower beside the QR code, which needs the room for its code.
            Column(Modifier.width(if (phoneLayout) 300.dp else 360.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Brand()
                Text(
                    if (showForm) "Sign in with a password" else "Sign in to Halo",
                    style = TvType.Hero,
                    modifier = Modifier.padding(top = 24.dp),
                )
                Text(
                    when {
                        showForm -> "For a Halo server that has its own accounts. Press select on a field to type."
                        phoneOffer != null -> "Approve this TV on your phone. Your library, history and addons follow your account to every screen."
                        else -> "Your library, history and addons follow your account to every screen."
                    },
                    style = TvType.Body,
                )
                if (phoneLayout) {
                    SecondaryChoices(
                        onUsePassword = { showForm = true },
                        onContinueWithoutAccount = onContinueWithoutAccount,
                    )
                }
            }
            Column(
                Modifier.weight(1f).padding(top = 72.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                notice?.let { ErrorLine(it) }
                if (showForm) {
                    PasswordForm(
                        server = server,
                        username = username,
                        password = password,
                        busy = busy,
                        error = error,
                        onServer = { server = it; error = null },
                        onUsername = { username = it; error = null },
                        onPassword = { password = it; error = null },
                        onSubmit = ::submit,
                        onBack = {
                            showForm = false
                            error = null
                        },
                    )
                } else if (offerChecked) {
                    val offer = phoneOffer
                    if (offer != null) {
                        TvPhoneSignIn(
                            offer = offer,
                            serverUrl = serverUrl,
                            onSession = { session -> onDeviceSession(serverUrl, session) },
                        )
                    } else {
                        Choices(
                            onUsePassword = { showForm = true },
                            onContinueWithoutAccount = onContinueWithoutAccount,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun Brand() {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        Box(
            Modifier.size(44.dp).background(HaloColors.Accent, RoundedCornerShape(12.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Text("H", color = Color.White, fontSize = 19.sp, fontWeight = FontWeight.ExtraBold)
        }
        Text("Halo", color = HaloColors.Text, fontSize = 22.sp, fontWeight = FontWeight.ExtraBold)
    }
}

@Composable
private fun Choices(onUsePassword: () -> Unit, onContinueWithoutAccount: () -> Unit) {
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { first.requestFocus() } }
    TvRowSurface(
        title = "Use a username and password",
        hint = "For a Halo server that has its own accounts",
        onClick = onUsePassword,
        modifier = Modifier.focusRequester(first),
    )
    TvRowSurface(
        title = "Continue without an account",
        hint = "Add your own addons. Sign in later from Settings.",
        onClick = onContinueWithoutAccount,
    )
    Text(
        "Signing in with your phone is coming to Halo on TV.",
        style = TvType.Caption,
        modifier = Modifier.padding(top = 8.dp, start = 4.dp),
    )
}

/** The other ways in, below the phone sign-in, in the same order as without it. */
@Composable
private fun SecondaryChoices(onUsePassword: () -> Unit, onContinueWithoutAccount: () -> Unit) {
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { first.requestFocus() } }
    Column(Modifier.padding(top = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        TvButton("Use a username and password", onClick = onUsePassword, modifier = Modifier.focusRequester(first))
        TvButton("Continue without an account", onClick = onContinueWithoutAccount)
    }
}

private const val OfferWaitMs = 4_000L

@Composable
private fun PasswordForm(
    server: String,
    username: String,
    password: String,
    busy: Boolean,
    error: String?,
    onServer: (String) -> Unit,
    onUsername: (String) -> Unit,
    onPassword: (String) -> Unit,
    onSubmit: () -> Unit,
    onBack: () -> Unit,
) {
    val first = remember { FocusRequester() }
    val signIn = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { first.requestFocus() } }
    TvTextField(
        value = server,
        onValueChange = onServer,
        label = "Server address",
        placeholder = "https://halo.example.com",
        keyboardType = KeyboardType.Uri,
        imeAction = ImeAction.Next,
        outerFocus = first,
    )
    TvTextField(value = username, onValueChange = onUsername, label = "Username", imeAction = ImeAction.Next)
    TvTextField(
        value = password,
        onValueChange = onPassword,
        label = "Password",
        password = true,
        imeAction = ImeAction.Done,
        onSubmit = onSubmit,
        // Back sits nearer the field's middle; after the password comes Sign in.
        down = signIn,
    )
    error?.let { ErrorLine(it) }
    Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        TvButton(
            if (busy) "Signing in…" else "Sign in",
            onClick = onSubmit,
            modifier = Modifier.focusRequester(signIn),
            style = TvButtonStyle.Primary,
            enabled = !busy,
        )
        TvButton("Back", onClick = onBack)
    }
}

@Composable
private fun ErrorLine(text: String) {
    Text(
        text,
        color = HaloColors.Danger,
        fontSize = 15.sp,
        lineHeight = 20.sp,
        modifier = Modifier
            .fillMaxWidth()
            .semantics { liveRegion = LiveRegionMode.Polite },
    )
}
