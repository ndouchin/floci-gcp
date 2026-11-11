package io.floci.gcp.services.gke;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.nullValue;

/**
 * A cluster reports the Pod and Service ranges GKE gives it: VPC-native by default, with the ranges
 * filled into {@code ipAllocationPolicy}, or routes-based with Services in the last {@code /20} of
 * the Pod range and a per-node range size.
 */
@QuarkusTest
class GkeNetworkingRestIntegrationTest {

    private static final String BASE = "/container/v1/projects/gke-net-it/locations/us-central1";

    @Test
    void aVpcNativeClusterReportsItsRanges() {
        given().contentType("application/json")
                .body("{\"cluster\":{\"name\":\"vpc-native\",\"ipAllocationPolicy\":"
                        + "{\"useIpAliases\":true,\"clusterIpv4CidrBlock\":\"10.96.0.0/16\"}}}")
                .when().post(BASE + "/clusters")
                .then().statusCode(200);

        given().when().get(BASE + "/clusters/vpc-native")
                .then().statusCode(200)
                .body("clusterIpv4Cidr", equalTo("10.96.0.0/16"))
                .body("servicesIpv4Cidr", equalTo("34.118.224.0/20"))
                .body("ipAllocationPolicy.useIpAliases", equalTo(true))
                .body("ipAllocationPolicy.clusterIpv4CidrBlock", equalTo("10.96.0.0/16"))
                .body("ipAllocationPolicy.servicesIpv4CidrBlock", equalTo("34.118.224.0/20"))
                .body("ipAllocationPolicy.stackType", equalTo("IPV4"))
                .body("nodeIpv4CidrSize", nullValue());
    }

    @Test
    void aRoutesBasedClusterReportsItsRanges() {
        given().contentType("application/json")
                .body("{\"cluster\":{\"name\":\"routes\",\"clusterIpv4Cidr\":\"10.200.0.0/16\","
                        + "\"ipAllocationPolicy\":{\"useRoutes\":true}}}")
                .when().post(BASE + "/clusters")
                .then().statusCode(200);

        given().when().get(BASE + "/clusters/routes")
                .then().statusCode(200)
                .body("clusterIpv4Cidr", equalTo("10.200.0.0/16"))
                .body("servicesIpv4Cidr", equalTo("10.200.240.0/20"))
                .body("nodeIpv4CidrSize", equalTo(24))
                .body("ipAllocationPolicy.useRoutes", equalTo(true))
                .body("ipAllocationPolicy.useIpAliases", nullValue());
    }

    @Test
    void anInvalidRangeIsRejected() {
        given().contentType("application/json")
                .body("{\"cluster\":{\"name\":\"bad-range\",\"clusterIpv4Cidr\":\"10.96.1.0/16\"}}")
                .when().post(BASE + "/clusters")
                .then().statusCode(400)
                .body("error.status", equalTo("INVALID_ARGUMENT"));
        given().when().get(BASE + "/clusters/bad-range").then().statusCode(404);
    }
}
