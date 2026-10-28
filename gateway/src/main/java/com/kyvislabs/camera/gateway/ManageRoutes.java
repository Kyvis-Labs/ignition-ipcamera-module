package com.kyvislabs.camera.gateway;

import java.nio.file.Files;
import java.time.ZoneId;
import java.util.List;
import java.util.function.Supplier;

import com.inductiveautomation.ignition.common.gson.JsonArray;
import com.inductiveautomation.ignition.common.gson.JsonObject;
import com.inductiveautomation.ignition.common.gson.JsonParser;
import com.inductiveautomation.ignition.gateway.dataroutes.HttpMethod;
import com.inductiveautomation.ignition.gateway.dataroutes.PermissionType;
import com.inductiveautomation.ignition.gateway.dataroutes.RequestContext;
import com.inductiveautomation.ignition.gateway.dataroutes.RouteGroup;
import com.inductiveautomation.ignition.gateway.dataroutes.RouteHandler;
import com.kyvislabs.camera.gateway.config.CameraConfig;
import com.kyvislabs.camera.gateway.events.CameraEvent;
import com.kyvislabs.camera.gateway.mediamtx.RecordingStore;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Routes behind the gateway's per-camera Manage page, under the platform's own permissions
 * (READ for the GETs, WRITE for every POST) like the rest of {@code /data/camera}:
 *
 * <ul>
 *   <li>{@code POST /live}, {@code /timeline}, {@code /playback} -- the viewer ops the Perspective
 *   components use, same request and response shapes ({@link CameraOps}).</li>
 *   <li>{@code GET /storage?camera=&tz=} -- size and per-day breakdown of the camera's recordings,
 *   events and exports; {@code canDelete} says whether the gateway can remove segments
 *   (embedded MediaMTX only).</li>
 *   <li>{@code GET /events?camera=&start=&end=} -- events and bookmarks, newest first.</li>
 *   <li>{@code POST /recordings/delete} {@code {camera, start, end, force?}} -- segments starting in
 *   [start, end); 409 with the events when unexported events overlap and not {@code force}.</li>
 *   <li>{@code POST /recordings/delete-all} {@code {camera}} -- everything but the segment being written.</li>
 *   <li>{@code POST /events/delete} {@code {camera, id}}, {@code /events/protect} {@code {camera, id, protect}},
 *   {@code /events/export} {@code {camera, id}}.</li>
 *   <li>{@code POST /snapshot} {@code {camera, time?, height?}} -> a snapshot URL; {@code POST /ptz}
 *   {@code {camera, action, ...}} -- see {@link CameraOps#ptz}.</li>
 *   <li>{@code GET /exports?camera=}, {@code POST /exports} {@code {camera, start, end}} (a range copied
 *   out as MP4, at most four hours), {@code POST /exports/delete} {@code {camera, file}}. Downloads go
 *   through {@code GET /export?camera=&file=&token=} ({@link MediaRoutes}).</li>
 * </ul>
 */
public final class ManageRoutes {
    private ManageRoutes() {
    }

    public static void mount(RouteGroup routes, Supplier<CameraServices> ref, Supplier<CameraOps> ops) {
        // WRITE, not READ: the platform refuses a POST behind a read-only session check (no CSRF
        // token is required for reads), and the page is for gateway admins anyway
        for (String op : List.of("live", "timeline", "playback", "snapshot", "ptz")) {
            routes.newRoute("/" + op)
                .method(HttpMethod.POST)
                .requirePermission(PermissionType.WRITE)
                .type(RouteGroup.TYPE_JSON)
                .nocache()
                .handler(json((req, res) -> {
                    JsonObject body = JsonParser.parseString(req.readBody()).getAsJsonObject();
                    CameraOps o = ops.get();
                    return switch (op) {
                        case "live" -> o.live(body, req.getActor(), cfg -> true);
                        case "timeline" -> o.timeline(body, req.getActor(), cfg -> true);
                        case "snapshot" -> o.snapshot(body, req.getActor(), cfg -> true);
                        case "ptz" -> o.ptz(body, cfg -> true);
                        default -> o.playback(body, req.getActor(), cfg -> true);
                    };
                }))
                .mount();
        }

        routes.newRoute("/exports")
            .method(HttpMethod.GET)
            .requirePermission(PermissionType.READ)
            .type(RouteGroup.TYPE_JSON)
            .nocache()
            .handler(json((req, res) -> {
                String camera = cameraParam(req.getParameter("camera"));
                JsonArray arr = new JsonArray();
                for (com.kyvislabs.camera.gateway.events.EventService.Export e : ref.get().events().listExports(camera)) {
                    arr.add(exportJson(e));
                }
                JsonObject out = new JsonObject();
                out.add("exports", arr);
                return out;
            }))
            .mount();

        // {camera, start, end} -> {exports: [...]}: the range copied out of MediaMTX, one file per recorded run
        routes.newRoute("/exports")
            .method(HttpMethod.POST)
            .requirePermission(PermissionType.WRITE)
            .type(RouteGroup.TYPE_JSON)
            .nocache()
            .handler(json((req, res) -> {
                JsonObject body = JsonParser.parseString(req.readBody()).getAsJsonObject();
                String camera = cameraParam(body.get("camera").getAsString());
                long start = body.get("start").getAsLong();
                long end = body.get("end").getAsLong();
                if (end - start > MAX_EXPORT_MS) {
                    throw new IllegalArgumentException("exports are limited to " + MAX_EXPORT_MS / 3_600_000 + " hours at a time");
                }
                JsonArray arr = new JsonArray();
                for (java.nio.file.Path p : ref.get().events().exportRange(camera, start, end)) {
                    arr.add(exportJson(new com.kyvislabs.camera.gateway.events.EventService.Export(
                        p.getFileName().toString(), Files.size(p), Files.getLastModifiedTime(p).toMillis())));
                }
                JsonObject out = new JsonObject();
                out.add("exports", arr);
                return out;
            }))
            .mount();

        routes.newRoute("/exports/delete")
            .method(HttpMethod.POST)
            .requirePermission(PermissionType.WRITE)
            .type(RouteGroup.TYPE_JSON)
            .nocache()
            .handler(json((req, res) -> {
                JsonObject body = JsonParser.parseString(req.readBody()).getAsJsonObject();
                boolean gone = ref.get().events().deleteExport(cameraParam(body.get("camera").getAsString()), body.get("file").getAsString());
                JsonObject out = new JsonObject();
                out.addProperty("deleted", gone ? 1 : 0);
                return out;
            }))
            .mount();

        routes.newRoute("/storage")
            .method(HttpMethod.GET)
            .requirePermission(PermissionType.READ)
            .type(RouteGroup.TYPE_JSON)
            .nocache()
            .handler(json((req, res) -> {
                String camera = cameraParam(req.getParameter("camera"));
                ZoneId zone = zone(req.getParameter("tz"));
                return storageJson(camera, ref.get().storage().summary(camera, zone));
            }))
            .mount();

        routes.newRoute("/events")
            .method(HttpMethod.GET)
            .requirePermission(PermissionType.READ)
            .type(RouteGroup.TYPE_JSON)
            .nocache()
            .handler(json((req, res) -> {
                String camera = cameraParam(req.getParameter("camera"));
                long now = System.currentTimeMillis();
                long start = longParam(req.getParameter("start"), now - 30L * 86_400_000);
                long end = longParam(req.getParameter("end"), now + 60_000);
                CameraServices s = ref.get();
                JsonArray arr = new JsonArray();
                List<CameraEvent> list = s.events().list(camera, start, end);
                for (int i = list.size() - 1; i >= 0; i--) {
                    arr.add(eventJson(s, list.get(i)));
                }
                JsonObject out = new JsonObject();
                out.add("events", arr);
                return out;
            }))
            .mount();

        routes.newRoute("/recordings/delete")
            .method(HttpMethod.POST)
            .requirePermission(PermissionType.WRITE)
            .type(RouteGroup.TYPE_JSON)
            .nocache()
            .handler(json((req, res) -> {
                JsonObject body = JsonParser.parseString(req.readBody()).getAsJsonObject();
                String camera = cameraParam(body.get("camera").getAsString());
                boolean force = body.has("force") && body.get("force").getAsBoolean();
                RecordingStore.Deleted d = ref.get().storage().deleteRange(camera,
                    body.get("start").getAsLong(), body.get("end").getAsLong(), force);
                return deletedJson(d, -1);
            }))
            .mount();

        routes.newRoute("/recordings/delete-all")
            .method(HttpMethod.POST)
            .requirePermission(PermissionType.WRITE)
            .type(RouteGroup.TYPE_JSON)
            .nocache()
            .handler(json((req, res) -> {
                JsonObject body = JsonParser.parseString(req.readBody()).getAsJsonObject();
                String camera = cameraParam(body.get("camera").getAsString());
                CameraServices s = ref.get();
                int before = s.events().list(camera, 0, Long.MAX_VALUE).size();
                RecordingStore.Deleted d = s.storage().deleteAll(camera);
                int after = s.events().list(camera, 0, Long.MAX_VALUE).size();
                return deletedJson(d, before - after);
            }))
            .mount();

        routes.newRoute("/events/delete")
            .method(HttpMethod.POST)
            .requirePermission(PermissionType.WRITE)
            .type(RouteGroup.TYPE_JSON)
            .nocache()
            .handler(json((req, res) -> {
                JsonObject body = JsonParser.parseString(req.readBody()).getAsJsonObject();
                ref.get().events().delete(cameraParam(body.get("camera").getAsString()), body.get("id").getAsString());
                JsonObject out = new JsonObject();
                out.addProperty("deleted", 1);
                return out;
            }))
            .mount();

        routes.newRoute("/events/protect")
            .method(HttpMethod.POST)
            .requirePermission(PermissionType.WRITE)
            .type(RouteGroup.TYPE_JSON)
            .nocache()
            .handler(json((req, res) -> {
                JsonObject body = JsonParser.parseString(req.readBody()).getAsJsonObject();
                CameraServices s = ref.get();
                CameraEvent e = s.events().setProtect(cameraParam(body.get("camera").getAsString()),
                    body.get("id").getAsString(), body.get("protect").getAsBoolean());
                return eventJson(s, e);
            }))
            .mount();

        routes.newRoute("/events/export")
            .method(HttpMethod.POST)
            .requirePermission(PermissionType.WRITE)
            .type(RouteGroup.TYPE_JSON)
            .nocache()
            .handler(json((req, res) -> {
                JsonObject body = JsonParser.parseString(req.readBody()).getAsJsonObject();
                CameraServices s = ref.get();
                CameraEvent e = s.events().exportNow(cameraParam(body.get("camera").getAsString()), body.get("id").getAsString());
                return eventJson(s, e);
            }))
            .mount();
    }

    /** Longest range one export request copies; longer ones are several requests. */
    static final long MAX_EXPORT_MS = 4 * 3_600_000L;

    // ---- JSON ----

    static JsonObject exportJson(com.kyvislabs.camera.gateway.events.EventService.Export e) {
        JsonObject o = new JsonObject();
        o.addProperty("file", e.file());
        o.addProperty("bytes", e.bytes());
        o.addProperty("modified", e.modifiedMs());
        return o;
    }

    static JsonObject storageJson(String camera, StorageService.Summary s) {
        JsonObject out = new JsonObject();
        out.addProperty("camera", camera);
        out.addProperty("mode", s.mode());
        out.addProperty("canDelete", s.canDelete());
        if (s.recordingsPath() != null) {
            out.addProperty("recordingsPath", s.recordingsPath());
        }
        out.addProperty("zone", s.zone().getId());
        out.addProperty("recordingNow", s.recordingNow());
        out.addProperty("segmentMinutes", s.segmentMinutes());
        JsonObject rec = new JsonObject();
        rec.addProperty("bytes", s.bytes());
        rec.addProperty("segments", s.segments());
        rec.addProperty("oldest", s.oldestMs());
        rec.addProperty("newest", s.newestMs());
        out.add("recordings", rec);
        JsonArray days = new JsonArray();
        for (StorageService.Day d : s.days()) {
            JsonObject o = new JsonObject();
            o.addProperty("day", d.day().toString());
            o.addProperty("bytes", d.bytes());
            o.addProperty("segments", d.segments());
            o.addProperty("durationSec", d.durationSec());
            o.addProperty("from", d.fromMs());
            o.addProperty("to", d.toMs());
            days.add(o);
        }
        out.add("days", days);
        JsonObject ev = new JsonObject();
        ev.addProperty("count", s.eventCount());
        ev.addProperty("clips", s.clipCount());
        ev.addProperty("bytes", s.eventBytes());
        out.add("events", ev);
        JsonObject ex = new JsonObject();
        ex.addProperty("files", s.exportFiles());
        ex.addProperty("bytes", s.exportBytes());
        out.add("exports", ex);
        JsonArray warnings = new JsonArray();
        s.warnings().forEach(warnings::add);
        out.add("warnings", warnings);
        return out;
    }

    static JsonObject eventJson(CameraServices s, CameraEvent e) {
        JsonObject o = new JsonObject();
        o.addProperty("id", e.id());
        o.addProperty("kind", e.kind() == CameraEvent.Kind.EVENT ? "event" : "bookmark");
        o.addProperty("start", e.startMs());
        if (e.open()) {
            o.add("end", com.inductiveautomation.ignition.common.gson.JsonNull.INSTANCE);
        } else {
            o.addProperty("end", e.endMs());
        }
        o.addProperty("label", e.label());
        o.addProperty("source", e.source());
        o.addProperty("user", e.user());
        o.addProperty("protect", e.protect());
        o.addProperty("open", e.open());
        JsonArray clips = new JsonArray();
        for (CameraEvent.ClipPart part : e.clips()) {
            JsonObject c = new JsonObject();
            c.addProperty("file", part.file());
            c.addProperty("start", part.startMs());
            c.addProperty("end", part.endMs());
            long bytes = 0;
            if (s != null) {
                try {
                    bytes = Files.size(s.events().store().clipPath(e.camera(), part));
                } catch (Exception missing) {
                    bytes = 0;
                }
            }
            c.addProperty("bytes", bytes);
            clips.add(c);
        }
        o.add("clips", clips);
        return o;
    }

    static JsonObject deletedJson(RecordingStore.Deleted d, int events) {
        JsonObject out = new JsonObject();
        out.addProperty("deleted", d.files());
        out.addProperty("bytes", d.bytes());
        out.addProperty("skipped", d.skipped());
        if (events >= 0) {
            out.addProperty("events", events);
        }
        return out;
    }

    // ---- helpers ----

    static String cameraParam(String camera) {
        if (camera == null || !CameraConfig.NAME.matcher(camera).matches()) {
            throw new IllegalArgumentException("invalid camera name");
        }
        return camera;
    }

    static ZoneId zone(String tz) {
        if (tz == null || tz.isBlank()) {
            return ZoneId.systemDefault();
        }
        try {
            return ZoneId.of(tz);
        } catch (java.time.DateTimeException e) {
            return ZoneId.systemDefault();
        }
    }

    private static long longParam(String v, long dflt) {
        try {
            return v == null || v.isBlank() ? dflt : Long.parseLong(v);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("not a number: " + v);
        }
    }

    /** {error} JSON with a status that says why: 400 bad input, 409 conflict, 502 anything downstream. */
    private static RouteHandler json(RouteHandler h) {
        return (RequestContext req, HttpServletResponse res) -> {
            try {
                return h.handle(req, res);
            } catch (StorageService.StorageConflict e) {
                res.setStatus(HttpServletResponse.SC_CONFLICT);
                JsonObject out = error(e);
                JsonArray events = new JsonArray();
                e.events.forEach(ev -> events.add(eventJson(null, ev)));
                out.add("events", events);
                return out;
            } catch (IllegalStateException e) {
                res.setStatus(HttpServletResponse.SC_CONFLICT);
                return error(e);
            } catch (IllegalArgumentException | com.inductiveautomation.ignition.common.gson.JsonParseException e) {
                res.setStatus(HttpServletResponse.SC_BAD_REQUEST);
                return error(e);
            } catch (Exception e) {
                res.setStatus(HttpServletResponse.SC_BAD_GATEWAY);
                return error(e);
            }
        };
    }

    private static JsonObject error(Exception e) {
        JsonObject o = new JsonObject();
        o.addProperty("error", e.getMessage() == null ? e.toString() : e.getMessage());
        return o;
    }
}
