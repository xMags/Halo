package moe.ditto.halo.screens.signin

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import moe.ditto.halo.PlatformDependencies
import moe.ditto.halo.auth.AddonAuthenticator
import moe.ditto.halo.auth.AuthEvent
import moe.ditto.halo.auth.LocalAuthenticator
import moe.ditto.halo.auth.LoginPhase
import moe.ditto.halo.auth.LoginPresenter
import moe.ditto.halo.auth.LoginState
import moe.ditto.halo.resources.Res
import moe.ditto.halo.resources.halo_mark
import moe.ditto.halo.ui.HaloColors
import moe.ditto.halo.ui.HaloDimensions
import moe.ditto.halo.ui.HaloIcons
import moe.ditto.halo.ui.monoStyle
import org.jetbrains.compose.resources.painterResource

/** Where "Need help?" goes; the same page as the desktop app's. */
private const val HelpUrl = "https://github.com/xMags/Halo/issues"

/*
 * The design's teal (#4CC2FF, HaloColors.AccentLight) carries the title's
 * accent word, the links and the chip icons, as the approved sign-in design
 * draws them; the token's "never text" rule is the shell's, and this screen
 * matches the desktop sign-in instead.
 */
private val Teal = HaloColors.AccentLight
private val Ink2 = HaloColors.Text.copy(alpha = 0.8f)
private val Ink3 = HaloColors.Text.copy(alpha = 0.62f)
private val Stroke = Color.White.copy(alpha = 0.11f)
private val OnlineGreen = Color(0xFF5DD39E)

private const val WaitingCopy = "Approve the sign-in in the page that just opened, then come back to Halo."
private const val PasswordOnlyMessage =
    "That server signs in through a web page, not a username and password."

/**
 * The product sign-in: the backdrop and the poster fan behind a glass card.
 *
 * Two presenters run it. The main one asks [serverUrl] (this build's server)
 * how it signs in as soon as the screen opens, and offers that: the add-on's
 * button, a browser button, or a username and password form for a server with
 * local accounts. Nothing opens the browser until the user taps. The second
 * one sits behind "Use username & password", for a self-hosted server with
 * local accounts: it asks the address the user types and accepts only a
 * server that signs in with a password.
 *
 * Signing in hands the session to [localAuthenticator] or [addonAuthenticator]
 * (the session controller), whose state change moves the app to the shell.
 */
