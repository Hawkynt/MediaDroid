package com.hawkynt.mediadroyd.desktop

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.res.loadXmlImageVector
import androidx.compose.ui.unit.Density
import androidx.compose.ui.use
import org.jetbrains.skia.EncodedImageFormat
import org.xml.sax.InputSource
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File

/**
 * The desktop icons drawn from the Android launcher icon's own layers (app/src/main/res/drawable): a 256-pixel PNG
 * for Linux and an ICO holding it for Windows, written to desktop/icons. `./gradlew :desktop:icons` after the launcher
 * icon changes.
 */
fun main(args: Array<String>) {
    val root = File(args.firstOrNull() ?: ".")
    fun layer(name: String) = File(root, "app/src/main/res/drawable/$name.xml").inputStream()
        .use { loadXmlImageVector(InputSource(it), Density(1f)) }
    val background = layer("ic_launcher_background")
    val foreground = layer("ic_launcher_foreground")
    val size = 256
    val png = ImageComposeScene(size, size).use { scene ->
        scene.setContent {
            Box(Modifier.fillMaxSize().clip(RoundedCornerShape(percent = 22))) {
                Image(rememberVectorPainter(background), null, Modifier.fillMaxSize())
                Image(rememberVectorPainter(foreground), null, Modifier.fillMaxSize())
            }
        }
        scene.render().encodeToData(EncodedImageFormat.PNG)!!.bytes
    }
    val out = File(root, "desktop/icons").apply { mkdirs() }
    File(out, "mediadroyd.png").writeBytes(png)
    // An ICO of one 256-pixel PNG entry (width and height 0 mean 256), as Windows reads since Vista.
    val ico = ByteArrayOutputStream()
    DataOutputStream(ico).apply {
        fun le16(v: Int) { write(v and 0xFF); write(v shr 8 and 0xFF) }
        fun le32(v: Int) { le16(v and 0xFFFF); le16(v ushr 16) }
        le16(0); le16(1); le16(1)
        write(0); write(0); write(0); write(0); le16(1); le16(32); le32(png.size); le32(6 + 16)
        write(png)
    }
    File(out, "mediadroyd.ico").writeBytes(ico.toByteArray())
    println("wrote ${out.path}")
}
