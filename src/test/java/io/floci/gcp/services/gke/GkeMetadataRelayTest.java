package io.floci.gcp.services.gke;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GkeMetadataRelayTest {

    private static final String UPSTREAM = "http://172.18.0.1:4588/_floci-gcp/gke/metadata/projects/p1/locations/"
            + "us-central1-a/clusters/c1/nodes/gke-c1-default-pool-1a2b3c4d-x9k2/";

    @Test
    void theRelayListensOnBothMetadataAddressesAndForwardsToTheNodesResponder() {
        String config = GkeMetadataRelay.nginxConfig(UPSTREAM, "relay-secret");

        assertTrue(config.contains("listen 169.254.169.252:988;"), config);
        assertTrue(config.contains("listen 169.254.169.254:80;"), config);
        assertTrue(config.contains("proxy_pass " + UPSTREAM + "gke/;"), config);
        assertTrue(config.contains("proxy_pass " + UPSTREAM + "gce/;"), config);
        assertTrue(config.contains("proxy_set_header X-Floci-Gke-Peer $remote_addr;"), config);
        assertTrue(config.contains("proxy_set_header X-Floci-Gke-Relay-Key relay-secret;"), config);
        assertTrue(config.contains("proxy_buffering off;"), config);
        assertTrue(config.contains("error_page 502 504 =500"), config);
    }

    @Test
    void podTrafficGoesToTheGkeMetadataServerOnWorkloadIdentityNodes() {
        String script = GkeMetadataRelay.nodeScript("10.60.0.0/14", true);

        assertTrue(script.contains("ip addr add 169.254.169.254/32 dev lo"), script);
        assertTrue(script.contains("ip addr add 169.254.169.252/32 dev lo"), script);
        assertTrue(script.contains("-A FLOCI-GKE-METADATA -s 10.60.0.0/14 -d 169.254.169.254/32 -p tcp --dport 80 "
                + "-j DNAT --to-destination 169.254.169.252:988"), script);
        assertTrue(script.contains("-S PREROUTING | sed -n 2p)\" != \"-A PREROUTING -j FLOCI-GKE-METADATA\""), script);
        assertTrue(script.contains("-I PREROUTING 1 -j FLOCI-GKE-METADATA"), script);
        assertTrue(script.contains("-I OUTPUT 1 -j FLOCI-GKE-METADATA-LOCAL"), script);
    }

    @Test
    void podTrafficStaysOnTheNodesComputeMetadataListenerOtherwise() {
        String script = GkeMetadataRelay.nodeScript("10.60.0.0/14", false);

        assertTrue(script.contains("-j DNAT --to-destination 169.254.169.254:80"), script);
        assertFalse(script.contains("169.254.169.252:988"), script);
    }

    @Test
    void reprogrammingANodeReplacesItsRulesRatherThanAddingMore() {
        String script = GkeMetadataRelay.nodeScript(null, true);

        assertTrue(script.contains("-F FLOCI-GKE-METADATA\n"), script);
        assertTrue(script.contains("-F FLOCI-GKE-METADATA-LOCAL\n"), script);
        assertTrue(script.contains("-N FLOCI-GKE-METADATA 2>/dev/null || true"), script);
        assertTrue(script.contains("-A FLOCI-GKE-METADATA -d 169.254.169.254/32"), script);
    }
}
