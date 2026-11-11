package io.floci.gcp.services.gke;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.floci.gcp.core.common.GcpException;
import io.floci.gcp.core.common.ProjectNumbers;
import io.floci.gcp.services.compute.ComputeMetadataTree;
import io.floci.gcp.services.compute.MetadataTreeRenderer;
import io.floci.gcp.services.credentials.CredentialTokenService;
import io.floci.gcp.services.gke.model.StoredCluster;
import io.floci.gcp.services.gke.model.StoredNode;
import io.floci.gcp.services.gke.model.StoredNodePool;
import io.floci.gcp.services.iam.IamServiceAccountAuthorizationAdapter;
import io.floci.gcp.services.iam.WorkloadIdentityPrincipals;
import io.floci.gcp.services.iam.authorization.IamAuthorizationService;
import io.floci.gcp.services.iam.authorization.IamOperation;
import io.floci.gcp.services.iamcredentials.IamCredentialsService;
import io.floci.gcp.services.sts.StsException;
import io.floci.gcp.services.sts.StsService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * floci-gcp's side of the GKE metadata server: answers what a node's relay forwards. A request on the GKE metadata
 * listener from a Pod address of a node that runs the GKE metadata server is a workload: it acts as its Kubernetes
 * ServiceAccount through Workload Identity Federation for GKE (a TokenRequest token exchanged at STS, then, with the
 * {@code iam.gke.io/gcp-service-account} annotation, impersonating that Google service account). Anything else (the
 * node, {@code hostNetwork} Pods, Pods of {@code GCE_METADATA} pools) acts as the node pool's service account.
 */
@ApplicationScoped
public class GkeMetadataService {

    private static final Logger LOG = Logger.getLogger(GkeMetadataService.class);
    private static final ObjectMapper JSON = new ObjectMapper();
    static final String GSA_ANNOTATION = "iam.gke.io/gcp-service-account";
    static final String PRINCIPAL_EMAIL_ANNOTATION = "iam.gke.io/return-principal-id-as-email";
    static final String CLOUD_PLATFORM = "https://www.googleapis.com/auth/cloud-platform";
    /** gcloud's default node scopes when a pool names none ({@code gcloud container clusters create --scopes}). */
    static final List<String> DEFAULT_NODE_SCOPES = List.of(
            "https://www.googleapis.com/auth/devstorage.read_only",
            "https://www.googleapis.com/auth/logging.write",
            "https://www.googleapis.com/auth/monitoring",
            "https://www.googleapis.com/auth/service.management.readonly",
            "https://www.googleapis.com/auth/servicecontrol",
            "https://www.googleapis.com/auth/trace.append");
    private static final Duration REFRESH_MARGIN = Duration.ofMinutes(5);
    private static final String TEXT = MetadataTreeRenderer.TEXT;

    /** One relayed request: where it came from, its path under the listener, headers and query parameters. */
    public record Request(String project, String location, String cluster, String node, String listener, String path,
                          boolean slash, String peer, String relayKey, String flavor, String forwardedFor,
                          Map<String, String> query) {
    }

    /** What to answer: a status, a body, its type and ETag, and the target of a redirect. */
    public record Answer(int status, String body, String contentType, String etag, String location) {

        static Answer text(int status, String body) {
            return new Answer(status, body, TEXT, null, null);
        }
    }

    private record CachedToken(String token, Instant expiry) {
    }

    private final GkeService gke;
    private final GkeWorkloads workloads;
    private final StsService sts;
    private final IamAuthorizationService authorization;
    private final IamServiceAccountAuthorizationAdapter serviceAccounts;
    private final IamCredentialsService credentials;
    private final CredentialTokenService tokens;
    private final Clock clock;
    private final Map<String, CachedToken> tokenCache = new ConcurrentHashMap<>();

