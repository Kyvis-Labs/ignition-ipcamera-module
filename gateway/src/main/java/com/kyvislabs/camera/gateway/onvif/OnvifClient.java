package com.kyvislabs.camera.gateway.onvif;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.w3c.dom.Document;
import org.w3c.dom.Element;

/**
 * Minimal ONVIF client: device info, media profiles and stream URIs (Profile S, media ver10),
 * and PullPoint events. No generated stubs -- a handful of hand-written requests covers what
 * the camera module needs and works across vendors.
 *
 * <p>Authentication: WS-Security UsernameToken digests on every request, with HTTP Digest
 * added when the camera answers 401 (many Hikvision/Dahua firmwares want both).
 */
public class OnvifClient {
    static final String DEVICE_NS = "http://www.onvif.org/ver10/device/wsdl";
    static final String MEDIA_NS = "http://www.onvif.org/ver10/media/wsdl";
    static final String EVENTS_NS = "http://www.onvif.org/ver10/events/wsdl";
    static final String SCHEMA_NS = "http://www.onvif.org/ver10/schema";
    static final String TOPIC_NS = "http://docs.oasis-open.org/wsn/t-1";

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private final String deviceUrl;
    private final String user;
    private final String password;
    private Duration clockOffset = Duration.ZERO;
    private String mediaUrl;
    private String eventsUrl;

    public OnvifClient(String deviceUrl, String user, String password) {
        this.deviceUrl = deviceUrl;
        this.user = user;
        this.password = password;
    }

    public record Profile(String token, String name, String encoding, int width, int height) {
    }

    /**
     * One event topic the camera can raise.
     *
     * @param topic     path without the namespace prefix, e.g. RuleEngine/CellMotionDetector/Motion;
     *                  a configured event type matches notifications whose topic contains it
     * @param dataItems names of the message's data items, e.g. IsMotion or State
     * @param stateful  whether a data item carries an on/off state the recorder can follow
     * @param listed    whether GetEventProperties lists it (false: only seen in notifications)
     */
    public record EventTopic(String topic, List<String> dataItems, boolean stateful, boolean listed) {
    }

    public record DeviceInfo(String manufacturer, String model, String firmware, String serial) {
    }

    /**
     * Measures the camera's clock against ours (digests carry a timestamp the camera checks)
     * and finds the media and events service addresses.
     */
    public void connect() throws IOException {
        try {
            Document d = call(deviceUrl, "<GetSystemDateAndTime xmlns=\"" + DEVICE_NS + "\"/>", false, null, Duration.ofSeconds(5));
            Element utc = Soap.first(d, "UTCDateTime");
            if (utc != null) {
                LocalDateTime t = LocalDateTime.of(
                    Integer.parseInt(Soap.text(utc, "Year")), Integer.parseInt(Soap.text(utc, "Month")),
                    Integer.parseInt(Soap.text(utc, "Day")), Integer.parseInt(Soap.text(utc, "Hour")),
                    Integer.parseInt(Soap.text(utc, "Minute")), Integer.parseInt(Soap.text(utc, "Second")));
                clockOffset = Duration.between(Instant.now(), t.toInstant(ZoneOffset.UTC));
            }
        } catch (IOException e) {
            // some cameras require auth even for this; carry on with our own clock
            clockOffset = Duration.ZERO;
        }
        Document caps = call(deviceUrl, "<GetCapabilities xmlns=\"" + DEVICE_NS + "\"><Category>All</Category></GetCapabilities>",
            true, null, Duration.ofSeconds(5));
        Element media = Soap.first(caps, "Media");
        Element events = Soap.first(caps, "Events");
        mediaUrl = media == null ? deviceUrl : fixHost(Soap.text(media, "XAddr"));
        eventsUrl = events == null ? null : fixHost(Soap.text(events, "XAddr"));
    }

    public Duration clockOffset() {
        return clockOffset;
    }

