package com.flyfishxu.kadb

import android.content.Context
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbInterface
import android.os.Build
import androidx.documentfile.provider.DocumentFile
import com.flyfishxu.kadb.transport.UsbTransportChannel
import okio.sink
import okio.source
import java.io.File
import java.nio.file.Files

fun Kadb.pull(
    dst: DocumentFile,
    remotePath: String,
    context: Context
) {
    val outputStream = context.contentResolver.openOutputStream(dst.uri)
    checkNotNull(outputStream)
    outputStream.use { stream ->
        stream.sink().use { sink ->
            pull(sink, remotePath)
        }
    }
}

fun Kadb.push(
    src: DocumentFile,
    remotePath: String,
    context: Context,
    mode: Int = readMode(src),
    lastModifiedMs: Long = src.lastModified()
) {
    val inputStream = context.contentResolver.openInputStream(src.uri)
    checkNotNull(inputStream)
    inputStream.use { stream ->
        stream.source().use { source ->
            push(source, remotePath, mode, lastModifiedMs)
        }
    }
}

fun Kadb.install(
    src: DocumentFile,
    context: Context
) {
    val inputStream = context.contentResolver.openInputStream(src.uri)
    checkNotNull(inputStream)
    inputStream.use { stream ->
        stream.source().use { source ->
            install(source, src.length())
        }
    }
}

fun Kadb.readMode(file: DocumentFile): Int {
    // SYNC SEND uses Unix mode bits (e.g. 0o400) encoded as decimal.
    // https://android.googlesource.com/platform/system/core/+/refs/tags/android-11.0.0_r20/adb/SYNC.TXT
    var mode = 0
    if (file.canRead()) {
        mode = mode or 256
    }
    if (file.canWrite()) {
        mode = mode or 128
    }
    return mode
}

actual fun Kadb.readMode(file: File): Int {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        Files.getAttribute(file.toPath(), "unix:mode") as? Int ?: throw RuntimeException(
            "Unable to read file mode"
        )
    } else {
        var mode = 0
        if (file.canRead()) {
            mode = mode or 256
        }
        if (file.canWrite()) {
            mode = mode or 128
        }
        if (file.canExecute()) {
            mode = mode or 64
        }
        mode
    }
}

/**
 * Create a [Kadb] instance that communicates with a USB-attached Android device over ADB OTG.
 *
 * The caller is responsible for obtaining [UsbDeviceConnection] permission via
 * [android.hardware.usb.UsbManager] and for finding the correct ADB [UsbInterface] on the device
 * (typically interface class 0xFF / subclass 0x42 / protocol 0x01).
 *
 * All [Kadb] features – shell, sync/push/pull, install, port forwarding – work over USB in the
 * same way as over TCP/IP.  Wireless pairing is not applicable to USB connections.
 *
 * Example:
 * ```kotlin
 * val usbManager = getSystemService(USB_SERVICE) as UsbManager
 * val device = usbManager.deviceList.values.first { it.isAdbDevice() }
 * val connection = usbManager.openDevice(device)
 * val iface = device.findAdbInterface()  // your helper that picks the ADB interface
 * val kadb = Kadb.createUsb(connection, iface)
 * kadb.use {
 *     val result = it.shell("echo hello")
 *     println(result.output)
 * }
 * ```
 *
 * @param connection An open [UsbDeviceConnection] with permission for the ADB interface.
 * @param iface      The ADB bulk-transfer [UsbInterface] on the device.
 * @param options    Optional protocol options (e.g. [KadbOptions.delayedAckMode]).
 */
fun Kadb.Companion.createUsb(
    connection: UsbDeviceConnection,
    iface: UsbInterface,
    options: KadbOptions = KadbOptions()
): Kadb = Kadb(
    channelSupplier = { UsbTransportChannel.open(connection, iface) },
    options = options
)