    @Inject
    public GkeMetadataService(GkeService gke, GkeWorkloads workloads, StsService sts,
                              IamAuthorizationService authorization, IamServiceAccountAuthorizationAdapter serviceAccounts,
                              IamCredentialsService credentials, CredentialTokenService tokens) {
        this(gke, workloads, sts, authorization, serviceAccounts, credentials, tokens, Clock.systemUTC());
    }

    GkeMetadataService(GkeService gke, GkeWorkloads workloads, StsService sts, IamAuthorizationService authorization,
                       IamServiceAccountAuthorizationAdapter serviceAccounts, IamCredentialsService credentials,
                       CredentialTokenService tokens, Clock clock) {
        this.gke = gke;
        this.workloads = workloads;
        this.sts = sts;
        this.authorization = authorization;
        this.serviceAccounts = serviceAccounts;
        this.credentials = credentials;
        this.tokens = tokens;
        this.clock = clock;
    }

    private record Caller(StoredCluster cluster, StoredNodePool pool, String zone, GkeWorkloads.Pod pod) {

        boolean workload() {
            return pod != null;
        }
    }

    public Answer answer(Request request) {
        Optional<StoredCluster> found = gke.findCluster(request.project(), request.location(), request.cluster());
        if (found.isEmpty()) {
            return Answer.text(404, "Unknown cluster " + request.cluster() + ".");
        }
        StoredCluster cluster = found.get();
        if (cluster.getMetadataRelayKey() == null || !cluster.getMetadataRelayKey().equals(request.relayKey())) {
            return Answer.text(403, "This metadata endpoint only answers the cluster's own metadata relays.");
        }
        StoredNodePool pool = null;
        String zone = null;
        for (StoredNodePool candidate : gke.listNodePools(request.project(), request.location(), request.cluster())) {
            for (StoredNode node : candidate.getNodes() == null ? List.<StoredNode>of() : candidate.getNodes()) {
                if (node.getName().equals(request.node())) {
                    pool = candidate;
                    zone = node.getZone();
                }
            }
        }
        if (pool == null) {
            return Answer.text(404, "Unknown node " + request.node() + ".");
        }
        if (request.forwardedFor() != null) {
            return Answer.text(403, "Request contains an X-Forwarded-For header and was rejected.");
        }
        String path = request.path() == null ? "" : request.path();
        if (path.isEmpty()) {
            return new Answer(200, "computeMetadata/\n", TEXT, null, null);
        }
        if (!"Google".equals(request.flavor())) {
            return Answer.text(403, "Missing Metadata-Flavor:Google header.");
        }
        if (path.equals("computeMetadata") || path.equals("computeMetadata/")) {
            return path.endsWith("/") ? new Answer(200, "v1/\n", TEXT, null, null)
                    : new Answer(301, null, TEXT, null, "/computeMetadata/");
        }
        if (!path.equals("computeMetadata/v1") && !path.startsWith("computeMetadata/v1/")) {
            return Answer.text(404, "Not Found");
        }
        String subPath = path.equals("computeMetadata/v1") ? "" : path.substring("computeMetadata/v1/".length());
        boolean slash = request.slash() || (subPath.isEmpty() && path.endsWith("/"));

        Caller caller;
        try {
            caller = caller(request, cluster, pool, zone);
        } catch (GkeWorkloads.ControlPlaneUnavailable e) {
            return Answer.text(500, "Unable to reach the control plane of cluster " + cluster.getName() + ": " + e.getMessage());
        }
        if (caller == null) {
            return Answer.text(404, "No Pod with address " + request.peer() + " runs on node " + request.node() + ".");
        }
        ComputeMetadataTree.Dir tree = GkeMetadataTree.build(cluster, zone, request.node(), identity(caller));
        Map<String, String> query = request.query() == null ? Map.of() : request.query();
        String rawQuery = query.entrySet().stream().map(e -> e.getKey() + "=" + e.getValue())
                .collect(Collectors.joining("&"));
        return switch (MetadataTreeRenderer.render(tree, subPath, slash, "true".equalsIgnoreCase(query.get("recursive")),
                query.get("alt"), rawQuery, Set.of())) {
            case MetadataTreeRenderer.NotFound ignored -> Answer.text(404, "Not Found");
            case MetadataTreeRenderer.Redirect redirect -> new Answer(301, null, TEXT, null, redirect.location());
            case MetadataTreeRenderer.Rendered rendered -> new Answer(200, rendered.body(), rendered.contentType(),
                    rendered.etag(), null);
            case MetadataTreeRenderer.Generated generated -> generate(caller, generated.path(), query);
        };
    }

