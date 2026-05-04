@file:JvmName("BitmapUtils")

package com.baidu.paddle.lite.demo.common

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import androidx.exifinterface.media.ExifInterface

fun decodeBitmapWithOrientation(contentResolver: ContentResolver, uri: Uri): Bitmap? {
    val bitmap = contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it) }
        ?: return null
    val orientation = contentResolver.openInputStream(uri)?.use {
        ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
    } ?: ExifInterface.ORIENTATION_NORMAL
    val degrees = when (orientation) {
        ExifInterface.ORIENTATION_ROTATE_90  -> 90f
        ExifInterface.ORIENTATION_ROTATE_180 -> 180f
        ExifInterface.ORIENTATION_ROTATE_270 -> 270f
        else -> return bitmap
    }
    return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, Matrix().apply { postRotate(degrees) }, true)
        .also { if (it !== bitmap) bitmap.recycle() }
}