@Composable
internal fun SignInScreen(
    dependencies: PlatformDependencies,
    localAuthenticator: LocalAuthenticator,
    addonAuthenticator: AddonAuthenticator,
    authEvents: Flow<AuthEvent>,
    serverUrl: String,
    rememberedServerUrl: String?,
    notice: String?,
    onOpenGate: (() -> Unit)?,
) {
    fun presenter() = LoginPresenter(
        authConfigSource = dependencies.authConfigSource,
        nativeHostRequests = dependencies.nativeHostRequests,
        localAuthenticator = localAuthenticator,
        addonAuthenticator = addonAuthenticator,
    )
    val main = remember { presenter().also { it.editServerUrl(serverUrl) } }
    var mainState by remember { mutableStateOf(main.state) }
    val custom = remember { presenter() }
    val scope = rememberCoroutineScope()
    var addonJob by remember { mutableStateOf<Job?>(null) }

    var discovery by remember { mutableIntStateOf(0) }
    LaunchedEffect(discovery) {
        main.continueFromServer(startOidc = false)
        mainState = main.state
    }
    LaunchedEffect(Unit) {
        authEvents.collect { event ->
            main.onAuthEvent(event)
            mainState = main.state
        }
    }

    var form by remember { mutableStateOf(false) }
    var formServer by remember { mutableStateOf(rememberedServerUrl?.takeIf { it != serverUrl }.orEmpty()) }
    var formUsername by remember { mutableStateOf("") }
    var formPassword by remember { mutableStateOf("") }
    var formBusy by remember { mutableStateOf(false) }
    var formError by remember { mutableStateOf<String?>(null) }
    var formSignedIn by remember { mutableStateOf(false) }

    fun submitForm() {
        if (formBusy) return
        formBusy = true
        formError = null
        scope.launch {
            custom.editServerUrl(formServer)
            custom.continueFromServer(startOidc = false)
            when (custom.state.phase) {
                is LoginPhase.LocalCredentials -> {
                    custom.editUsername(formUsername)
                    custom.editPassword(formPassword)
                    custom.submitLocalCredentials()
                    if (custom.state.phase is LoginPhase.LocalSignedIn) {
                        formSignedIn = true
                    } else {
                        formError = custom.state.error ?: "Could not sign in."
                    }
                }
                LoginPhase.Server -> formError = custom.state.error ?: "Could not reach that server."
                else -> formError = PasswordOnlyMessage
            }
            formBusy = false
        }
    }

    val phase = mainState.phase
    val signedIn = formSignedIn || phase is LoginPhase.LocalSignedIn || phase is LoginPhase.AddonSignedIn ||
        phase is LoginPhase.OidcSucceeded
    val showForm = form && !signedIn

    Box(Modifier.fillMaxSize()) {
        SignInBackdrop()
        SignInPosterFan(Modifier.align(Alignment.TopCenter).statusBarsPadding())

        val entrance = remember { Animatable(0f) }
        LaunchedEffect(Unit) { entrance.animateTo(1f, tween(700, easing = SignInEase)) }

        // Anchored to the bottom and sized to its content, so a taller state
        // grows over the posters; it scrolls when the keyboard leaves no room.
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .widthIn(max = HaloDimensions.LoginMaxWidth)
                .fillMaxWidth()
                .padding(start = 14.dp, end = 14.dp, top = 24.dp, bottom = 18.dp)
                .graphicsLayer {
                    alpha = entrance.value
                    translationY = (1f - entrance.value) * 14.dp.toPx()
                }
                .clip(RoundedCornerShape(28.dp))
                .background(
                    Brush.verticalGradient(
                        listOf(Color(0xD118_1B23), Color(0xE610_1218)),
                    ),
                )
                .border(1.dp, Stroke, RoundedCornerShape(28.dp))
                .verticalScroll(rememberScrollState())
                .padding(start = 22.dp, end = 22.dp, top = 24.dp, bottom = 4.dp),
        ) {
            CardMeta()
            Title()
            if (!showForm) Intro()

            Column(
                modifier = Modifier.padding(top = 24.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                notice?.let { ErrorLine(it) }
                when {
                    signedIn -> SignedIn()
                    showForm -> PasswordForm(
                        server = formServer,
                        username = formUsername,
                        password = formPassword,
                        busy = formBusy,
                        error = formError,
                        onServer = { formServer = it; formError = null },
                        onUsername = { formUsername = it; formError = null },
                        onPassword = { formPassword = it; formError = null },
                        onSubmit = ::submitForm,
                        backLabel = when (phase) {
                            is LoginPhase.AddonReady -> "${phase.config.addon.buttonLabel} instead"
                            is LoginPhase.OidcReady -> "Continue in browser instead"
                            else -> "Back"
                        },
                        onBack = { form = false; formError = null },
                    )
                    else -> MainActions(
                        state = mainState,
                        onRetry = {
                            main.editServerUrl(serverUrl)
                            mainState = main.state
                            discovery += 1
                        },
                        onBeginAddon = {
                            val attempt = main.beginAddon()
                            if (attempt != null) {
                                mainState = main.state
                                addonJob?.cancel()
                                addonJob = scope.launch {
                                    main.completeAddon(attempt)
                                    mainState = main.state
                                }
                            }
                        },
                        onCancelAddon = {
                            addonJob?.cancel()
                            addonJob = null
                            main.cancelAddon()
                            mainState = main.state
                        },
                        onBeginOidc = {
                            main.beginOidc()
                            mainState = main.state
                        },
                        onRetryOidc = {
                            main.retryOidc()
                            mainState = main.state
                        },
                        onUsername = {
                            main.editUsername(it)
                            mainState = main.state
                        },
                        onPassword = {
                            main.editPassword(it)
                            mainState = main.state
                        },
                        onSubmitLocal = {
                            scope.launch {
                                main.submitLocalCredentials()
                                mainState = main.state
                            }
                        },
                        onUsePassword = { form = true },
                    )
                }
                onOpenGate?.let { TextLink("Open gate menu", it) }
            }

            Footer(
                status = when {
                    phase == LoginPhase.Discovering -> ServerStatus.Connecting
                    phase == LoginPhase.Server && mainState.error != null -> ServerStatus.Unreachable
                    phase == LoginPhase.Server -> ServerStatus.Connecting
                    else -> ServerStatus.Online
                },
            )
        }
    }
}

