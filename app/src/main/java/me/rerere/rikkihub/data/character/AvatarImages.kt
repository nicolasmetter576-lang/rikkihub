package me.rerere.rikkihub.data.character

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.ByteArrayOutputStream

/** 把 JPEG / WebP 等卡面转成 PNG（导出 PNG 卡用）；解不开返回 null。 */
fun imageBytesToPng(bytes: ByteArray, @Suppress("UNUSED_PARAMETER") ext: String?): ByteArray? = runCatching {
    val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return null
    try {
        ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
    } finally {
        bitmap.recycle()
    }
}.getOrNull()
