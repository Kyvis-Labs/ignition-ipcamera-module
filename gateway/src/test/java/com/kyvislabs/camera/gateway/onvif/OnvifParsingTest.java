package com.kyvislabs.camera.gateway.onvif;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;

import org.junit.jupiter.api.Test;

class OnvifParsingTest {
    private static final String PROFILES = """
        <s:Envelope xmlns:s="http://www.w3.org/2003/05/soap-envelope" xmlns:trt="http://www.onvif.org/ver10/media/wsdl" xmlns:tt="http://www.onvif.org/ver10/schema">
        <s:Body><trt:GetProfilesResponse>
          <trt:Profiles token="Profile_1" fixed="true"><tt:Name>mainStream</tt:Name>
            <tt:VideoEncoderConfiguration token="V1"><tt:Name>v</tt:Name><tt:Encoding>H264</tt:Encoding>
              <tt:Resolution><tt:Width>2560</tt:Width><tt:Height>1440</tt:Height></tt:Resolution></tt:VideoEncoderConfiguration>
          </trt:Profiles>
          <trt:Profiles token="Profile_2"><tt:Name>subStream</tt:Name>
            <tt:VideoEncoderConfiguration token="V2"><tt:Encoding>H264</tt:Encoding>
              <tt:Resolution><tt:Width>640</tt:Width><tt:Height>360</tt:Height></tt:Resolution></tt:VideoEncoderConfiguration>
          </trt:Profiles>
        </trt:GetProfilesResponse></s:Body></s:Envelope>""";

    private static final String PULL = """
        <s:Envelope xmlns:s="http://www.w3.org/2003/05/soap-envelope" xmlns:tev="http://www.onvif.org/ver10/events/wsdl"
          xmlns:wsnt="http://docs.oasis-open.org/wsn/b-2" xmlns:tt="http://www.onvif.org/ver10/schema" xmlns:tns1="http://www.onvif.org/ver10/topics">
        <s:Body><tev:PullMessagesResponse><tev:CurrentTime>2026-10-25T10:00:00Z</tev:CurrentTime>
          <wsnt:NotificationMessage>
            <wsnt:Topic Dialect="http://www.onvif.org/ver10/tev/topicExpression/ConcreteSet">tns1:RuleEngine/CellMotionDetector/Motion</wsnt:Topic>
            <wsnt:Message><tt:Message UtcTime="2026-10-25T10:00:00Z" PropertyOperation="Changed">
              <tt:Source><tt:SimpleItem Name="VideoSourceConfigurationToken" Value="VSC1"/><tt:SimpleItem Name="Rule" Value="MyMotionDetectorRule"/></tt:Source>
              <tt:Data><tt:SimpleItem Name="IsMotion" Value="true"/></tt:Data>
            </tt:Message></wsnt:Message>
          </wsnt:NotificationMessage>
          <wsnt:NotificationMessage>
            <wsnt:Topic>tns1:Device/Trigger/DigitalInput</wsnt:Topic>
            <wsnt:Message><tt:Message UtcTime="2026-10-25T10:00:01Z" PropertyOperation="Initialized">
              <tt:Source><tt:SimpleItem Name="InputToken" Value="DI_0"/></tt:Source>
              <tt:Data><tt:SimpleItem Name="LogicalState" Value="false"/></tt:Data>
            </tt:Message></wsnt:Message>
          </wsnt:NotificationMessage>
        </tev:PullMessagesResponse></s:Body></s:Envelope>""";

    @Test
    void profilesAreParsed() throws Exception {
        List<OnvifClient.Profile> p = OnvifClient.parseProfiles(Soap.parse(PROFILES));
        assertEquals(2, p.size());
        assertEquals(new OnvifClient.Profile("Profile_1", "mainStream", "H264", 2560, 1440), p.get(0));
        assertEquals(640, p.get(1).width());
    }

    @Test
    void notificationsCarryTopicSourceAndState() throws Exception {
        List<OnvifEvent> ev = OnvifEvent.parse(Soap.parse(PULL));
        assertEquals(2, ev.size());
        OnvifEvent motion = ev.get(0);
        assertEquals("tns1:RuleEngine/CellMotionDetector/Motion", motion.topic());
        assertEquals("Changed", motion.operation());
        assertEquals("MyMotionDetectorRule", motion.source().get("Rule"));
        assertTrue(motion.active());
        assertTrue(motion.matches(List.of()));
        assertTrue(motion.matches(List.of("Motion")));
        assertFalse(motion.matches(List.of("person")));
        OnvifEvent input = ev.get(1);
        assertFalse(input.active());
        assertFalse(input.matches(List.of()), "default types match motion only");
        assertTrue(input.matches(List.of("digitalinput")));
    }

