package com.example.imagegallerysaver

import android.annotation.TargetApi
import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.text.TextUtils
import android.text.format.DateUtils
import android.webkit.MimeTypeMap
import io.flutter.embedding.engine.plugins.FlutterPlugin
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import io.flutter.plugin.common.MethodChannel.MethodCallHandler
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.*

class ImageGallerySaverPlugin : FlutterPlugin, MethodCallHandler {
    private lateinit var methodChannel: MethodChannel
    private var applicationContext: Context? = null

    override fun onAttachedToEngine(binding: FlutterPlugin.FlutterPluginBinding) {
        this.applicationContext = binding.applicationContext
        methodChannel = MethodChannel(binding.binaryMessenger, "image_gallery_saver")
        methodChannel.setMethodCallHandler(this)
    }

    override fun onMethodCall(call: MethodCall, result: MethodChannel.Result) {
        when (call.method) {
            "saveImageToGallery" -> {
                val image = call.argument<ByteArray?>("imageBytes")
                val quality = call.argument<Int?>("quality")
                val name = call.argument<String?>("name")
                val folder = call.argument<String?>("folder")
                if (Build.VERSION.SDK_INT >= 30) {
                    result.success(
                        saveImageToGallery30(
                            applicationContext!!,
                            BitmapFactory.decodeByteArray(
                                image ?: ByteArray(0),
                                0,
                                image?.size ?: 0
                            ),
                            name ?: "",
                            folder = folder ?: "",
                        ),
                    )
                } else {
                    result.success(
                        saveImageToGallery(
                            BitmapFactory.decodeByteArray(
                                image ?: ByteArray(0),
                                0,
                                image?.size ?: 0,
                            ),
                            quality,
                            name ?: "",
                        ),
                    )
                }
            }

            "saveFileToGallery" -> {
                val path = call.argument<String?>("file")
                val name = call.argument<String?>("name")
                val folder = call.argument<String?>("folder")
                val isImage = call.argument<Boolean>("isImage") ?: true

                if (Build.VERSION.SDK_INT >= 30) {
                    result.success(
                        saveFileToGallery30(
                            applicationContext!!,
                            path,
                            name ?: "",
                            folder ?: "",
                            isImage,
                        ),
                    )
                } else {
                    result.success(saveFileToGallery(path, name))
                }
            }

            else -> result.notImplemented()
        }
    }

    override fun onDetachedFromEngine(binding: FlutterPlugin.FlutterPluginBinding) {
        applicationContext = null
        methodChannel.setMethodCallHandler(null)
    }


