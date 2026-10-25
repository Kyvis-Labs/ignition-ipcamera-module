package com.kyvislabs.camera.gateway.onvif;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

/** SOAP 1.2 envelopes with WS-Security UsernameToken digests, and namespace-agnostic parsing. */
final class Soap {
    private static final SecureRandom RANDOM = new SecureRandom();

    private Soap() {
    }

    /**
     * @param created device-clock time: digests are rejected when Created is far from the
     *                camera's own clock, so callers pass now + the measured clock offset
     * @param extraHeaders raw header XML (WS-Addressing for pull points), may be empty
     */
    static String envelope(String body, String user, String password, Instant created, String extraHeaders) {
        StringBuilder sb = new StringBuilder(512)
            .append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>")
            .append("<s:Envelope xmlns:s=\"http://www.w3.org/2003/05/soap-envelope\"")
            .append(" xmlns:a=\"http://www.w3.org/2005/08/addressing\"><s:Header>");
        if (user != null && !user.isEmpty()) {
            byte[] nonce = new byte[16];
            RANDOM.nextBytes(nonce);
            String ts = created.truncatedTo(ChronoUnit.SECONDS).toString();
            sb.append("<Security s:mustUnderstand=\"1\" xmlns=\"http://docs.oasis-open.org/wss/2004/01/oasis-200401-wss-wssecurity-secext-1.0.xsd\">")
                .append("<UsernameToken><Username>").append(xml(user)).append("</Username>")
                .append("<Password Type=\"http://docs.oasis-open.org/wss/2004/01/oasis-200401-wss-username-token-profile-1.0#PasswordDigest\">")
                .append(digest(nonce, ts, password == null ? "" : password)).append("</Password>")
                .append("<Nonce EncodingType=\"http://docs.oasis-open.org/wss/2004/01/oasis-200401-wss-soap-message-security-1.0#Base64Binary\">")
                .append(Base64.getEncoder().encodeToString(nonce)).append("</Nonce>")
                .append("<Created xmlns=\"http://docs.oasis-open.org/wss/2004/01/oasis-200401-wss-wssecurity-utility-1.0.xsd\">")
                .append(ts).append("</Created></UsernameToken></Security>");
        }
        sb.append(extraHeaders == null ? "" : extraHeaders).append("</s:Header><s:Body>").append(body).append("</s:Body></s:Envelope>");
        return sb.toString();
    }

    /** PasswordDigest = Base64(SHA-1(nonce + created + password)). */
    static String digest(byte[] nonce, String created, String password) {
        try {
            MessageDigest sha1 = MessageDigest.getInstance("SHA-1");
            sha1.update(nonce);
            sha1.update(created.getBytes(StandardCharsets.UTF_8));
            sha1.update(password.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(sha1.digest());
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    static String xml(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }

    static Document parse(String text) throws Exception {
        DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
        f.setNamespaceAware(true);
        f.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        f.setExpandEntityReferences(false);
        DocumentBuilder b = f.newDocumentBuilder();
        return b.parse(new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8)));
    }

    static List<Element> all(Node root, String localName) {
        NodeList nl = root instanceof Document d ? d.getElementsByTagNameNS("*", localName)
            : ((Element) root).getElementsByTagNameNS("*", localName);
        List<Element> out = new ArrayList<>(nl.getLength());
        for (int i = 0; i < nl.getLength(); i++) {
            out.add((Element) nl.item(i));
        }
        return out;
    }

    static Element first(Node root, String localName) {
        List<Element> l = all(root, localName);
        return l.isEmpty() ? null : l.get(0);
    }

    static String text(Node root, String localName) {
        Element e = first(root, localName);
        return e == null ? null : e.getTextContent().trim();
    }

    /** A SOAP Fault's reason text, or null if the document is not a fault. */
    static String fault(Document d) {
        Element f = first(d, "Fault");
        if (f == null) {
            return null;
        }
        String reason = text(f, "Text");
        String code = text(f, "Value");
        return (reason != null ? reason : "SOAP fault") + (code != null ? " (" + code + ")" : "");
    }
}
