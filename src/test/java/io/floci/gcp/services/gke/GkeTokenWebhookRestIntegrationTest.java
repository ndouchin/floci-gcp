package io.floci.gcp.services.gke;

import io.floci.gcp.services.credentials.CredentialTokenService;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.equalTo;

/**
 * With IAM authorization disabled, the token webhook authenticates every token as a cluster admin,
 * named after the service account of a Floci-issued token.
 */
@QuarkusTest
class GkeTokenWebhookRestIntegrationTest {

    static final String WEBHOOK = "/_floci-gcp/gke/token-webhook";
    static final String CLUSTER_WEBHOOK = WEBHOOK + "/projects/webhook-it/locations/us-central1/clusters/c1";

    @Inject
    CredentialTokenService tokens;

    static String tokenReview(String token) {
        return "{\"apiVersion\":\"authentication.k8s.io/v1\",\"kind\":\"TokenReview\",\"spec\":{\"token\":\"" + token + "\"}}";
    }

    @Test
    void aFlociIssuedTokenIsNamedAfterItsServiceAccount() {
        String token = tokens.mintImpersonatedToken("deployer@webhook-it.iam.gserviceaccount.com",
                Instant.now().plusSeconds(600)).getTokenValue();

        given().contentType("application/json").body(tokenReview(token))
                .when().post(CLUSTER_WEBHOOK)
                .then().statusCode(200)
                .body("apiVersion", equalTo("authentication.k8s.io/v1"))
                .body("status.authenticated", equalTo(true))
                .body("status.user.username", equalTo("deployer@webhook-it.iam.gserviceaccount.com"))
                .body("status.user.groups", contains("system:masters"));
    }

    @Test
    void anyOtherTokenStaysAClusterAdminOnTheBareAddressToo() {
        given().contentType("application/json").body(tokenReview("ya29.gcloud-user-token"))
                .when().post(WEBHOOK)
                .then().statusCode(200)
                .body("status.authenticated", equalTo(true))
                .body("status.user.username", equalTo("floci:gcp-iam"))
                .body("status.user.groups", contains("system:masters"));

        given().contentType("application/json").body(tokenReview(""))
                .when().post(CLUSTER_WEBHOOK)
                .then().statusCode(200)
                .body("status.authenticated", equalTo(false));
    }
}
