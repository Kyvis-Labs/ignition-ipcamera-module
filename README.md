# Camera Module

An Ignition module that adds IP camera live view, recording and playback to Perspective. Cameras are configured on the gateway and shown with the Camera View and Camera Wall components. The module uses [MediaMTX](https://github.com/bluenviron/mediamtx) to pull, record and serve video. MediaMTX runs as a separate service.

The Camera Module provides support for:

1. Generic RTSP cameras and UniFi Protect RTSPS streams
2. Off, continuous and tag driven recording, with pre-roll and post-roll
3. Timeline playback, bookmarks and event clips
4. Live video over WebRTC
5. Status tags in a tag provider called `Cameras`
6. Scripting through `system.camera`

Getting Started
---------------

1. Run MediaMTX, see [MediaMTX](#mediamtx)
2. Build the module, see [Building](#building)
3. Install the module into Ignition 8.3.9+. Start the gateway with `-Dignition.allowunsignedmodules=true`.
4. Set the media server settings and add your cameras through the gateway REST API, under `/data/api/v1/resources/com.kyvislabs.camera/`
5. Add a Camera View or Camera Wall component to a Perspective view and select your camera

# Architecture

```
Browser (Perspective) ── websocket ──▶ Gateway (this module)
  │  asks for a stream                   checks roles, creates a token for one camera
  ├── WebRTC ─────────────────────────▶ MediaMTX
  └── recordings ─────────────────────▶ MediaMTX

Gateway ── control API ──▶ MediaMTX: adds a path for each camera and repairs them on a timer
MediaMTX ── auth callback ──▶ Gateway: checks the token on each request
MediaMTX ── RTSP/RTSPS ──▶ Cameras
```

MediaMTX pulls the camera streams and records them. The gateway owns the configuration and decides who can see what. Browsers connect to MediaMTX directly, so it needs to be reachable over HTTPS, usually through a reverse proxy.

Video is passed through as the camera sends it. A Camera Wall should use camera substreams, otherwise every tile decodes the full resolution stream.

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
| `TAG` with pre-roll | Records to a rolling buffer and copies each event out as a clip | `eventRetentionDays` |
| `TAG` without pre-roll | Starts recording when triggered, about 1-2 seconds late | `eventRetentionDays` |

An event starts `preRollSec` before the first trigger and ends `postRollSec` after the last trigger clears. Long events are split every hour.

> **Note:** Retention is per camera. Switching a camera from `CONTINUOUS` to `TAG` applies the shorter buffer retention to its existing recordings.

**TAG conditions:** `true`, `false`, `> n`, `>= n`, `< n`, `<= n`, `= v`, `!= v`. Blank means truthy.

Tag driven bookmarks work in any record mode.

# Components

## Camera View

`kyvislabs.camera.view`

| Prop | Default | Description |
|---|---|---|
| `camera` | `""` | Camera name |
| `mode` | `live` | `live` or `playback` |
| `playbackTime` | `null` | Time in epoch ms. Updates while playing, and writing it seeks. |
| `quality` | `auto` | `auto`, `main` or `sub` |
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
```

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
./gradlew build      # Camera.unsigned.modl and unit tests
scripts/deploy.sh    # build and load on the dev gateway
scripts/harness.sh   # run the components without Perspective
```

Unsigned modules need `-Dignition.allowunsignedmodules=true` on the gateway.
