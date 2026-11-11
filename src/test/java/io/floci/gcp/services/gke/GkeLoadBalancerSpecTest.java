package io.floci.gcp.services.gke;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GkeLoadBalancerSpecTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String UID = "6f1c2b8e-1d2c-4a5b-9e8f-0a1b2c3d4e5f";
    private static final String CLUSTER_ID = "c".repeat(64);

    private static JsonNode service(String extraMetadata, String extraSpec) throws Exception {
        return JSON.readTree("""
                {"metadata": {"name": "web", "namespace": "shop", "uid": "%s" %s},
                 "spec": {"type": "LoadBalancer", "ports": [
                   {"name": "http", "protocol": "TCP", "port": 80, "targetPort": 8080, "nodePort": 31080},
                   {"name": "dns", "protocol": "UDP", "port": 53, "nodePort": 31053}] %s}}
                """.formatted(UID, extraMetadata, extraSpec));
    }

    private static GkeLoadBalancerSpec spec(String extraMetadata, String extraSpec, int minor) throws Exception {
        return GkeLoadBalancerSpec.from(service(extraMetadata, extraSpec), minor);
    }

    @Test
    void aClasslessServiceGetsAnExternalTargetPoolLoadBalancerBeforeGke137() throws Exception {
        GkeLoadBalancerSpec spec = spec("", "", 35);

        assertTrue(spec.handled());
        assertFalse(spec.internal());
        assertFalse(spec.backendService());
        // cloud-provider cloud.go DefaultLoadBalancerName: "a" + UID without dashes, 32 characters in all.
        assertEquals("a6f1c2b8e1d2c4a5b9e8f0a1b2c3d4e5", spec.forwardingRuleName(CLUSTER_ID));
        assertEquals(List.of(new GkeLoadBalancerSpec.Port("TCP", 80, 31080), new GkeLoadBalancerSpec.Port("UDP", 53, 31053)),
                spec.ports());
        assertNull(spec.error());
    }

    @Test
    void backendServiceBasedExternalLoadBalancersFromGke137OrWhenAsked() throws Exception {
        // Service LB concepts: backend-service based by default from 1.37, or with cloud.google.com/l4-rbs: enabled
        // or class networking.gke.io/l4-regional-external; the -legacy class forces target pools.
        assertTrue(spec("", "", 37).backendService());
        assertTrue(spec(", \"annotations\": {\"cloud.google.com/l4-rbs\": \"enabled\"}", "", 35).backendService());
        assertTrue(spec("", ", \"loadBalancerClass\": \"networking.gke.io/l4-regional-external\"", 35).backendService());
        assertFalse(spec("", ", \"loadBalancerClass\": \"networking.gke.io/l4-regional-external-legacy\"", 37)
                .backendService());
        String name = spec("", "", 37).forwardingRuleName(CLUSTER_ID);
        assertTrue(name.matches("^k8s2-tcp-[0-9a-z]{8}-shop-web-[0-9a-z]{8}$"), name);
    }

    @Test
    void internalLoadBalancersByAnnotationOrClass() throws Exception {
        assertTrue(spec(", \"annotations\": {\"networking.gke.io/load-balancer-type\": \"Internal\"}", "", 35).internal());
        assertTrue(spec(", \"annotations\": {\"cloud.google.com/load-balancer-type\": \"Internal\"}", "", 35).internal());
        GkeLoadBalancerSpec byClass = spec("", ", \"loadBalancerClass\": \"networking.gke.io/l4-regional-internal\"", 35);
        assertTrue(byClass.internal());
        assertTrue(byClass.backendService());
    }

    @Test
    void servicesOfOtherClassesAndTypesAreLeftAlone() throws Exception {
        assertFalse(spec("", ", \"loadBalancerClass\": \"example.com/other\"", 35).handled());
        assertFalse(GkeLoadBalancerSpec.from(JSON.readTree("""
                {"metadata": {"name": "c", "namespace": "d", "uid": "u"}, "spec": {"type": "ClusterIP"}}"""), 35)
                .handled());
    }

    @Test
    void theAddressAnnotationWinsOverLoadBalancerIp() throws Exception {
        // Service LB parameters: networking.gke.io/load-balancer-ip-addresses names an address resource and wins.
        GkeLoadBalancerSpec both = spec(", \"annotations\": {\"networking.gke.io/load-balancer-ip-addresses\": \"web-ip\"}",
                ", \"loadBalancerIP\": \"203.0.113.40\"", 35);
        assertEquals("web-ip", both.requestedAddressName());
        assertNull(both.requestedAddress());
        assertEquals("203.0.113.40", spec("", ", \"loadBalancerIP\": \"203.0.113.40\"", 35).requestedAddress());
    }

    @Test
    void servicesWithoutNodePortsCannotBeServed() throws Exception {
        GkeLoadBalancerSpec spec = GkeLoadBalancerSpec.from(JSON.readTree("""
                {"metadata": {"name": "c", "namespace": "d", "uid": "u"}, "spec": {"type": "LoadBalancer",
                 "allocateLoadBalancerNodePorts": false, "ports": [{"port": 80, "protocol": "TCP"}]}}"""), 35);
        assertTrue(spec.handled());
        assertTrue(spec.error().contains("allocateLoadBalancerNodePorts"), spec.error());
    }

    @Test
    void internalAddressesComeFromTheLowerHalfOfTheClusterSubnet() {
        assertEquals("172.18.0.20", GkeLoadBalancerSpec.internalAddress("172.18.0.0/16", Set.of(), null));
        assertEquals("172.18.0.21", GkeLoadBalancerSpec.internalAddress("172.18.0.0/16", Set.of("172.18.0.20"), null));
        assertEquals("172.18.0.99", GkeLoadBalancerSpec.internalAddress("172.18.0.0/16", Set.of(), "172.18.0.99"));
        assertNull(GkeLoadBalancerSpec.internalAddress("172.18.0.0/16", Set.of(), "172.18.200.1"));
        assertNull(GkeLoadBalancerSpec.internalAddress("172.18.0.0/16", Set.of("172.18.0.99"), "172.18.0.99"));
    }

    @Test
    void theStatusIsOneIngressAddress() {
        assertEquals("{\"status\":{\"loadBalancer\":{\"ingress\":[{\"ip\":\"203.0.113.7\"}]}}}",
                GkeLoadBalancerSpec.statusPatch("203.0.113.7"));
    }
}
