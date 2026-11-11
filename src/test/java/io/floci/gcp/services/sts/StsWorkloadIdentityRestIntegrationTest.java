package io.floci.gcp.services.sts;

import io.floci.gcp.core.common.WorkloadIdentityIssuers;
import io.floci.gcp.services.credentials.CredentialTokenService;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.startsWith;
import static org.mockito.Mockito.when;

/**
 * The three request shapes clients send a GKE ServiceAccount token to STS in: form-encoded (Google auth libraries),
 * JSON snake_case (Google's Secret Manager CSI provider) and JSON camelCase (the GKE metadata server, observed), on
 * {@code /v1/token} and the legacy {@code /v1/identitybindingtoken}, with no {@code Authorization} header.
 */
@QuarkusTest
class StsWorkloadIdentityRestIntegrationTest {

    private static final String JWT_TYPE = "urn:ietf:params:oauth:token-type:jwt";

    @InjectMock
    WorkloadIdentityIssuers issuers;

    @BeforeEach
    void knowTheTestCluster() {
        when(issuers.provider(WorkloadIdentityTokens.ISSUER)).thenReturn(Optional.of(new WorkloadIdentityIssuers.Provider(
                WorkloadIdentityTokens.ISSUER, WorkloadIdentityTokens.POOL, "123456789012", WorkloadIdentityTokens.jwks())));
    }

    private static String token() {
        return WorkloadIdentityTokens.sign(WorkloadIdentityTokens.claims(Instant.now()));
    }

    @Test
    void formEncodedExchange() {
        given().contentType("application/x-www-form-urlencoded")
                .formParam("grant_type", StsService.TOKEN_EXCHANGE_GRANT_TYPE)
                .formParam("subject_token_type", JWT_TYPE)
                .formParam("subject_token", token())
                .formParam("requested_token_type", StsService.ACCESS_TOKEN_TYPE)
                .formParam("audience", WorkloadIdentityTokens.AUDIENCE)
                .formParam("scope", "https://www.googleapis.com/auth/cloud-platform")
                .post("/v1/token")
                .then().statusCode(200)
                .body("access_token", startsWith(CredentialTokenService.FEDERATED_TOKEN_PREFIX))
                .body("issued_token_type", equalTo(StsService.ACCESS_TOKEN_TYPE))
                .body("token_type", equalTo("Bearer"))
                .body("expires_in", equalTo(3600));
    }

    @Test
    void jsonSnakeCaseExchangeOnTheLegacyIdentityBindingTokenPath() {
        given().contentType("application/json")
                .body(Map.of("grant_type", StsService.TOKEN_EXCHANGE_GRANT_TYPE, "subject_token_type", JWT_TYPE,
                        "subject_token", token(), "requested_token_type", StsService.ACCESS_TOKEN_TYPE,
                        "audience", WorkloadIdentityTokens.AUDIENCE,
                        "scope", "https://www.googleapis.com/auth/cloud-platform"))
                .post("/v1/identitybindingtoken")
                .then().statusCode(200)
                .body("access_token", startsWith(CredentialTokenService.FEDERATED_TOKEN_PREFIX));
    }

    @Test
    void jsonCamelCaseExchange() {
        given().contentType("application/json").queryParam("alt", "json")
                .body(Map.of("grantType", StsService.TOKEN_EXCHANGE_GRANT_TYPE, "subjectTokenType", JWT_TYPE,
                        "subjectToken", token(), "requestedTokenType", StsService.ACCESS_TOKEN_TYPE,
                        "audience", WorkloadIdentityTokens.AUDIENCE,
                        "scope", "https://www.googleapis.com/auth/cloud-platform"))
                .post("/v1/token")
                .then().statusCode(200)
                .body("access_token", startsWith(CredentialTokenService.FEDERATED_TOKEN_PREFIX));
    }

    @Test
    void aTokenOfAnotherAudienceIsAnOauthError() {
        given().contentType("application/json")
                .body(Map.of("grantType", StsService.TOKEN_EXCHANGE_GRANT_TYPE, "subjectTokenType", JWT_TYPE,
                        "subjectToken", token(), "requestedTokenType", StsService.ACCESS_TOKEN_TYPE,
                        "audience", "identitynamespace:other.svc.id.goog:" + WorkloadIdentityTokens.ISSUER))
                .post("/v1/token")
                .then().statusCode(400)
                .body("error", equalTo("invalid_grant"));
    }
}