    private Caller caller(Request request, StoredCluster cluster, StoredNodePool pool, String zone) {
        boolean workloadListener = "gke".equals(request.listener()) && GkeService.runsGkeMetadataServer(cluster, pool)
                && inCidr(request.peer(), cluster.getClusterIpv4Cidr());
        if (!workloadListener) {
            return new Caller(cluster, pool, zone, null);
        }
        return workloads.podAt(cluster, request.node(), request.peer())
                .map(pod -> new Caller(cluster, pool, zone, pod))
                .orElse(null);
    }

    private GkeMetadataTree.Identity identity(Caller caller) {
        if (!caller.workload()) {
            return new GkeMetadataTree.Identity(nodeServiceAccount(caller.cluster(), caller.pool()), nodeScopes(caller.pool()));
        }
        Map<String, String> annotations = caller.pod().annotations();
        String email;
        if ("true".equalsIgnoreCase(annotations.get(PRINCIPAL_EMAIL_ANNOTATION))) {
            email = WorkloadIdentityPrincipals.member(workload(caller));
        } else if (annotations.get(GSA_ANNOTATION) != null && !annotations.get(GSA_ANNOTATION).isBlank()) {
            email = annotations.get(GSA_ANNOTATION);
        } else {
            email = caller.cluster().getWorkloadPool();
        }
        return new GkeMetadataTree.Identity(email, List.of(CLOUD_PLATFORM));
    }

    private WorkloadIdentityPrincipals.Workload workload(Caller caller) {
        return new WorkloadIdentityPrincipals.Workload(ProjectNumbers.of(caller.cluster().getProject()),
                caller.cluster().getWorkloadPool(), caller.pod().namespace(), caller.pod().serviceAccount(),
                caller.pod().serviceAccountUid(), GkeWorkloadIdentity.issuer(caller.cluster()));
    }

    /** The pool's {@code config.serviceAccount}, else the Compute Engine default service account. */
    static String nodeServiceAccount(StoredCluster cluster, StoredNodePool pool) {
        Object configured = pool.getConfig() == null ? null : pool.getConfig().get("serviceAccount");
        if (configured instanceof String email && !email.isBlank() && !"default".equals(email)) {
            return email;
        }
        return ProjectNumbers.of(cluster.getProject()) + "-compute@developer.gserviceaccount.com";
    }

    private static List<String> nodeScopes(StoredNodePool pool) {
        if (pool.getConfig() != null && pool.getConfig().get("oauthScopes") instanceof List<?> scopes && !scopes.isEmpty()) {
            return scopes.stream().map(String::valueOf).toList();
        }
        return DEFAULT_NODE_SCOPES;
    }

    private Answer generate(Caller caller, List<String> path, Map<String, String> query) {
        if ("token".equals(path.get(path.size() - 1))) {
            try {
                return token(caller);
            } catch (GkeWorkloads.ControlPlaneUnavailable e) {
                return Answer.text(500, "Unable to reach the control plane of cluster " + caller.cluster().getName()
                        + ": " + e.getMessage());
            }
        }
        String audience = query.get("audience");
        if (audience == null || audience.isBlank()) {
            return Answer.text(400, "non-empty audience parameter required");
        }
        return identityToken(caller, audience, "full".equals(query.get("format")));
    }

