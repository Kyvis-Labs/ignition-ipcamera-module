# Camera Module

An Ignition module that adds IP camera live view, recording and playback to Perspective. Cameras are configured on the gateway and shown with the Camera View and Camera Wall components. The module uses [MediaMTX](https://github.com/bluenviron/mediamtx) to pull, record and serve video. MediaMTX can run inside the module or as a separate service.

The Camera Module provides support for:

1. Generic RTSP cameras, ONVIF cameras and UniFi Protect RTSPS streams
2. ONVIF discovery, stream URL lookup, events and PTZ
3. Off, continuous, camera event and tag driven recording, with pre-roll and post-roll
4. Timeline playback, bookmarks, event clips, snapshots and MP4 export
5. WebRTC for low latency, or low-latency HLS over the gateway's own port
6. Transcoding to H.264 for browsers that cannot decode a camera's codec, or to save bandwidth
7. Status and ONVIF event tags in a tag provider called `Cameras`
8. Scripting through `system.camera`

Open Source
---------------

The Camera Module is an open source project distributed under the Apache 2.0 license. Please feel free to download the source code and contribute. The platform builds include MediaMTX, which is distributed under the MIT license.

Getting Started
---------------

1. Download the module for your platform from [releases](https://github.com/Kyvis-Labs/ignition-ipcamera-module/releases)
2. Install the module into Ignition 8.3.9+ and accept its certificate
3. Log into the Ignition Gateway
4. Click on `Connections > Cameras > Settings` and check the media server settings. The platform builds need no changes, but `Media Port` (8189, UDP and TCP) must be open in the host firewall to use WebRTC.
5. Click on `Connections > Cameras > Connections` and click `Create Camera`
6. Enter the camera manually or click `Discover Cameras` to find ONVIF cameras
7. Add a Camera View or Camera Wall component to a Perspective view and select your camera

| Module | MediaMTX |
|---|---|
| `Camera-windows-x64.modl` | Included, Windows x64 |
| `Camera-linux-x64.modl` | Included, Linux x64 |
| `Camera-linux-arm64.modl` | Included, Linux ARM64 |
| `Camera-macos-arm64.modl` | Included, macOS on Apple silicon |
| `Camera.modl` | Not included, see [External MediaMTX](#external-mediamtx) |

ffmpeg is not included. Install it on the gateway host if you need transcoding.

# Architecture

```
Browser (Perspective) ── websocket ──▶ Gateway (this module)
  │  asks for a stream                   checks roles, creates a token for one camera
  ├── HLS and recordings ─────────────▶ Gateway ──▶ MediaMTX
  ├── WebRTC signalling ──────────────▶ Gateway ──▶ MediaMTX
  └── WebRTC media (port 8189) ────────────────────▶ MediaMTX

Gateway ── control API ──▶ MediaMTX: adds a path for each camera and repairs them on a timer
MediaMTX ── auth callback ──▶ Gateway: checks the token on each request
MediaMTX ── RTSP/RTSPS ──▶ Cameras
```

MediaMTX pulls the camera streams and records them. The gateway owns the configuration and decides who can see what. A reverse proxy is only needed if you want browsers to reach an external MediaMTX directly.

Without a transcode, video is passed through as the camera sends it. A Camera Wall should use camera substreams, otherwise every tile decodes the full resolution stream.

# Cameras

Camera names become part of the stream path and can only use letters, digits, `_`, `.` and `-`. A camera cannot be renamed because its recordings are stored by name.

- **RTSP:** Enter the stream URLs and put the credentials in their own fields.
- **ONVIF:** Enter the credentials and click `Fill Stream URLs from ONVIF`. `ONVIF Tags` keeps an event subscription open and exposes the camera's events as tags.
- **UniFi Protect:** Enable RTSP on the camera in Protect. Use the `rtsps://...:7441/...` link and the certificate's SHA-256 fingerprint.

Each camera has `viewRoles` and `playbackRoles`. If empty, anyone who can open a view showing the camera can see it.

# Recording

| Mode | What it does | Kept for |
|---|---|---|
| `OFF` | Live only | - |
| `CONTINUOUS` | Records everything | `retentionDays` |
| `EVENT` / `TAG` with pre-roll | Records to a rolling buffer and copies each event out as a clip | `eventRetentionDays` |
| `EVENT` / `TAG` without pre-roll | Starts recording when triggered, about 1-2 seconds late | `eventRetentionDays` |

An event starts `preRollSec` before the first trigger and ends `postRollSec` after the last trigger clears. Long events are split every hour.

> **Note:** Retention is per camera. Switching a camera from `CONTINUOUS` to `EVENT` or `TAG` applies the shorter buffer retention to its existing recordings.

**TAG conditions:** `true`, `false`, `> n`, `>= n`, `< n`, `<= n`, `= v`, `!= v`. Blank means truthy.

**EVENT types:** Text matched against the camera's ONVIF topics and data, such as `motion`, `person` or `vehicle`. Blank means motion. `Detect from Camera` lists what the camera offers.

Tag driven bookmarks work in any record mode.

# Manage Page

Click `... > Manage` on a camera to open its page in the gateway:

- Live video, recordings, snapshots, bookmarks and PTZ
- Export a time range as MP4
- Storage used per day, and delete recordings
- Search, play, protect, export and delete events
- View all cameras together, live or at a point in time

Sizes and deletes need access to the files, so they only work with the included MediaMTX.

# Components

## Camera View

`kyvislabs.camera.view`

| Prop | Default | Description |
|---|---|---|
| `camera` | `""` | Camera name |
| `playback.mode` | `live` | `live` or `playback` |
| `playback.time` | `null` | Time in epoch ms. Updates while playing, and writing it seeks. |
| `stream.quality` | `auto` | `auto`, `main`, `sub`, a transcode profile such as `720p`, or `custom` |
| `stream.customQuality` | `{height: 540, bitrateKbps: 1200}` | Size used by `custom` |
| `stream.transport` | `auto` | `auto` (gateway default), `hls` or `webrtc` |
| `stream.muted` | `true` | Mutes the audio |
| `stream.fit` | `contain` | `contain`, `cover` or `fill` |
| `status.show` | `true` | Shows the name over the video |
| `controls.show` | `true` | Shows the control bar |
| `controls.collapsed` | `false` | Hides the controls and timeline behind a tab |
| `controls.bookmarks` | `true` | Shows the bookmark button |
| `controls.snapshots` | `true` | Shows the snapshot button |
| `timeline.show` | `true` | Shows the timeline |
| `timeline.range` | `1h` | `1h`, `6h`, `24h` or `7d` |
| `ptz.show` | `true` | Allows PTZ controls on a camera that supports them |
| `ptz.visible` | `true` | Shows the PTZ pad |
| `ptz.speed` | `0.5` | PTZ speed, 0.1 to 1 |

Events: `onModeChange`, `onBookmark` and `onMarkerClick`.

## Camera Wall

`kyvislabs.camera.wall`

| Prop | Default | Description |
|---|---|---|
| `cameras` | `[]` | Camera names, or `{camera, label}` |
| `layout.columns`, `layout.rows` | `0` | Grid size, `0` for automatic. Extra cameras go on more pages. |
| `layout.gap` | `2` | Pixels between tiles |
| `layout.tourSeconds` | `0` | Seconds between pages, `0` for off |
| `playback.mode`, `playback.time` | | Shared by all tiles |
| `stream.transport` | `auto` | Same as Camera View |
| `stream.fit` | `cover` | Same as Camera View |
| `stream.maxLiveStreams` | `16` | Maximum tiles playing live at once |
| `status.show`, `controls.show`, `timeline.show` | `true` | Shows each part |
| `controls.collapsed` | `false` | Same as Camera View |
| `timeline.range` | `1h` | Same as Camera View |

Double click a tile to maximize it and switch to the main stream. Double click again to return.

# Tags

The module creates a tag provider called `Cameras`.

| Tag | Description |
|---|---|
| `MediaServer/Online`, `MediaServer/LastError` | Media server state |
| `<camera>/Online`, `<camera>/SubOnline` | Stream state |
| `<camera>/Recording`, `<camera>/RecordMode` | Recording state |
| `<camera>/Readers`, `<camera>/BytesReceived` | Stream usage |
| `<camera>/Codecs`, `<camera>/LastError`, `<camera>/LastEventStart` | Stream details |
| `<camera>/EventSourceOK`, `<camera>/EventSourceError` | ONVIF event subscription state |
| `<camera>/Onvif/Device/...` | Manufacturer, model, firmware, serial and clock offset |
| `<camera>/Onvif/Events/<topic>/...` | `Active`, `LastSeen` and `Data` for each topic the camera sends |

In `EVENT` mode, put an alarm on `EventSourceOK = false` so you know if the camera's events stop.

# Scripting

Functions are in gateway scope. Times are Dates or epoch ms.

```python
system.camera.getCameras()
system.camera.getStatus("front")
system.camera.getRecordings("front", start, end)
system.camera.getStorage("front")
system.camera.deleteRecordings("front", start, end)
system.camera.deleteAllRecordings("front")
system.camera.exportRange("front", start, end)
system.camera.exportClip("front", start, end)
system.camera.getExports("front")
system.camera.deleteExport("front", file)

system.camera.getEvents("front", start, end)
system.camera.getEvent("front", id)
system.camera.deleteEvent("front", id)
system.camera.protectEvent("front", id, True)
system.camera.exportEvent("front", id)
system.camera.startRecording("front", "Line stopped")
system.camera.stopRecording("front")
system.camera.addBookmark("front", "Operator note")
system.camera.addBookmark("front", "Batch 42", start, end, True)

system.camera.snapshot("front")
system.camera.thumbnail("front", eventId)

system.camera.ptzSupported("front")
system.camera.ptzMove("front", pan, tilt, zoom)
system.camera.ptzStop("front")
system.camera.ptzHome("front")
system.camera.ptzPresets("front")
system.camera.ptzGotoPreset("front", token)
system.camera.ptzSetPreset("front", "Door")
system.camera.ptzRemovePreset("front", token)

system.camera.discoverOnvif("192.0.2.0/24")
system.camera.getOnvifProfiles("http://192.0.2.20/onvif/device_service", "admin", "password")
```

# Live Video

The default is set in `Settings > Live Transport`, and a component can override it.

| | WebRTC (default) | Low-latency HLS |
|---|---|---|
| Delay | Under 1 second | About 1-2 seconds |
| Ports | Gateway port and media port | Gateway port only |
| Video goes through the gateway | No | Yes |
| AAC audio | No | Yes |

WebRTC falls back to HLS if it cannot connect. For lower HLS delay, set the camera's keyframe interval to 1-2 seconds.

# Transcoding

The gateway transcodes to H.264 when a browser cannot decode the camera's codec, or when a component asks for a profile or custom size. A substream close to the requested size is used instead when there is one.

Viewers of the same camera and size share a live transcode, and it stops shortly after the last viewer leaves. `Maximum Active Transcodes` limits live and recording transcodes together.

Encoders are CPU, VA-API, Intel Quick Sync and NVIDIA NVENC. A GPU encoder needs the device and its driver wherever ffmpeg runs. In a container, pass in the device, such as `/dev/dri`. The Settings page shows an error if the selected encoder does not work.

# External MediaMTX

Use `Camera.modl` or set `Mode` to `External` to run MediaMTX yourself. MediaMTX needs its `-ffmpeg` image for live transcoding. The module was built against MediaMTX 1.21.2.

```yaml
api: yes
hls: yes
hlsVariant: lowLatency
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

In `Settings`, enter the URLs the gateway uses to reach MediaMTX and the same `Auth Callback Key`. Leave `Public URL` blank to send browsers through the gateway. Streams are refused until the key is set.

# Building

Requires JDK 17 and Node.js.

```
./gradlew build                        # Camera.unsigned.modl and unit tests
./gradlew build -Pplatform=linux-x64   # one platform build
scripts/build-all.sh                   # all five, signed if the key is available
IGNITION_STACK=/path/to/stack scripts/deploy.sh   # build and load on a dev gateway
GATEWAY=https://gateway.example.com API_TOKEN_FILE=/path/to/token scripts/harness.sh   # run the components without Perspective
```

Unsigned modules need `-Dignition.allowunsignedmodules=true` on the gateway.

# Releases

Pushing a tag such as `v0.2.0` builds and signs all five modules and creates a GitHub release. The workflow needs two repository secrets, `CAMERA_SIGNING_PFX_BASE64` and `CAMERA_SIGNING_PASSWORD`.
