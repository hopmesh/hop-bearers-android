<p align="center">
  <img alt="Hop" src="https://hopme.sh/hop-mark.svg" width="200">
</p>

<h1 align="center">Hop Bearers for Android</h1>

<p align="center">
  <b>The radios Hop rides on Android: BLE, LAN, and cloud relay, as independent Gradle modules.</b><br>
  Each bearer moves opaque bytes between two peers and conforms to one small contract, so a node plugs in only the transports it wants.
</p>

<p align="center">
  <img src="https://img.shields.io/badge/Kotlin-2.4-7F52FF" alt="Kotlin 2.4">
  <img src="https://img.shields.io/badge/Android-minSdk%2029-3ddc84" alt="Android minSdk 29">
  <img src="https://img.shields.io/badge/license-Apache--2.0-3ddc84" alt="license Apache-2.0">
</p>

---

Hop is a **delay-tolerant, end-to-end-encrypted mesh**: messages hop device to device over BLE, Wi-Fi,
and the internet until they reach the person or service you meant. Held, never dropped.

**Hop Bearers for Android is the transport layer.** Three independent Android library modules (BLE, LAN,
cloud relay) each discover peers, form links, and shuttle application bytes, and each implements the
same tiny `Bearer` / `LinkSink` contract from the Kotlin SDK. The bearer owns the radio and its own
dedup; the core never sees a socket, and you pull in only the pipes you need.

## What's in the box

| Module          | Transport   | How it works                                                        |
| --------------- | ----------- | ------------------------------------------------------------------- |
| `bearer-ble`    | BLE         | GATT carries the PSM handshake, L2CAP carries data, iBeacon wakes the app |
| `bearer-lan`    | Wi-Fi / LAN | NSD `_hoplan._tcp` discovery over TCP                               |
| `bearer-relay`  | Internet    | one outbound WebSocket to a relay (OkHttp, no inbound port)         |

## Install

The modules publish as Android libraries (Maven / AAR). Point Gradle at the package repo and add the
transports you want (`minSdk` 29, the floor for L2CAP CoC):

```kotlin
// settings.gradle.kts
dependencyResolutionManagement {
    repositories {
        google(); mavenCentral()
        maven("https://maven.pkg.github.com/hopmesh/hop-bearers-android")
    }
}
```

```kotlin
// build.gradle.kts
dependencies {
    implementation("sh.hopme.bearers:bearer-ble:0.0.1")
    implementation("sh.hopme.bearers:bearer-lan:0.0.1")
    implementation("sh.hopme.bearers:bearer-relay:0.0.1")
}
```

Each module depends only on the Kotlin SDK (`sh.hop`), which carries the `Bearer` / `LinkSink` / `HopRole`
contract and the registry.

## Usage

Register the bearers you want with a `BearerManager` (one `LinkId` space across every radio) and give
it a sink. That's the whole seam:

```kotlin
import sh.hop.BearerManager
import sh.hop.LinkSink
import sh.hopme.bearers.ble.BleBearer
import sh.hopme.bearers.lan.LanBearer
import sh.hopme.bearers.relay.RelayBearer
import java.security.SecureRandom

val myId = ByteArray(16).also { SecureRandom().nextBytes(it) }

val mesh = BearerManager()
mesh.register(BleBearer(context, myId))      // GATT PSM handshake, L2CAP data, iBeacon wake
mesh.register(LanBearer(context, myId))      // NSD _hoplan._tcp + TCP
mesh.register(RelayBearer("wss://relay.hopme.sh/"))

mesh.sink = myConsumer                         // gets linkUp / linkBytes / linkDown
mesh.start()

// later, send opaque bytes on a live link; the core owns every byte of crypto
mesh.send(packet, linkId)
```

In a real app the sink is a Hop node: `HopRuntime` (in the Kotlin SDK) wires a `BearerManager` to a
`hop-core` node so every link drives the node and the node's outbound packets route back to the owning
bearer. `BearerManager.start()`/`stop()` isolate each bearer, so BLE failing to listen (radio off at
launch) can't abort LAN or relay.

## The contract

A bearer names nothing about BLE, Wi-Fi, or sockets. It reports three things and accepts `send`:

```kotlin
interface LinkSink {
    fun linkUp(link: Long, role: HopRole, peerId: ByteArray)
    fun linkBytes(link: Long, bytes: ByteArray)
    fun linkDown(link: Long)
}

interface Bearer {
    var sink: LinkSink?
    val transportName: String       // short UI tag: "BT" / "LAN" / "Relay"
    fun start()
    fun stop()
    fun send(bytes: ByteArray, link: Long)
}
```

The Noise XX handshake that authenticates both ends lives inside the node, not the bearer, so a bearer
carries ciphertext it can't read.

## Status

Prototype. The pure link, dedup, and handshake logic (dial backoff, keep-rule, the framing and dispatch
loop, the iBeacon layout) is extracted into headless classes and unit-tested (JUnit + Robolectric)
under an 80% floor. The device-bound BLE radio classes that neither Robolectric nor an emulator can run
are excluded from the coverage denominator and exercised on real hardware instead. BLE reliability
follows the Ditto design: GATT only for the PSM handshake, data always on L2CAP.

## The Hop family

Hop is one protocol with many faces. The endpoint SDKs, same surface in your language:
[node](https://github.com/hopmesh/hop-sdk-node) ·
[python](https://github.com/hopmesh/hop-sdk-python) ·
[go](https://github.com/hopmesh/hop-sdk-go) ·
[ruby](https://github.com/hopmesh/hop-sdk-ruby) ·
[crystal](https://github.com/hopmesh/hop-sdk-crystal) ·
[elixir](https://github.com/hopmesh/hop-sdk-elixir) ·
[apple](https://github.com/hopmesh/hop-sdk-apple) ·
[android](https://github.com/hopmesh/hop-sdk-android).
The protocol core is [hop-core](https://github.com/hopmesh/hop-core) / [libhop](https://github.com/hopmesh/libhop).

## License

[Apache-2.0](./LICENSE.md), use it freely. Only the protocol core (`hop-core`) is FSL-1.1-ALv2,
source-available and converting to Apache-2.0 after two years.
