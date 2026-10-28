package com.kyvislabs.camera.gateway;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;

import com.inductiveautomation.ignition.common.gson.JsonObject;
import com.kyvislabs.camera.gateway.events.CameraEvent;
import org.junit.jupiter.api.Test;

/** The Manage page reads these shapes; a renamed field there is a blank card, not an error. */
class ManageRoutesJsonTest {
    @Test
    void externalModeHasNoSizesAndNoDelete() {
        StorageService.Summary s = new StorageService.Summary("external", false, null, ZoneOffset.UTC, true,
            null, 0, null, null, List.of(new StorageService.Day(LocalDate.of(2026, 10, 27), null, 0, 3600.5, 1L, 2L)),
            2, 1, 1234, 0, 0, List.of(), 10);
        JsonObject o = ManageRoutes.storageJson("cam1", s);
        assertEquals("external", o.get("mode").getAsString());
        assertFalse(o.get("canDelete").getAsBoolean());
        assertFalse(o.has("recordingsPath"));
        assertTrue(o.getAsJsonObject("recordings").get("bytes").isJsonNull(), "size unknown, not zero");
        assertTrue(o.getAsJsonArray("days").get(0).getAsJsonObject().get("bytes").isJsonNull());
        assertEquals(3600.5, o.getAsJsonArray("days").get(0).getAsJsonObject().get("durationSec").getAsDouble());
        assertEquals(1234, o.getAsJsonObject("events").get("bytes").getAsLong());
    }

    @Test
    void embeddedModeReportsEverything() {
        StorageService.Summary s = new StorageService.Summary("embedded", true, "/data/rec", ZoneId.of("America/Edmonton"), false,
            5000L, 2, 10L, 20L, List.of(new StorageService.Day(LocalDate.of(2026, 10, 27), 5000L, 2, 1200, 10L, 20L)),
            0, 0, 0, 1, 99, List.of("recorded durations unavailable: x"), 5);
        JsonObject o = ManageRoutes.storageJson("cam1", s);
        assertTrue(o.get("canDelete").getAsBoolean());
        assertEquals("/data/rec", o.get("recordingsPath").getAsString());
        assertEquals("America/Edmonton", o.get("zone").getAsString());
        assertEquals(5000, o.getAsJsonObject("recordings").get("bytes").getAsLong());
        assertEquals("2026-10-27", o.getAsJsonArray("days").get(0).getAsJsonObject().get("day").getAsString());
        assertEquals(1, o.getAsJsonArray("warnings").size());
        assertEquals(99, o.getAsJsonObject("exports").get("bytes").getAsLong());
        assertEquals(5, o.get("segmentMinutes").getAsInt());
    }

    @Test
    void eventsCarryOpenStateAndClips() {
        CameraEvent open = new CameraEvent("a", "cam1", CameraEvent.Kind.EVENT, 1000, 0, "motion", "event", "", false, List.of());
        JsonObject o = ManageRoutes.eventJson(null, open);
        assertTrue(o.get("end").isJsonNull());
        assertTrue(o.get("open").getAsBoolean());
        assertEquals("event", o.get("kind").getAsString());

        CameraEvent b = new CameraEvent("b", "cam1", CameraEvent.Kind.BOOKMARK, 1000, 2000, "", "user", "operator", true,
            List.of(new CameraEvent.ClipPart("b.mp4", 1000, 2000)));
        JsonObject bo = ManageRoutes.eventJson(null, b);
        assertEquals("bookmark", bo.get("kind").getAsString());
        assertTrue(bo.get("protect").getAsBoolean());
        assertEquals(1, bo.getAsJsonArray("clips").size());
        assertEquals("b.mp4", bo.getAsJsonArray("clips").get(0).getAsJsonObject().get("file").getAsString());
    }

    @Test
    void parametersAreChecked() {
        assertThrows(IllegalArgumentException.class, () -> ManageRoutes.cameraParam("../etc"));
        assertThrows(IllegalArgumentException.class, () -> ManageRoutes.cameraParam(null));
        assertEquals("front-1", ManageRoutes.cameraParam("front-1"));
        assertEquals(ZoneId.of("Europe/Paris"), ManageRoutes.zone("Europe/Paris"));
        assertEquals(ZoneId.systemDefault(), ManageRoutes.zone("Mars/Olympus"), "unknown zones fall back");
    }
}
