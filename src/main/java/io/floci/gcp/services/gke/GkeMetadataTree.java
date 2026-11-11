package io.floci.gcp.services.gke;

import io.floci.gcp.core.common.ProjectNumbers;
import io.floci.gcp.services.compute.ComputeMetadataTree.Dir;
import io.floci.gcp.services.compute.ComputeMetadataTree.Leaf;
import io.floci.gcp.services.gke.model.StoredCluster;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;

/**
 * The subset of metadata the GKE metadata server serves ("About Workload Identity Federation for GKE", metadata
 * server endpoints): {@code instance/} hostname, id, zone, the three cluster attributes and one service account
 * (listed as {@code default} and by its email), and {@code project/} id and number. "The attributes listed in the
 * table are the only supported attributes"; any other key is not found. {@code universe/universe-domain} is served as
 * floci-gcp's Compute Engine metadata server serves it.
 */
final class GkeMetadataTree {

    /** Who the caller is: the email its service account directory shows, and that account's scopes. */
    record Identity(String email, List<String> scopes) {
    }

    private GkeMetadataTree() {
    }

    static Dir build(StoredCluster cluster, String zone, String nodeName, Identity identity) {
        String number = ProjectNumbers.of(cluster.getProject());
        Dir account = new Dir()
                .put("aliases", Leaf.list(List.of("default")))
                .put("email", identity.email())
                .put("identity", Leaf.dynamic())
                .put("scopes", Leaf.list(identity.scopes()))
                .put("token", Leaf.dynamic());
        Dir instance = new Dir()
                .put("attributes", Dir.raw()
                        .put("cluster-location", cluster.getLocation())
                        .put("cluster-name", cluster.getName())
                        .put("cluster-uid", cluster.getId() == null ? "" : cluster.getId()))
                .put("hostname", nodeName + "." + zone + ".c." + cluster.getProject() + ".internal")
                .put("id", Leaf.number(instanceId(cluster.getProject(), zone, nodeName)))
                .put("service-accounts", Dir.raw().put("default", account).put(identity.email(), account))
                .put("zone", "projects/" + number + "/zones/" + zone);
        Dir project = new Dir()
                .put("numeric-project-id", Leaf.number(number))
                .put("project-id", cluster.getProject());
        return new Dir()
                .put("instance", instance)
                .put("project", project)
                .put("universe", new Dir().put("universe-domain", "googleapis.com"));
    }

    /** A stable 19-digit id for the node's instance, derived from its provider ID. */
    static String instanceId(String project, String zone, String nodeName) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(GkeNodes.providerId(project, zone, nodeName).getBytes(StandardCharsets.UTF_8));
            long value = 0;
            for (int i = 0; i < 8; i++) {
                value = (value << 8) | (digest[i] & 0xff);
            }
            return Long.toString(1_000_000_000_000_000_000L + Math.floorMod(value, 8_000_000_000_000_000_000L));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
