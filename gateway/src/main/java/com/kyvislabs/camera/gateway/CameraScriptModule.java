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
import com.kyvislabs.camera.gateway.onvif.OnvifDiscovery;
import com.kyvislabs.camera.gateway.onvif.OnvifProbe;

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

    /**
     * Copies [start, end] out of the recordings, one MP4 per recorded run (so a gap gives two
     * files); returns the paths. The files are listed by {@link #getExports}.
     */
    public List<String> exportRange(String camera, Object start, Object end) throws Exception {
        List<String> out = new ArrayList<>();
        svc().events().exportRange(camera, ms(start), ms(end)).forEach(p -> out.add(p.toString()));
        return out;
    }

    /** The camera's exported files: list of {file, path, bytes, modified}. */
    public List<Map<String, Object>> getExports(String camera) throws Exception {
        List<Map<String, Object>> out = new ArrayList<>();
        for (var e : svc().events().listExports(camera)) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("file", e.file());
            m.put("path", svc().events().exportFile(camera, e.file()).toString());
            m.put("bytes", e.bytes());
            m.put("modified", new Date(e.modifiedMs()));
            out.add(m);
        }
        return out;
    }

    public boolean deleteExport(String camera, String file) throws Exception {
        return svc().events().deleteExport(camera, file);
    }

    // ---- events ----

    public Map<String, Object> getEvent(String camera, String id) throws Exception {
        return svc().events().get(camera, id).map(this::describe).orElse(null);
    }

    /** Removes a closed event or bookmark and its clips. */
    public void deleteEvent(String camera, String id) throws Exception {
        svc().events().delete(camera, id);
    }

    /** Keeps (or releases) an event or bookmark from retention; protecting exports its clip if it has none. */
    public Map<String, Object> protectEvent(String camera, String id, boolean protect) throws Exception {
        return describe(svc().events().setProtect(camera, id, protect));
    }

    /** Exports the event's clip now; fails if the recording no longer holds it. */
    public Map<String, Object> exportEvent(String camera, String id) throws Exception {
        return describe(svc().events().exportNow(camera, id));
    }

    // ---- storage ----

    /**
     * What the camera's recordings take up: {mode, canDelete, bytes, segments, oldest, newest,
     * days: [{day, bytes, segments, durationSec}], eventCount, clipCount, eventBytes, exportFiles,
     * exportBytes}. Sizes are null with an external MediaMTX. Days are in the gateway's zone.
     */
    public Map<String, Object> getStorage(String camera) throws Exception {
        StorageService.Summary s = svc().storage().summary(camera, java.time.ZoneId.systemDefault());
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("mode", s.mode());
        m.put("canDelete", s.canDelete());
        m.put("recordingsPath", s.recordingsPath());
        m.put("bytes", s.bytes());
        m.put("segments", s.segments());
        m.put("oldest", s.oldestMs() == null ? null : new Date(s.oldestMs()));
        m.put("newest", s.newestMs() == null ? null : new Date(s.newestMs()));
        List<Map<String, Object>> days = new ArrayList<>();
        for (StorageService.Day d : s.days()) {
            Map<String, Object> dm = new LinkedHashMap<>();
            dm.put("day", d.day().toString());
            dm.put("bytes", d.bytes());
            dm.put("segments", d.segments());
            dm.put("durationSec", d.durationSec());
            days.add(dm);
        }
        m.put("days", days);
        m.put("eventCount", s.eventCount());
        m.put("clipCount", s.clipCount());
        m.put("eventBytes", s.eventBytes());
        m.put("exportFiles", s.exportFiles());
        m.put("exportBytes", s.exportBytes());
        return m;
    }

    /**
     * Deletes the recording segments starting in [start, end). Refuses when events in the range
     * still wait for their clip, unless {@code force}. Returns {deleted, bytes, skipped}.
     */
    public Map<String, Object> deleteRecordings(String camera, Object start, Object end, boolean force) throws Exception {
        return deleted(svc().storage().deleteRange(camera, ms(start), ms(end), force));
    }

    public Map<String, Object> deleteRecordings(String camera, Object start, Object end) throws Exception {
        return deleteRecordings(camera, start, end, false);
    }

    /** Every segment but the one being written, every closed event and bookmark, and the exports. */
    public Map<String, Object> deleteAllRecordings(String camera) throws Exception {
        return deleted(svc().storage().deleteAll(camera));
    }

    private static Map<String, Object> deleted(com.kyvislabs.camera.gateway.mediamtx.RecordingStore.Deleted d) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("deleted", d.files());
        m.put("bytes", d.bytes());
        m.put("skipped", d.skipped());
        return m;
    }

    // ---- stills ----

    /** A JPEG of the camera now, as bytes (e.g. for system.net.sendEmail attachments). */
    public byte[] snapshot(String camera) throws Exception {
        return snapshot(camera, null, 720);
    }

    /** A JPEG of the camera at {@code time} (null = now), {@code height} rows tall. */
    public byte[] snapshot(String camera, Object time, int height) throws Exception {
        Long t = time == null ? null : ms(time);
        return java.nio.file.Files.readAllBytes(svc().snapshots().snapshot(camera, t, height));
    }

    /** The event's thumbnail JPEG as bytes. */
    public byte[] thumbnail(String camera, String id) throws Exception {
        CameraEvent e = svc().events().get(camera, id).orElseThrow(() -> new IllegalArgumentException("no event " + id));
        return java.nio.file.Files.readAllBytes(svc().snapshots().thumbnail(camera, e));
    }

    // ---- PTZ (ONVIF cameras with a PTZ service) ----

    public boolean ptzSupported(String camera) {
        return svc().ptz().supported(camera);
    }

    /** Starts moving at the given speeds, each -1..1, until {@link #ptzStop}. */
    public void ptzMove(String camera, double pan, double tilt, double zoom) throws Exception {
        svc().ptz().move(camera, pan, tilt, zoom);
    }

    public void ptzStop(String camera) throws Exception {
        svc().ptz().stop(camera);
    }

    public void ptzHome(String camera) throws Exception {
        svc().ptz().home(camera);
    }

    /** The camera's presets: list of {token, name}. */
    public List<Map<String, Object>> ptzPresets(String camera) throws Exception {
        List<Map<String, Object>> out = new ArrayList<>();
        for (var p : svc().ptz().info(camera).presets()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("token", p.token());
            m.put("name", p.name());
            out.add(m);
        }
        return out;
    }

    public void ptzGotoPreset(String camera, String token) throws Exception {
        svc().ptz().gotoPreset(camera, token);
    }

    /** Saves the current position as a preset; returns its token. */
    public String ptzSetPreset(String camera, String name) throws Exception {
        return svc().ptz().setPreset(camera, name);
    }

    public void ptzRemovePreset(String camera, String token) throws Exception {
        svc().ptz().removePreset(camera, token);
    }

    /** WS-Discovery; with a CIDR (e.g. "192.0.2.0/24") also probes each address directly. */
    public List<Map<String, Object>> discoverOnvif(String cidr) throws Exception {
        List<Map<String, Object>> out = new ArrayList<>();
        for (OnvifDiscovery.Device d : OnvifDiscovery.discover(
            cidr == null || cidr.isBlank() ? List.of() : OnvifDiscovery.hosts(cidr), 3000)) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("address", d.address());
            m.put("xaddrs", d.xaddrs());
            m.put("name", d.name());
            m.put("hardware", d.hardware());
            out.add(m);
        }
        return out;
    }

    public List<Map<String, Object>> discoverOnvif() throws Exception {
        return discoverOnvif(null);
    }

    /** Device info and each media profile's RTSP URI (credentials not embedded). */
    public Map<String, Object> getOnvifProfiles(String url, String username, String password) throws Exception {
        OnvifProbe.Result r = OnvifProbe.probe(url, username, password);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("manufacturer", r.device() == null ? null : r.device().manufacturer());
        m.put("model", r.device() == null ? null : r.device().model());
        m.put("events", r.events());
        m.put("mainUrl", r.suggestedMain());
        m.put("subUrl", r.suggestedSub());
        List<Map<String, Object>> profiles = new ArrayList<>();
        for (OnvifProbe.ProfileUri p : r.profiles()) {
            Map<String, Object> pm = new LinkedHashMap<>();
            pm.put("name", p.name());
            pm.put("encoding", p.encoding());
            pm.put("width", p.width());
            pm.put("height", p.height());
            pm.put("uri", p.uri());
            profiles.add(pm);
        }
        m.put("profiles", profiles);
        return m;
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