    public DeviceInfo deviceInfo() throws IOException {
        Document d = call(deviceUrl, "<GetDeviceInformation xmlns=\"" + DEVICE_NS + "\"/>", true, null, Duration.ofSeconds(5));
        return new DeviceInfo(Soap.text(d, "Manufacturer"), Soap.text(d, "Model"),
            Soap.text(d, "FirmwareVersion"), Soap.text(d, "SerialNumber"));
    }

    public List<Profile> profiles() throws IOException {
        Document d = call(mediaUrl, "<GetProfiles xmlns=\"" + MEDIA_NS + "\"/>", true, null, Duration.ofSeconds(5));
        return parseProfiles(d);
    }

    static List<Profile> parseProfiles(Document d) {
        List<Profile> out = new ArrayList<>();
        for (Element p : Soap.all(d, "Profiles")) {
            Element enc = Soap.first(p, "VideoEncoderConfiguration");
            Element res = enc == null ? null : Soap.first(enc, "Resolution");
            out.add(new Profile(p.getAttribute("token"), Soap.text(p, "Name"),
                enc == null ? null : Soap.text(enc, "Encoding"),
                res == null ? 0 : parseInt(Soap.text(res, "Width")),
                res == null ? 0 : parseInt(Soap.text(res, "Height"))));
        }
        return out;
    }

    public String streamUri(String profileToken) throws IOException {
        String body = "<GetStreamUri xmlns=\"" + MEDIA_NS + "\"><StreamSetup>"
            + "<Stream xmlns=\"" + SCHEMA_NS + "\">RTP-Unicast</Stream>"
            + "<Transport xmlns=\"" + SCHEMA_NS + "\"><Protocol>RTSP</Protocol></Transport>"
            + "</StreamSetup><ProfileToken>" + Soap.xml(profileToken) + "</ProfileToken></GetStreamUri>";
        Document d = call(mediaUrl, body, true, null, Duration.ofSeconds(5));
        return fixHost(Soap.text(d, "Uri"));
    }

    // ---- events ----

    public boolean supportsEvents() {
        return eventsUrl != null;
    }

    /** The topics the camera can raise (GetEventProperties), in the order it lists them. */
    public List<EventTopic> eventTopics() throws IOException {
        if (eventsUrl == null) {
            return List.of();
        }
        Document d = call(eventsUrl, "<GetEventProperties xmlns=\"" + EVENTS_NS + "\"/>", true, null, Duration.ofSeconds(10));
        return parseTopics(d);
    }

    /**
     * Flattens a TopicSet. Each element is a topic namespace level; a level is a topic when it
     * says so (wstop:topic="true") or describes a message.
     */
    static List<EventTopic> parseTopics(Document d) {
        Element set = Soap.first(d, "TopicSet");
        List<EventTopic> out = new ArrayList<>();
        if (set != null) {
            for (Element c : children(set)) {
                walkTopics(c, c.getLocalName(), out);
            }
        }
        return out;
    }

    private static void walkTopics(Element e, String path, List<EventTopic> out) {
        Element description = null;
        for (Element c : children(e)) {
            if ("MessageDescription".equals(c.getLocalName())) {
                description = c;
            }
        }
        if (description != null || "true".equalsIgnoreCase(e.getAttributeNS(TOPIC_NS, "topic"))) {
            List<String> items = new ArrayList<>();
            Element data = description == null ? null : Soap.first(description, "Data");
            if (data != null) {
                for (Element i : Soap.all(data, "SimpleItemDescription")) {
                    items.add(i.getAttribute("Name"));
                }
            }
            boolean stateful = items.stream().anyMatch(i -> OnvifEvent.STATE_ITEMS.contains(i.toLowerCase(java.util.Locale.ROOT)));
            out.add(new EventTopic(path, List.copyOf(items), stateful, true));
        }
        for (Element c : children(e)) {
            if (!"MessageDescription".equals(c.getLocalName())) {
                walkTopics(c, path + "/" + c.getLocalName(), out);
            }
        }
    }

