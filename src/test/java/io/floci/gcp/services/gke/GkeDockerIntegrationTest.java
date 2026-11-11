package io.floci.gcp.services.gke;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.dockerjava.api.command.InspectContainerResponse;
import com.github.dockerjava.api.model.Container;
import io.floci.gcp.config.EmulatorConfig;
import io.floci.gcp.core.common.docker.ContainerLifecycleManager;
import io.floci.gcp.core.common.docker.ContainerStorageHelper;
import io.floci.gcp.services.credentials.CredentialTokenService;
import io.floci.gcp.services.gke.model.StoredCluster;
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

import java.math.BigInteger;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.RSAPublicKeySpec;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.anyOf;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Runs GKE clusters on real k3s containers: an agentless control plane on the k3s release of the
 * requested version, node pools as k3s agents carrying GKE node names, labels, taints and provider
 * IDs, restore of a stopped control plane, and a master upgrade onto the next minor's image. Needs
 * Docker; skipped when no Docker daemon is reachable or {@code -Dfloci.skip-docker-tests=true} is set.
 */
@QuarkusTest
@TestProfile(GkeDockerIntegrationTest.RealClusters.class)
@EnabledIf("io.floci.gcp.services.gke.GkeDockerIntegrationTest#dockerAvailable")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class GkeDockerIntegrationTest {

    private static final String PROJECT = "gke-docker-it";
    private static final String ZONE = "us-central1-a";
    private static final String CLUSTER = "real";
    private static final String BASE = "/container/v1/projects/" + PROJECT + "/locations/" + ZONE;
    private static final Duration TIMEOUT = Duration.ofMinutes(5);
    private static final ObjectMapper JSON = new ObjectMapper();

    public static class RealClusters implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of(
                    "floci-gcp.services.gke.mock", "false",
                    "quarkus.http.test-port", "18589",
                    "floci-gcp.port", "18589",
                    "floci-gcp.docker.resource-namespace", "gke-it");
        }
    }

    static boolean dockerAvailable() {
        if (Boolean.getBoolean("floci.skip-docker-tests")) {
            return false;
        }
        return System.getenv("DOCKER_HOST") != null || Files.exists(Path.of("/var/run/docker.sock"));
    }

    @Inject
    GkeService gkeService;

    @Inject
    GkeClusterManager clusterManager;

    @Inject
    ContainerLifecycleManager lifecycleManager;

    @Inject
    CredentialTokenService tokens;

    @Inject
    EmulatorConfig emulatorConfig;

    private static EmulatorConfig config;

    private static GkeService service;
    private static ContainerLifecycleManager docker;

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
    void createsAnAgentlessControlPlaneOnThePinnedK3sRelease() {
        service = gkeService;
        docker = lifecycleManager;
        config = emulatorConfig;
        Response create = given().contentType("application/json").body("""
                {"cluster": {"name": "%s", "initialClusterVersion": "1.35",
                  "ipAllocationPolicy": {"useIpAliases": true, "clusterIpv4CidrBlock": "10.60.0.0/16"},
                  "defaultMaxPodsConstraint": {"maxPodsPerNode": "64"},
                  "nodePools": [{"name": "default-pool", "initialNodeCount": 1,
                                 "config": {"machineType": "e2-medium", "labels": {"team": "it"}}}]}}
                """.formatted(CLUSTER)).post(BASE + "/clusters");
        create.then().statusCode(200);
        awaitOperation(create.path("name"));

        Response cluster = given().get(BASE + "/clusters/" + CLUSTER);
        cluster.then().statusCode(200);
        assertEquals("RUNNING", cluster.path("status"));
        assertEquals("1.35.10-gke.1000000", cluster.path("currentMasterVersion"));

        StoredCluster stored = stored();
        String image = docker.getDockerClient().inspectContainerCmd(stored.getContainerId()).exec().getConfig().getImage();
        assertEquals("rancher/k3s:v1.35.10-k3s1", image);
        assertTrue(kubectl("version", "-o", "json").contains("v1.35.10+k3s1"));
    }

    @Test
    @Order(15)
    void signsServiceAccountTokensUnderTheGkeIssuerWithTheKeysGetJsonWebKeysReturns() throws Exception {
        String issuer = "https://container.googleapis.com/v1/projects/" + PROJECT + "/locations/" + ZONE
                + "/clusters/" + CLUSTER;
        JsonNode discovery = JSON.readTree(kubectl("get", "--raw", "/.well-known/openid-configuration"));
        assertEquals(issuer, discovery.path("issuer").asText());
        assertEquals(issuer + "/jwks", discovery.path("jwks_uri").asText());

        await("default ServiceAccount", () -> !kubectlOrEmpty("-n", "default", "get", "serviceaccount", "default",
                "-o", "name").isEmpty());
        String token = kubectl("create", "token", "default", "-n", "default", "--audience", PROJECT + ".svc.id.goog")
                .trim();
        String[] parts = token.split("\\.");
        JsonNode header = JSON.readTree(Base64.getUrlDecoder().decode(parts[0]));
        assertEquals(issuer, JSON.readTree(Base64.getUrlDecoder().decode(parts[1])).path("iss").asText());

        List<Map<String, String>> keys = given().get(BASE + "/clusters/" + CLUSTER + "/jwks").path("keys");
        Map<String, String> key = keys.stream().filter(k -> header.path("kid").asText().equals(k.get("kid")))
                .findFirst().orElseThrow(() -> new AssertionError("no key " + header + " in " + keys));
        PublicKey publicKey = KeyFactory.getInstance("RSA").generatePublic(new RSAPublicKeySpec(
                new BigInteger(1, Base64.getUrlDecoder().decode(key.get("n"))),
                new BigInteger(1, Base64.getUrlDecoder().decode(key.get("e")))));
        Signature signature = Signature.getInstance("SHA256withRSA");
        signature.initVerify(publicKey);
        signature.update((parts[0] + "." + parts[1]).getBytes(StandardCharsets.US_ASCII));
        assertTrue(signature.verify(Base64.getUrlDecoder().decode(parts[2])), "the token verifies against GetJSONWebKeys");
    }

    @Test
    @Order(20)
    void runsNodePoolsAsGkeNamedNodesSizedFromTheirMachineType() throws Exception {
        awaitPool("default-pool", "RUNNING");
        JsonNode nodes = JSON.readTree(kubectl("get", "nodes", "-o", "json")).path("items");
        assertEquals(1, nodes.size(), "the control plane runs no node of its own");
        JsonNode node = nodes.get(0);
        String name = node.path("metadata").path("name").asText();
        assertTrue(name.matches("^gke-real-default-pool-[0-9a-f]{8}-[a-z0-9]{4}$"), name);
        assertEquals("gce://" + PROJECT + "/" + ZONE + "/" + name, node.path("spec").path("providerID").asText());
        JsonNode labels = node.path("metadata").path("labels");
        assertEquals("default-pool", labels.path("cloud.google.com/gke-nodepool").asText());
        assertEquals("e2", labels.path("cloud.google.com/machine-family").asText());
        assertEquals("cos", labels.path("cloud.google.com/gke-os-distribution").asText());
        assertEquals("containerd", labels.path("cloud.google.com/gke-container-runtime").asText());
        assertEquals("e2-medium", labels.path("node.kubernetes.io/instance-type").asText());
        assertEquals(ZONE, labels.path("topology.kubernetes.io/zone").asText());
        assertEquals("us-central1", labels.path("topology.kubernetes.io/region").asText());
        assertEquals("it", labels.path("team").asText());
        JsonNode allocatable = node.path("status").path("allocatable");
        assertEquals("940m", allocatable.path("cpu").asText());
        long memoryMi = kibibytes(allocatable.path("memory").asText()) / 1024;
        assertTrue(memoryMi > 2850 && memoryMi < 3100, "e2-medium allocatable memory, got " + memoryMi + "Mi");

        Response pool = given().get(BASE + "/clusters/" + CLUSTER + "/nodePools/default-pool");
        String hash = name.substring("gke-real-default-pool-".length(), "gke-real-default-pool-".length() + 8);
        assertEquals(List.of("https://www.googleapis.com/compute/v1/projects/" + PROJECT + "/zones/" + ZONE
                + "/instanceGroupManagers/gke-real-default-pool-" + hash + "-grp"), pool.path("instanceGroupUrls"));
    }

    @Test
    @Order(25)
    void podsResolveMetadataGoogleInternalToTheMetadataServerAddress() {
        kubectlApply("""
                apiVersion: v1
                kind: Pod
                metadata: {name: dns-probe, namespace: default}
                spec:
                  containers:
                  - name: probe
                    image: rancher/mirrored-library-busybox:1.37.0
                    command: ["sleep", "3600"]
                """);
        await("dns-probe Running", () -> "Running".equals(kubectlOrEmpty("get", "pod", "dns-probe", "-o",
                "jsonpath={.status.phase}")));
        await("metadata.google.internal resolves", () -> kubectlOrEmpty("exec", "dns-probe", "--", "nslookup",
                "metadata.google.internal").contains("169.254.169.254"));
        kubectl("delete", "pod", "dns-probe", "--wait=false");
    }

    @Test
    @Order(30)
    void appliesThePodAndServiceRangesAndKeepsMetricsServer() throws Exception {
        Response cluster = given().get(BASE + "/clusters/" + CLUSTER);
        assertEquals("10.60.0.0/16", cluster.path("clusterIpv4Cidr"));
        assertEquals("34.118.224.0/20", cluster.path("servicesIpv4Cidr"));

        JsonNode node = nodesOfPool("default-pool").get(0);
        String podCidr = node.path("spec").path("podCIDR").asText();
        assertTrue(podCidr.startsWith("10.60.") && podCidr.endsWith("/25"), podCidr);
        assertEquals("34.118.224.1", kubectl("get", "service", "kubernetes", "-o", "jsonpath={.spec.clusterIP}"));
        assertEquals("34.118.224.10", kubectl("-n", "kube-system", "get", "service", "kube-dns",
                "-o", "jsonpath={.spec.clusterIP}"));
        await("metrics-server APIService Available", () -> "True".equals(kubectlOrEmpty("get", "apiservice",
                "v1beta1.metrics.k8s.io", "-o", "jsonpath={.status.conditions[?(@.type==\"Available\")].status}")));
        await("CoreDNS on a Pod address", () -> kubectlOrEmpty("-n", "kube-system", "get", "pods", "-l",
                "k8s-app=kube-dns", "-o", "jsonpath={.items[0].status.podIP}").startsWith("10.60."));
    }

    @Test
    @Order(40)
    void authenticatesBearerTokensThroughTheClustersTokenWebhook() {
        String token = tokens.mintImpersonatedToken("deployer@" + PROJECT + ".iam.gserviceaccount.com",
                Instant.now().plusSeconds(600)).getTokenValue();
        URI apiServer = URI.create(stored().getInternalEndpoint());
        Response review = given().relaxedHTTPSValidation().baseUri(apiServer.getScheme() + "://" + apiServer.getHost())
                .port(apiServer.getPort())
                .header("Authorization", "Bearer " + token).contentType("application/json")
                .body("{\"apiVersion\": \"authentication.k8s.io/v1\", \"kind\": \"SelfSubjectReview\"}")
                .post("/apis/authentication.k8s.io/v1/selfsubjectreviews");
        review.then().statusCode(anyOf(is(200), is(201)));
        assertEquals("deployer@" + PROJECT + ".iam.gserviceaccount.com", review.path("status.userInfo.username"));
        List<String> groups = review.path("status.userInfo.groups");
        assertTrue(groups.contains("system:masters") && groups.contains("system:authenticated"), groups.toString());
    }

    @Test
    @Order(50)
    void providesGkeStorageClassesAndBindsAVolumeOnTheDefaultOne() throws Exception {
        Map<String, JsonNode> classes = new HashMap<>();
        for (JsonNode sc : JSON.readTree(kubectl("get", "storageclass", "-o", "json")).path("items")) {
            classes.put(sc.path("metadata").path("name").asText(), sc);
        }
        assertEquals(List.of("premium-rwo", "standard", "standard-rwo"), classes.keySet().stream().sorted().toList());
        JsonNode standardRwo = classes.get("standard-rwo");
        assertEquals("true", standardRwo.path("metadata").path("annotations")
                .path("storageclass.kubernetes.io/is-default-class").asText());
        assertEquals("pd.csi.storage.gke.io", standardRwo.path("provisioner").asText());
        assertEquals("pd-balanced", standardRwo.path("parameters").path("type").asText());
        assertEquals("WaitForFirstConsumer", standardRwo.path("volumeBindingMode").asText());
        assertTrue(standardRwo.path("allowVolumeExpansion").asBoolean());
        assertEquals("pd-ssd", classes.get("premium-rwo").path("parameters").path("type").asText());
        assertEquals("pd-standard", classes.get("standard").path("parameters").path("type").asText());

        kubectlApply("""
                apiVersion: v1
                kind: PersistentVolumeClaim
                metadata: {name: data, namespace: default}
                spec: {accessModes: [ReadWriteOnce], resources: {requests: {storage: 1Gi}}}
                ---
                apiVersion: v1
                kind: Pod
                metadata: {name: writer, namespace: default}
                spec:
                  containers:
                  - name: writer
                    image: rancher/mirrored-library-busybox:1.37.0
                    command: ["sh", "-c", "echo ok > /data/probe && sleep 3600"]
                    volumeMounts: [{name: data, mountPath: /data}]
                  volumes: [{name: data, persistentVolumeClaim: {claimName: data}}]
                """);
        await("PVC data Bound", () -> "Bound".equals(kubectl("get", "pvc", "data", "-o", "jsonpath={.status.phase}")));
        JsonNode pvc = JSON.readTree(kubectl("get", "pvc", "data", "-o", "json"));
        assertEquals("standard-rwo", pvc.path("spec").path("storageClassName").asText());
        assertEquals("pd.csi.storage.gke.io", pvc.path("metadata").path("annotations")
                .path("volume.kubernetes.io/storage-provisioner").asText());
        kubectl("delete", "pod", "writer", "--wait=false");
    }

    @Test
    @Order(60)
    void resizesPoolsAndAppliesTheirTaints() throws Exception {
        Response resize = given().urlEncodingEnabled(false).contentType("application/json").body("{\"nodeCount\": 2}")
                .post(BASE + "/clusters/" + CLUSTER + "/nodePools/default-pool:setSize");
        resize.then().statusCode(200);
        awaitOperation(resize.path("name"));
        assertEquals(2, nodesOfPool("default-pool").size());

        Response tainted = given().contentType("application/json").body("""
                {"nodePool": {"name": "tainted", "initialNodeCount": 1, "config": {
                  "machineType": "n2-standard-2",
                  "taints": [{"key": "dedicated", "value": "batch", "effect": "NO_SCHEDULE"}]}}}
                """).post(BASE + "/clusters/" + CLUSTER + "/nodePools");
        tainted.then().statusCode(200);
        awaitOperation(tainted.path("name"));
        List<JsonNode> taintedNodes = nodesOfPool("tainted");
        assertEquals(1, taintedNodes.size());
        JsonNode taint = taintedNodes.get(0).path("spec").path("taints").get(0);
        assertEquals("dedicated", taint.path("key").asText());
        assertEquals("batch", taint.path("value").asText());
        assertEquals("NoSchedule", taint.path("effect").asText());

        given().delete(BASE + "/clusters/" + CLUSTER + "/nodePools/tainted").then().statusCode(200);
        assertEquals(0, nodesOfPool("tainted").size());
        assertEquals(2, nodeContainers().size());
    }

    @Test
    @Order(70)
    void gpuPoolsAdvertiseTheirGpusAndCarryGkesGpuTaint() throws Exception {
        Response gpu = given().contentType("application/json").body("""
                {"nodePool": {"name": "gpu", "initialNodeCount": 1, "config": {
                  "machineType": "n1-standard-4",
                  "accelerators": [{"acceleratorType": "nvidia-tesla-t4", "acceleratorCount": "1"}]}}}
                """).post(BASE + "/clusters/" + CLUSTER + "/nodePools");
        gpu.then().statusCode(200);
        awaitOperation(gpu.path("name"));

        JsonNode node = nodesOfPool("gpu").get(0);
        assertEquals("nvidia-tesla-t4", node.path("metadata").path("labels").path("cloud.google.com/gke-accelerator").asText());
        assertEquals("1", node.path("status").path("capacity").path("nvidia.com/gpu").asText());
        JsonNode taint = node.path("spec").path("taints").get(0);
        assertEquals("nvidia.com/gpu", taint.path("key").asText());
        assertEquals("present", taint.path("value").asText());
        assertEquals("NoSchedule", taint.path("effect").asText());

        kubectlApply("""
                apiVersion: v1
                kind: Pod
                metadata: {name: cuda, namespace: default}
                spec:
                  containers:
                  - name: cuda
                    image: rancher/mirrored-library-busybox:1.37.0
                    command: ["sleep", "3600"]
                    resources: {limits: {nvidia.com/gpu: 1}}
                """);
        await("GPU pod Running", () -> "Running".equals(kubectl("get", "pod", "cuda", "-o", "jsonpath={.status.phase}")));
        JsonNode pod = JSON.readTree(kubectl("get", "pod", "cuda", "-o", "json"));
        assertEquals(node.path("metadata").path("name").asText(), pod.path("spec").path("nodeName").asText());
        boolean tolerated = false;
        for (JsonNode toleration : pod.path("spec").path("tolerations")) {
            tolerated |= "nvidia.com/gpu".equals(toleration.path("key").asText());
        }
        assertTrue(tolerated, "ExtendedResourceToleration adds the GPU toleration");
        kubectl("delete", "pod", "cuda", "--wait=false");

        given().delete(BASE + "/clusters/" + CLUSTER + "/nodePools/gpu").then().statusCode(200);
    }

    @Test
    @Order(75)
    void loadBalancerServicesGetAddressesTheEmulatorServesAndReleases() throws Exception {
        kubectlApply("""
                apiVersion: apps/v1
                kind: Deployment
                metadata: {name: web, namespace: default}
                spec:
                  replicas: 1
                  selector: {matchLabels: {app: web}}
                  template:
                    metadata: {labels: {app: web}}
                    spec:
                      containers:
                      - name: httpd
                        image: rancher/mirrored-library-busybox:1.37.0
                        command: ["sh", "-c", "mkdir -p /www && echo hello > /www/index.html && httpd -f -p 8080 -h /www"]
                ---
                apiVersion: v1
                kind: Service
                metadata: {name: web, namespace: default}
                spec: {type: LoadBalancer, selector: {app: web}, ports: [{port: 80, targetPort: 8080}]}
                ---
                apiVersion: v1
                kind: Service
                metadata: {name: edge, namespace: default}
                spec: {type: LoadBalancer, selector: {app: web}, ports: [{port: 8081, targetPort: 8080}]}
                ---
                apiVersion: v1
                kind: Service
                metadata:
                  name: web-internal
                  namespace: default
                  annotations: {networking.gke.io/load-balancer-type: Internal}
                spec: {type: LoadBalancer, selector: {app: web}, ports: [{port: 80, targetPort: 8080}]}
                ---
                apiVersion: v1
                kind: Pod
                metadata: {name: lb-client, namespace: default}
                spec:
                  containers:
                  - name: client
                    image: rancher/mirrored-library-busybox:1.37.0
                    command: ["sleep", "3600"]
                """);
        await("web has an external address", () -> kubectlOrEmpty("get", "service", "web", "-o",
                "jsonpath={.status.loadBalancer.ingress[0].ip}").startsWith("203.0.113."));
        String address = kubectl("get", "service", "web", "-o", "jsonpath={.status.loadBalancer.ingress[0].ip}");
        String uid = kubectl("get", "service", "web", "-o", "jsonpath={.metadata.uid}");
        String rule = ("a" + uid.replace("-", "")).substring(0, 32);
        String rulePath = "/compute/v1/projects/" + PROJECT + "/regions/us-central1/forwardingRules/" + rule;
        assertEquals(address, given().get(rulePath).then().statusCode(200).extract().path("IPAddress"));
        assertTrue(kubectl("get", "service", "web", "-o", "jsonpath={.metadata.finalizers}")
                .contains("service.kubernetes.io/load-balancer-cleanup"));
        assertTrue(kubectl("get", "events", "--field-selector", "involvedObject.name=web", "-o",
                "jsonpath={.items[*].reason}").contains("EnsuredLoadBalancer"));

        String hostPorts = kubectl("get", "service", "web", "-o",
                "jsonpath={.metadata.annotations.floci\\.io/load-balancer-host-ports}");
        int hostPort = Integer.parseInt(hostPorts.substring(hostPorts.lastIndexOf(':') + 1));
        await("web answers on its host port", () -> {
            try {
                return given().baseUri("http://127.0.0.1").port(hostPort).get("/").asString().contains("hello");
            } catch (RuntimeException e) {
                return false;
            }
        });
        await("lb-client Running", () -> "Running".equals(kubectlOrEmpty("get", "pod", "lb-client", "-o",
                "jsonpath={.status.phase}")));
        await("a pod reaches the external address", () -> kubectlOrEmpty("exec", "lb-client", "--", "wget", "-qO-",
                "-T", "5", "http://" + address + "/").contains("hello"));

        await("web-internal has an internal address", () -> !kubectlOrEmpty("get", "service", "web-internal", "-o",
                "jsonpath={.status.loadBalancer.ingress[0].ip}").isEmpty());
        String internal = kubectl("get", "service", "web-internal", "-o", "jsonpath={.status.loadBalancer.ingress[0].ip}");
        assertTrue(GkeMetadataService.inCidr(internal, stored().getNetworkSubnet()), internal);
        await("a pod reaches the internal address", () -> kubectlOrEmpty("exec", "lb-client", "--", "wget", "-qO-",
                "-T", "5", "http://" + internal + "/").contains("hello"));

        kubectl("scale", "deployment", "web", "--replicas=2");
        Thread.sleep(8000);
        assertEquals(address, kubectl("get", "service", "web", "-o", "jsonpath={.status.loadBalancer.ingress[0].ip}"));

        kubectl("delete", "service", "web", "--wait=true", "--timeout=60s");
        given().get(rulePath).then().statusCode(404);
        Map<String, String> filter = new HashMap<>(ContainerStorageHelper.resourceIdentityLabels("gke", CLUSTER, PROJECT, ZONE));
        filter.put(GkeClusterManager.ROLE_LABEL, GkeClusterManager.ROLE_LOAD_BALANCER);
        assertEquals(2, docker.listContainersByLabels("list appliances", filter).size(),
                "edge and web-internal keep their appliances");
    }

    @Test
    @Order(80)
    void restoresAStoppedControlPlane() {
        StoredCluster before = stored();
        docker.getDockerClient().stopContainerCmd(before.getContainerId()).withTimeout(5).exec();

        service.restoreRuntime();
        assertEquals("RECONCILING", given().get(BASE + "/clusters/" + CLUSTER).path("status"));
        await("cluster RUNNING again", () -> "RUNNING".equals(given().get(BASE + "/clusters/" + CLUSTER).path("status")));
        awaitPool("default-pool", "RUNNING");

        StoredCluster after = stored();
        assertEquals(before.getContainerId(), after.getContainerId());
        assertEquals(before.getCaCertificate(), after.getCaCertificate());
        assertEquals(before.getHostPort(), after.getHostPort());
    }

    @Test
    @Order(90)
    void upgradesTheControlPlaneOntoTheNextMinorsImage() {
        Response upgrade = given().urlEncodingEnabled(false).contentType("application/json")
                .body("{\"masterVersion\": \"1.36\"}")
                .post(BASE + "/clusters/" + CLUSTER + ":updateMaster");
        upgrade.then().statusCode(200);
        assertEquals("RUNNING", upgrade.path("status"));
        assertEquals("RECONCILING", given().get(BASE + "/clusters/" + CLUSTER).path("status"));
        awaitOperation(upgrade.path("name"));

        Response cluster = given().get(BASE + "/clusters/" + CLUSTER);
        assertEquals("RUNNING", cluster.path("status"));
        assertEquals("1.36.6-gke.1000000", cluster.path("currentMasterVersion"));
        assertEquals("1.35.10-gke.1000000", cluster.path("currentNodeVersion"));
        assertTrue(kubectl("version", "-o", "json").contains("v1.36.6+k3s1"));
        await("nodes Ready after the upgrade", () -> {
            try {
                return nodesOfPool("default-pool").stream().allMatch(GkeDockerIntegrationTest::ready);
            } catch (Exception e) {
                return false;
            }
        });
    }

    @Test
    @Order(100)
    void deletingTheClusterRemovesItsContainersVolumesAndNetwork() {
        StoredCluster before = stored();
        List<String> volumes = new ArrayList<>();
        for (Container container : clusterContainers()) {
            for (InspectContainerResponse.Mount mount : docker.getDockerClient().inspectContainerCmd(container.getId())
                    .exec().getMounts()) {
                if (mount.getName() != null) {
                    volumes.add(mount.getName());
                }
            }
        }
        assertFalse(volumes.isEmpty());
        given().delete(BASE + "/clusters/" + CLUSTER).then().statusCode(200);

        assertTrue(clusterContainers().isEmpty(), "no container of the cluster is left");
        assertEquals(List.of(), volumes.stream().filter(docker::volumeExists).toList(),
                "the volumes of the cluster's containers, anonymous ones included, are gone");
        assertTrue(docker.getDockerClient().listNetworksCmd().withNameFilter(before.getNetworkName()).exec().stream()
                .noneMatch(n -> before.getNetworkName().equals(n.getName())), "the cluster network is gone");
        String publicNetwork = ContainerStorageHelper.dockerName(config, "gke-lb-public");
        await("the load balancer network is gone", () -> docker.getDockerClient().listNetworksCmd()
                .withNameFilter(publicNetwork).exec().stream().noneMatch(n -> publicNetwork.equals(n.getName())));
        assertFalse(docker.volumeExists(before.getVolumeName()));
    }

    private StoredCluster stored() {
        return service.getCluster(PROJECT, ZONE, CLUSTER);
    }

    private String kubectl(String... args) {
        ContainerLifecycleManager.ExecResult result = clusterManager.kubectl(stored(), List.of(args));
        assertEquals(0, result.exitCode(), result.stderr());
        return result.stdout();
    }

    private String kubectlOrEmpty(String... args) {
        ContainerLifecycleManager.ExecResult result = clusterManager.kubectl(stored(), List.of(args));
        return result == null || result.exitCode() != 0 || result.stdout() == null ? "" : result.stdout().trim();
    }

    private void kubectlApply(String manifest) {
        ContainerLifecycleManager.ExecResult result = clusterManager.applyManifest(stored(), manifest);
        assertEquals(0, result.exitCode(), result.stderr());
    }

    private List<JsonNode> nodesOfPool(String pool) throws Exception {
        List<JsonNode> nodes = new ArrayList<>();
        for (JsonNode node : JSON.readTree(kubectl("get", "nodes", "-o", "json")).path("items")) {
            if (pool.equals(node.path("metadata").path("labels").path("cloud.google.com/gke-nodepool").asText())) {
                nodes.add(node);
            }
        }
        return nodes;
    }

    private static boolean ready(JsonNode node) {
        for (JsonNode condition : node.path("status").path("conditions")) {
            if ("Ready".equals(condition.path("type").asText())) {
                return "True".equals(condition.path("status").asText());
            }
        }
        return false;
    }

    private List<Container> clusterContainers() {
        return docker.listContainersByLabels("list test cluster containers",
                ContainerStorageHelper.resourceIdentityLabels("gke", CLUSTER, PROJECT, ZONE));
    }

    private List<Container> nodeContainers() {
        Map<String, String> filter = new HashMap<>(ContainerStorageHelper.resourceIdentityLabels("gke", CLUSTER, PROJECT, ZONE));
        filter.put(GkeClusterManager.ROLE_LABEL, GkeClusterManager.ROLE_NODE);
        return docker.listContainersByLabels("list test node containers", filter);
    }

    private static long kibibytes(String quantity) {
        if (quantity.endsWith("Ki")) {
            return Long.parseLong(quantity.substring(0, quantity.length() - 2));
        }
        if (quantity.endsWith("Mi")) {
            return Long.parseLong(quantity.substring(0, quantity.length() - 2)) * 1024;
        }
        return Long.parseLong(quantity) / 1024;
    }

    private static void awaitOperation(String operation) {
        await("operation " + operation + " DONE", () -> {
            Response op = given().get(BASE + "/operations/" + operation);
            if ("DONE".equals(op.path("status"))) {
                Object error = op.path("error");
                assertEquals(null, error, "operation " + operation + " failed: " + op.asString());
                return true;
            }
            return false;
        });
    }

    private static void awaitPool(String pool, String status) {
        await("node pool " + pool + " " + status, () ->
                status.equals(given().get(BASE + "/clusters/" + CLUSTER + "/nodePools/" + pool).path("status")));
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
