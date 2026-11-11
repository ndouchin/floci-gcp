package io.floci.gcp.services.gke;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.floci.gcp.core.common.GcpException;
import io.floci.gcp.core.common.ProjectNumbers;
import io.floci.gcp.core.storage.InMemoryStorage;
import io.floci.gcp.services.credentials.CredentialTokenService;
import io.floci.gcp.services.gke.model.StoredCluster;
import io.floci.gcp.services.gke.model.StoredNode;
import io.floci.gcp.services.gke.model.StoredNodePool;
import io.floci.gcp.services.iam.IamServiceAccountAuthorizationAdapter;
import io.floci.gcp.services.iam.authorization.IamAuthorizationService;
import io.floci.gcp.services.iam.authorization.IamOperation;
import io.floci.gcp.services.iamcredentials.IamCredentialsService;
import io.floci.gcp.services.sts.StsService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class GkeMetadataServiceTest {

    private static final Instant NOW = Instant.parse("2026-11-11T10:00:00Z");
    private static final String PROJECT = "p1";
    private static final String POOL = "p1.svc.id.goog";
    private static final String KEY = "relay-key";
    private static final String WI_NODE = "gke-c1-default-pool-1a2b3c4d-aaaa";
    private static final String GCE_NODE = "gke-c1-legacy-5e6f7a8b-bbbb";
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String FEDERATED = CredentialTokenService.FEDERATED_TOKEN_PREFIX + "workload";

    private final GkeService gke = mock(GkeService.class);
    private final GkeWorkloads workloads = mock(GkeWorkloads.class);
    private final StsService sts = mock(StsService.class);
    private final IamAuthorizationService authorization = mock(IamAuthorizationService.class);
    private final IamCredentialsService credentials = mock(IamCredentialsService.class);
    private final CredentialTokenService tokens = new CredentialTokenService(new InMemoryStorage<>(),
            Clock.fixed(NOW, ZoneOffset.UTC));
    private GkeMetadataService service;
    private StoredCluster cluster;

    @BeforeEach
    void setUp() {
        cluster = new StoredCluster();
        cluster.setName("c1");
        cluster.setProject(PROJECT);
        cluster.setLocation("us-central1-a");
        cluster.setStatus("RUNNING");
        cluster.setId("b".repeat(64));
        cluster.setWorkloadPool(POOL);
        cluster.setClusterIpv4Cidr("10.60.0.0/14");
        cluster.setMetadataRelayKey(KEY);
        when(gke.findCluster(PROJECT, "us-central1-a", "c1")).thenReturn(Optional.of(cluster));
        when(gke.listNodePools(PROJECT, "us-central1-a", "c1")).thenReturn(List.of(
                pool("default-pool", WI_NODE, Map.of("workloadMetadataConfig", Map.of("mode", "GKE_METADATA"))),
                pool("legacy", GCE_NODE, Map.of("workloadMetadataConfig", Map.of("mode", "GCE_METADATA"),
                        "serviceAccount", "nodes@p1.iam.gserviceaccount.com"))));
        when(sts.exchangeToken(anyString(), anyString(), anyString(), anyString(), isNull(), anyString(), anyString()))
                .thenReturn(Map.of("access_token", FEDERATED, "expires_in", 3600L));
        when(credentials.generateAccessToken(anyString(), any(), isNull())).thenAnswer(inv ->
                new IamCredentialsService.GeneratedAccessToken("floci-gcp-impersonated-" + inv.getArgument(0),
                        NOW.plusSeconds(3600)));
        service = new GkeMetadataService(gke, workloads, sts, authorization,
                mock(IamServiceAccountAuthorizationAdapter.class), credentials, tokens, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private static StoredNodePool pool(String name, String node, Map<String, Object> config) {
        StoredNodePool pool = new StoredNodePool();
        pool.setName(name);
        pool.setConfig(new HashMap<>(config));
        pool.setNodes(new ArrayList<>(List.of(new StoredNode(node, "us-central1-a"))));
        return pool;
    }

    private void podAt(String ip, Map<String, String> annotations) {
        when(workloads.podAt(any(), eq(WI_NODE), eq(ip))).thenReturn(Optional.of(new GkeWorkloads.Pod("team", "web-0",
                "pod-uid", "app", "ksa-uid", annotations)));
        when(workloads.serviceAccountToken(any(), any(), eq(POOL))).thenReturn("ksa.jwt.token");
    }

    private GkeMetadataService.Answer get(String node, String listener, String peer, String path) {
        return service.answer(new GkeMetadataService.Request(PROJECT, "us-central1-a", "c1", node, listener, path,
                path.endsWith("/") || path.isEmpty(), peer, KEY, "Google", null, Map.of()));
    }

    private static JsonNode json(GkeMetadataService.Answer answer) throws Exception {
        assertEquals(200, answer.status(), answer.body());
        return JSON.readTree(answer.body());
    }

    @Test
    void anAnnotatedServiceAccountActsAsItsGoogleServiceAccount() throws Exception {
        podAt("10.60.1.5", Map.of("iam.gke.io/gcp-service-account", "app@p1.iam.gserviceaccount.com"));

        JsonNode token = json(get(WI_NODE, "gke", "10.60.1.5", "computeMetadata/v1/instance/service-accounts/default/token"));

        assertEquals("floci-gcp-impersonated-app@p1.iam.gserviceaccount.com", token.path("access_token").asText());
        assertEquals("Bearer", token.path("token_type").asText());
        assertEquals(3600, token.path("expires_in").asInt());
        assertEquals("app@p1.iam.gserviceaccount.com",
                get(WI_NODE, "gke", "10.60.1.5", "computeMetadata/v1/instance/service-accounts/default/email").body());
        verify(sts).exchangeToken(anyString(), eq("urn:ietf:params:oauth:token-type:jwt"), eq("ksa.jwt.token"),
                anyString(), isNull(), eq("identitynamespace:" + POOL + ":"
                        + "https://container.googleapis.com/v1/projects/p1/locations/us-central1-a/clusters/c1"), anyString());
    }

    @Test
    void anUnannotatedServiceAccountGetsAFederatedTokenAndThePoolAsEmail() throws Exception {
        podAt("10.60.1.6", Map.of());

        JsonNode token = json(get(WI_NODE, "gke", "10.60.1.6", "computeMetadata/v1/instance/service-accounts/default/token"));

        assertEquals(FEDERATED, token.path("access_token").asText());
        assertEquals(POOL, get(WI_NODE, "gke", "10.60.1.6",
                "computeMetadata/v1/instance/service-accounts/default/email").body());
        verify(credentials, never()).generateAccessToken(anyString(), any(), any());
    }

    @Test
    void returnPrincipalIdAsEmailAnswersTheWorkloadsPrincipalIdentifier() {
        podAt("10.60.1.7", Map.of("iam.gke.io/return-principal-id-as-email", "true"));

        assertEquals("principal://iam.googleapis.com/projects/" + ProjectNumbers.of(PROJECT)
                        + "/locations/global/workloadIdentityPools/" + POOL + "/subject/ns/team/sa/app",
                get(WI_NODE, "gke", "10.60.1.7", "computeMetadata/v1/instance/service-accounts/default/email").body());
    }

    @Test
    void podsOfAComputeMetadataPoolAndHostNetworkPodsActAsTheNodesServiceAccount() throws Exception {
        assertEquals("nodes@p1.iam.gserviceaccount.com", get(GCE_NODE, "gce", "10.60.2.3",
                "computeMetadata/v1/instance/service-accounts/default/email").body());
        assertEquals(ProjectNumbers.of(PROJECT) + "-compute@developer.gserviceaccount.com", get(WI_NODE, "gce",
                "169.254.169.254", "computeMetadata/v1/instance/service-accounts/default/email").body());

        String token = json(get(GCE_NODE, "gce", "10.60.2.3",
                "computeMetadata/v1/instance/service-accounts/default/token")).path("access_token").asText();
        assertEquals("nodes@p1.iam.gserviceaccount.com", tokens.lookupBearerToken(token).orElseThrow().getPrincipal());
        verify(workloads, never()).podAt(any(), any(), any());
    }

    @Test
    void anUnreachableControlPlaneAnswers500() {
        when(workloads.podAt(any(), any(), any())).thenThrow(new GkeWorkloads.ControlPlaneUnavailable("down"));

        assertEquals(500, get(WI_NODE, "gke", "10.60.1.8",
                "computeMetadata/v1/instance/service-accounts/default/token").status());
    }

    @Test
    void requestsMustComeFromTheClustersRelayWithTheMetadataFlavor() {
        GkeMetadataService.Answer wrongKey = service.answer(new GkeMetadataService.Request(PROJECT, "us-central1-a", "c1",
                WI_NODE, "gce", "computeMetadata/v1/project/project-id", false, "169.254.169.254", "nope", "Google",
                null, Map.of()));
        GkeMetadataService.Answer noFlavor = service.answer(new GkeMetadataService.Request(PROJECT, "us-central1-a", "c1",
                WI_NODE, "gce", "computeMetadata/v1/project/project-id", false, "169.254.169.254", KEY, null, null,
                Map.of()));
        GkeMetadataService.Answer forwarded = service.answer(new GkeMetadataService.Request(PROJECT, "us-central1-a",
                "c1", WI_NODE, "gce", "computeMetadata/v1/project/project-id", false, "169.254.169.254", KEY, "Google",
                "10.0.0.1", Map.of()));
        GkeMetadataService.Answer root = service.answer(new GkeMetadataService.Request(PROJECT, "us-central1-a", "c1",
                WI_NODE, "gce", "", true, "169.254.169.254", KEY, null, null, Map.of()));

        assertEquals(403, wrongKey.status());
        assertEquals(403, noFlavor.status());
        assertEquals(403, forwarded.status());
        assertEquals(200, root.status());
        assertEquals("computeMetadata/\n", root.body());
        assertEquals("p1", get(WI_NODE, "gce", "169.254.169.254", "computeMetadata/v1/project/project-id").body());
        assertEquals(404, get(WI_NODE, "gce", "169.254.169.254", "computeMetadata/v1/instance/attributes/kube-env").status());
    }

    @Test
    void underEnforcementAMissingWorkloadIdentityUserBindingIsTheErrorGkeLogs() {
        podAt("10.60.1.9", Map.of("iam.gke.io/gcp-service-account", "app@p1.iam.gserviceaccount.com"));
        when(authorization.enabled()).thenReturn(true);
        doThrow(GcpException.permissionDenied("denied")).when(authorization).authorize(anyString(), any(),
                any(IamOperation.class));

        GkeMetadataService.Answer answer = get(WI_NODE, "gke", "10.60.1.9",
                "computeMetadata/v1/instance/service-accounts/default/token");

        assertEquals(403, answer.status());
        assertTrue(answer.body().contains("GenerateAccessToken(\"app@p1.iam.gserviceaccount.com\", \"\")"), answer.body());
        assertTrue(answer.body().contains("Permission 'iam.serviceAccounts.getAccessToken' denied on resource "
                + "(or it may not exist)."), answer.body());
        verify(credentials, never()).generateAccessToken(anyString(), any(), any());
    }
}
