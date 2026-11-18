package com.example.screenshare

import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.Executors

class MjpegServer(private val port: Int) {

    private val lock = Object()
    private var frame: ByteArray? = null
    private var frameSeq = 0L

    @Volatile
    private var running = false
    private var serverSocket: ServerSocket? = null
    private val executor = Executors.newCachedThreadPool()

    fun start() {
        running = true
        val ss = ServerSocket(port)
        serverSocket = ss

        executor.execute {
            while (running) {
                try {
                    val client = ss.accept()
                    executor.execute { handle(client) }
                } catch (e: IOException) {
                    if (!running) break
                }
            }
        }
    }

    fun stop() {
        running = false
        try {
            serverSocket?.close()
        } catch (_: IOException) {
        }
        serverSocket = null
        executor.shutdownNow()
        synchronized(lock) { lock.notifyAll() }
    }

    fun updateFrame(jpeg: ByteArray) {
        synchronized(lock) {
            frame = jpeg
            frameSeq++
            lock.notifyAll()
        }
    }

    private fun waitForNextFrame(lastSeq: Long): Pair<Long, ByteArray>? {
        synchronized(lock) {
            while (running && frameSeq == lastSeq) {
                lock.wait(1000)
            }
            val f = frame ?: return null
            return frameSeq to f
        }
    }

    private fun handle(socket: Socket) {
        try {
            socket.use { s ->
                s.soTimeout = 5000
                val reader = BufferedReader(InputStreamReader(s.getInputStream()))
                val requestLine = reader.readLine() ?: return
                val path = requestLine.split(" ").getOrNull(1)?.substringBefore('?') ?: "/"

                // خواندن هدرها تا خط خالی
                while (true) {
                    val line = reader.readLine()
                    if (line.isNullOrEmpty()) break
                }

                val out = s.getOutputStream()
                when (path) {
                    "/" -> sendHtml(out)
                    "/stream" -> sendStream(out)
                    "/snapshot.jpg" -> sendSnapshot(out)
                    else -> send404(out)
                }
            }
        } catch (_: Exception) {
            // اتصال قطع شده یا خطای دیگر - نادیده گرفته می‌شود
        }
    }

    private fun sendHtml(out: OutputStream) {
        val body = """
            <!DOCTYPE html>
            <html>
            <head>
              <meta name="viewport" content="width=device-width, initial-scale=1">
              <title>Screen</title>
            </head>
            <body style="margin:0;background:#000;">
              <img src="/stream" style="width:100%;height:auto;display:block;">
            </body>
            </html>
        """.trimIndent().toByteArray()

        val header = "HTTP/1.1 200 OK\r\n" +
                "Content-Type: text/html; charset=utf-8\r\n" +
                "Content-Length: ${body.size}\r\n" +
                "Connection: close\r\n\r\n"
        out.write(header.toByteArray())
        out.write(body)
        out.flush()
    }

    private fun sendStream(out: OutputStream) {
        val header = "HTTP/1.1 200 OK\r\n" +
                "Content-Type: multipart/x-mixed-replace; boundary=frame\r\n" +
                "Cache-Control: no-cache\r\n" +
                "Connection: close\r\n\r\n"
        out.write(header.toByteArray())
        out.flush()

        var seq = 0L
        while (running) {
            val result = waitForNextFrame(seq) ?: continue
            seq = result.first
            val jpeg = result.second

            out.write(
                ("--frame\r\n" +
                        "Content-Type: image/jpeg\r\n" +
                        "Content-Length: ${jpeg.size}\r\n\r\n").toByteArray()
            )
            out.write(jpeg)
            out.write("\r\n".toByteArray())
            out.flush()
        }
    }

    private fun sendSnapshot(out: OutputStream) {
        val jpeg = synchronized(lock) { frame }
        if (jpeg == null) {
            send404(out)
            return
        }
        val header = "HTTP/1.1 200 OK\r\n" +
                "Content-Type: image/jpeg\r\n" +
                "Content-Length: ${jpeg.size}\r\n" +
                "Cache-Control: no-cache\r\n" +
                "Connection: close\r\n\r\n"
        out.write(header.toByteArray())
        out.write(jpeg)
        out.flush()
    }

    private fun send404(out: OutputStream) {
        val header = "HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"
        out.write(header.toByteArray())
        out.flush()
    }

    companion object {
        fun getLocalIp(): String {
            try {
                val interfaces = NetworkInterface.getNetworkInterfaces() ?: return "0.0.0.0"
                for (ni in interfaces) {
                    if (!ni.isUp || ni.isLoopback) continue
                    for (addr in ni.inetAddresses) {
                        val host = addr.hostAddress
                        if (addr is Inet4Address && !addr.isLoopbackAddress && host != null) {
                            return host
                        }
                    }
                }
            } catch (_: Exception) {
            }
            return "0.0.0.0"
        }
    }
}