    @Test
    void subscriberTracksDetectorsAndReleasesOnLoss() throws Exception {
        List<String> calls = new java.util.ArrayList<>();
        OnvifEventSubscriber s = new OnvifEventSubscriber("cam", null, List.of("motion"),
            (cam, key, label, on) -> calls.add(label + "=" + on), (cam, ok, err) -> { });
        List<OnvifEvent> ev = OnvifEvent.parse(Soap.parse(PULL));
        s.handle(ev.get(0));
        s.handle(ev.get(0));
        s.handle(ev.get(1));
        assertEquals(List.of("RuleEngine/CellMotionDetector/Motion=true"), calls, "repeat true and non-matching topics are ignored");
        s.handle(new OnvifEvent(ev.get(0).topic(), "Changed", ev.get(0).source(), java.util.Map.of("IsMotion", "false")));
        assertEquals("RuleEngine/CellMotionDetector/Motion=false", calls.get(1));
    }

    @Test
    void observerSeesEveryNotificationAndLoss() throws Exception {
        List<String> seen = new java.util.ArrayList<>();
        OnvifEventSubscriber s = new OnvifEventSubscriber("cam", null, List.of("motion"),
            (cam, key, label, on) -> { }, (cam, ok, err) -> { });
        s.setObserver(new OnvifEventSubscriber.Observer() {
            @Override
            public void event(String camera, OnvifEvent e) {
                seen.add(camera + ":" + e.tagPath() + ":" + e.active() + ":" + e.dataString());
            }
        });
        for (OnvifEvent e : OnvifEvent.parse(Soap.parse(PULL))) {
            s.handle(e);
        }
        assertEquals(List.of("cam:RuleEngine/CellMotionDetector/Motion:true:IsMotion=true",
            "cam:Device/Trigger/DigitalInput:false:LogicalState=false"), seen, "non-matching topics reach the observer too");
    }

    @Test
    void topicsBecomeTagFolders() {
        assertEquals("VideoSource/DayNightVision",
            new OnvifEvent("tns1:VideoSource/tnsaxis:DayNightVision", "", java.util.Map.of(), java.util.Map.of()).tagPath(),
            "a namespace prefix on an inner segment is dropped too");
        assertEquals("RuleEngine/Cell_Motion", new OnvifEvent("RuleEngine/Cell.Motion", "", java.util.Map.of(), java.util.Map.of()).tagPath());
        assertEquals("Event", new OnvifEvent("", "", java.util.Map.of(), java.util.Map.of()).tagPath());
    }

    @Test
    void ptzPresetsAreParsed() throws Exception {
        String xml = """
            <s:Envelope xmlns:s="http://www.w3.org/2003/05/soap-envelope" xmlns:tptz="http://www.onvif.org/ver20/ptz/wsdl" xmlns:tt="http://www.onvif.org/ver10/schema">
            <s:Body><tptz:GetPresetsResponse>
              <tptz:Preset token="1"><tt:Name>Door</tt:Name></tptz:Preset>
              <tptz:Preset token="2"><tt:Name>Desk</tt:Name></tptz:Preset>
            </tptz:GetPresetsResponse></s:Body></s:Envelope>""";
        List<OnvifClient.Preset> presets = OnvifClient.parsePresets(Soap.parse(xml));
        assertEquals(2, presets.size());
        assertEquals("1", presets.get(0).token());
        assertEquals("Desk", presets.get(1).name());
    }

    @Test
    void faultsAreReported() throws Exception {
        String fault = """
            <s:Envelope xmlns:s="http://www.w3.org/2003/05/soap-envelope"><s:Body><s:Fault>
            <s:Code><s:Value>s:Sender</s:Value><s:Subcode><s:Value>ter:NotAuthorized</s:Value></s:Subcode></s:Code>
            <s:Reason><s:Text xml:lang="en">Sender not Authorized</s:Text></s:Reason></s:Fault></s:Body></s:Envelope>""";
        assertEquals("Sender not Authorized (s:Sender)", Soap.fault(Soap.parse(fault)));
        assertNull(Soap.fault(Soap.parse(PROFILES)));
    }