    private static List<Element> children(Element e) {
        List<Element> out = new ArrayList<>();
        for (org.w3c.dom.Node n = e.getFirstChild(); n != null; n = n.getNextSibling()) {
            if (n instanceof Element c) {
                out.add(c);
            }
        }
        return out;
    }

    /** Creates a pull point; returns its address. */
    public String createPullPoint(Duration termination) throws IOException {
        Document d = call(eventsUrl, "<CreatePullPointSubscription xmlns=\"" + EVENTS_NS + "\">"
            + "<InitialTerminationTime>" + termination + "</InitialTerminationTime></CreatePullPointSubscription>",
            true, null, Duration.ofSeconds(10));
        Element ref = Soap.first(d, "SubscriptionReference");
        String address = ref == null ? null : Soap.text(ref, "Address");
        if (address == null) {
            throw new IOException("camera returned no pull point address");
        }
        return fixHost(address);
    }

    /** Long-polls the pull point; returns when messages arrive or after {@code timeout}. */
    public List<OnvifEvent> pull(String pullPoint, Duration timeout, int limit) throws IOException {
        Document d = call(pullPoint, "<PullMessages xmlns=\"" + EVENTS_NS + "\"><Timeout>" + timeout
            + "</Timeout><MessageLimit>" + limit + "</MessageLimit></PullMessages>",
            true, addressing(pullPoint, EVENTS_NS + "/PullPointSubscription/PullMessagesRequest"), timeout.plusSeconds(10));
        return OnvifEvent.parse(d);
    }

    public void renew(String pullPoint, Duration termination) throws IOException {
        call(pullPoint, "<Renew xmlns=\"http://docs.oasis-open.org/wsn/b-2\"><TerminationTime>" + termination
            + "</TerminationTime></Renew>", true,
            addressing(pullPoint, "http://docs.oasis-open.org/wsn/bw-2/SubscriptionManager/RenewRequest"), Duration.ofSeconds(10));
    }

    public void unsubscribe(String pullPoint) {
        try {
            call(pullPoint, "<Unsubscribe xmlns=\"http://docs.oasis-open.org/wsn/b-2\"/>", true,
                addressing(pullPoint, "http://docs.oasis-open.org/wsn/bw-2/SubscriptionManager/UnsubscribeRequest"), Duration.ofSeconds(5));
        } catch (IOException e) {
            // best effort: the subscription expires on its own
        }
    }

    private static String addressing(String to, String action) {
        return "<a:Action s:mustUnderstand=\"1\">" + action + "</a:Action><a:To s:mustUnderstand=\"1\">" + Soap.xml(to) + "</a:To>";
    }

    // ---- transport ----

    private Document call(String url, String body, boolean auth, String headers, Duration timeout) throws IOException {
        String envelope = Soap.envelope(body, auth ? user : null, password, Instant.now().plus(clockOffset), headers);
        HttpResponse<String> r = post(url, envelope, null, timeout);
        if (r.statusCode() == 401 && auth && user != null) {
            String challenge = r.headers().firstValue("WWW-Authenticate").orElse("");
            if (challenge.regionMatches(true, 0, "Digest", 0, 6)) {
                // fresh envelope: the WS-Security nonce must not be replayed
                envelope = Soap.envelope(body, user, password, Instant.now().plus(clockOffset), headers);
                URI u = URI.create(url);
                String uri = u.getRawPath() + (u.getRawQuery() == null ? "" : "?" + u.getRawQuery());
                r = post(url, envelope, httpDigest(challenge, "POST", uri), timeout);
            }
        }
        Document d;
        try {
            d = Soap.parse(r.body());
        } catch (Exception e) {
            throw new IOException("HTTP " + r.statusCode() + " from " + url + " (not SOAP)");
        }
        String fault = Soap.fault(d);
        if (fault != null || r.statusCode() / 100 != 2) {
            throw new IOException(fault != null ? fault : "HTTP " + r.statusCode() + " from " + url);
        }
        return d;
    }

