package com.kyvislabs.camera.gateway;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import com.inductiveautomation.ignition.common.gson.JsonObject;
import com.kyvislabs.camera.gateway.events.CameraEvent;
import com.kyvislabs.camera.gateway.mediamtx.MediaMtxClient;
import com.kyvislabs.camera.gateway.mediamtx.PathConfigs;

/**
 * Gateway-scope {@code system.camera.*} -- Perspective, gateway event and tag event scripts.
 *
 * <p>Times are accepted as {@link Date} (what Jython passes for {@code system.date.now()}) or
 * epoch milliseconds, and returned as {@link Date}. Results are plain lists and dicts.
 */
public class CameraScriptModule {
    private final Supplier<CameraServices> servicesRef;

    public CameraScriptModule(Supplier<CameraServices> services) {
        this.servicesRef = services;
    }

    private CameraServices svc() {
        CameraServices s = servicesRef.get();
        if (s == null) {
            throw new IllegalStateException("camera module is not started");
        }
        return s;
    }

    /** Names of enabled cameras. */
    public List<String> getCameras() {
        return new ArrayList<>(svc().manager().cameras().keySet());
    }

    /** Stream state for one camera: online, recording, recordMode, codecs, readers. */
    public Map<String, Object> getStatus(String camera) {
        var cfg = svc().manager().camera(camera)
            .orElseThrow(() -> new IllegalArgumentException("no camera named '" + camera + "'"));
        JsonObject main = svc().manager().runtime(PathConfigs.mainPath(camera));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("online", main != null && main.get("ready").getAsBoolean());
        out.put("recording", svc().manager().recording(camera, main));
        out.put("recordMode", cfg.recordMode().name());
        out.put("codecs", CameraManager.codecs(main));
        out.put("readers", main != null && main.has("readers") ? main.getAsJsonArray("readers").size() : 0);
        return out;
    }

    /** Recorded runs between start and end: list of {start, end} dicts. */
    public List<Map<String, Object>> getRecordings(String camera, Object start, Object end) throws Exception {
        List<Map<String, Object>> out = new ArrayList<>();
        for (MediaMtxClient.Span s : svc().manager().client().listRecordings(PathConfigs.mainPath(camera),
            Instant.ofEpochMilli(ms(start)), Instant.ofEpochMilli(ms(end)))) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("start", Date.from(s.start()));
            m.put("end", Date.from(s.end()));
            out.add(m);
        }
        return out;
    }

    /** Opens (or extends) an event on an EVENT/TAG camera until {@link #stopRecording}. */
    public void startRecording(String camera, String label) {
        svc().events().trigger(camera, true, label, "script");
    }

    public void startRecording(String camera) {
        startRecording(camera, "script");
    }

    /** Releases the script trigger; the event closes after the camera's post-roll. */
    public void stopRecording(String camera) {
        svc().events().trigger(camera, false, null, "script");
    }

    public Map<String, Object> addBookmark(String camera, String label) throws Exception {
        long now = System.currentTimeMillis();
        return describe(svc().events().addBookmark(camera, label, now - 10_000, now + 10_000, false, "", "script"));
    }

    /**
     * @param protect also export the clip, so it outlives recording retention
     */
    public Map<String, Object> addBookmark(String camera, String label, Object start, Object end, boolean protect)
        throws Exception {
        return describe(svc().events().addBookmark(camera, label, ms(start), ms(end), protect, "", "script"));
    }

    /** Events and bookmarks overlapping [start, end]. */
    public List<Map<String, Object>> getEvents(String camera, Object start, Object end) throws Exception {
        List<Map<String, Object>> out = new ArrayList<>();
        for (CameraEvent e : svc().events().list(camera, ms(start), ms(end))) {
            out.add(describe(e));
        }
        return out;
    }

    /** Copies [start, end] to an MP4 under the camera's exports folder; returns its path. */
    public String exportClip(String camera, Object start, Object end) throws Exception {
        return svc().events().exportClip(camera, ms(start), ms(end)).toString();
    }

    private Map<String, Object> describe(CameraEvent e) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", e.id());
        m.put("camera", e.camera());
        m.put("kind", e.kind().name());
        m.put("start", new Date(e.startMs()));
        m.put("end", e.open() ? null : new Date(e.endMs()));
        m.put("label", e.label());
        m.put("source", e.source());
        m.put("protect", e.protect());
        List<String> clips = new ArrayList<>();
        for (CameraEvent.ClipPart part : e.clips()) {
            clips.add(svc().events().store().clipPath(e.camera(), part).toString());
        }
        m.put("clips", clips);
        return m;
    }

    static long ms(Object t) {
        if (t instanceof Date d) {
            return d.getTime();
        }
        if (t instanceof Number n) {
            return n.longValue();
        }
        if (t instanceof String s) {
            return Instant.parse(s).toEpochMilli();
        }
        throw new IllegalArgumentException("expected a date or epoch milliseconds, got " + t);
    }
}