    @Test
    void doctypesAreRefused() {
        assertThrows(Exception.class, () -> Soap.parse("<!DOCTYPE x [<!ENTITY e SYSTEM \"file:///etc/passwd\">]><x>&e;</x>"));
    }

    @Test
    void passwordDigestMatchesTheSpecExample() {
        // ONVIF / WS-Security profile: Base64(SHA1(nonce + created + password))
        byte[] nonce = Base64.getDecoder().decode("LKqI6G/AikKCQrN0zqZFlg==");
        assertEquals("tuOSpGlFlIXsozq4HFNeeGeFLEI=", Soap.digest(nonce, "2010-09-16T07:50:45Z", "userpassword"));
    }

    @Test
    void discoveryMatchesAreParsed() {
        String m = """
            <SOAP-ENV:Envelope xmlns:SOAP-ENV="http://www.w3.org/2003/05/soap-envelope" xmlns:wsa="http://schemas.xmlsoap.org/ws/2004/08/addressing" xmlns:d="http://schemas.xmlsoap.org/ws/2005/04/discovery">
            <SOAP-ENV:Body><d:ProbeMatches><d:ProbeMatch>
              <wsa:EndpointReference><wsa:Address>urn:uuid:4f1a</wsa:Address></wsa:EndpointReference>
              <d:Scopes>onvif://www.onvif.org/type/video_encoder onvif://www.onvif.org/name/Front%20Door onvif://www.onvif.org/hardware/RLC-810A</d:Scopes>
              <d:XAddrs>http://192.0.2.20/onvif/device_service http://[fe80::1]/onvif/device_service</d:XAddrs>
            </d:ProbeMatch></d:ProbeMatches></SOAP-ENV:Body></SOAP-ENV:Envelope>""";
        List<OnvifDiscovery.Device> d = OnvifDiscovery.parseMatches("192.0.2.20", m);
        assertEquals(1, d.size());
        assertEquals("Front Door", d.get(0).name());
        assertEquals("RLC-810A", d.get(0).hardware());
        assertEquals(2, d.get(0).xaddrs().size());
        assertEquals("urn:uuid:4f1a", d.get(0).endpoint());
    }

    @Test
    void sweepIsBoundedAndSkipsNetworkAndBroadcast() throws Exception {
        assertEquals(254, OnvifDiscovery.hosts("192.0.2.77/24").size());
        assertEquals("192.0.2.1", OnvifDiscovery.hosts("192.0.2.0/24").get(0).getHostAddress());
        assertThrows(IllegalArgumentException.class, () -> OnvifDiscovery.hosts("198.18.0.0/16"));
    }

    @Test
    void advertisedHostsAreReplacedByTheReachableOne() {
        OnvifClient c = new OnvifClient("http://192.0.2.20:8000/onvif/device_service", "u", "p");
        assertEquals("http://192.0.2.20:8000/onvif/Media", c.fixHost("http://192.0.2.9/onvif/Media"));
        assertEquals("rtsp://192.0.2.20:554/h264Preview_01_main", c.fixHost("rtsp://192.0.2.9:554/h264Preview_01_main"));
    }

    @Test
    void httpDigestFollowsRfc7616() {
        OnvifClient c = new OnvifClient("http://h/onvif/device_service", "Mufasa", "Circle of Life");
        String h = c.httpDigest("Digest realm=\"testrealm@host.com\", qop=\"auth\", nonce=\"dcd98b7102dd2f0e8b11d0f600bfb0c093\", opaque=\"5ccc069c403ebaf9f0171e9517f40e41\"",
            "GET", "/dir/index.html");
        assertTrue(h.startsWith("Digest username=\"Mufasa\""));
        assertTrue(h.contains("qop=auth, nc=00000001"));
        assertTrue(h.contains("opaque=\"5ccc069c403ebaf9f0171e9517f40e41\""));
        assertEquals(StandardCharsets.UTF_8.name(), "UTF-8");
    }

