package moe.ditto.halo.tv.kit

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import moe.ditto.halo.resources.Res
import moe.ditto.halo.resources.halo_mark
import org.jetbrains.compose.resources.painterResource

/** The Halo mark, the same artwork the phone and tablet app show. */
@Composable
internal fun TvHaloMark(size: Dp, modifier: Modifier = Modifier) {
    Image(
        painter = painterResource(Res.drawable.halo_mark),
        contentDescription = null,
        modifier = modifier.size(size),
        contentScale = ContentScale.Fit,
    )
}
