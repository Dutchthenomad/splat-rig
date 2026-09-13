package com.splatrig.capture

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

object Uploader {
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.MINUTES)
        .readTimeout(10, TimeUnit.MINUTES)
        .build()

    fun uploadAndRun(baseUrl: String, zip: File): String {
        val url = baseUrl.trimEnd('/')
        val body = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("file", zip.name, zip.asRequestBody("application/zip".toMediaType()))
            .build()
        val upload = Request.Builder().url("$url/sessions").post(body).build()
        val sessionId = client.newCall(upload).execute().use { resp ->
            if (!resp.isSuccessful) error("upload ${resp.code}: ${resp.body?.string()}")
            JSONObject(resp.body!!.string()).getString("id")
        }
        val run = Request.Builder()
            .url("$url/sessions/$sessionId/run")
            .post(ByteArray(0).toRequestBody(null))
            .build()
        client.newCall(run).execute().use { resp ->
            if (!resp.isSuccessful) error("run ${resp.code}: ${resp.body?.string()}")
        }
        return sessionId
    }
}
