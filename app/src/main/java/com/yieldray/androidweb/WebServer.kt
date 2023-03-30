package com.yieldray.androidweb

import android.util.Log
import fi.iki.elonen.NanoHTTPD
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okio.BufferedSink
import okio.source
import java.io.IOException
import java.io.InputStream
import java.net.*
import java.security.KeyStore
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory


class WebServer(val loadAssert: (String) -> InputStream) : NanoHTTPD(8123) {

    override fun start() {
        if (false) {
            // preserved for https
            val keystoreStream: InputStream = loadAssert("keystore.bks")
            val keyStore = KeyStore.getInstance(KeyStore.getDefaultType())
            keyStore.load(keystoreStream, "password".toCharArray())

            val trustManagerFactory =
                TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
            trustManagerFactory.init(keyStore)

            val ctx = SSLContext.getInstance("TLS")
            ctx.init(null, trustManagerFactory.trustManagers, null)

            super.makeSecure(ctx.serverSocketFactory, null)
        }
        super.start()
        Log.d("NanoHTTPD", "Server running at http://localhost:$listeningPort/")
    }

    override fun stop() {
        super.stop()
        Log.d("NanoHTTPD", "Server stopping at http://localhost:$listeningPort/")
    }

    fun isPortOpen(port: Int): Boolean {
        var socket: Socket? = null
        return try {
            socket = Socket()
            socket.connect(InetSocketAddress("localhost", port), 500)
            true
        } catch (e: Exception) {
            Log.d("NanoHTTPD", e.toString())
            false
        } finally {
            try {
                socket?.close()
            } catch (e: Exception) {
                Log.d("NanoHTTPD", e.toString())
            }
        }
    }


    override fun serve(session: IHTTPSession): Response {
        var uri = session.uri ?: "/"
        if ("/" == uri) uri = "/index.html"
        if ("/api/proxy" == uri) return apiProxy(session)

        uri = uri.substring(1)

        return try {
            // send file from android asserts
            val inputStream = loadAssert(uri)
            newChunkedResponse(Response.Status.OK, getMimeType(uri), inputStream)
        } catch (e: IOException) {
            // not found in android asserts
            try {
                // use /404.html
                newChunkedResponse(Response.Status.NOT_FOUND, MIME_HTML, loadAssert("404.html"))
            } catch (e: IOException) {
                // no /404.html found
                newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "404")
            }
        }
    }

    private fun isURL(url: String): Boolean {
        return try {
            URL(url)
            true
        } catch (e: MalformedURLException) {
            Log.e("MalformedURLException", url, e)
            false
        }
    }


    // use okhttp
    private val client = OkHttpClient()

    /**
     * send a x-mod[req|res]-* response for proxy
     */
    private fun apiProxy(session: IHTTPSession): Response {
        val headers = session.headers.toMutableMap()
        val headersModRes = HashMap<String, String>()
        val url = headers["x-modreq"] ?: ""
        if (!isURL(url)) return newFixedLengthResponse(
            Response.Status.BAD_REQUEST,
            MIME_PLAINTEXT,
            "x-modreq is invalid url"
        )
        headers.remove("x-modreq")
        headers.remove("host")
        headers["referer"] = url
        headers.forEach {
            val (key, value) = it
            if (key.startsWith("x-modreq-")) {
                headers[key.removePrefix("x-modreq-")] = value
                headers.remove(key)
            } else if (key.startsWith("x-modres-")) {
                headersModRes[key.removePrefix("x-modres-")] = value
                headers.remove(key)
            }
        }


        val request: Request = Request.Builder().url(url).run {
            // set headers
            headers.forEach {
                val (key, value) = it
                header(key, value) // loop replace header
            }

            // create body from inputStream
            val requestBody: RequestBody = object : RequestBody() {
                override fun contentType() =
                    (headers["content-type"] ?: "application/octet-stream").toMediaType()

                @Throws(IOException::class)
                override fun contentLength(): Long = -1

                @Throws(IOException::class)
                override fun writeTo(sink: BufferedSink) {
                    try {
                        session.inputStream.source().use { source -> sink.writeAll(source) }
                    } catch (e: IOException) {
                        Log.e("okhttp", "RequestBody", e)
                        throw e
                    }
                }
            }

            // set method
            when (session.method) {
                Method.GET -> get()
                Method.PUT -> put(requestBody)
                Method.POST -> post(requestBody)
                Method.DELETE -> delete(requestBody)
                Method.HEAD -> head()
                Method.OPTIONS -> method("OPTIONS", requestBody)
                Method.TRACE -> method("TRACE", requestBody)
                Method.CONNECT -> method("CONNTECT", requestBody)
                Method.PATCH -> patch(requestBody)
                Method.PROPFIND -> method("PROPFIND", requestBody)
                Method.PROPPATCH -> method("PROPPATCH", requestBody)
                Method.MKCOL -> method("MKCOL", requestBody)
                Method.MOVE -> method("MOVE", requestBody)
                Method.COPY -> method("COPY", requestBody)
                Method.LOCK -> method("LOCK", requestBody)
                Method.UNLOCK -> method("UNLOCK", requestBody)
                else -> return newFixedLengthResponse(
                    Response.Status.NOT_ACCEPTABLE,
                    MIME_PLAINTEXT,
                    "request method is not supported"
                )
            }
            // build
            build()
        }

        try {
            client.newCall(request).execute().use { response ->
                val status = nanoStatus(response.code, response.message)
                val mime = response.headers["content-type"] ?: ""

//                val stream = response.body?.byteStream()
                val stream = response.body?.bytes()?.inputStream()
                /*  TAKE CARE
                * cannot call .byteStream(), which will cause NanoHTTPD crash
                * however call .bytes().inputStream() works
                * */

                val resp = newChunkedResponse(status, mime, stream)
                // headers for response
                val headersResp = HashMap<String, String>()
                // set from okhttp
                response.headers.forEach {
                    val (key, value) = it
                    headersResp[key] = value
                }
                // set from x-modres-*
                headersModRes.forEach {
                    val (key, value) = it
                    headersResp[key] = value
                }
                // use the constructed headers
                headersResp.forEach {
                    val (key, value) = it
                    resp.addHeader(key, value)
                }
                // send response
                return resp

            }
        } catch (e: Exception) {
            return newFixedLengthResponse(
                Response.Status.SERVICE_UNAVAILABLE,
                MIME_PLAINTEXT,
                "fail to proxy, network error, reason: $e"
            ).also { Log.e("request error", "/api/proxy", e) }
        }
    }


    /**
     * this manually create Response.IStatus which NanoHTTPD requires
     */
    private fun nanoStatus(code: Int, text: String) = object : Response.IStatus {
        override fun getRequestStatus() = code

        override fun getDescription() = text.ifEmpty {
            Response.Status.values().forEach {
                if (it.requestStatus == code) return it.description
            }
            text
        }

    }

    /**
     * get mime type for writing content-type
     */
    private fun getMimeType(fileUrl: String): String {
        val fileNameMap: FileNameMap = URLConnection.getFileNameMap()
        return fileNameMap.getContentTypeFor(fileUrl)
    }
}