    private HttpResponse<String> post(String url, String envelope, String authorization, Duration timeout) throws IOException {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(url)).timeout(timeout)
            .header("Content-Type", "application/soap+xml; charset=utf-8")
            .POST(HttpRequest.BodyPublishers.ofString(envelope, StandardCharsets.UTF_8));
        if (authorization != null) {
            b.header("Authorization", authorization);
        }
        try {
            return http.send(b.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted", e);
        }
    }

    private static final Pattern PARAM = Pattern.compile("(\\w+)=(?:\"([^\"]*)\"|([^,\\s]*))");
    private static final java.security.SecureRandom RANDOM = new java.security.SecureRandom();
    private String lastNonce;
    private int nonceCount;

    /** RFC 7616 Digest: MD5 or SHA-256 (and their -sess forms), qop=auth when offered. */
    synchronized String httpDigest(String challenge, String method, String uri) {
        Map<String, String> p = new LinkedHashMap<>();
        Matcher m = PARAM.matcher(challenge.substring(6));
        while (m.find()) {
            p.put(m.group(1).toLowerCase(), m.group(2) != null ? m.group(2) : m.group(3));
        }
        String realm = p.getOrDefault("realm", "");
        String nonce = p.getOrDefault("nonce", "");
        String qop = p.get("qop");
        String algorithm = p.getOrDefault("algorithm", "MD5");
        boolean sha256 = algorithm.toUpperCase().startsWith("SHA-256");
        boolean sess = algorithm.toLowerCase().endsWith("-sess");
        if (!nonce.equals(lastNonce)) {
            lastNonce = nonce;
            nonceCount = 0;
        }
        byte[] cn = new byte[8];
        RANDOM.nextBytes(cn);
        String cnonce = HexFormat.of().formatHex(cn);
        String ha1 = hash(sha256, user + ":" + realm + ":" + (password == null ? "" : password));
        if (sess) {
            ha1 = hash(sha256, ha1 + ":" + nonce + ":" + cnonce);
        }
        String ha2 = hash(sha256, method + ":" + uri);
        StringBuilder h = new StringBuilder("Digest username=\"").append(user).append("\", realm=\"").append(realm)
            .append("\", nonce=\"").append(nonce).append("\", uri=\"").append(uri).append("\", algorithm=").append(algorithm);
        boolean auth = qop != null && java.util.Arrays.stream(qop.split(",")).map(String::trim).anyMatch("auth"::equals);
        if (auth) {
            String nc = String.format("%08x", ++nonceCount);
            h.append(", qop=auth, nc=").append(nc).append(", cnonce=\"").append(cnonce).append("\", response=\"")
                .append(hash(sha256, ha1 + ":" + nonce + ":" + nc + ":" + cnonce + ":auth:" + ha2)).append("\"");
        } else {
            h.append(", response=\"").append(hash(sha256, ha1 + ":" + nonce + ":" + ha2)).append("\"");
        }
        if (p.containsKey("opaque")) {
            h.append(", opaque=\"").append(p.get("opaque")).append("\"");
        }
        return h.toString();
    }

    private static String hash(boolean sha256, String s) {
        return sha256 ? sha256(s) : md5(s);
    }

    private static String sha256(String s) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static String md5(String s) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("MD5").digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * Cameras behind NAT, or with a misconfigured network page, advertise service addresses on
     * a host the gateway cannot reach. Service paths are kept but the host is always the one
     * the device URL used.
     */
    String fixHost(String url) {
        if (url == null) {
            return null;
        }
        try {
            URI dev = URI.create(deviceUrl);
            URI u = URI.create(url.trim());
            if (u.getHost() == null) {
                return url;
            }
            int port = u.getScheme() != null && u.getScheme().startsWith("rtsp") ? u.getPort() : dev.getPort();
            return new URI(u.getScheme(), u.getRawUserInfo(), dev.getHost(), port, u.getRawPath(), u.getRawQuery(), null).toString()
                .replace("%25", "%");
        } catch (Exception e) {
            return url;
        }
    }

    private static int parseInt(String s) {
        try {
            return s == null ? 0 : Integer.parseInt(s.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
