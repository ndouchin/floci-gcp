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

    /**
     * The relay's nginx configuration: the GKE metadata server listener ({@code 169.254.169.252:988}, the workload
     * path) and the Compute Engine one ({@code 169.254.169.254:80}, for nodes, {@code hostNetwork} pods and pods of
     * {@code GCE_METADATA} pools), both forwarded to the node's responder at {@code upstream} with the caller's address
     * and the cluster's relay key. Responses are not buffered, so {@code wait_for_change} streams, and an unreachable
     * floci-gcp answers 500, as GKE's metadata server does without its control plane.
     */
    static String nginxConfig(String upstream, String relayKey) {
        return """
                worker_processes 1;
                error_log /dev/stderr warn;
                events {
                    worker_connections 512;
                }
                http {
                    access_log off;
                    proxy_http_version 1.1;
                    proxy_buffering off;
                    proxy_request_buffering off;
                    proxy_connect_timeout 5s;
                    proxy_read_timeout 3600s;
                    proxy_send_timeout 3600s;
                    proxy_set_header Host $host;
                    proxy_set_header Connection "";
                    proxy_set_header X-Floci-Gke-Peer $remote_addr;
                    proxy_set_header X-Floci-Gke-Relay-Key %2$s;
                    server {
                        listen %3$s:%4$d;
                        location / {
                            proxy_pass %1$sgke/;
                        }
                        error_page 502 504 =500 @unavailable;
                        location @unavailable {
                            default_type text/plain;
                            return 500 "The metadata server cannot reach floci-gcp.";
                        }
                    }
                    server {
                        listen %5$s:80;
                        location / {
                            proxy_pass %1$sgce/;
                        }
                        error_page 502 504 =500 @unavailable;
                        location @unavailable {
                            default_type text/plain;
                            return 500 "The metadata server cannot reach floci-gcp.";
                        }
                    }
                }
                """.formatted(upstream, relayKey, GKE_METADATA_IP, GKE_METADATA_PORT, METADATA_IP);
    }

    /**
     * The shell run in a node container to put the metadata addresses on its loopback and route pod requests for
     * {@code 169.254.169.254:80}: to the GKE metadata server listener on nodes that run it, to the Compute Engine
     * listener otherwise. The chain is put back first in {@code PREROUTING}, ahead of any {@code hostPort: 80} rule
     * inserted since, and node processes reach the local listener directly. Running it again replaces the rules.
     */
    static String nodeScript(String podCidr, boolean gkeMetadata) {
        String source = podCidr == null || podCidr.isBlank() ? "" : "-s " + podCidr + " ";
        String target = gkeMetadata ? GKE_METADATA_IP + ":" + GKE_METADATA_PORT : METADATA_IP + ":80";
        return """
                IPT=/bin/aux/iptables
                [ -x "$IPT" ] || IPT=iptables
                ip addr add %1$s/32 dev lo 2>/dev/null || true
                ip addr add %2$s/32 dev lo 2>/dev/null || true
                set -e
                $IPT -w -t nat -N FLOCI-GKE-METADATA 2>/dev/null || true
                $IPT -w -t nat -F FLOCI-GKE-METADATA
                $IPT -w -t nat -A FLOCI-GKE-METADATA %3$s-d %1$s/32 -p tcp --dport 80 -j DNAT --to-destination %4$s
                if [ "$($IPT -w -t nat -S PREROUTING | sed -n 2p)" != "-A PREROUTING -j FLOCI-GKE-METADATA" ]; then
                  $IPT -w -t nat -D PREROUTING -j FLOCI-GKE-METADATA 2>/dev/null || true
                  $IPT -w -t nat -I PREROUTING 1 -j FLOCI-GKE-METADATA
                fi
                $IPT -w -t nat -N FLOCI-GKE-METADATA-LOCAL 2>/dev/null || true
                $IPT -w -t nat -F FLOCI-GKE-METADATA-LOCAL
                $IPT -w -t nat -A FLOCI-GKE-METADATA-LOCAL -d %1$s/32 -p tcp --dport 80 -j ACCEPT
                if [ "$($IPT -w -t nat -S OUTPUT | sed -n 2p)" != "-A OUTPUT -j FLOCI-GKE-METADATA-LOCAL" ]; then
                  $IPT -w -t nat -D OUTPUT -j FLOCI-GKE-METADATA-LOCAL 2>/dev/null || true
                  $IPT -w -t nat -I OUTPUT 1 -j FLOCI-GKE-METADATA-LOCAL
                fi
                """.formatted(METADATA_IP, GKE_METADATA_IP, source, target);
    }
}
