package com.hawkynt.mediadroyd.ui

import android.graphics.Bitmap
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import android.view.SurfaceView
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hawkynt.mediadroyd.player.Snapshot
import com.hawkynt.mediadroyd.player.VideoFilterStage
import java.nio.ByteBuffer
import kotlin.coroutines.resume
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext

/** The picture on screen as [stage] draws it, filters and all, read back from it; null where it gives none. */
internal suspend fun stagePicture(stage: VideoFilterStage): Bitmap? {
    val read = suspendCancellableCoroutine<VideoFilterStage.ReadBack?> { done -> stage.snapshot { done.resume(it) } }
        ?: return null
    return withContext(Dispatchers.Default) {
        val rgba = Snapshot.upright(read.rgba, read.width, read.height)
        Bitmap.createBitmap(read.width, read.height, Bitmap.Config.ARGB_8888).apply {
            // ARGB_8888 keeps its bytes in memory as R, G, B, A: the read-back's own order.
            copyPixelsFromBuffer(ByteBuffer.wrap(rgba))
        }
    }
}

/**
 * The picture [view] shows, copied as it is — the decoder's own frame where the stage stands aside (no filter set,
 * HDR on an HDR screen); null where the system cannot copy it.
 */
internal suspend fun viewPicture(view: SurfaceView?): Bitmap? {
    if (view == null || view.width <= 0 || view.height <= 0 || !view.holder.surface.isValid) return null
    val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
    val result = suspendCancellableCoroutine { done ->
        runCatching {
            PixelCopy.request(view, bitmap, { done.resume(it) }, Handler(Looper.getMainLooper()))
        }.onFailure { done.resume(PixelCopy.ERROR_UNKNOWN) }
    }
    return bitmap.takeIf { result == PixelCopy.SUCCESS }
}

/** The snapshot just taken: the picture small, where it went, and a button to share it ([uri] null: not shareable). */
@Composable
internal fun SnapshotCard(picture: ImageBitmap, uri: Uri?, onShare: (Uri) -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier
            .background(Color.Black.copy(alpha = 0.75f), RoundedCornerShape(12.dp))
            .padding(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Image(
            picture,
            contentDescription = "The picture just taken",
            modifier = Modifier.height(72.dp)
                .aspectRatio(picture.width.toFloat() / picture.height.coerceAtLeast(1))
                .clip(RoundedCornerShape(6.dp)),
        )
        Spacer(Modifier.width(12.dp))
        Column {
            Text("Saved in Pictures", color = Color.White, fontSize = 16.sp)
            if (uri != null) {
                Button(onClick = { onShare(uri) }, modifier = Modifier.padding(top = 6.dp)) {
                    Icon(Icons.Filled.Share, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Share")
                }
            }
        }
    }
}
