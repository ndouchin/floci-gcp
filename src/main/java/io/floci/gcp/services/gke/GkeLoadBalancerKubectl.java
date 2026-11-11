package io.floci.gcp.services.gke;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.floci.gcp.core.common.docker.ContainerLifecycleManager;
import io.floci.gcp.services.gke.model.StoredCluster;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.io.IOException;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

/** What the load balancer controller reads from and writes to a cluster's API server, through kubectl in its control plane. */
@ApplicationScoped
public class GkeLoadBalancerKubectl {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final GkeClusterManager clusterManager;
    private final SecureRandom random = new SecureRandom();

    @Inject
    public GkeLoadBalancerKubectl(GkeClusterManager clusterManager) {
        this.clusterManager = clusterManager;
    }

    /** Every Service and Node of the cluster, each with its {@code kind}. */
    public List<JsonNode> list(StoredCluster cluster) {
        ContainerLifecycleManager.ExecResult result = run(cluster, List.of("get", "services,nodes", "--all-namespaces",
                "-o", "json"));
        try {
            List<JsonNode> items = new ArrayList<>();
            JSON.readTree(result.stdout()).path("items").forEach(items::add);
            return items;
        } catch (IOException e) {
            throw new IllegalStateException("unreadable kubectl output: " + e.getMessage(), e);
        }
    }

    public void setFinalizers(StoredCluster cluster, String namespace, String name, List<String> finalizers) {
        ObjectNode patch = JSON.createObjectNode();
        ArrayNode list = patch.putObject("metadata").putArray("finalizers");
        finalizers.forEach(list::add);
        run(cluster, List.of("patch", "service", name, "-n", namespace, "--type=merge", "-p", patch.toString()));
    }

    public void patchStatus(StoredCluster cluster, String namespace, String name, String patch) {
        run(cluster, List.of("patch", "service", name, "-n", namespace, "--subresource=status", "--type=merge", "-p", patch));
    }

    public void annotate(StoredCluster cluster, String namespace, String name, String key, String value) {
        run(cluster, List.of("annotate", "service", name, "-n", namespace, "--overwrite", key + "=" + value));
    }

    /** A core/v1 Event on the Service from {@code service-controller}, the cloud provider's service controller. */
    public void event(StoredCluster cluster, JsonNode service, String type, String reason, String message) {
        JsonNode metadata = service.path("metadata");
        String now = Instant.now().toString();
        ObjectNode event = JSON.createObjectNode().put("apiVersion", "v1").put("kind", "Event");
        byte[] suffix = new byte[8];
        random.nextBytes(suffix);
        event.putObject("metadata").put("name", metadata.path("name").asText() + "." + HexFormat.of().formatHex(suffix))
                .put("namespace", metadata.path("namespace").asText());
        event.putObject("involvedObject").put("apiVersion", "v1").put("kind", "Service")
                .put("name", metadata.path("name").asText()).put("namespace", metadata.path("namespace").asText())
                .put("uid", metadata.path("uid").asText());
        event.put("reason", reason).put("message", message).put("type", type)
                .put("firstTimestamp", now).put("lastTimestamp", now).put("count", 1);
        event.putObject("source").put("component", "service-controller");
        ContainerLifecycleManager.ExecResult result = clusterManager.applyManifest(cluster, event.toString());
        if (result == null || result.exitCode() != 0) {
            throw new IllegalStateException("could not record event " + reason + ": "
                    + (result == null ? "no result" : result.stderr()));
        }
    }

    private ContainerLifecycleManager.ExecResult run(StoredCluster cluster, List<String> args) {
        ContainerLifecycleManager.ExecResult result = clusterManager.kubectl(cluster, args);
        if (result == null || result.exitCode() != 0) {
            throw new IllegalStateException("kubectl " + args.get(0) + " failed: " + (result == null ? "no result" : result.stderr()));
        }
        return result;
    }
}
