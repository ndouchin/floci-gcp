package io.floci.gcp.services.gke;

/**
 * Where pods find the GKE metadata server and how a node reaches floci-gcp's responder. GKE intercepts requests to
 * {@code http://metadata.google.internal} ({@code 169.254.169.254:80}) on each node and serves Workload Identity
 * pods from {@code 169.254.169.252:988}, the address a NetworkPolicy must allow ("About Workload Identity Federation
 * for GKE").
 */
final class GkeMetadataRelay {

    static final String METADATA_HOST = "metadata.google.internal";
    static final String METADATA_IP = "169.254.169.254";
    static final String GKE_METADATA_IP = "169.254.169.252";
    static final int GKE_METADATA_PORT = 988;
    static final String DNS_MANIFEST_ENTRY = "server/manifests/floci-gke-metadata-dns.yaml";

    private GkeMetadataRelay() {
    }

    /**
     * A {@code coredns-custom} server block (k3s's CoreDNS imports its {@code *.server} keys) answering
     * {@code metadata.google.internal} with the metadata server address. k3s applies it from its auto-deploy
     * directory at every start, so edits to that ConfigMap do not survive a control plane restart.
     */
    static String dnsManifest() {
        return """
                apiVersion: v1
                kind: ConfigMap
                metadata:
                  name: coredns-custom
                  namespace: kube-system
                data:
                  gke-metadata.server: |
                    %s:53 {
                        hosts {
                            %s %s
                        }
                    }
                """.formatted(METADATA_HOST, METADATA_IP, METADATA_HOST);
    }
}
