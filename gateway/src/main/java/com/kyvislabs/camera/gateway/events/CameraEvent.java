package com.kyvislabs.camera.gateway.events;

import java.util.List;

/**
 * A recorded event (from EVENT/TAG recording) or a bookmark, as stored and shown on timelines.
 *
 * @param startMs epoch ms, including pre-roll
 * @param endMs   epoch ms including post-roll; 0 while an event is still open
 * @param clips   exported clip parts in the camera's event folder; more than one when the
 *                event spans a gap in the recording (camera reconnect), which MediaMTX's
 *                playback server will not stitch across
 */
public record CameraEvent(
    String id,
    String camera,
    Kind kind,
    long startMs,
    long endMs,
    String label,
    String source,
    String user,
    boolean protect,
    List<ClipPart> clips
) {
    public enum Kind { EVENT, BOOKMARK }

    public record ClipPart(String file, long startMs, long endMs) {
    }

    public CameraEvent {
        clips = clips == null ? List.of() : List.copyOf(clips);
    }

    public CameraEvent withEnd(long end) {
        return new CameraEvent(id, camera, kind, startMs, end, label, source, user, protect, clips);
    }

    public CameraEvent withClips(List<ClipPart> parts) {
        return new CameraEvent(id, camera, kind, startMs, endMs, label, source, user, protect, parts);
    }

    /** The clip part containing t, if any. */
    public ClipPart clipAt(long t) {
        for (ClipPart c : clips) {
            if (c.startMs() <= t && c.endMs() > t) {
                return c;
            }
        }
        return null;
    }

    public boolean open() {
        return endMs == 0;
    }

    public boolean overlaps(long a, long b) {
        long end = open() ? Long.MAX_VALUE : endMs;
        return startMs < b && end > a;
    }
}
