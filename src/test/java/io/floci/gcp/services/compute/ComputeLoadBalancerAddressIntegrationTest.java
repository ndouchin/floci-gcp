package io.floci.gcp.services.compute;

import io.floci.gcp.core.common.GcpException;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The regional forwarding rules GKE writes for the external addresses of LoadBalancer Services: they take addresses
 * from Compute's external pool, so instances and other rules never get the same one, and claim a reserved address a
 * Service asks for.
 */
@QuarkusTest
class ComputeLoadBalancerAddressIntegrationTest extends ComputeTestSupport {

    @Inject
    ComputeService compute;

    @Test
    void aLoadBalancerRuleTakesAnAddressFromTheExternalPoolAndGivesItBack() throws Exception {
        String root = root();
        String project = root.substring(root.lastIndexOf('/') + 1);

        String first = compute.reserveLoadBalancerAddress(project, "us-central1", "a1", null, Set.of("203.0.113.1"),
                "TCP", List.of(80), "{\"kubernetes.io/service-name\":\"shop/web\"}");
        assertEquals("203.0.113.2", first);
        assertEquals(first, compute.reserveLoadBalancerAddress(project, "us-central1", "a1", null, Set.of(), "TCP",
                List.of(80), ""));

        var rule = given().get(root + "/regions/us-central1/forwardingRules/a1").then().statusCode(200).extract().jsonPath();
        assertEquals(first, rule.getString("IPAddress"));
        assertEquals("EXTERNAL", rule.getString("loadBalancingScheme"));
        assertEquals("TCP", rule.getString("IPProtocol"));
        assertEquals("80-80", rule.getString("portRange"));
        assertEquals("{\"kubernetes.io/service-name\":\"shop/web\"}", rule.getString("description"));

        done(root, post(root + "/regions/us-central1/addresses", Map.of("name", "next")));
        String next = given().get(root + "/regions/us-central1/addresses/next").jsonPath().getString("address");
        assertNotEquals(first, next);

        compute.releaseLoadBalancerAddress(project, "us-central1", "a1");
        given().get(root + "/regions/us-central1/forwardingRules/a1").then().statusCode(404);
    }

    @Test
    void aRequestedReservedAddressIsClaimedAndAnAddressInUseIsRefused() throws Exception {
        String root = root();
        String project = root.substring(root.lastIndexOf('/') + 1);
        done(root, post(root + "/regions/us-central1/addresses", Map.of("name", "web-ip")));
        var reserved = given().get(root + "/regions/us-central1/addresses/web-ip").jsonPath();

        assertEquals(reserved.getString("address"), compute.reservedAddress(project, "us-central1", "web-ip").orElseThrow());
        String claimed = compute.reserveLoadBalancerAddress(project, "us-central1", "k8s2-tcp-web", reserved.getString("address"),
                Set.of(), "TCP", List.of(80, 443), "");
        assertEquals(reserved.getString("address"), claimed);
        var address = given().get(root + "/regions/us-central1/addresses/web-ip").jsonPath();
        assertEquals("IN_USE", address.getString("status"));
        assertTrue(address.getList("users").get(0).toString().endsWith("/regions/us-central1/forwardingRules/k8s2-tcp-web"));
        assertEquals(List.of("80", "443"), given().get(root + "/regions/us-central1/forwardingRules/k8s2-tcp-web")
                .jsonPath().getList("ports"));

        assertThrows(GcpException.class, () -> compute.reserveLoadBalancerAddress(project, "us-central1", "other",
                claimed, Set.of(), "TCP", List.of(80), ""));
        compute.releaseLoadBalancerAddress(project, "us-central1", "k8s2-tcp-web");
        assertEquals("RESERVED", given().get(root + "/regions/us-central1/addresses/web-ip").jsonPath().getString("status"));
    }
}
