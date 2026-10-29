package moe.ditto.halo.screens

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import moe.ditto.halo.SessionGraph
import moe.ditto.halo.api.Me
import moe.ditto.halo.cache.QueryState
import moe.ditto.halo.ui.HaloColors
import moe.ditto.halo.ui.HaloIcons
import org.jetbrains.compose.resources.decodeToImageBitmap

/**
 * The account's profile picture, at the end of Home's title as Last Music shows
 * it; tapping it opens Settings, where the account lives.
 *
 * The picture is fetched only when `Me.avatarVersion` names one, once per
 * fingerprint (see `AccountRepository.avatar`). Signed in without a picture it
 * shows the account's initial; without an account, a person glyph, since
 * Settings is where signing in starts. A picture that fails to load or decode
 * falls back the same way rather than leaving a hole.
 */
@Composable
internal fun AccountAvatar(
    graph: SessionGraph,
    /** Null where the picture only shows who is signed in, as on Settings' account card. */
    onClick: (() -> Unit)?,
    modifier: Modifier = Modifier,
    size: Dp = DefaultAvatarSize,
) {
    val me by remember(graph) { graph.account.observe() }.collectAsState(QueryState())
    val account = me.value
    val version = account?.avatarVersion
    val picture by produceState<ImageBitmap?>(null, graph, version) {
        value = null
        if (version == null) return@produceState
        value = try {
            graph.account.avatar(version)?.let { bytes ->
                withContext(Dispatchers.Default) { bytes.decodeToImageBitmap() }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null
        }
    }

    val name = account?.username.orEmpty()
    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .background(HaloColors.SurfaceHigh)
            .border(1.dp, HaloColors.GlassBorder, CircleShape)
            .then(
                if (onClick == null) {
                    Modifier
                } else {
                    Modifier.clickable(
                        role = Role.Button,
                        onClickLabel = if (name.isNotEmpty()) "$name: account and settings" else "Account and settings",
                        onClick = onClick,
                    )
                },
            ),
        contentAlignment = Alignment.Center,
    ) {
        val bitmap = picture
        when {
            bitmap != null -> Image(
                bitmap = bitmap,
                contentDescription = if (name.isNotEmpty()) name else "Your profile picture",
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
            name.isNotEmpty() -> Text(
                text = name.take(1).uppercase(),
                color = HaloColors.Text,
                fontSize = (size.value * 0.4f).sp,
                fontWeight = FontWeight.SemiBold,
            )
            else -> Icon(
                imageVector = HaloIcons.Person,
                contentDescription = "Account and settings",
                tint = HaloColors.TextDim,
                modifier = Modifier.size(size * 0.55f),
            )
        }
    }
}

private val DefaultAvatarSize = 44.dp

/** The username with its first letter raised, as the desktop's account card shows it. */
internal fun accountDisplayName(username: String): String =
    username.replaceFirstChar { it.uppercaseChar() }

/**
 * The mono caption under the account name: the account's tier when the server
 * has tiers (`PREMIUM`), else the account kind and whether it administers this
 * server. The desktop's `accountLabel`.
 */
internal fun accountLabel(me: Me?): String = when {
    me?.plan != null -> me.plan.uppercase()
    me?.isAdmin == true -> "ADMIN · HALO ACCOUNT"
    else -> "HALO ACCOUNT"
}
