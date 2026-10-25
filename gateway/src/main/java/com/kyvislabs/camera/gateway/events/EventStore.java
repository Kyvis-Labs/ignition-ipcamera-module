package com.kyvislabs.camera.gateway.events;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Predicate;

import com.inductiveautomation.ignition.common.gson.Gson;
import com.inductiveautomation.ignition.common.gson.GsonBuilder;
import com.inductiveautomation.ignition.common.gson.reflect.TypeToken;

/**
 * Events, bookmarks and their clips, one folder per camera:
 * {@code <root>/<camera>/events.json} plus {@code <id>.mp4} clips.
 *
 * <p>A plain JSON file, rewritten atomically on every change, is deliberate: volumes are
 * hundreds to low thousands per camera, the gateway has no database to depend on, and the
 * folder can be copied, inspected and backed up as-is.
 */
public class EventStore {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private final Path root;
    private final Map<String, List<CameraEvent>> cache = new HashMap<>();

    public EventStore(Path root) {
        this.root = root;
    }

    public Path root() {
        return root;
    }

    public Path folder(String camera) {
        return root.resolve(camera);
    }

    public Path clipPath(String camera, CameraEvent.ClipPart part) {
        return folder(camera).resolve(part.file());
    }

    public synchronized void put(CameraEvent e) throws IOException {
        List<CameraEvent> list = new ArrayList<>(load(e.camera()));
        list.removeIf(x -> x.id().equals(e.id()));
        list.add(e);
        list.sort(Comparator.comparingLong(CameraEvent::startMs));
        save(e.camera(), list);
    }

    public synchronized Optional<CameraEvent> get(String camera, String id) throws IOException {
        return load(camera).stream().filter(e -> e.id().equals(id)).findFirst();
    }

    /** Events overlapping [startMs, endMs), oldest first. */
    public synchronized List<CameraEvent> list(String camera, long startMs, long endMs) throws IOException {
        return load(camera).stream().filter(e -> e.overlaps(startMs, endMs)).toList();
    }

    /** Deletes matching events and their clips; returns how many were removed. */
    public synchronized int removeIf(String camera, Predicate<CameraEvent> doomed) throws IOException {
        List<CameraEvent> list = new ArrayList<>(load(camera));
        List<CameraEvent> gone = list.stream().filter(doomed).toList();
        if (gone.isEmpty()) {
            return 0;
        }
        for (CameraEvent e : gone) {
            for (CameraEvent.ClipPart part : e.clips()) {
                Files.deleteIfExists(clipPath(camera, part));
            }
        }
        list.removeAll(gone);
        save(camera, list);
        return gone.size();
    }

    private List<CameraEvent> load(String camera) throws IOException {
        List<CameraEvent> cached = cache.get(camera);
        if (cached != null) {
            return cached;
        }
        Path file = folder(camera).resolve("events.json");
        List<CameraEvent> list = List.of();
        if (Files.exists(file)) {
            try (Reader r = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                List<CameraEvent> read = GSON.fromJson(r, new TypeToken<List<CameraEvent>>() { }.getType());
                list = read == null ? List.of() : List.copyOf(read);
            }
        }
        cache.put(camera, list);
        return list;
    }

    private void save(String camera, List<CameraEvent> list) throws IOException {
        Path dir = Files.createDirectories(folder(camera));
        Path tmp = dir.resolve("events.json.tmp");
        try (Writer w = Files.newBufferedWriter(tmp, StandardCharsets.UTF_8)) {
            GSON.toJson(list, w);
        }
        try {
            Files.move(tmp, dir.resolve("events.json"), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(tmp, dir.resolve("events.json"), StandardCopyOption.REPLACE_EXISTING);
        }
        cache.put(camera, List.copyOf(list));
    }
}