    private static final String TOPICS = """
        <s:Envelope xmlns:s="http://www.w3.org/2003/05/soap-envelope" xmlns:tev="http://www.onvif.org/ver10/events/wsdl"
          xmlns:wstop="http://docs.oasis-open.org/wsn/t-1" xmlns:tt="http://www.onvif.org/ver10/schema" xmlns:tns1="http://www.onvif.org/ver10/topics">
        <s:Body><tev:GetEventPropertiesResponse>
          <wstop:TopicSet>
            <tns1:RuleEngine>
              <CellMotionDetector><Motion wstop:topic="true"><tt:MessageDescription IsProperty="true">
                <tt:Source><tt:SimpleItemDescription Name="Rule" Type="xs:string"/></tt:Source>
                <tt:Data><tt:SimpleItemDescription Name="IsMotion" Type="xs:boolean"/></tt:Data>
              </tt:MessageDescription></Motion></CellMotionDetector>
              <MyRuleDetector>
                <PeopleDetect wstop:topic="true"><tt:MessageDescription IsProperty="true">
                  <tt:Data><tt:SimpleItemDescription Name="State" Type="xs:boolean"/></tt:Data></tt:MessageDescription></PeopleDetect>
                <VehicleDetect wstop:topic="true"><tt:MessageDescription IsProperty="true">
                  <tt:Data><tt:SimpleItemDescription Name="State" Type="xs:boolean"/></tt:Data></tt:MessageDescription></VehicleDetect>
              </MyRuleDetector>
            </tns1:RuleEngine>
            <tns1:Device><Trigger><DigitalInput wstop:topic="true"/></Trigger></tns1:Device>
            <tns1:Monitoring><ProcessorUsage wstop:topic="true"><tt:MessageDescription IsProperty="true">
              <tt:Data><tt:SimpleItemDescription Name="Value" Type="xs:float"/></tt:Data></tt:MessageDescription></ProcessorUsage></tns1:Monitoring>
          </wstop:TopicSet>
        </tev:GetEventPropertiesResponse></s:Body></s:Envelope>""";

    @Test
    void parsesTopicSetIntoPaths() throws Exception {
        List<OnvifClient.EventTopic> t = OnvifClient.parseTopics(Soap.parse(TOPICS));
        assertEquals(List.of("RuleEngine/CellMotionDetector/Motion", "RuleEngine/MyRuleDetector/PeopleDetect",
            "RuleEngine/MyRuleDetector/VehicleDetect", "Device/Trigger/DigitalInput", "Monitoring/ProcessorUsage"),
            t.stream().map(OnvifClient.EventTopic::topic).toList());
        assertEquals(List.of("IsMotion"), t.get(0).dataItems());
        assertTrue(t.get(0).stateful());
        assertTrue(t.get(1).stateful());
        assertFalse(t.get(3).stateful(), "a topic with no message description has no known state");
        // a selected topic matches the notification's prefixed topic
        assertTrue(new OnvifEvent("tns1:RuleEngine/MyRuleDetector/PeopleDetect", "Changed", java.util.Map.of(),
            java.util.Map.of("State", "true")).matches(List.of(t.get(1).topic())));
    }

    @Test
    void sentTopicsAddUnlistedOnes() throws Exception {
        List<OnvifClient.EventTopic> listed = OnvifClient.parseTopics(Soap.parse(TOPICS));
        List<OnvifEvent> sent = List.of(
            new OnvifEvent("tns1:RuleEngine/CellMotionDetector/Motion", "Initialized", java.util.Map.of(), java.util.Map.of("IsMotion", "false")),
            new OnvifEvent("tns1:RuleEngine/MyRuleDetector/Visitor", "Initialized", java.util.Map.of(), java.util.Map.of("State", "false")),
            new OnvifEvent("tns1:Monitoring/OperatingTime/LastReset", "Initialized", java.util.Map.of(), java.util.Map.of("Status", "x")));
        List<OnvifClient.EventTopic> all = OnvifProbe.withSent(listed, sent);
        assertEquals(listed.size() + 2, all.size());
        OnvifClient.EventTopic visitor = all.get(listed.size());
        assertEquals("RuleEngine/MyRuleDetector/Visitor", visitor.topic());
        assertTrue(visitor.stateful());
        assertFalse(visitor.listed());
        assertFalse(all.get(listed.size() + 1).stateful());
        assertTrue(all.get(0).listed());
    }
}