@Composable
private fun CardMeta() {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Image(
            painter = painterResource(Res.drawable.halo_mark),
            contentDescription = null,
            modifier = Modifier.size(15.dp),
        )
        Text(
            "HALO",
            modifier = Modifier.padding(start = 7.dp),
            style = monoStyle(10.5.sp, FontWeight.Bold, HaloColors.Text, 1.5.sp),
        )
        Spacer(Modifier.weight(1f))
        Text("MEDIA CENTER", style = monoStyle(10.5.sp, FontWeight.Medium, Ink3, 1.5.sp))
    }
}

@Composable
private fun Title() {
    Text(
        text = buildAnnotatedString {
            append("Your shows,\n")
            withStyle(SpanStyle(color = Teal, fontStyle = FontStyle.Italic, fontWeight = FontWeight.Bold)) {
                append("everywhere.")
            }
        },
        // -1 dp: the display weight's side bearing at this size reads as an indent.
        modifier = Modifier.padding(top = 16.dp).offset(x = (-1).dp).semantics { heading() },
        style = TextStyle(
            color = HaloColors.Text,
            fontSize = 38.sp,
            lineHeight = 39.5.sp,
            fontWeight = FontWeight.Black,
            letterSpacing = (-1.1).sp,
        ),
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Intro() {
    Text(
        "Stream from your addons, download for the trip, and pick up where you left off.",
        modifier = Modifier.padding(top = 10.dp),
        style = TextStyle(color = Ink2, fontSize = 15.sp, lineHeight = 21.75.sp),
    )
    FlowRow(
        modifier = Modifier.padding(top = 14.dp),
        horizontalArrangement = Arrangement.spacedBy(5.dp),
        verticalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Chip(HaloIcons.Extension, "Addons")
        Chip(HaloIcons.DownloadOutline, "Downloads")
        Chip(HaloIcons.Subtitles, "Subtitles")
        Chip(HaloIcons.Refresh, "Sync")
    }
}

@Composable
private fun Chip(icon: ImageVector, label: String) {
    Row(
        modifier = Modifier
            .height(28.dp)
            .border(1.dp, Stroke, CircleShape)
            .background(Color.White.copy(alpha = 0.045f), CircleShape)
            .padding(horizontal = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = Teal, modifier = Modifier.size(13.dp))
        Text(label, style = TextStyle(color = Ink2, fontSize = 12.sp))
    }
}

@Composable
private fun MainActions(
    state: LoginState,
    onRetry: () -> Unit,
    onBeginAddon: () -> Unit,
    onCancelAddon: () -> Unit,
    onBeginOidc: () -> Unit,
    onRetryOidc: () -> Unit,
    onUsername: (String) -> Unit,
    onPassword: (String) -> Unit,
    onSubmitLocal: () -> Unit,
    onUsePassword: () -> Unit,
) {
    when (val phase = state.phase) {
        LoginPhase.Server, LoginPhase.Discovering -> {
            if (state.error != null && phase == LoginPhase.Server) {
                ErrorLine("Couldn’t reach the Halo server. Check your connection and try again.")
                QuietButton("Try again", onRetry)
            } else {
                StatusLine("CONNECTING", spinning = true)
            }
            TextLink("Use username & password", onUsePassword)
        }
        is LoginPhase.AddonReady -> {
            state.error?.let { ErrorLine(it) }
            PrimaryButton(phase.config.addon.buttonLabel, onBeginAddon, icon = HaloIcons.OpenInNew)
            TextLink("Use username & password", onUsePassword)
        }
        is LoginPhase.AddonSigningIn -> {
            Waiting()
            TextLink("Cancel", onCancelAddon)
        }
        is LoginPhase.OidcReady -> {
            PrimaryButton("Continue in browser", onBeginOidc, icon = HaloIcons.OpenInNew)
            TextLink("Use username & password", onUsePassword)
        }
        is LoginPhase.OidcRequested -> Waiting()
        is LoginPhase.OidcFailed -> {
            ErrorLine(phase.reason)
            PrimaryButton("Try again", onRetryOidc, icon = HaloIcons.OpenInNew)
        }
        // This build's own server uses local accounts: its form, without an
        // address to type, since the server is already known.
        is LoginPhase.LocalCredentials, is LoginPhase.LocalSubmitting -> {
            Field("Username", state.username, onUsername, KeyboardType.Text, ImeAction.Next)
            Field("Password", state.password, onPassword, KeyboardType.Password, ImeAction.Done, password = true)
            state.error?.let { ErrorLine(it) }
            PrimaryButton(
                "Sign in",
                onSubmitLocal,
                enabled = state.canSubmitCredentials,
                busy = phase is LoginPhase.LocalSubmitting,
            )
        }
        else -> Unit
    }
}

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
    backLabel: String,
    onBack: () -> Unit,
) {
    Field(
        "Server address",
        server,
        onServer,
        KeyboardType.Uri,
        ImeAction.Next,
        placeholder = "https://your-server.example",
    )
    Field("Username", username, onUsername, KeyboardType.Text, ImeAction.Next)
    Field("Password", password, onPassword, KeyboardType.Password, ImeAction.Done, password = true)
    error?.let { ErrorLine(it) }
    PrimaryButton(
        "Sign in",
        onSubmit,
        enabled = server.isNotBlank() && username.isNotBlank() && password.isNotBlank(),
        busy = busy,
    )
    TextLink(backLabel, onBack)
}

@Composable
private fun Waiting() {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Check your browser", style = TextStyle(color = HaloColors.Text, fontSize = 20.sp, fontWeight = FontWeight.Medium))
        Text(WaitingCopy, style = TextStyle(color = Ink2, fontSize = 14.5.sp, lineHeight = 21.75.sp))
        StatusLine("WAITING FOR BROWSER", spinning = true)
    }
}

