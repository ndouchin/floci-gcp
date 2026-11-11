package io.floci.gcp.services.sts;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.floci.gcp.core.common.WorkloadIdentityIssuers;
import io.floci.gcp.core.storage.InMemoryStorage;
import io.floci.gcp.services.credentials.CredentialAccessBoundaryParser;
import io.floci.gcp.services.credentials.CredentialTokenService;
import io.floci.gcp.services.credentials.StoredCredentialToken;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static io.floci.gcp.services.sts.WorkloadIdentityTokens.AUDIENCE;
import static io.floci.gcp.services.sts.WorkloadIdentityTokens.ISSUER;
import static io.floci.gcp.services.sts.WorkloadIdentityTokens.POOL;
import static io.floci.gcp.services.sts.WorkloadIdentityTokens.claims;
import static io.floci.gcp.services.sts.WorkloadIdentityTokens.sign;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** STS's exchange of a GKE ServiceAccount token (subject_token_type jwt) for a federated access token. */
class StsWorkloadIdentityServiceTest {

    private static final Instant NOW = Instant.parse("2026-11-11T10:00:00Z");
    private static final String JWT_TYPE = "urn:ietf:params:oauth:token-type:jwt";

    private final InMemoryStorage<String, StoredCredentialToken> store = new InMemoryStorage<>();
    private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
    private final CredentialTokenService tokens = new CredentialTokenService(store, clock);
    private final AtomicInteger refreshes = new AtomicInteger();
    private String jwks = WorkloadIdentityTokens.jwks();
    private String clusterPool = POOL;

    private final WorkloadIdentityIssuers issuers = new WorkloadIdentityIssuers() {
        @Override
        public Optional<Provider> provider(String issuer) {
            return ISSUER.equals(issuer) ? Optional.of(new Provider(ISSUER, clusterPool, "123456789012", jwks))
                    : Optional.empty();
        }

        @Override
        public Optional<String> refreshJwks(String issuer) {
            refreshes.incrementAndGet();
            jwks = WorkloadIdentityTokens.jwks();
            return Optional.of(jwks);
        }
    };

    private final StsService service = new StsService(new CredentialAccessBoundaryParser(new ObjectMapper()), tokens,
            issuers, clock);

    private Map<String, Object> exchange(String token, String audience) {
        return service.exchangeToken(StsService.TOKEN_EXCHANGE_GRANT_TYPE, JWT_TYPE, token,
                StsService.ACCESS_TOKEN_TYPE, null, audience, "https://www.googleapis.com/auth/cloud-platform");
    }

    private String reject(String token, String audience) {
        return assertThrows(StsException.class, () -> exchange(token, audience)).error();
    }

    @Test
    void exchangesAServiceAccountTokenForAFederatedAccessToken() {
        Map<String, Object> response = exchange(sign(claims(NOW)), AUDIENCE);

        String token = (String) response.get("access_token");
        assertTrue(token.startsWith(CredentialTokenService.FEDERATED_TOKEN_PREFIX), token);
        assertEquals(StsService.ACCESS_TOKEN_TYPE, response.get("issued_token_type"));
        assertEquals("Bearer", response.get("token_type"));
        assertEquals(3600L, ((Number) response.get("expires_in")).longValue());
        StoredCredentialToken stored = store.get(token).orElseThrow();
        assertEquals("principal://iam.googleapis.com/projects/123456789012/locations/global/workloadIdentityPools/"
                + POOL + "/subject/ns/team/sa/app", stored.getPrincipal());
        assertEquals("uid-app", stored.getAttributes().get("service_account_uid"));
        assertEquals(ISSUER, stored.getAttributes().get("cluster"));
    }

    @Test
    void rejectsTokensThatDoNotProveTheWorkloadAsInvalidGrant() {
        ObjectNode wrongAudience = claims(NOW);
        wrongAudience.putArray("aud").add("other.svc.id.goog");
        ObjectNode wrongIssuer = claims(NOW);
        wrongIssuer.put("iss", WorkloadIdentityTokens.ISSUER + "-2");
        ObjectNode expired = claims(NOW.minusSeconds(3600));
        ObjectNode stale = claims(NOW.minusSeconds(25 * 3600));
        stale.put("exp", NOW.plusSeconds(60).getEpochSecond());
        ObjectNode notAServiceAccount = claims(NOW);
        notAServiceAccount.put("sub", "alice");
        String valid = sign(claims(NOW));
        String[] parts = valid.split("\\.");

        assertEquals("invalid_grant", reject(sign(wrongAudience), AUDIENCE));
        assertEquals("invalid_grant", reject(sign(wrongIssuer), AUDIENCE));
        assertEquals("invalid_grant", reject(sign(expired), AUDIENCE));
        assertEquals("invalid_grant", reject(sign(stale), AUDIENCE));
        assertEquals("invalid_grant", reject(sign(notAServiceAccount), AUDIENCE));
        assertEquals("invalid_grant", reject(sign(Map.of("alg", "RS256", "kid", "unknown"), claims(NOW)), AUDIENCE));
        assertEquals("invalid_grant", reject(parts[0] + "." + parts[1] + "." + parts[2].substring(4) + "AAAA", AUDIENCE));
        assertEquals("invalid_grant", reject(sign(Map.of("alg", "none", "kid", "k1"), claims(NOW)), AUDIENCE));
        assertEquals("invalid_grant", reject(sign(Map.of("alg", "HS256", "kid", "k1"), claims(NOW)), AUDIENCE));
        assertEquals("invalid_grant", reject(sign(Map.of("alg", "RS256"), claims(NOW)), AUDIENCE));
        assertEquals("invalid_grant", reject("not-a-jwt", AUDIENCE));
        clusterPool = null;
        assertEquals("invalid_grant", reject(valid, AUDIENCE));
    }

    @Test
    void anUnknownKeyIdRereadsTheClustersKeysOnce() {
        jwks = "{\"keys\":[]}";
        Map<String, Object> response = exchange(sign(claims(NOW)), AUDIENCE);

        assertTrue(((String) response.get("access_token")).startsWith(CredentialTokenService.FEDERATED_TOKEN_PREFIX));
        assertEquals(1, refreshes.get());
    }

    @Test
    void rejectsAudiencesItCannotFederateAsInvalidRequest() {
        String valid = sign(claims(NOW));
        assertEquals("invalid_request", reject(valid, null));
        assertEquals("invalid_request", reject(valid, "identitynamespace:" + POOL));
        assertEquals("invalid_request", reject(valid, "//iam.googleapis.com/projects/1/locations/global/"
                + "workloadIdentityPools/pool/providers/provider"));
        assertEquals("invalid_request", reject(valid, "identitynamespace:" + POOL + ":https://example.com/issuer"));
    }

    @Test
    void theAccessTokenExchangeStillRequiresItsOptions() {
        StsException e = assertThrows(StsException.class, () -> service.exchangeToken(
                StsService.TOKEN_EXCHANGE_GRANT_TYPE, StsService.ACCESS_TOKEN_TYPE, "source", StsService.ACCESS_TOKEN_TYPE,
                null, null, null));
        assertEquals("invalid_request", e.error());
    }
}
