package org.thepacket.meshcore.app.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import com.google.zxing.BarcodeFormat
import com.journeyapps.barcodescanner.BarcodeEncoder

/**
 * Encode [content] as a square QR [ImageBitmap], or null if it won't fit (e.g. too long).
 * Cached per content+size so it isn't re-encoded on every recomposition.
 */
@Composable
fun rememberQrBitmap(content: String, sizePx: Int = 640): ImageBitmap? =
    remember(content, sizePx) {
        if (content.isBlank()) null
        else runCatching {
            BarcodeEncoder().encodeBitmap(content, BarcodeFormat.QR_CODE, sizePx, sizePx).asImageBitmap()
        }.getOrNull()
    }
