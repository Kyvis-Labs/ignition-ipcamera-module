package com.kyvislabs.camera.gateway.onvif;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The subscription loop against a stub ONVIF device that has a Media service but no Events
 * service: a tags-only subscription reports that once and stops, an EVENT-mode one keeps trying.
 */
class OnvifSubscriberLoopTest {
    private static final String NO_EVENTS = """
        <s:Envelope xmlns:s="http://www.w3.org/2003/05/soap-envelope" xmlns:tds="http://www.onvif.org/ver10/device/wsdl" xmlns:tt="http://www.onvif.org/ver10/schema">
        <s:Body><tds:GetCapabilitiesResponse><tds:Capabilities>
          <tt:Media><tt:XAddr>http://127.0.0.1/onvif/media</tt:XAddr></tt:Media>
        </tds:Capabilities></tds:GetCapabilitiesResponse></s:Body></s:Envelope>""";

    private HttpServer server;
    private final AtomicInteger requests = new AtomicInteger();
    private final List<String> health = new CopyOnWriteArrayList<>();

    @BeforeEach
    void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/onvif/device_service", ex -> {
            requests.incrementAndGet();
            byte[] body = NO_EVENTS.getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", "application/soap+xml");
            ex.sendResponseHeaders(200, body.length);
            try (OutputStream out = ex.getResponseBody()) {
                out.write(body);
            }
        });
        server.start();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private OnvifEventSubscriber subscriber() {
        OnvifClient client = new OnvifClient("http://127.0.0.1:" + server.getAddress().getPort() + "/onvif/device_service", "u", "p");
        return new OnvifEventSubscriber("cam", client, List.of(), (cam, key, label, on) -> { },
            (cam, ok, err) -> health.add(ok + ":" + err));
    }

    @Test
    void tagsOnlySubscriptionGivesUpOnACameraWithoutEvents() throws Exception {
        OnvifEventSubscriber s = subscriber();
        s.setRequired(false);
        s.start();
        waitUntil(() -> !s.isAlive(), 5000);
        assertFalse(s.isAlive(), "the loop ends instead of retrying");
        assertEquals(List.of("false:camera has no ONVIF events service"), health);
        int seen = requests.get();
        Thread.sleep(2500);
        assertEquals(seen, requests.get(), "no further requests to the camera");
    }

    @Test
    void requiredSubscriptionKeepsRetrying() throws Exception {
        OnvifEventSubscriber s = subscriber();
        s.start();
        try {
            // first failure immediately, the retry 2 s later
            waitUntil(() -> health.size() >= 2, 8000);
            assertTrue(health.size() >= 2, "retried: " + health);
            assertTrue(health.stream().allMatch("false:camera has no ONVIF events service"::equals), health.toString());
            assertTrue(s.isAlive());
        } finally {
            s.close();
        }
        waitUntil(() -> !s.isAlive(), 5000);
        assertFalse(s.isAlive(), "close stops the retry loop");
    }

    private static void waitUntil(java.util.function.BooleanSupplier done, long ms) throws InterruptedException {
        long deadline = System.currentTimeMillis() + ms;
        while (!done.getAsBoolean() && System.currentTimeMillis() < deadline) {
            Thread.sleep(50);
        }
    }
}
