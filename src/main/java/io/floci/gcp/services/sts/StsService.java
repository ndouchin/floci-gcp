package io.floci.gcp.services.sts;

import io.floci.gcp.config.EmulatorConfig;
import io.floci.gcp.core.common.GcpException;
import io.floci.gcp.core.common.ServiceDescriptor;
import io.floci.gcp.core.common.ServiceProtocol;
import io.floci.gcp.core.common.ServiceRegistry;
import io.floci.gcp.core.common.WorkloadIdentityIssuers;
import io.floci.gcp.services.credentials.CredentialAccessBoundaryParser;
import io.floci.gcp.services.credentials.CredentialAccessBoundaryRule;
import io.floci.gcp.services.credentials.CredentialTokenService;
import io.floci.gcp.services.credentials.StoredCredentialToken;
import io.floci.gcp.services.iam.WorkloadIdentityPrincipals;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;

import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@ApplicationScoped
public class StsService {

    static final String TOKEN_EXCHANGE_GRANT_TYPE = "urn:ietf:params:oauth:grant-type:token-exchange";
    static final String ACCESS_TOKEN_TYPE = "urn:ietf:params:oauth:token-type:access_token";
    static final String JWT_TOKEN_TYPE = "urn:ietf:params:oauth:token-type:jwt";
    static final String BEARER_TOKEN_TYPE = "Bearer";
    /** Federated access tokens for GKE workloads last an hour ("About Workload Identity Federation for GKE"). */
    static final long FEDERATED_LIFETIME_SECONDS = 3600;

    private final ServiceRegistry serviceRegistry;
    private final EmulatorConfig config;
    private final CredentialAccessBoundaryParser cabParser;
    private final CredentialTokenService tokenService;
    private final WorkloadIdentityTokenVerifier verifier;
    private final Clock clock;

    @Inject
    public StsService(ServiceRegistry serviceRegistry, EmulatorConfig config,
            CredentialAccessBoundaryParser cabParser, CredentialTokenService tokenService,
            WorkloadIdentityIssuers issuers) {
        this(serviceRegistry, config, cabParser, tokenService, issuers, Clock.systemUTC());
    }

    StsService(CredentialAccessBoundaryParser cabParser, CredentialTokenService tokenService) {
        this(null, null, cabParser, tokenService, null, Clock.systemUTC());
    }

    StsService(CredentialAccessBoundaryParser cabParser, CredentialTokenService tokenService,
            WorkloadIdentityIssuers issuers, Clock clock) {
        this(null, null, cabParser, tokenService, issuers, clock);
    }

    private StsService(ServiceRegistry serviceRegistry, EmulatorConfig config,
            CredentialAccessBoundaryParser cabParser, CredentialTokenService tokenService,
            WorkloadIdentityIssuers issuers, Clock clock) {
        this.serviceRegistry = serviceRegistry;
        this.config = config;
        this.cabParser = cabParser;
        this.tokenService = tokenService;
        this.verifier = new WorkloadIdentityTokenVerifier(issuers, clock);
        this.clock = clock;
    }

    void onStart(@Observes StartupEvent ev) {
        serviceRegistry.register(ServiceDescriptor.builder("sts")
                .enabled(config.services().sts().enabled())
                .storageKey("credential-tokens")
                .protocol(ServiceProtocol.REST)
                .resourceClasses(StsController.class)
                .api("sts.googleapis.com", "Security Token Service API")
                .build());
    }

    public Map<String, Object> exchangeToken(String grantType, String subjectTokenType,
            String subjectToken, String requestedTokenType, String options) {
        return exchangeToken(grantType, subjectTokenType, subjectToken, requestedTokenType, options, null, null);
    }

    /**
     * {@code sts.googleapis.com/v1/token}: an access token downscoped by a Credential Access Boundary
     * ({@code subject_token_type} access_token, {@code options}), or a GKE ServiceAccount token federated into an
     * access token for its workload ({@code subject_token_type} jwt, {@code audience}
     * {@code identitynamespace:POOL:PROVIDER}).
     */
    public Map<String, Object> exchangeToken(String grantType, String subjectTokenType, String subjectToken,
            String requestedTokenType, String options, String audience, String scope) {
        requireEquals("grant_type", grantType, TOKEN_EXCHANGE_GRANT_TYPE);
        requireEquals("requested_token_type", requestedTokenType, ACCESS_TOKEN_TYPE);
        if (subjectToken == null || subjectToken.isBlank()) {
            throw invalidRequest("subject_token is required");
        }
        if (JWT_TOKEN_TYPE.equals(subjectTokenType)) {
            return federate(subjectToken, audience);
        }
        requireEquals("subject_token_type", subjectTokenType, ACCESS_TOKEN_TYPE);
        if (options == null || options.isBlank()) {
            throw invalidRequest("options is required");
        }

        CredentialTokenService.MintedDownscopedToken mintedToken;
        try {
            List<CredentialAccessBoundaryRule> rules = cabParser.parse(options);
            mintedToken = tokenService.mintDownscopedToken(subjectToken, rules);
        } catch (GcpException e) {
            throw invalidGrant(e.getMessage());
        }
        return response(mintedToken.token(), mintedToken.expiresInSeconds());
    }

    private Map<String, Object> federate(String subjectToken, String audience) {
        WorkloadIdentityPrincipals.Workload workload = verifier.verify(subjectToken, audience);
        StoredCredentialToken token = tokenService.mintFederatedToken(workload,
                clock.instant().plusSeconds(FEDERATED_LIFETIME_SECONDS));
        return response(token, FEDERATED_LIFETIME_SECONDS);
    }

    private static Map<String, Object> response(StoredCredentialToken token, long expiresIn) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("access_token", token.getTokenValue());
        response.put("issued_token_type", ACCESS_TOKEN_TYPE);
        response.put("token_type", BEARER_TOKEN_TYPE);
        response.put("expires_in", expiresIn);
        return response;
    }

    private static void requireEquals(String field, String actual, String expected) {
        if (!expected.equals(actual)) {
            throw invalidRequest(field + " must be " + expected);
        }
    }

    private static StsException invalidRequest(String message) {
        return new StsException("invalid_request", message);
    }

    private static StsException invalidGrant(String message) {
        return new StsException("invalid_grant", message);
    }
}
