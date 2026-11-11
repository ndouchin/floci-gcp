package io.floci.gcp.services.gke;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.floci.gcp.core.common.docker.ContainerLifecycleManager;
import io.floci.gcp.services.gke.model.StoredCluster;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * What the GKE metadata server asks a cluster's API server: which pod has an address on a node, and a ServiceAccount
 * token for it. Pods are looked up by address because traffic DNATed on the node keeps the pod's source address, the
 * only thing that tells pods apart there. Answers are cached for a few seconds.
 */
@ApplicationScoped
public class GkeWorkloads {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Duration CACHE = Duration.ofSeconds(5);
    /** The shortest lifetime the API server grants a TokenRequest. */
    private static final String TOKEN_DURATION = "10m";

    /** A pod and the ServiceAccount it runs as, with that account's annotations. */
    public record Pod(String namespace, String name, String uid, String serviceAccount, String serviceAccountUid,
                      Map<String, String> annotations) {
    }

    /** The cluster's API server did not answer. */
    public static class ControlPlaneUnavailable extends RuntimeException {
        public ControlPlaneUnavailable(String message) {
            super(message);
        }
    }

    private record Cached(Optional<Pod> pod, Instant until) {
    }

    private final GkeClusterManager clusterManager;
    private final Map<String, Cached> pods = new ConcurrentHashMap<>();

    @Inject
    public GkeWorkloads(GkeClusterManager clusterManager) {
        this.clusterManager = clusterManager;
    }

    /** The pod on {@code node} whose address is {@code ip}, or empty when there is none. */
    public Optional<Pod> podAt(StoredCluster cluster, String node, String ip) {
        String key = cluster.getContainerId() + "|" + node + "|" + ip;
        Cached cached = pods.get(key);
        if (cached != null && cached.until().isAfter(Instant.now())) {
            return cached.pod();
        }
        Optional<Pod> pod = lookUp(cluster, node, ip);
        pods.put(key, new Cached(pod, Instant.now().plus(CACHE)));
        return pod;
    }

    private Optional<Pod> lookUp(StoredCluster cluster, String node, String ip) {
        JsonNode items = kubectlJson(cluster, List.of("get", "pods", "--all-namespaces", "--field-selector=status.podIP="
                + ip + ",spec.nodeName=" + node, "-o", "json")).path("items");
        for (JsonNode item : items) {
            if (item.path("spec").path("hostNetwork").asBoolean(false)) {
                continue;
            }
            String namespace = item.path("metadata").path("namespace").asText();
            String account = item.path("spec").path("serviceAccountName").asText("default");
            JsonNode serviceAccount = kubectlJson(cluster, List.of("get", "serviceaccount", account, "-n", namespace,
                    "-o", "json"));
            Map<String, String> annotations = new HashMap<>();
            serviceAccount.path("metadata").path("annotations").fields()
                    .forEachRemaining(e -> annotations.put(e.getKey(), e.getValue().asText()));
            return Optional.of(new Pod(namespace, item.path("metadata").path("name").asText(),
                    item.path("metadata").path("uid").asText(), account,
                    serviceAccount.path("metadata").path("uid").asText(null), annotations));
        }
        return Optional.empty();
    }

    /** A TokenRequest token for the pod's ServiceAccount, bound to the pod, with the given audience. */
    public String serviceAccountToken(StoredCluster cluster, Pod pod, String audience) {
        ContainerLifecycleManager.ExecResult result = exec(cluster, List.of("create", "token", pod.serviceAccount(),
                "-n", pod.namespace(), "--audience", audience, "--duration", TOKEN_DURATION, "--bound-object-kind", "Pod",
                "--bound-object-name", pod.name(), "--bound-object-uid", pod.uid()));
        return result.stdout().trim();
    }

    private JsonNode kubectlJson(StoredCluster cluster, List<String> args) {
        try {
            return JSON.readTree(exec(cluster, args).stdout());
        } catch (IOException e) {
            throw new ControlPlaneUnavailable("unreadable kubectl output: " + e.getMessage());
        }
    }

    private ContainerLifecycleManager.ExecResult exec(StoredCluster cluster, List<String> args) {
        ContainerLifecycleManager.ExecResult result;
        try {
            result = clusterManager.kubectl(cluster, args);
        } catch (RuntimeException e) {
            throw new ControlPlaneUnavailable(e.getMessage());
        }
        if (result == null || result.exitCode() != 0) {
            throw new ControlPlaneUnavailable(result == null ? "no answer" : result.stderr());
        }
        return result;
    }
}