    private Answer token(Caller caller) {
        Instant now = clock.instant();
        String email = identity(caller).email();
        String key = caller.workload() ? "pod|" + caller.pod().uid() + "|" + gsa(caller)
                : "node|" + caller.cluster().getContainerId() + "|" + email;
        CachedToken cached = tokenCache.get(key);
        if (cached == null || !cached.expiry().minus(REFRESH_MARGIN).isAfter(now)) {
            Optional<CachedToken> minted = caller.workload() ? workloadToken(caller) : Optional.of(nodeToken(email, now));
            if (minted.isEmpty()) {
                return forbidden(gsa(caller), "getAccessToken", "GenerateAccessToken");
            }
            cached = minted.get();
            tokenCache.put(key, cached);
        }
        ObjectNode body = JSON.createObjectNode()
                .put("access_token", cached.token())
                .put("expires_in", Math.max(0, Duration.between(now, cached.expiry()).toSeconds()))
                .put("token_type", "Bearer");
        return new Answer(200, MetadataTreeRenderer.write(body), MetadataTreeRenderer.JSON_TYPE, null, null);
    }

    private CachedToken nodeToken(String email, Instant now) {
        Instant expiry = now.plusSeconds(CredentialTokenService.DEFAULT_LIFETIME_SECONDS);
        return new CachedToken(tokens.mintImpersonatedToken(email, expiry).getTokenValue(), expiry);
    }

    private static String gsa(Caller caller) {
        String gsa = caller.pod().annotations().get(GSA_ANNOTATION);
        return gsa == null || gsa.isBlank() ? null : gsa;
    }

    /**
     * The workload's token, the way {@code gke-metadata-server} gets it: a TokenRequest token for its ServiceAccount
     * with the pool as audience, exchanged at STS for a federated token, which then impersonates the annotated Google
     * service account. Empty when IAM refuses the impersonation.
     */
    private Optional<CachedToken> workloadToken(Caller caller) {
        StoredCluster cluster = caller.cluster();
        String pool = cluster.getWorkloadPool();
        String serviceAccountToken = workloads.serviceAccountToken(cluster, caller.pod(), pool);
        Map<String, Object> exchanged;
        try {
            exchanged = sts.exchangeToken("urn:ietf:params:oauth:grant-type:token-exchange",
                    "urn:ietf:params:oauth:token-type:jwt", serviceAccountToken,
                    "urn:ietf:params:oauth:token-type:access_token", null,
                    "identitynamespace:" + pool + ":" + GkeWorkloadIdentity.issuer(cluster), CLOUD_PLATFORM);
        } catch (StsException e) {
            throw new GkeWorkloads.ControlPlaneUnavailable("STS refused the ServiceAccount token: " + e.getMessage());
        }
        String federated = (String) exchanged.get("access_token");
        Instant expiry = clock.instant().plusSeconds(((Number) exchanged.get("expires_in")).longValue());
        String gsa = gsa(caller);
        if (gsa == null) {
            return Optional.of(new CachedToken(federated, expiry));
        }
        if (!impersonationAllowed(federated, "GenerateAccessToken", gsa)) {
            return Optional.empty();
        }
        IamCredentialsService.GeneratedAccessToken token = credentials.generateAccessToken(gsa, List.of(CLOUD_PLATFORM),
                null);
        return Optional.of(new CachedToken(token.accessToken(), token.expireTime()));
    }

    /** Under {@code iam.authorization-mode=enforce}, whether the federated workload may act as the Google service account. */
    private boolean impersonationAllowed(String federated, String operation, String gsa) {
        if (!authorization.enabled()) {
            return true;
        }
        try {
            authorization.authorize("Bearer " + federated, serviceAccounts,
                    new IamOperation(operation, "projects/-/serviceAccounts/" + gsa));
            return true;
        } catch (GcpException e) {
            LOG.debugv("Workload Identity impersonation of {0} refused: {1}", gsa, e.getMessage());
            return false;
        }
    }

