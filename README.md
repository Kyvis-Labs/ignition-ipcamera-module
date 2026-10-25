# Camera Module

An Ignition module that manages IP cameras for Perspective. Cameras are configured on the gateway, and the module uses [MediaMTX](https://github.com/bluenviron/mediamtx) to pull and record their video. MediaMTX runs as a separate service.

The Camera Module provides support for:

1. Generic RTSP cameras and UniFi Protect RTSPS streams
2. Off and continuous recording
3. Status tags in a tag provider called `Cameras`

Getting Started
---------------

1. Run MediaMTX, see [MediaMTX](#mediamtx)
2. Build the module, see [Building](#building)
3. Install the module into Ignition 8.3.9+. Start the gateway with `-Dignition.allowunsignedmodules=true`.
4. Set the media server settings and add your cameras through the gateway REST API, under `/data/api/v1/resources/com.kyvislabs.camera/`

# Architecture

```
Browser (Perspective) ── websocket ──▶ Gateway (this module)
  │  asks for a stream                   checks roles, creates a token for one camera
  ├── WebRTC ─────────────────────────▶ MediaMTX

Gateway ── control API ──▶ MediaMTX: adds a path for each camera and repairs them on a timer
MediaMTX ── auth callback ──▶ Gateway: checks the token on each request
MediaMTX ── RTSP/RTSPS ──▶ Cameras
```

MediaMTX pulls the camera streams and records them. The gateway owns the configuration and decides who can see what. Browsers connect to MediaMTX directly, so it needs to be reachable over HTTPS, usually through a reverse proxy.

# Cameras

Camera names become part of the stream path and can only use letters, digits, `_`, `.` and `-`. A camera cannot be renamed because its recordings are stored by name.

- **RTSP:** Enter the stream URLs and put the credentials in their own fields.
- **UniFi Protect:** Enable RTSP on the camera in Protect. Use the `rtsps://...:7441/...` link and the certificate's SHA-256 fingerprint.

Each camera has `viewRoles` and `playbackRoles`. If empty, anyone who can open a view showing the camera can see it.

# Recording

| Mode | What it does | Kept for |
|---|---|---|
| `OFF` | Live only | - |
| `CONTINUOUS` | Records everything | `retentionDays` |

# Tags

The module creates a tag provider called `Cameras`.

| Tag | Description |
|---|---|
| `MediaServer/Online`, `MediaServer/LastError` | Media server state |
| `<camera>/Online`, `<camera>/SubOnline` | Stream state |
| `<camera>/Recording`, `<camera>/RecordMode` | Recording state |
| `<camera>/Readers`, `<camera>/BytesReceived` | Stream usage |
| `<camera>/Codecs`, `<camera>/LastError` | Stream details |

# MediaMTX

Run MediaMTX yourself, as a container or a service. The module was built against MediaMTX 1.21.2.

```yaml
api: yes
webrtcLocalUDPAddress: :8189
webrtcLocalTCPAddress: :8189
webrtcAdditionalHosts: [<addresses browsers use>]
playback: yes
authMethod: http
authHTTPAddress: http://<gateway>:8088/data/camera/auth?key=<shared key>
authHTTPExclude: [{action: api}, {action: metrics}, {action: pprof}]
pathDefaults:
  recordPath: /recordings/%path/%Y-%m-%d_%H-%M-%S-%f
  recordFormat: fmp4
  recordPartDuration: 1s
paths: {}
```

In the media server settings, enter the URLs the gateway uses to reach MediaMTX, the `Public URL` browsers use, and the same `Auth Callback Key`. Streams are refused until the key is set.

# Building

Requires JDK 17 and Node.js.

```
./gradlew build     # Camera.unsigned.modl and unit tests
scripts/deploy.sh   # build and load on the dev gateway
```

Unsigned modules need `-Dignition.allowunsignedmodules=true` on the gateway.
