package io.floci.gcp.services.gke;

import io.floci.gcp.core.common.GcpException;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GkeNetworkingTest {

    @Test
    void aClusterWithoutRangesIsVpcNativeOnAFreeSlash14AndTheManagedServicesRange() {
        GkeNetworking.Ranges ranges = GkeNetworking.resolve(Map.of(), 110, List.of());
        assertFalse(ranges.routesBased());
        assertEquals("10.0.0.0/14", ranges.podCidr());
        assertEquals("34.118.224.0/20", ranges.servicesCidr());
        assertEquals(24, ranges.nodeMaskSize());

        GkeNetworking.Ranges next = GkeNetworking.resolve(Map.of(), 110, List.of("10.0.0.0/14", "10.4.1.7"));
        assertEquals("10.8.0.0/14", next.podCidr());
    }

    @Test
    void vpcNativeRangesComeFromTheIpAllocationPolicy() {
        GkeNetworking.Ranges explicit = GkeNetworking.resolve(Map.of("ipAllocationPolicy", Map.of(
                "useIpAliases", true, "clusterIpv4CidrBlock", "10.96.0.0/16", "servicesIpv4CidrBlock", "10.100.0.0/22")),
                110, List.of());
        assertEquals("10.96.0.0/16", explicit.podCidr());
        assertEquals("10.100.0.0/22", explicit.servicesCidr());

        GkeNetworking.Ranges sizes = GkeNetworking.resolve(Map.of("ipAllocationPolicy", Map.of(
                "clusterIpv4CidrBlock", "/17", "servicesIpv4CidrBlock", "/22")), 110, List.of());
        assertEquals("10.0.0.0/17", sizes.podCidr());
        assertEquals("34.118.224.0/22", sizes.servicesCidr());

        GkeNetworking.Ranges deprecated = GkeNetworking.resolve(Map.of("clusterIpv4Cidr", "10.20.0.0/14"), 110, List.of());
        assertEquals("10.20.0.0/14", deprecated.podCidr());
        assertFalse(deprecated.routesBased());
    }

    @Test
    void routesBasedClustersTakeServicesFromTheLastSlash20OfThePodRange() {
        GkeNetworking.Ranges ranges = GkeNetworking.resolve(Map.of("clusterIpv4Cidr", "10.96.0.0/16",
                "ipAllocationPolicy", Map.of("useRoutes", true)), 110, List.of());
        assertTrue(ranges.routesBased());
        assertEquals("10.96.0.0/16", ranges.podCidr());
        assertEquals("10.96.240.0/20", ranges.servicesCidr());
        assertEquals(24, ranges.nodeMaskSize());

        assertEquals("10.3.240.0/20", GkeNetworking.resolve(Map.of("ipAllocationPolicy", Map.of("useRoutes", true)),
                110, List.of()).servicesCidr());
    }

    @Test
    void theNodeRangeHoldsTwiceTheMaximumPods() {
        assertEquals(24, GkeNetworking.nodeMaskSize(110));
        assertEquals(25, GkeNetworking.nodeMaskSize(64));
        assertEquals(26, GkeNetworking.nodeMaskSize(32));
        assertEquals(28, GkeNetworking.nodeMaskSize(8));
        assertEquals(25, GkeNetworking.resolve(Map.of(), 64, List.of()).nodeMaskSize());
    }

    @Test
    void invalidRangesAreRejected() {
        for (Map<String, Object> body : List.<Map<String, Object>>of(
                Map.of("clusterIpv4Cidr", "10.96.0.0"),
                Map.of("clusterIpv4Cidr", "10.96.1.0/16"),
                Map.of("clusterIpv4Cidr", "300.1.0.0/16"),
                Map.of("ipAllocationPolicy", Map.of("useRoutes", true, "useIpAliases", true)),
                Map.of("ipAllocationPolicy", Map.of("useRoutes", true), "clusterIpv4Cidr", "10.0.0.0/22"),
                Map.of("ipAllocationPolicy", Map.of("clusterIpv4CidrBlock", "10.0.0.0/26")),
                Map.of("ipAllocationPolicy", Map.of("clusterIpv4CidrBlock", "10.0.0.0/14",
                        "servicesIpv4CidrBlock", "10.1.0.0/20")))) {
            GcpException e = assertThrows(GcpException.class, () -> GkeNetworking.resolve(body, 110, List.of()),
                    body.toString());
            assertEquals(400, e.getHttpStatus());
        }
    }

    @Test
    void theDnsServiceTakesTheTenthAddress() {
        assertEquals("34.118.224.10", GkeNetworking.dnsAddress("34.118.224.0/20"));
    }
}