    /**
     * The error GKE's documentation shows a workload logging when its Kubernetes ServiceAccount lacks
     * {@code roles/iam.workloadIdentityUser} on the Google service account ("Troubleshoot GKE authentication").
     */
    private static Answer forbidden(String gsa, String permission, String method) {
        return Answer.text(403, "generic::permission_denied: loading: " + method + "(\"" + gsa + "\", \"\"): "
                + "googleapi: Error 403: Permission 'iam.serviceAccounts." + permission
                + "' denied on resource (or it may not exist).");
    }

    private Answer identityToken(Caller caller, String audience, boolean full) {
        if (!caller.workload()) {
            return new Answer(200, unsignedIdToken(identity(caller).email(), audience), TEXT, null, null);
        }
        String gsa = gsa(caller);
        if (gsa == null) {
            return Answer.text(404, "Workload Identity Federation for GKE issues identity tokens only for Kubernetes "
                    + "ServiceAccounts annotated with " + GSA_ANNOTATION + ".");
        }
        Map<String, Object> exchanged;
        try {
            exchanged = sts.exchangeToken("urn:ietf:params:oauth:grant-type:token-exchange",
                    "urn:ietf:params:oauth:token-type:jwt",
                    workloads.serviceAccountToken(caller.cluster(), caller.pod(), caller.cluster().getWorkloadPool()),
                    "urn:ietf:params:oauth:token-type:access_token", null, "identitynamespace:"
                            + caller.cluster().getWorkloadPool() + ":" + GkeWorkloadIdentity.issuer(caller.cluster()),
                    CLOUD_PLATFORM);
        } catch (StsException | GkeWorkloads.ControlPlaneUnavailable e) {
            return Answer.text(500, "Unable to federate the workload: " + e.getMessage());
        }
        if (!impersonationAllowed((String) exchanged.get("access_token"), "GenerateIdToken", gsa)) {
            return forbidden(gsa, "getOpenIdToken", "GenerateIdToken");
        }
        try {
            return new Answer(200, credentials.generateIdToken(gsa, audience, full).get("token"), TEXT, null, null);
        } catch (GcpException e) {
            return Answer.text(e.getHttpStatus() == 403 ? 403 : 404, e.getMessage());
        }
    }

    /** An ID token shaped like Google's but not signed by a Google key, as floci-gcp's Compute metadata server mints. */
    private String unsignedIdToken(String email, String audience) {
        long now = clock.instant().getEpochSecond();
        ObjectNode claims = JSON.createObjectNode()
                .put("aud", audience).put("azp", email).put("email", email).put("email_verified", true)
                .put("exp", now + 3600).put("iat", now).put("iss", "https://accounts.google.com")
                .put("sub", Long.toUnsignedString(email.hashCode() & 0xffffffffL));
        ObjectNode header = JSON.createObjectNode().put("alg", "RS256").put("kid", "floci-gcp").put("typ", "JWT");
        Base64.Encoder base64 = Base64.getUrlEncoder().withoutPadding();
        return base64.encodeToString(MetadataTreeRenderer.write(header).getBytes(StandardCharsets.UTF_8)) + "."
                + base64.encodeToString(MetadataTreeRenderer.write(claims).getBytes(StandardCharsets.UTF_8)) + "."
                + base64.encodeToString("floci-gcp-unsigned".getBytes(StandardCharsets.UTF_8));
    }

    static boolean inCidr(String ip, String cidr) {
        if (ip == null || cidr == null || !ip.matches("^\\d{1,3}(\\.\\d{1,3}){3}$")) {
            return false;
        }
        int slash = cidr.indexOf('/');
        int bits = Integer.parseInt(cidr.substring(slash + 1));
        long mask = bits == 0 ? 0 : (0xffffffffL << (32 - bits)) & 0xffffffffL;
        return (toLong(ip) & mask) == (toLong(cidr.substring(0, slash)) & mask);
    }

    private static long toLong(String ip) {
        long value = 0;
        for (String part : ip.split("\\.")) {
            value = (value << 8) | Integer.parseInt(part);
        }
        return value;
    }
}