@Composable
private fun SignedIn() {
    Text("You’re all set", style = TextStyle(color = HaloColors.Text, fontSize = 22.sp, fontWeight = FontWeight.Medium))
    StatusLine("OPENING YOUR LIBRARY…", spinning = true)
}

@Composable
private fun StatusLine(label: String, spinning: Boolean) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        if (spinning) {
            CircularProgressIndicator(
                modifier = Modifier.size(11.dp),
                color = Teal,
                trackColor = Color.White.copy(alpha = 0.18f),
                strokeWidth = 2.dp,
            )
        }
        Text(label, style = monoStyle(10.5.sp, FontWeight.Medium, Ink3, 1.5.sp))
    }
}

@Composable
private fun ErrorLine(text: String) {
    Text(text, style = TextStyle(color = HaloColors.Danger, fontSize = 13.5.sp, lineHeight = 19.sp))
}

@Composable
private fun Field(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    keyboardType: KeyboardType,
    imeAction: ImeAction,
    password: Boolean = false,
    placeholder: String? = null,
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(label, style = TextStyle(color = Ink2, fontSize = 13.sp))
        OutlinedTextField(
            modifier = Modifier.fillMaxWidth().semantics { contentDescription = label },
            value = value,
            onValueChange = onValueChange,
            singleLine = true,
            placeholder = placeholder?.let { { Text(it, color = HaloColors.Text.copy(alpha = 0.42f)) } },
            keyboardOptions = KeyboardOptions(keyboardType = keyboardType, imeAction = imeAction),
            visualTransformation = if (password) PasswordVisualTransformation() else VisualTransformation.None,
            colors = OutlinedTextFieldDefaults.colors(
                focusedTextColor = HaloColors.Text,
                unfocusedTextColor = HaloColors.Text,
                focusedContainerColor = HaloColors.FieldFill,
                unfocusedContainerColor = HaloColors.FieldFill,
                focusedBorderColor = HaloColors.Accent,
                unfocusedBorderColor = Color.White.copy(alpha = 0.14f),
                cursorColor = HaloColors.Accent,
            ),
            shape = RoundedCornerShape(12.dp),
        )
    }
}

