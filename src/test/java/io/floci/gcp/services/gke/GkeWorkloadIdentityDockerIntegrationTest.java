package io.floci.gcp.services.gke;

import com.github.dockerjava.api.model.Container;
import io.floci.gcp.core.common.ProjectNumbers;
import io.floci.gcp.core.common.docker.ContainerLifecycleManager;
import io.floci.gcp.core.common.docker.ContainerStorageHelper;
import io.floci.gcp.services.credentials.CredentialTokenService;
import io.floci.gcp.services.gke.model.StoredCluster;
import io.floci.gcp.services.gke.model.StoredNode;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import io.restassured.response.Response;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.condition.EnabledIf;

import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Workload Identity Federation for GKE on a real cluster, under {@code iam.authorization-mode=enforce}: Pods reach the
 * metadata server at {@code 169.254.169.254} and {@code metadata.google.internal}, a Pod of a {@code GKE_METADATA}
 * pool gets a federated token for its Kubernetes ServiceAccount, an annotated one impersonates its Google service
 * account only with {@code roles/iam.workloadIdentityUser}, Pods of a {@code GCE_METADATA} pool and
 * {@code hostNetwork} Pods act as the node's service account, a restarted node gets its relay back, and deleting the
 * cluster removes the relays. Needs Docker, like {@link GkeDockerIntegrationTest}.
 */
