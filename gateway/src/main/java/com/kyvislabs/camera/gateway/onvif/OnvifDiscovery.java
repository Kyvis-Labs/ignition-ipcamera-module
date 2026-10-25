package com.kyvislabs.camera.gateway.onvif;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.w3c.dom.Document;
import org.w3c.dom.Element;

/**
 * WS-Discovery for ONVIF video devices. Multicast reaches cameras on the gateway's own L2
 * segment; a gateway in a bridged container (the usual Docker setup) only sees its bridge, so
 * a unicast sweep of a subnet (Probe sent straight to each address's port 3702) is offered too.
 */
public final class OnvifDiscovery {
    private static final String MULTICAST = "239.255.255.250";
    private static final int PORT = 3702;

    public record Device(String address, List<String> xaddrs, String name, String hardware, String endpoint) {
    }

    private OnvifDiscovery() {
    }

    static String probe() {
        return "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
            + "<e:Envelope xmlns:e=\"http://www.w3.org/2003/05/soap-envelope\""
            + " xmlns:w=\"http://schemas.xmlsoap.org/ws/2004/08/addressing\""
            + " xmlns:d=\"http://schemas.xmlsoap.org/ws/2005/04/discovery\""
            + " xmlns:dn=\"http://www.onvif.org/ver10/network/wsdl\"><e:Header>"
            + "<w:MessageID>uuid:" + UUID.randomUUID() + "</w:MessageID>"
            + "<w:To e:mustUnderstand=\"true\">urn:schemas-xmlsoap-org:ws:2005:04:discovery</w:To>"
            + "<w:Action e:mustUnderstand=\"true\">http://schemas.xmlsoap.org/ws/2005/04/discovery/Probe</w:Action>"
            + "</e:Header><e:Body><d:Probe><d:Types>dn:NetworkVideoTransmitter</d:Types></d:Probe></e:Body></e:Envelope>";
    }

    /**
     * @param sweep addresses to probe by unicast as well (may be empty)
     * @param waitMs how long to collect replies
     */
    public static List<Device> discover(List<InetAddress> sweep, int waitMs) throws IOException {
        byte[] msg = probe().getBytes(StandardCharsets.UTF_8);
        Map<String, Device> found = new LinkedHashMap<>();
        try (DatagramSocket socket = new DatagramSocket()) {
            socket.setBroadcast(true);
            socket.send(new DatagramPacket(msg, msg.length, new InetSocketAddress(MULTICAST, PORT)));
            for (InetAddress a : sweep) {
                socket.send(new DatagramPacket(msg, msg.length, new InetSocketAddress(a, PORT)));
            }
            long deadline = System.currentTimeMillis() + waitMs;
            byte[] buf = new byte[65_535];
            while (System.currentTimeMillis() < deadline) {
                socket.setSoTimeout((int) Math.max(1, deadline - System.currentTimeMillis()));
                DatagramPacket p = new DatagramPacket(buf, buf.length);
                try {
                    socket.receive(p);
                } catch (SocketTimeoutException e) {
                    break;
                }
                String text = new String(p.getData(), 0, p.getLength(), StandardCharsets.UTF_8);
                for (Device d : parseMatches(p.getAddress().getHostAddress(), text)) {
                    found.putIfAbsent(d.endpoint() == null ? d.address() : d.endpoint(), d);
                }
            }
        }
        return new ArrayList<>(found.values());
    }

    static List<Device> parseMatches(String from, String text) {
        List<Device> out = new ArrayList<>();
        Document d;
        try {
            d = Soap.parse(text);
        } catch (Exception e) {
            return out;
        }
        for (Element m : Soap.all(d, "ProbeMatch")) {
            String xaddrs = Soap.text(m, "XAddrs");
            String scopes = Soap.text(m, "Scopes");
            Element epr = Soap.first(m, "EndpointReference");
            out.add(new Device(from, xaddrs == null ? List.of() : List.of(xaddrs.trim().split("\\s+")),
                scope(scopes, "name"), scope(scopes, "hardware"), epr == null ? null : Soap.text(epr, "Address")));
        }
        return out;
    }

    /** Value of onvif://www.onvif.org/<kind>/<value> in a scopes list, URL-decoded. */
    static String scope(String scopes, String kind) {
        if (scopes == null) {
            return null;
        }
        String prefix = "onvif://www.onvif.org/" + kind + "/";
        for (String s : scopes.split("\\s+")) {
            if (s.startsWith(prefix)) {
                return java.net.URLDecoder.decode(s.substring(prefix.length()), StandardCharsets.UTF_8);
            }
        }
        return null;
    }

    /** Every host address in an IPv4 CIDR, capped at /22 (1022 hosts) to keep a sweep polite. */
    public static List<InetAddress> hosts(String cidr) throws IOException {
        String[] parts = cidr.trim().split("/");
        int bits = parts.length > 1 ? Integer.parseInt(parts[1]) : 32;
        if (bits < 22 || bits > 32) {
            throw new IllegalArgumentException("sweep at most a /22");
        }
        byte[] b = InetAddress.getByName(parts[0]).getAddress();
        int base = ((b[0] & 0xff) << 24) | ((b[1] & 0xff) << 16) | ((b[2] & 0xff) << 8) | (b[3] & 0xff);
        int mask = bits == 0 ? 0 : -1 << (32 - bits);
        int net = base & mask;
        int size = 1 << (32 - bits);
        List<InetAddress> out = new ArrayList<>();
        for (int i = size > 2 ? 1 : 0; i < (size > 2 ? size - 1 : size); i++) {
            int a = net + i;
            out.add(InetAddress.getByAddress(new byte[]{(byte) (a >>> 24), (byte) (a >>> 16), (byte) (a >>> 8), (byte) a}));
        }
        return out;
    }
}