@Composable
private fun PrimaryButton(
    label: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
    busy: Boolean = false,
    icon: ImageVector? = null,
) {
    val shape = RoundedCornerShape(14.dp)
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(52.dp)
            .clip(shape)
            .background(
                Brush.linearGradient(listOf(HaloColors.Accent, HaloColors.AccentLight)),
                alpha = if (enabled) 1f else 0.5f,
            )
            .clickable(enabled = enabled && !busy, role = Role.Button, onClick = onClick)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        if (busy) {
            CircularProgressIndicator(Modifier.size(20.dp), color = HaloColors.OnAccent, strokeWidth = 2.dp)
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(9.dp), verticalAlignment = Alignment.CenterVertically) {
                icon?.let { Icon(it, contentDescription = null, tint = HaloColors.OnAccent, modifier = Modifier.size(16.dp)) }
                Text(label, style = TextStyle(color = HaloColors.OnAccent, fontSize = 16.sp, fontWeight = FontWeight.Medium))
            }
        }
    }
}

@Composable
private fun QuietButton(label: String, onClick: () -> Unit) {
    val shape = RoundedCornerShape(14.dp)
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(52.dp)
            .clip(shape)
            .background(Color.White.copy(alpha = 0.06f))
            .border(1.dp, Stroke, shape)
            .clickable(role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, style = TextStyle(color = HaloColors.Text, fontSize = 16.sp, fontWeight = FontWeight.Medium))
    }
}

/** A secondary action, centred under the full-width buttons on their label's axis. */
@Composable
private fun TextLink(label: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 44.dp)
            .clickable(role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, style = TextStyle(color = Teal, fontSize = 14.sp))
    }
}

private enum class ServerStatus(val label: String, val dot: Color) {
    Connecting("CONNECTING", Color.White.copy(alpha = 0.45f)),
    Online("SERVER ONLINE", OnlineGreen),
    Unreachable("SERVER UNREACHABLE", HaloColors.Danger),
}

@Composable
private fun Footer(status: ServerStatus) {
    val uriHandler = LocalUriHandler.current
    Column(Modifier.padding(top = 16.dp)) {
        Box(Modifier.fillMaxWidth().height(1.dp).background(Color.White.copy(alpha = 0.07f)))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(7.dp).background(status.dot, CircleShape))
            Text(
                status.label,
                modifier = Modifier.padding(start = 8.dp),
                style = monoStyle(10.5.sp, FontWeight.Medium, Ink3, 1.5.sp),
            )
            Spacer(Modifier.weight(1f))
            // Padded to a 44 dp tap target; same face and size as the status,
            // so the two share a baseline.
            Text(
                "NEED HELP?",
                modifier = Modifier
                    .clickable(role = Role.Button) { uriHandler.openUri(HelpUrl) }
                    .padding(vertical = 16.dp),
                style = monoStyle(10.5.sp, FontWeight.Medium, Teal, 1.5.sp),
            )
        }
    }
}
