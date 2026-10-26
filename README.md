# Camera Module

An Ignition module that adds IP camera live view, recording and playback to Perspective. Cameras are configured on the gateway and shown with the Camera View and Camera Wall components. The module uses [MediaMTX](https://github.com/bluenviron/mediamtx) to pull, record and serve video. MediaMTX can run inside the module or as a separate service.

The Camera Module provides support for:

1. Generic RTSP cameras, ONVIF cameras and UniFi Protect RTSPS streams
2. ONVIF discovery, stream URL lookup and events
3. Off, continuous, camera event and tag driven recording, with pre-roll and post-roll
4. Timeline playback, bookmarks and event clips
5. Low-latency HLS over the gateway's own port, or WebRTC for lower latency
6. Transcoding to H.264 for browsers that cannot decode a camera's codec, or to save bandwidth
7. Status tags in a tag provider called `Cameras`
8. Scripting through `system.camera`

Getting Started
---------------

1. Build the module, see [Building](#building)
2. Install the module into Ignition 8.3.9+. Start the gateway with `-Dignition.allowunsignedmodules=true`.
3. Log into the Ignition Gateway
4. Click on `Connections > Cameras > Settings` and check the media server settings. The platform builds need no changes, but `Media Port` (8189, UDP and TCP) must be open in the host firewall to use WebRTC.
5. Click on `Connections > Cameras > Connections` and click `Create Camera`
6. Enter the camera manually or click `Discover Cameras` to find ONVIF cameras
7. Add a Camera View or Camera Wall component to a Perspective view and select your camera

| Module | MediaMTX |
|---|---|
| `Camera-windows-x64.unsigned.modl` | Included, Windows x64 |
| `Camera-linux-x64.unsigned.modl` | Included, Linux x64 |
| `Camera-linux-arm64.unsigned.modl` | Included, Linux ARM64 |
| `Camera-macos-arm64.unsigned.modl` | Included, macOS on Apple silicon |
| `Camera.unsigned.modl` | Not included, see [External MediaMTX](#external-mediamtx) |

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
- **ONVIF:** Enter the credentials and click `Fill Stream URLs from ONVIF`.
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

# Components

## Camera View

`kyvislabs.camera.view`

| Prop | Default | Description |
|---|---|---|
| `camera` | `""` | Camera name |
| `mode` | `live` | `live` or `playback` |
| `playbackTime` | `null` | Time in epoch ms. Updates while playing, and writing it seeks. |
| `quality` | `auto` | `auto`, `main`, `sub`, a transcode profile such as `720p`, or `custom` |
| `customQuality` | `{height: 540, bitrateKbps: 1200}` | Size used by `custom` |
| `transport` | `auto` | `auto` (gateway default), `hls` or `webrtc` |
| `muted` | `true` | Mutes the audio |
| `objectFit` | `contain` | `contain`, `cover` or `fill` |
| `showStatus` | `true` | Shows the name over the video |
| `showControls` | `true` | Shows the control bar |
| `showTimeline` | `true` | Shows the timeline |
| `allowBookmarks` | `true` | Shows the bookmark button |

Events: `onModeChange`, `onBookmark` and `onMarkerClick`.

## Camera Wall

`kyvislabs.camera.wall`

| Prop | Default | Description |
|---|---|---|
| `cameras` | `[]` | Camera names, or `{camera, label}` |
| `columns`, `rows` | `0` | Grid size, `0` for automatic. Extra cameras go on more pages. |
| `gap` | `2` | Pixels between tiles |
| `tourSeconds` | `0` | Seconds between pages, `0` for off |
| `mode`, `playbackTime` | | Shared by all tiles |
| `transport` | `auto` | Same as Camera View |
| `objectFit` | `cover` | Same as Camera View |
| `maxLiveStreams` | `16` | Maximum tiles playing live at once |
| `showStatus`, `showControls`, `showTimeline` | `true` | Shows each part |

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

In `EVENT` mode, put an alarm on `EventSourceOK = false` so you know if the camera's events stop.

# Scripting

Functions are in gateway scope. Times are Dates or epoch ms.

```python
system.camera.getCameras()
system.camera.getStatus("front")
system.camera.getRecordings("front", start, end)
system.camera.exportClip("front", start, end)

system.camera.getEvents("front", start, end)
system.camera.startRecording("front", "Line stopped")
system.camera.stopRecording("front")
system.camera.addBookmark("front", "Operator note")
system.camera.addBookmark("front", "Batch 42", start, end, True)

system.camera.discoverOnvif("192.0.2.0/24")
system.camera.getOnvifProfiles("http://192.0.2.20/onvif/device_service", "admin", "password")
```

# Live Video

The default is set in `Settings > Live Transport`, and a component can override it.

| | Low-latency HLS (default) | WebRTC |
|---|---|---|
| Delay | About 1-2 seconds | Under 1 second |
| Ports | Gateway port only | Gateway port and media port |
| Video goes through the gateway | Yes | No |
| AAC audio | Yes | No |

WebRTC falls back to HLS if it cannot connect. For lower HLS delay, set the camera's keyframe interval to 1-2 seconds.

# Transcoding

The gateway transcodes to H.264 when a browser cannot decode the camera's codec, or when a component asks for a profile or custom size. A substream close to the requested size is used instead when there is one.

Viewers of the same camera and size share a live transcode, and it stops shortly after the last viewer leaves. `Maximum Active Transcodes` limits live and recording transcodes together.

Encoders are CPU, VA-API, Intel Quick Sync and NVIDIA NVENC. A GPU encoder needs the device and its driver wherever ffmpeg runs. In a container, pass in the device, such as `/dev/dri`. The Settings page shows an error if the selected encoder does not work.

# External MediaMTX

Use `Camera.unsigned.modl` or set `Mode` to `External` to run MediaMTX yourself. MediaMTX needs its `-ffmpeg` image for live transcoding. The module was built against MediaMTX 1.21.2.

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
scripts/build-all.sh                   # all five
scripts/deploy.sh                      # build and load on the dev gateway
scripts/harness.sh                     # run the components without Perspective
```

Unsigned modules need `-Dignition.allowunsignedmodules=true` on the gateway.
