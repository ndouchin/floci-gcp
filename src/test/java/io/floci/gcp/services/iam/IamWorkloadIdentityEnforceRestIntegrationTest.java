package io.floci.gcp.services.iam;

import io.floci.gcp.core.common.ProjectNumbers;
import io.floci.gcp.services.credentials.CredentialTokenService;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.startsWith;

/**
 * A federated GKE workload token under {@code iam.authorization-mode=enforce}: its {@code principal://} identifier
 * grants direct access, and impersonating a Google service account needs {@code roles/iam.workloadIdentityUser}
 * for one of its member forms, here the legacy {@code serviceAccount:PROJECT_ID.svc.id.goog[NS/KSA]}.
 */
@QuarkusTest
@TestProfile(IamEnforcementIntegrationTest.EnforceProfile.class)
class IamWorkloadIdentityEnforceRestIntegrationTest {

    @Inject
    CredentialTokenService tokens;

    @Test
    void aFederatedWorkloadIsAuthorizedByItsPrincipalAndByWorkloadIdentityUser() {
        String project = "wi-" + UUID.randomUUID().toString().substring(0, 8);
        String pool = project + ".svc.id.goog";
        String number = ProjectNumbers.of(project);
        String credential = "Bearer " + tokens.mintFederatedToken(new WorkloadIdentityPrincipals.Workload(number, pool,
                "team", "app", "uid-1", "https://container.googleapis.com/v1/projects/" + project
                        + "/locations/us-central1/clusters/c1"), Instant.now().plusSeconds(600)).getTokenValue();
        String principal = "principal://iam.googleapis.com/projects/" + number
                + "/locations/global/workloadIdentityPools/" + pool + "/subject/ns/team/sa/app";

        given().contentType("application/json").body(Map.of("replication", Map.of("automatic", Map.of())))
                .queryParam("secretId", "db")
                .post("/v1/projects/" + project + "/secrets").then().statusCode(200);
        given().urlEncodingEnabled(false).contentType("application/json").body(Map.of("payload", Map.of("data",
                        Base64.getEncoder().encodeToString("s3cr3t".getBytes()))))
                .post("/v1/projects/" + project + "/secrets/db:addVersion").then().statusCode(200);
        String access = "/v1/projects/" + project + "/secrets/db/versions/1:access";

        given().urlEncodingEnabled(false).header("Authorization", credential).get(access).then().statusCode(403);
        given().urlEncodingEnabled(false).contentType("application/json").body(Map.of("policy", Map.of("bindings", List.of(
                        Map.of("role", "roles/secretmanager.secretAccessor", "members", List.of(principal))))))
                .post("/v1/projects/" + project + "/secrets/db:setIamPolicy").then().statusCode(200);
        given().urlEncodingEnabled(false).header("Authorization", credential).get(access).then().statusCode(200);

        String gsa = "gsa@" + project + ".iam.gserviceaccount.com";
        given().contentType("application/json").body(Map.of("accountId", "gsa"))
                .post("/v1/projects/" + project + "/serviceAccounts").then().statusCode(200);
        String generate = "/v1/projects/-/serviceAccounts/" + gsa + ":generateAccessToken";
        Map<String, Object> scope = Map.of("scope", List.of("https://www.googleapis.com/auth/cloud-platform"));

        given().urlEncodingEnabled(false).header("Authorization", credential).contentType("application/json").body(scope).post(generate)
                .then().statusCode(403);
        given().urlEncodingEnabled(false).contentType("application/json").body(Map.of("policy", Map.of("bindings", List.of(
                        Map.of("role", "roles/iam.workloadIdentityUser",
                                "members", List.of("serviceAccount:" + pool + "[team/app]"))))))
                .post("/v1/projects/" + project + "/serviceAccounts/" + gsa + ":setIamPolicy").then().statusCode(200);
        given().urlEncodingEnabled(false).header("Authorization", credential).contentType("application/json").body(scope).post(generate)
                .then().statusCode(200)
                .body("accessToken", startsWith(CredentialTokenService.IMPERSONATED_TOKEN_PREFIX));
    }
}
