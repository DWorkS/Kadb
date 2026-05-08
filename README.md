# Kadb

[![Maven Central](https://img.shields.io/maven-central/v/com.flyfishxu/kadb.svg)](https://central.sonatype.com/artifact/com.flyfishxu/kadb)

Kadb is a Kotlin Multiplatform ADB client library for talking directly to `adbd`.

It is intended for apps and tools that need shell, sync, install, pairing, or port forwarding without embedding the full `adb` CLI or server stack.

[Platform Notes](docs/platform.md) · [Host Identity](docs/kadbcert.md) · [Docs Index](docs/README.md)

## Overview

- Direct Kotlin API for `adbd`
- Android and JVM targets
- Wireless pairing, shell, file transfer, install, and TCP forwarding
- **USB OTG ADB** – talk to an attached Android device without a PC
- AOSP-aligned host behavior where practical

Kadb is not a full adb server replacement. USB discovery, transport brokering, and server-style device tracking are out of scope.

## Installation

```kotlin
dependencies {
    implementation("com.flyfishxu:kadb:2.1.1")
}
```

## Quick Start

Connect to an existing device transport:

```kotlin
Kadb.create("127.0.0.1", 5555).use { kadb ->
    val response = kadb.shell("echo hello")
    check(response.exitCode == 0)
    check(response.output == "hello\n")
}
```

Pair with a new Android 11+ device:

```kotlin
Kadb.pair("10.0.0.175", 37755, "643102")
```

## API Overview

| Capability | API |
| --- | --- |
| Connect to `adbd` (TCP) | `Kadb.create(...)` |
| Connect to `adbd` (USB OTG) | `Kadb.createUsb(...)` *(Android only)* |
| Wireless pairing | `Kadb.pair(...)` |
| Shell | `shell(...)`, `openShell()`, `openPtyShellSession()` |
| File transfer | `push(...)`, `pull(...)`, `openSync()` |
| APK install | `install(...)`, `installMultiple(...)`, `uninstall(...)` |
| Port forwarding | `tcpForward(...)` |
| Transport reuse | `resetConnection()` |

## USB OTG ADB

Kadb supports ADB over USB OTG on Android.  This lets one Android device act as an ADB host and
control another Android device that is plugged in via a USB OTG cable — no PC required.

### Manifest permissions

Add the USB host permission to your `AndroidManifest.xml`:

```xml
<uses-feature android:name="android.hardware.usb.host" />
<uses-permission android:name="android.hardware.usb.action.USB_DEVICE_ATTACHED" />
```

Optionally add a device filter to auto-launch your activity when an ADB device is attached:

```xml
<activity ...>
    <intent-filter>
        <action android:name="android.hardware.usb.action.USB_DEVICE_ATTACHED" />
    </intent-filter>
    <meta-data
        android:name="android.hardware.usb.action.USB_DEVICE_ATTACHED"
        android:resource="@xml/device_filter" />
</activity>
```

`res/xml/device_filter.xml` (ADB device class):

```xml
<?xml version="1.0" encoding="utf-8"?>
<resources>
    <usb-device class="255" subclass="66" protocol="1" />
</resources>
```

### Finding the ADB interface

ADB bulk endpoints live on a USB interface with class `0xFF`, subclass `0x42`, protocol `0x01`:

```kotlin
fun UsbDevice.findAdbInterface(): UsbInterface? {
    for (i in 0 until interfaceCount) {
        val iface = getInterface(i)
        if (iface.interfaceClass == 0xFF &&
            iface.interfaceSubclass == 0x42 &&
            iface.interfaceProtocol == 0x01) {
            return iface
        }
    }
    return null
}
```

### Connecting

```kotlin
val usbManager = getSystemService(USB_SERVICE) as UsbManager

// Request permission (show system dialog) then open the connection:
val device: UsbDevice = ...          // from UsbManager.getDeviceList() or intent
val iface = device.findAdbInterface() ?: error("No ADB interface found")
val connection = usbManager.openDevice(device) ?: error("Permission not granted")

val kadb = Kadb.createUsb(connection, iface)
kadb.use {
    val result = it.shell("echo hello via USB OTG")
    println(result.output)           // "hello via USB OTG\n"
}
```

All existing `Kadb` APIs work identically over USB:

```kotlin
kadb.use {
    it.install(apkFile)              // install an APK
    it.push(localFile, "/data/local/tmp/file.txt")
    it.shell("pm list packages")
}
```

### Notes

- The first connection triggers the *"Allow USB debugging?"* prompt on the target device.
- USB ADB uses plain RSA key-pair authentication — no TLS handshake is involved.
- `Kadb.pair(...)` is not applicable for USB connections.
- Reconnection after device disconnect requires creating a new `Kadb.createUsb(...)` instance
  with a fresh `UsbDeviceConnection` obtained from `UsbManager`.

## Examples

Install an APK:

```kotlin
Kadb.create("127.0.0.1", 5555).use { kadb ->
    kadb.install(apkFile)
}
```

Push a file:

```kotlin
Kadb.create("127.0.0.1", 5555).use { kadb ->
    kadb.push(localFile, "/data/local/tmp/remote.txt")
}
```

Forward a TCP port:

```kotlin
Kadb.create("127.0.0.1", 5555).tcpForward(
    hostPort = 7001,
    targetPort = 7001
).use {
    // localhost:7001 now forwards to the device's port 7001
}
```

## Building the Library for Android

### Prerequisites

| Tool | Minimum version |
|------|-----------------|
| JDK  | 21 |
| Android SDK | API 36 (compile), API 23 (min) |
| Gradle | ships with the wrapper (`./gradlew`) |

### Clone and build

```bash
git clone https://github.com/DWorkS/Kadb.git
cd Kadb
./gradlew :kadb:assembleRelease
```

The Android AAR artifact is produced at:

```
kadb/build/outputs/aar/kadb-release.aar
```

### Run tests

```bash
# JVM unit tests (no device required)
./gradlew :kadb:jvmTest
```

### Publish to local Maven repository

```bash
./gradlew :kadb:publishToMavenLocal
```

After publishing, add `mavenLocal()` to your project's repository list and depend on:

```kotlin
implementation("com.flyfishxu:kadb:2.1.1")
```

### Publish to JitPack (fork)

Push a tag to your fork and add the JitPack repository:

```kotlin
repositories {
    maven("https://jitpack.io")
}
dependencies {
    implementation("com.github.YourUser:Kadb:TAG")
}
```

## Platform and Pairing Notes

- Android target support starts at `minSdk 23`
- Basic connect / shell / sync / install flows do not require the full `adb` binary
- Pairing has stricter requirements than ordinary client operations
- JVM pairing requires `conscrypt-openjdk-uber`
- Android 6 to 8 usually need a custom Conscrypt dependency for pairing

Example JVM pairing dependency:

```kotlin
dependencies {
    implementation("com.flyfishxu:kadb:2.1.1")
    implementation("org.conscrypt:conscrypt-openjdk-uber:2.5.2")
}
```

More detail: [docs/platform.md](docs/platform.md)

## Scope and Limitations

- Kadb is a direct client library, not a full adb server
- USB OTG transport requires Android USB host mode hardware support

## Documentation

- [Documentation Index](docs/README.md)
- [Platform Notes](docs/platform.md)
- [KadbCert](docs/kadbcert.md)

## Acknowledgements

- [Dadb](https://github.com/mobile-dev-inc/dadb)
- [libadb-android](https://github.com/MuntashirAkon/libadb-android)
- [spake2-java](https://github.com/Flyfish233/spake2-java)
- [ADB-OTG](https://github.com/KhunHtetzNaing/ADB-OTG) – reference USB OTG implementation