@QuarkusTest
@TestProfile(GkeWorkloadIdentityDockerIntegrationTest.Enforced.class)
@EnabledIf("io.floci.gcp.services.gke.GkeDockerIntegrationTest#dockerAvailable")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class GkeWorkloadIdentityDockerIntegrationTest {

    private static final String PROJECT = "gke-wi-docker-it";
    private static final String ZONE = "us-central1-a";
    private static final String CLUSTER = "wi";
    private static final String POOL = PROJECT + ".svc.id.goog";
    private static final String GSA = "gsa@" + PROJECT + ".iam.gserviceaccount.com";
    private static final String NODE_SA = "nodes@" + PROJECT + ".iam.gserviceaccount.com";
    private static final String BASE = "/container/v1/projects/" + PROJECT + "/locations/" + ZONE;
    private static final String TOKEN = "http://169.254.169.254/computeMetadata/v1/instance/service-accounts/default/token";
    private static final Duration TIMEOUT = Duration.ofMinutes(5);

    public static class Enforced implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of(
                    "floci-gcp.services.gke.mock", "false",
                    "floci-gcp.services.iam.authorization-mode", "enforce",
                    "quarkus.http.test-port", "18591",
                    "floci-gcp.port", "18591",
                    "floci-gcp.docker.resource-namespace", "gke-wi-it");
        }
    }

    @Inject
    GkeService gkeService;

    @Inject
    GkeClusterManager clusterManager;

    @Inject
    ContainerLifecycleManager lifecycleManager;

    @Inject
    CredentialTokenService tokens;

    private static GkeService service;

    @AfterAll
    static void removeLeftovers() {
        if (service != null) {
            try {
                service.deleteCluster(PROJECT, ZONE, CLUSTER);
            } catch (RuntimeException ignored) {
                // Already deleted by the last test; nothing left to clean.
            }
        }
    }

    @Test
    @Order(10)
    void podsReachTheMetadataServerOnBothAddresses() {
        service = gkeService;
        Response create = given().contentType("application/json").body("""
                {"cluster": {"name": "%s", "workloadIdentityConfig": {"workloadPool": "%s"},
                  "nodePools": [
                    {"name": "default-pool", "initialNodeCount": 1},
                    {"name": "legacy", "initialNodeCount": 1, "config": {
                      "serviceAccount": "%s", "workloadMetadataConfig": {"mode": "GCE_METADATA"}}}]}}
                """.formatted(CLUSTER, POOL, NODE_SA)).post(BASE + "/clusters");
        create.then().statusCode(200);
        await("cluster RUNNING", () -> "RUNNING".equals(given().get(BASE + "/clusters/" + CLUSTER).path("status")));
        await("pools RUNNING", () -> "RUNNING".equals(pool("default-pool")) && "RUNNING".equals(pool("legacy")));

        kubectlApply("""
                apiVersion: v1
                kind: Namespace
                metadata: {name: team}
                ---
                apiVersion: v1
                kind: ServiceAccount
                metadata: {name: app, namespace: team}
                ---
                apiVersion: v1
                kind: ServiceAccount
                metadata:
                  name: linked
                  namespace: team
                  annotations: {iam.gke.io/gcp-service-account: %s}
                """.formatted(GSA));
        pod("app", "app", "nodeSelector: {iam.gke.io/gke-metadata-server-enabled: \"true\"}");
        pod("linked", "linked", "nodeSelector: {iam.gke.io/gke-metadata-server-enabled: \"true\"}");
        pod("legacy", "app", "nodeSelector: {cloud.google.com/gke-nodepool: legacy}");
        pod("host", "app", "nodeSelector: {iam.gke.io/gke-metadata-server-enabled: \"true\"}\n  hostNetwork: true");
        for (String pod : List.of("app", "linked", "legacy", "host")) {
            await("pod " + pod + " Running", () -> "Running".equals(kubectlOrEmpty("-n", "team", "get", "pod", pod,
                    "-o", "jsonpath={.status.phase}")));
        }

        assertEquals(PROJECT, fetch("app", "http://169.254.169.254/computeMetadata/v1/project/project-id"));
        await("metadata.google.internal answers", () -> PROJECT.equals(fetchOrEmpty("app",
                "http://metadata.google.internal/computeMetadata/v1/project/project-id")));
        assertEquals(PROJECT, fetch("legacy", "http://169.254.169.254/computeMetadata/v1/project/project-id"));
    }

    @Test
    @Order(20)
    void aWorkloadPoolPodGetsAFederatedTokenForItsServiceAccount() {
        String token = accessToken(fetch("app", TOKEN));
        assertEquals("principal://iam.googleapis.com/projects/" + ProjectNumbers.of(PROJECT)
                        + "/locations/global/workloadIdentityPools/" + POOL + "/subject/ns/team/sa/app",
                tokens.lookupBearerToken(token).orElseThrow().getPrincipal());
        assertEquals(POOL, fetch("app", "http://169.254.169.254/computeMetadata/v1/instance/service-accounts/default/email"));

        given().contentType("application/json").body(Map.of("replication", Map.of("automatic", Map.of())))
                .queryParam("secretId", "db").post("/v1/projects/" + PROJECT + "/secrets").then().statusCode(200);
        given().urlEncodingEnabled(false).contentType("application/json")
                .body(Map.of("payload", Map.of("data", Base64.getEncoder().encodeToString("s3cr3t".getBytes()))))
                .post("/v1/projects/" + PROJECT + "/secrets/db:addVersion").then().statusCode(200);
        String access = "/v1/projects/" + PROJECT + "/secrets/db/versions/1:access";
        given().urlEncodingEnabled(false).header("Authorization", "Bearer " + token).get(access).then().statusCode(403);
        given().urlEncodingEnabled(false).contentType("application/json").body(Map.of("policy", Map.of("bindings",
                        List.of(Map.of("role", "roles/secretmanager.secretAccessor", "members", List.of(
                                "principal://iam.googleapis.com/projects/" + ProjectNumbers.of(PROJECT)
                                        + "/locations/global/workloadIdentityPools/" + POOL + "/subject/ns/team/sa/app"))))))
                .post("/v1/projects/" + PROJECT + "/secrets/db:setIamPolicy").then().statusCode(200);
        given().urlEncodingEnabled(false).header("Authorization", "Bearer " + token).get(access).then().statusCode(200);
    }

    @Test
    @Order(30)
    void anAnnotatedServiceAccountNeedsWorkloadIdentityUserOnItsGoogleServiceAccount() {
        given().contentType("application/json").body(Map.of("accountId", "gsa"))
                .post("/v1/projects/" + PROJECT + "/serviceAccounts").then().statusCode(200);

        ContainerLifecycleManager.ExecResult denied = clusterManager.kubectl(stored(), List.of("-n", "team", "exec",
                "linked", "--", "wget", "-qO-", "--header", "Metadata-Flavor: Google", TOKEN));
        assertTrue(denied.exitCode() != 0 && denied.stderr().contains("403"), denied.stderr());
        Response body = given().header(GkeMetadataController.PEER_HEADER, podIp("linked"))
                .header(GkeMetadataController.RELAY_KEY_HEADER, stored().getMetadataRelayKey())
                .header("Metadata-Flavor", "Google")
                .get("/_floci-gcp/gke/metadata/projects/" + PROJECT + "/locations/" + ZONE + "/clusters/" + CLUSTER
                        + "/nodes/" + nodeOf("linked") + "/gke/computeMetadata/v1/instance/service-accounts/default/token");
        assertEquals(403, body.statusCode());
        assertTrue(body.asString().contains("Permission 'iam.serviceAccounts.getAccessToken' denied on resource "
                + "(or it may not exist)."), body.asString());

        given().urlEncodingEnabled(false).contentType("application/json").body(Map.of("policy", Map.of("bindings",
                        List.of(Map.of("role", "roles/iam.workloadIdentityUser",
                                "members", List.of("serviceAccount:" + POOL + "[team/linked]"))))))
                .post("/v1/projects/" + PROJECT + "/serviceAccounts/" + GSA + ":setIamPolicy").then().statusCode(200);
        String token = accessToken(fetch("linked", TOKEN));
        assertEquals(GSA, tokens.lookupBearerToken(token).orElseThrow().getPrincipal());
        assertEquals(GSA, fetch("linked", "http://169.254.169.254/computeMetadata/v1/instance/service-accounts/default/email"));
    }

    @Test
    @Order(40)
    void computeMetadataPoolsAndHostNetworkPodsActAsTheNodesServiceAccount() {
        assertEquals(NODE_SA, tokens.lookupBearerToken(accessToken(fetch("legacy", TOKEN))).orElseThrow().getPrincipal());
        assertEquals(ProjectNumbers.of(PROJECT) + "-compute@developer.gserviceaccount.com",
                tokens.lookupBearerToken(accessToken(fetch("host", TOKEN))).orElseThrow().getPrincipal());
    }

    @Test
    @Order(50)
    void aRestartedNodeGetsItsRelayBack() {
        StoredNode node = gkeService.getNodePool(PROJECT, ZONE, CLUSTER, "default-pool").getNodes().get(0);
        lifecycleManager.getDockerClient().restartContainerCmd(node.getContainerId()).withTimeout(5).exec();
        await("restarted node Ready", () -> kubectlOrEmpty("get", "node", node.getName(), "-o",
                "jsonpath={.status.conditions[?(@.type==\"Ready\")].status}").equals("True"));
        gkeService.watchMetadataRelays(PROJECT, ZONE, CLUSTER);

        await("pod app answers again", () -> PROJECT.equals(fetchOrEmpty("app",
                "http://169.254.169.254/computeMetadata/v1/project/project-id")));
    }

    @Test
    @Order(60)
    void deletingTheClusterRemovesItsRelays() {
        Map<String, String> filter = new HashMap<>(ContainerStorageHelper.resourceIdentityLabels("gke", CLUSTER, PROJECT, ZONE));
        filter.put(GkeClusterManager.ROLE_LABEL, GkeClusterManager.ROLE_METADATA_RELAY);
        assertEquals(2, lifecycleManager.listContainersByLabels("list relays", filter).size());

        given().delete(BASE + "/clusters/" + CLUSTER).then().statusCode(200);

        List<Container> left = lifecycleManager.listContainersByLabels("list cluster containers",
                ContainerStorageHelper.resourceIdentityLabels("gke", CLUSTER, PROJECT, ZONE));
        assertTrue(left.isEmpty(), "no container of the cluster is left: " + left.size());
    }

    private static String pool(String name) {
        return given().get(BASE + "/clusters/" + CLUSTER + "/nodePools/" + name).path("status");
    }

    private void pod(String name, String serviceAccount, String placement) {
        kubectlApply("""
                apiVersion: v1
                kind: Pod
                metadata: {name: %s, namespace: team}
                spec:
                  serviceAccountName: %s
                  %s
                  containers:
                  - name: probe
                    image: rancher/mirrored-library-busybox:1.37.0
                    command: ["sleep", "3600"]
                """.formatted(name, serviceAccount, placement));
    }

    private String fetch(String pod, String url) {
        ContainerLifecycleManager.ExecResult result = clusterManager.kubectl(stored(), List.of("-n", "team", "exec", pod,
                "--", "wget", "-qO-", "--header", "Metadata-Flavor: Google", url));
        assertEquals(0, result.exitCode(), pod + " " + url + ": " + result.stderr());
        return result.stdout().trim();
    }

    private String fetchOrEmpty(String pod, String url) {
        ContainerLifecycleManager.ExecResult result = clusterManager.kubectl(stored(), List.of("-n", "team", "exec", pod,
                "--", "wget", "-qO-", "-T", "5", "--header", "Metadata-Flavor: Google", url));
        return result == null || result.exitCode() != 0 ? "" : result.stdout().trim();
    }

    private static String accessToken(String json) {
        int start = json.indexOf("\"access_token\":\"") + "\"access_token\":\"".length();
        return json.substring(start, json.indexOf('"', start));
    }

    private String podIp(String pod) {
        return kubectlOrEmpty("-n", "team", "get", "pod", pod, "-o", "jsonpath={.status.podIP}");
    }

    private String nodeOf(String pod) {
        return kubectlOrEmpty("-n", "team", "get", "pod", pod, "-o", "jsonpath={.spec.nodeName}");
    }

    private StoredCluster stored() {
        return gkeService.getCluster(PROJECT, ZONE, CLUSTER);
    }

    private String kubectlOrEmpty(String... args) {
        ContainerLifecycleManager.ExecResult result = clusterManager.kubectl(stored(), List.of(args));
        return result == null || result.exitCode() != 0 || result.stdout() == null ? "" : result.stdout().trim();
    }

    private void kubectlApply(String manifest) {
        ContainerLifecycleManager.ExecResult result = clusterManager.applyManifest(stored(), manifest);
        assertEquals(0, result.exitCode(), result.stderr());
        assertFalse(result.stdout().isBlank());
    }

    private static void await(String what, Supplier<Boolean> condition) {
        Instant deadline = Instant.now().plus(TIMEOUT);
        while (Instant.now().isBefore(deadline)) {
            if (Boolean.TRUE.equals(condition.get())) {
                return;
            }
            try {
                Thread.sleep(2000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                fail("interrupted waiting for " + what);
            }
        }
        fail("timed out waiting for " + what);
    }
}
