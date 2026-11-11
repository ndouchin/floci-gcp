package io.floci.gcp.services.gke;

import io.floci.gcp.services.gke.model.StoredCluster;

/** The names Workload Identity Federation for GKE gives a cluster: its issuer and its workload pool. */
final class GkeWorkloadIdentity {

    /** The issuer k3s signs ServiceAccount tokens under by default. */
    static final String K3S_ISSUER = "https://kubernetes.default.svc.cluster.local";
    static final String ISSUER_PREFIX = "https://container.googleapis.com/v1/";

    private GkeWorkloadIdentity() {
    }

    /** {@code https://container.googleapis.com/v1/projects/P/locations/L/clusters/C}, the cluster's OIDC issuer. */
    static String issuer(StoredCluster cluster) {
        return issuer(cluster.getProject(), cluster.getLocation(), cluster.getName());
    }

    static String issuer(String project, String location, String cluster) {
        return ISSUER_PREFIX + "projects/" + project + "/locations/" + location + "/clusters/" + cluster;
    }
}
