/*
 * Copyright (c) 2024 Flyfish-Xu
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.flyfishxu.kadb.transport

import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import java.io.EOFException
import java.io.IOException
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.util.concurrent.TimeUnit
import kotlin.math.min

/**
 * [TransportChannel] implementation backed by a USB bulk-transfer connection.
 *
 * USB ADB uses the Android Open Accessory (AOA) / ADB protocol over bulk endpoints.
 * Unlike TCP/IP ADB, there is no TLS layer – authentication is done through the plain
 * ADB AUTH exchange (token → signature → RSA public key).
 *
 * Obtain an instance via [UsbTransportChannel.open].
 */
internal class UsbTransportChannel private constructor(
    private val connection: UsbDeviceConnection,
    private val iface: UsbInterface,
    private val endpointIn: UsbEndpoint,
    private val endpointOut: UsbEndpoint
) : TransportChannel {

    @Volatile
    private var closed = false

    override val isOpen: Boolean
        get() = !closed

    // USB has no notion of socket addresses; provide neutral placeholders so callers
    // that inspect these fields (e.g. logging) do not crash.
    override val localAddress: InetSocketAddress
        get() = InetSocketAddress("localhost", 0)
    override val remoteAddress: InetSocketAddress
        get() = InetSocketAddress("localhost", 0)

    override suspend fun read(dst: ByteBuffer, timeout: Long, unit: TimeUnit): Int {
        val timeoutMs = timeoutMs(timeout, unit)
        val max = min(dst.remaining(), MAX_USB_PACKET)
        val buf = ByteArray(max)
        val read = connection.bulkTransfer(endpointIn, buf, max, timeoutMs)
        if (read < 0) return -1
        dst.put(buf, 0, read)
        return read
    }

    override suspend fun write(src: ByteBuffer, timeout: Long, unit: TimeUnit): Int {
        val timeoutMs = timeoutMs(timeout, unit)
        val max = min(src.remaining(), MAX_USB_PACKET)
        val buf = ByteArray(max)
        src.get(buf, 0, max)
        val written = connection.bulkTransfer(endpointOut, buf, max, timeoutMs)
        if (written < 0) throw IOException("USB bulk write failed (bulkTransfer returned $written)")
        return written
    }

    override suspend fun readExactly(dst: ByteBuffer, timeout: Long, unit: TimeUnit) {
        while (dst.hasRemaining()) {
            val read = read(dst, timeout, unit)
            if (read < 0) throw EOFException("EOF during USB readExactly")
        }
    }

    override suspend fun writeExactly(src: ByteBuffer, timeout: Long, unit: TimeUnit) {
        while (src.hasRemaining()) {
            write(src, timeout, unit)
        }
    }

    // USB bulk endpoints do not support half-close; both are no-ops.
    override suspend fun shutdownInput() = Unit
    override suspend fun shutdownOutput() = Unit

    override fun close() {
        if (closed) return
        closed = true
        runCatching { connection.releaseInterface(iface) }
        runCatching { connection.close() }
    }

    companion object {
        // Maximum bytes per single bulkTransfer call. 16 KiB is well within USB HS limits
        // and avoids over-large allocations while still being efficient for ADB payloads.
        private const val MAX_USB_PACKET = 16 * 1024

        /**
         * Find the ADB bulk endpoints on [iface], claim the interface, and return a ready channel.
         *
         * @param connection An open [UsbDeviceConnection] obtained from [android.hardware.usb.UsbManager].
         * @param iface      The ADB [UsbInterface] on the device (class 0xFF, subclass 0x42, protocol 0x01).
         * @throws IllegalArgumentException if the required IN/OUT bulk endpoints cannot be found.
         * @throws IOException              if claiming the interface fails.
         */
        fun open(connection: UsbDeviceConnection, iface: UsbInterface): UsbTransportChannel {
            var epIn: UsbEndpoint? = null
            var epOut: UsbEndpoint? = null
            for (i in 0 until iface.endpointCount) {
                val ep = iface.getEndpoint(i)
                if (ep.type == UsbConstants.USB_ENDPOINT_XFER_BULK) {
                    when (ep.direction) {
                        UsbConstants.USB_DIR_IN -> epIn = ep
                        UsbConstants.USB_DIR_OUT -> epOut = ep
                    }
                }
            }
            requireNotNull(epIn) { "USB ADB interface has no bulk IN endpoint" }
            requireNotNull(epOut) { "USB ADB interface has no bulk OUT endpoint" }
            if (!connection.claimInterface(iface, true)) {
                throw IOException("Failed to claim USB interface for ADB")
            }
            return UsbTransportChannel(connection, iface, epIn, epOut)
        }

        private fun timeoutMs(timeout: Long, unit: TimeUnit): Int =
            if (timeout > 0) unit.toMillis(timeout).coerceAtMost(Int.MAX_VALUE.toLong()).toInt() else 0
    }
}