    private fun generateUri(extension: String = "", name: String? = null): Uri? {
        val fileName = name ?: System.currentTimeMillis().toString()
        val mimeType = getMIMEType(extension)
        val isVideo = mimeType?.startsWith("video") == true

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // >= android 10
            val uri = when {
                isVideo -> MediaStore.Video.Media.EXTERNAL_CONTENT_URI
                else -> MediaStore.Images.Media.EXTERNAL_CONTENT_URI
            }

            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                put(
                    MediaStore.MediaColumns.RELATIVE_PATH, when {
                        isVideo -> Environment.DIRECTORY_MOVIES
                        else -> Environment.DIRECTORY_PICTURES
                    }
                )
                if (!TextUtils.isEmpty(mimeType)) {
                    put(
                        when {
                            isVideo -> MediaStore.Video.Media.MIME_TYPE
                            else -> MediaStore.Images.Media.MIME_TYPE
                        }, mimeType
                    )
                }
            }
            applicationContext?.contentResolver?.insert(uri, values)
        } else {
            // < android 10
            val storePath =
                Environment.getExternalStoragePublicDirectory(
                    when {
                        isVideo -> Environment.DIRECTORY_MOVIES
                        else -> Environment.DIRECTORY_PICTURES
                    }
                ).absolutePath
            val appDir = File(storePath).apply {
                if (!exists()) {
                    mkdir()
                }
            }

            val file =
                File(appDir, if (extension.isNotEmpty()) "$fileName.$extension" else fileName)
            Uri.fromFile(file)
        }
    }

    /**
     * get file Mime Type
     *
     * @param extension extension
     * @return file Mime Type
     */
    private fun getMIMEType(extension: String): String? {
        return if (!TextUtils.isEmpty(extension)) {
            MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension.lowercase())
        } else {
            null
        }
    }


    private fun saveImageToGallery(
        bmp: Bitmap?,
        quality: Int?,
        name: String?,
    ): HashMap<String, Any?> {
        // check parameters
        if (bmp == null || quality == null) {
            return SaveResultModel(false, null, "parameters error").toHashMap()
        }
        // check applicationContext
        val context = applicationContext ?: return SaveResultModel(
            false,
            null,
            "applicationContext null"
        ).toHashMap()
        val currentTime: Long = System.currentTimeMillis()
        val imageDate: String =
            SimpleDateFormat("yyyyMMdd-HHmmss", Locale.getDefault()).format(Date(currentTime))
        val screenshotFileNameTemplate = "%s.jpg"
        val imageFileName = if(name.isNullOrEmpty()){
            String.format(screenshotFileNameTemplate, imageDate)
        } else{
            name
        }
        val fileUri = generateUri("jpg", name = imageFileName)
        var fos: OutputStream? = null
        var success = false
        try {
            if (fileUri != null) {
                fos = context.contentResolver.openOutputStream(fileUri)
                if (fos != null) {
                    println("ImageGallerySaverPlugin $quality")
                    bmp.compress(Bitmap.CompressFormat.JPEG, quality, fos)
                    fos.flush()
                    success = true
                }
            }
        } catch (e: IOException) {
            SaveResultModel(false, null, e.toString()).toHashMap()
        } finally {
            fos?.close()
            bmp.recycle()
        }
        return if (success) {
            SaveResultModel(fileUri.toString().isNotEmpty(), fileUri.toString(), null).toHashMap()
        } else {
            SaveResultModel(false, null, "saveImageToGallery fail").toHashMap()
        }
    }

    /**
     * android 10 以上版本
     */
    @TargetApi(Build.VERSION_CODES.Q)
    fun saveImageToGallery30(
        context: Context,
        image: Bitmap?,
        name: String?,
        folder: String = "",
    ): HashMap<String, Any?> {
        val currentTime: Long = System.currentTimeMillis()
        val imageDate: String =
            SimpleDateFormat("yyyyMMdd-HHmmss", Locale.getDefault()).format(Date(currentTime))
        val screenshotFileNameTemplate = "%s.png"
        val mImageFileName: String = name ?: String.format(screenshotFileNameTemplate, imageDate)
        val values = ContentValues()

        values.put(
            MediaStore.MediaColumns.RELATIVE_PATH,
            if (folder.isEmpty()) {
                Environment.DIRECTORY_PICTURES
            } else {
                "${Environment.DIRECTORY_PICTURES}${File.separator}$folder"
            },
        )
        values.put(MediaStore.MediaColumns.DISPLAY_NAME, mImageFileName)
        values.put(MediaStore.MediaColumns.MIME_TYPE, "image/png")
        values.put(MediaStore.MediaColumns.DATE_ADDED, currentTime / 1000)
        values.put(MediaStore.MediaColumns.DATE_MODIFIED, currentTime / 1000)
        values.put(
            MediaStore.MediaColumns.DATE_EXPIRES,
            (currentTime + DateUtils.DAY_IN_MILLIS) / 1000,
        )
        values.put(MediaStore.MediaColumns.IS_PENDING, 1)
        val resolver: ContentResolver = context.contentResolver
        val uri: Uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            ?: return SaveResultModel(false, null, "").toHashMap()

        try {
            resolver.openOutputStream(uri).use { out ->
                if (image != null && out != null) {
                    if (!image.compress(Bitmap.CompressFormat.PNG, 100, out)) {
                        return SaveResultModel(false, null, "").toHashMap()
                    }
                }
            }
            values.clear()
            values.put(MediaStore.MediaColumns.IS_PENDING, 0)
            values.putNull(MediaStore.MediaColumns.DATE_EXPIRES)
            resolver.update(uri, values, null, null)
        } catch (e: IOException) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                resolver.delete(uri, null)
            }
            return SaveResultModel(false, null, "").toHashMap()
        }
        return SaveResultModel(true, null, "").toHashMap()
    }

    @TargetApi(Build.VERSION_CODES.Q)
    private fun saveFileToGallery30(
        context: Context,
        filePath: String?,
        name: String,
        folder: String,
        isImage: Boolean = true,
    ): HashMap<String, Any?> {
        if (filePath == null) {
            return SaveResultModel(false, null, "parameters error").toHashMap()
        }
        var fileName = filePath
        if (filePath.contains('/')) {
            fileName = filePath.substringAfterLast("/")
        }
        var suffix = "png"
        if (fileName.contains(".")) {
            suffix = fileName.substringAfterLast(".", "")
        }
        val isImageSuffix = suffix.equals("png", ignoreCase = true)
                || suffix.equals("webp", ignoreCase = true)
                || suffix.equals("jpg", ignoreCase = true)
                || suffix.equals("jpeg", ignoreCase = true)
                || suffix.equals("heic", ignoreCase = true)
                || suffix.equals("gif", ignoreCase = true)
                || suffix.equals("apng", ignoreCase = true)
                || suffix.equals("raw", ignoreCase = true)
                || suffix.equals("svg", ignoreCase = true)
                || suffix.equals("bmp", ignoreCase = true)
                || suffix.equals("tif", ignoreCase = true)

        if (isImage && !isImageSuffix) {
            suffix = "png"
        }
        val currentTime: Long = System.currentTimeMillis()
        val imageDate: String =
            SimpleDateFormat("yyyyMMdd-HHmmss", Locale.getDefault()).format(Date(currentTime))
        val screenshotFileNameTemplate = "%s.$suffix"
        val mImageFileName: String =
            name.ifEmpty { String.format(screenshotFileNameTemplate, imageDate) }
        val values = ContentValues()

        values.put(
            MediaStore.MediaColumns.RELATIVE_PATH,
            if (folder.isEmpty()) {
                if (isImage) {
                    Environment.DIRECTORY_PICTURES
                } else {
                    Environment.DIRECTORY_MOVIES
                }
            } else {
                if (isImage) {
                    "${Environment.DIRECTORY_PICTURES}${File.separator}$folder"
                } else {
                    "${Environment.DIRECTORY_MOVIES}${File.separator}$folder"
                }
            },
        )
        values.put(MediaStore.MediaColumns.DISPLAY_NAME, mImageFileName)
        try {
            values.put(
                MediaStore.MediaColumns.MIME_TYPE,
                if (isImage) "image/$suffix" else "video/$suffix",
            )
        } catch (_: java.lang.Exception) {
        }
        values.put(MediaStore.MediaColumns.DATE_ADDED, currentTime / 1000)
        values.put(MediaStore.MediaColumns.DATE_MODIFIED, currentTime / 1000)
        values.put(
            MediaStore.MediaColumns.DATE_EXPIRES,
            (currentTime + DateUtils.DAY_IN_MILLIS) / 1000,
        )
        values.put(MediaStore.MediaColumns.IS_PENDING, 1)
        val resolver: ContentResolver = context.contentResolver
        val uri: Uri = resolver.insert(
            if (isImage) {
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI
            } else {
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI
            },
            values,
        ) ?: return SaveResultModel(false, null, "").toHashMap()
        try {
            val fileInputStream = FileInputStream(filePath)
            val data = ByteArray(1024)
            var read: Int
            resolver.openOutputStream(uri).use { out ->
                while ((fileInputStream.read(data, 0, data.size).also { read = it }) != -1) {
                    out?.write(data, 0, read)
                }
            }
            values.clear()
            values.put(MediaStore.MediaColumns.IS_PENDING, 0)
            values.putNull(MediaStore.MediaColumns.DATE_EXPIRES)
            fileInputStream.close()
            resolver.update(uri, values, null, null)
        } catch (e: IOException) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                resolver.delete(uri, null)
            }
            return SaveResultModel(false, null, "").toHashMap()
        }
        return SaveResultModel(true, null, "").toHashMap()
    }

    private fun saveFileToGallery(filePath: String?, name: String?): HashMap<String, Any?> {
        // check parameters
        if (filePath == null) {
            return SaveResultModel(false, null, "parameters error").toHashMap()
        }
        val context = applicationContext ?: return SaveResultModel(
            false,
            null,
            "applicationContext null"
        ).toHashMap()
        var fileUri: Uri? = null
        var outputStream: OutputStream? = null
        var fileInputStream: FileInputStream? = null
        var success = false
        try {
            val originalFile = File(filePath)
            if (!originalFile.exists()) return SaveResultModel(
                false,
                null,
                "$filePath does not exist"
            ).toHashMap()
            fileUri = generateUri(originalFile.extension, name)
            if (fileUri != null) {
                outputStream = context.contentResolver?.openOutputStream(fileUri)
                if (outputStream != null) {
                    fileInputStream = FileInputStream(originalFile)

                    val buffer = ByteArray(10240)
                    var count: Int
                    while (fileInputStream.read(buffer).also { count = it } > 0) {
                        outputStream.write(buffer, 0, count)
                    }

                    outputStream.flush()
                    success = true
                }
            }
        } catch (e: IOException) {
            SaveResultModel(false, null, e.toString()).toHashMap()
        } finally {
            outputStream?.close()
            fileInputStream?.close()
        }
        return if (success) {
            SaveResultModel(fileUri.toString().isNotEmpty(), fileUri.toString(), null).toHashMap()
        } else {
            SaveResultModel(false, null, "saveFileToGallery fail").toHashMap()
        }

    }
}
class SaveResultModel(
    private var isSuccess: Boolean,
    private var filePath: String? = null,
    private var errorMessage: String? = null,
) {
    fun toHashMap(): HashMap<String, Any?> {
        val hashMap = HashMap<String, Any?>()
        hashMap["isSuccess"] = isSuccess
        hashMap["filePath"] = filePath
        hashMap["errorMessage"] = errorMessage
        return hashMap
    }
}
