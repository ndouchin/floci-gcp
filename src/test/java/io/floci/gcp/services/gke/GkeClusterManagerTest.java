package io.floci.gcp.services.gke;

import io.floci.gcp.services.gke.model.StoredCluster;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GkeClusterManagerTest {

    private static final String ISSUER = "https://container.googleapis.com/v1/projects/p1/locations/us-central1/clusters/c1";

    private static StoredCluster cluster() {
        StoredCluster cluster = new StoredCluster();
        cluster.setName("c1");
        cluster.setProject("p1");
        cluster.setLocation("us-central1");
        cluster.setServerIp("172.18.0.10");
        cluster.setClusterIpv4Cidr("10.60.0.0/14");
        cluster.setServicesIpv4Cidr("34.118.224.0/20");
        cluster.setNodePodCidrSize(24);
        return cluster;
    }

    @Test
    void theServerIssuesServiceAccountTokensUnderTheGkeIssuerAndStillAcceptsK3ssOwn() {
        StoredCluster cluster = cluster();
        cluster.setGkeServiceAccountIssuer(true);

        List<String> args = GkeClusterManager.serverArgs(cluster, "floci-gke-p1-c1", false);

        int gke = args.indexOf("--kube-apiserver-arg=service-account-issuer=" + ISSUER);
        int k3s = args.indexOf("--kube-apiserver-arg=service-account-issuer=https://kubernetes.default.svc.cluster.local");
        assertTrue(gke >= 0 && k3s > gke, args.toString());
        assertTrue(args.contains("--kube-apiserver-arg=service-account-jwks-uri=" + ISSUER + "/jwks"));
        assertTrue(args.contains("--kube-apiserver-arg=api-audiences=" + ISSUER
                + ",https://kubernetes.default.svc.cluster.local,k3s"));
        assertEquals("server", args.get(0));
        assertTrue(args.contains("--disable-agent"));
        assertTrue(args.contains("--advertise-address=172.18.0.10"));
        assertTrue(args.contains("--cluster-cidr=10.60.0.0/14"));
        assertTrue(args.contains("--tls-san=floci-gke-p1-c1"));
    }

    @Test
    void aServerStartedByAnEarlierBuildKeepsK3ssIssuer() {
        List<String> args = GkeClusterManager.serverArgs(cluster(), "floci-gke-p1-c1", false);
        assertFalse(args.stream().anyMatch(a -> a.contains("service-account-issuer")), args.toString());
    }

    @Test
    void theTokenWebhookArgumentsComeWithTheWebhook() {
        assertTrue(GkeClusterManager.serverArgs(cluster(), "n", true)
                .contains("--kube-apiserver-arg=authentication-token-webhook-version=v1"));
        assertFalse(GkeClusterManager.serverArgs(cluster(), "n", false).stream()
                .anyMatch(a -> a.contains("authentication-token-webhook")));
    }
}
