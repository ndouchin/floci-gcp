package io.floci.gcp.services.gke;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.matchesPattern;
import static org.hamcrest.Matchers.nullValue;

/**
 * {@code Cluster.workloadIdentityConfig} and {@code Cluster.id} as v1 and v1beta1 clients read them back, and the
 * {@code desiredWorkloadIdentityConfig} update Terraform sends to enable and disable Workload Identity.
 */
@QuarkusTest
class GkeWorkloadIdentityRestIntegrationTest {

    private static final String PROJECT = "gke-wi-it";
    private static final String LOCATION = "us-central1";
    private static final String V1 = "/container/v1/projects/" + PROJECT + "/locations/" + LOCATION;
    private static final String V1BETA1 = "/container/v1beta1/projects/" + PROJECT + "/locations/" + LOCATION;

    @Test
    void workloadPoolReadsBackOnBothVersionsAndDisappearsWhenDisabled() {
        given().contentType("application/json")
                .body("{\"cluster\": {\"name\": \"wi\", \"workloadIdentityConfig\": {\"workloadPool\": \""
                        + PROJECT + ".svc.id.goog\"}}}")
                .post(V1 + "/clusters").then().statusCode(200);

        given().get(V1 + "/clusters/wi").then().statusCode(200)
                .body("workloadIdentityConfig.workloadPool", equalTo(PROJECT + ".svc.id.goog"))
                .body("id", matchesPattern("^[0-9a-f]{64}$"));
        given().get(V1BETA1 + "/clusters/wi").then().statusCode(200)
                .body("workloadIdentityConfig.workloadPool", equalTo(PROJECT + ".svc.id.goog"));

        given().contentType("application/json")
                .body("{\"update\": {\"desiredWorkloadIdentityConfig\": {\"workloadPool\": \"\"}}}")
                .put(V1 + "/clusters/wi").then().statusCode(200);
        given().get(V1 + "/clusters/wi").then().statusCode(200)
                .body("workloadIdentityConfig", nullValue());

        given().delete(V1 + "/clusters/wi").then().statusCode(200);
    }

    @Test
    void aWorkloadPoolOfAnotherProjectIsAnInvalidArgument() {
        given().contentType("application/json")
                .body("{\"cluster\": {\"name\": \"elsewhere\", \"workloadIdentityConfig\": "
                        + "{\"workloadPool\": \"other.svc.id.goog\"}}}")
                .post(V1 + "/clusters").then().statusCode(400)
                .body("error.status", equalTo("INVALID_ARGUMENT"));
    }
}
