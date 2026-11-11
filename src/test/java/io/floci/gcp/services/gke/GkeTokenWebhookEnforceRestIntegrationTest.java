package io.floci.gcp.services.gke;

import io.floci.gcp.services.credentials.CredentialTokenService;
import io.floci.gcp.services.iam.IamService;
import io.floci.gcp.services.iam.model.StoredPolicy;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static io.floci.gcp.services.gke.GkeTokenWebhookRestIntegrationTest.tokenReview;
import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.equalTo;

/**
 * With {@code iam.authorization-mode=enforce}, the token webhook authenticates only Floci-issued
 * service account tokens, and makes cluster admins of those holding an admin role on the project.
 */
@QuarkusTest
@TestProfile(GkeTokenWebhookEnforceRestIntegrationTest.Enforce.class)
class GkeTokenWebhookEnforceRestIntegrationTest {

    private static final String SA = "deployer@test-project.iam.gserviceaccount.com";
    private static final String CLUSTER_WEBHOOK = GkeTokenWebhookRestIntegrationTest.WEBHOOK
            + "/projects/test-project/locations/us-central1/clusters/c1";

    public static class Enforce implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("floci-gcp.services.iam.authorization-mode", "enforce");
        }
    }

    @Inject
    CredentialTokenService tokens;

    @Inject
    IamService iam;

    @Test
    void tokensWithoutAPrincipalAreRejected() {
        given().contentType("application/json").body(tokenReview("ya29.gcloud-user-token"))
                .when().post(CLUSTER_WEBHOOK)
                .then().statusCode(200)
                .body("status.authenticated", equalTo(false));
    }

    @Test
    void projectAdminRolesDecideClusterAdmin() {
        String token = tokens.mintImpersonatedToken(SA, Instant.now().plusSeconds(600)).getTokenValue();
        try {
            iam.setPolicy("projects/test-project", new StoredPolicy());
            given().contentType("application/json").body(tokenReview(token))
                    .when().post(CLUSTER_WEBHOOK)
                    .then().statusCode(200)
                    .body("status.authenticated", equalTo(true))
                    .body("status.user.username", equalTo(SA))
                    .body("status.user.groups", contains("system:authenticated"));

            StoredPolicy admin = new StoredPolicy();
            admin.setBindings(List.of(Map.of("role", "roles/container.admin", "members", List.of("serviceAccount:" + SA))));
            iam.setPolicy("projects/test-project", admin);
            given().contentType("application/json").body(tokenReview(token))
                    .when().post(CLUSTER_WEBHOOK)
                    .then().statusCode(200)
                    .body("status.user.groups", contains("system:authenticated", "system:masters"));
        } finally {
            iam.setPolicy("projects/test-project", new StoredPolicy());
        }
    }
}
