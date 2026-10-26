package com.kyvislabs.camera.gateway.onvif;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.w3c.dom.Document;
import org.w3c.dom.Element;

/**
 * One ONVIF notification: its topic (e.g. {@code tns1:RuleEngine/CellMotionDetector/Motion}),
 * source items (which video source / rule) and data items (the state).
 */
public record OnvifEvent(String topic, String operation, Map<String, String> source, Map<String, String> data) {
    /** Data item names vendors use for the on/off state of a detector. */
    static final Set<String> STATE_ITEMS = Set.of(
        "ismotion", "state", "value", "isinside", "triggered", "logicalstate", "active", "alarm");

    static List<OnvifEvent> parse(Document d) {
        List<OnvifEvent> out = new ArrayList<>();
        for (Element n : Soap.all(d, "NotificationMessage")) {
            String topic = Soap.text(n, "Topic");
            Element msg = null;
            for (Element m : Soap.all(n, "Message")) {
                if (m.hasAttribute("UtcTime") || m.hasAttribute("PropertyOperation")) {
                    msg = m;
                    break;
                }
            }
            Map<String, String> source = new LinkedHashMap<>();
            Map<String, String> data = new LinkedHashMap<>();
            if (msg != null) {
                Element src = Soap.first(msg, "Source");
                Element dat = Soap.first(msg, "Data");
                if (src != null) {
                    Soap.all(src, "SimpleItem").forEach(i -> source.put(i.getAttribute("Name"), i.getAttribute("Value")));
                }
                if (dat != null) {
                    Soap.all(dat, "SimpleItem").forEach(i -> data.put(i.getAttribute("Name"), i.getAttribute("Value")));
                }
            }
            out.add(new OnvifEvent(topic == null ? "" : topic, msg == null ? "" : msg.getAttribute("PropertyOperation"),
                source, data));
        }
        return out;
    }

    /** The detector state carried by this message, or null when it carries no boolean state. */
    public Boolean active() {
        for (Map.Entry<String, String> e : data.entrySet()) {
            if (STATE_ITEMS.contains(e.getKey().toLowerCase(Locale.ROOT))) {
                String v = e.getValue().trim().toLowerCase(Locale.ROOT);
                if (v.equals("true") || v.equals("1") || v.equals("active") || v.equals("on")) {
                    return true;
                }
                if (v.equals("false") || v.equals("0") || v.equals("inactive") || v.equals("off")) {
                    return false;
                }
            }
        }
        return null;
    }

    /**
     * The topic as a tag folder path: namespace prefixes dropped from every segment and
     * characters Ignition does not allow in tag names replaced, so
     * {@code tns1:VideoSource/tnsaxis:DayNightVision} becomes {@code VideoSource/DayNightVision}.
     */
    public String tagPath() {
        StringBuilder out = new StringBuilder();
        for (String seg : topic.split("/")) {
            String s = seg.contains(":") ? seg.substring(seg.lastIndexOf(':') + 1) : seg;
            s = s.replaceAll("[^A-Za-z0-9_ -]", "_").trim();
            if (!s.isEmpty()) {
                out.append(out.length() == 0 ? "" : "/").append(s);
            }
        }
        return out.length() == 0 ? "Event" : out.toString();
    }

    /** Data items as {@code Name=Value} pairs, for a string tag. */
    public String dataString() {
        StringBuilder out = new StringBuilder();
        data.forEach((k, v) -> out.append(out.length() == 0 ? "" : ", ").append(k).append('=').append(v));
        return out.toString();
    }

    /**
     * Stable key for one detector: topic plus source items, so two motion rules on the same
     * camera are tracked separately.
     */
    public String key() {
        return topic + source;
    }

    /**
     * True when any configured type appears in the topic or data (case-insensitive substring):
     * "motion" matches CellMotionDetector/Motion and VideoSource/MotionAlarm; "person" matches
     * vendor object-detection topics or a data value of "Person"/"Human" when listed.
     */
    public boolean matches(List<String> types) {
        if (types.isEmpty()) {
            return topic.toLowerCase(Locale.ROOT).contains("motion");
        }
        String hay = (topic + " " + data.values() + " " + source.values()).toLowerCase(Locale.ROOT);
        for (String t : types) {
            if (!t.isBlank() && hay.contains(t.trim().toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }
}
