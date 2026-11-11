package io.floci.gcp.services.gke;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GkeNodesTest {

    private static final long GIB = 1024L * 1024 * 1024;

    @Test
    void aRegionalLocationSpreadsNodesOverThreeZones() {
        assertEquals(List.of("us-central1-a", "us-central1-b", "us-central1-c"),
                GkeNodes.zones(null, "us-central1"));
        assertEquals(List.of("europe-west4-b"), GkeNodes.zones(List.of("europe-west4-b"), "europe-west4"));
        assertEquals("us-central1", GkeNodes.region("us-central1-f"));
    }

    @Test
    void nodeCountsArePerZoneAndTheCapGoesToPoolsInOrder() {
        List<GkeNodes.PoolAllocation> allocations = GkeNodes.allocate(List.of(
                new GkeNodes.PoolDemand("default-pool", 1, List.of("z-a", "z-b", "z-c")),
                new GkeNodes.PoolDemand("workers", 2, List.of("z-a", "z-b")),
                new GkeNodes.PoolDemand("empty", 0, List.of("z-a"))), 5);

        assertEquals(List.of("z-a", "z-b", "z-c"), allocations.get(0).nodeZones());
        assertFalse(allocations.get(0).shortOfNodes());
        assertEquals(4, allocations.get(1).requested());
        assertEquals(List.of("z-a", "z-b"), allocations.get(1).nodeZones());
        assertTrue(allocations.get(1).shortOfNodes());
        assertEquals(List.of(), allocations.get(2).nodeZones());
        assertFalse(allocations.get(2).shortOfNodes());
    }

    @Test
    void namesFollowGkeInstanceGroupNaming() {
        String hash = GkeNodes.instanceGroupHash("p1", "c1", "default-pool", "us-central1-a");
        assertTrue(hash.matches("^[0-9a-f]{8}$"), hash);
        assertEquals(hash, GkeNodes.instanceGroupHash("p1", "c1", "default-pool", "us-central1-a"));
        assertFalse(hash.equals(GkeNodes.instanceGroupHash("p1", "c1", "default-pool", "us-central1-b")));

        assertEquals("gke-c1-default-pool-" + hash + "-x9k2", GkeNodes.nodeName("c1", "default-pool", hash, "x9k2"));
        assertEquals("https://www.googleapis.com/compute/v1/projects/p1/zones/us-central1-a/instanceGroupManagers/"
                        + "gke-c1-default-pool-" + hash + "-grp",
                GkeNodes.instanceGroupUrl("p1", "us-central1-a", "c1", "default-pool"));
        assertEquals("gce://p1/us-central1-a/gke-c1-default-pool-" + hash + "-x9k2",
                GkeNodes.providerId("p1", "us-central1-a", "gke-c1-default-pool-" + hash + "-x9k2"));

        String longName = GkeNodes.nodeName("a-forty-character-cluster-name-for-tests",
                "a-forty-character-node-pool-name-for-test", hash, "x9k2");
        assertTrue(longName.length() <= 63, longName);
        assertTrue(longName.startsWith("gke-a-forty-character-clus-a-forty-character-node-"), longName);
        assertTrue(longName.endsWith("-" + hash + "-x9k2"), longName);
    }

    @Test
    void aNodeCarriesGkeLabelsThePoolsLabelsAndTaints() {
        GkeNodes.NodeSpec spec = GkeNodes.spec("p1", "workers", "us-central1-b", "gke-c1-workers-1a2b3c4d-x9k2",
                Map.of("machineType", "n2-standard-4",
                        "labels", Map.of("team", "data"),
                        "taints", List.of(Map.of("key", "dedicated", "value", "data", "effect", "NO_EXECUTE"),
                                Map.of("key", "soft", "effect", "PREFER_NO_SCHEDULE"))),
                110, false, 6, 10 * GIB);

        Map<String, String> labels = spec.labels();
        assertEquals("workers", labels.get("cloud.google.com/gke-nodepool"));
        assertEquals("n2", labels.get("cloud.google.com/machine-family"));
        assertEquals("cos", labels.get("cloud.google.com/gke-os-distribution"));
        assertEquals("containerd", labels.get("cloud.google.com/gke-container-runtime"));
        assertEquals("pd-balanced", labels.get("cloud.google.com/gke-boot-disk"));
        assertEquals("110", labels.get("cloud.google.com/gke-max-pods-per-node"));
        assertEquals("standard", labels.get("cloud.google.com/gke-provisioning"));
        assertEquals("n2-standard-4", labels.get("node.kubernetes.io/instance-type"));
        assertEquals("us-central1-b", labels.get("topology.kubernetes.io/zone"));
        assertEquals("us-central1", labels.get("topology.kubernetes.io/region"));
        assertEquals("data", labels.get("team"));
        assertEquals(List.of("dedicated=data:NoExecute", "soft:PreferNoSchedule"), spec.taints());
        assertTrue(spec.kubeletArgs().contains("provider-id=gce://p1/us-central1-b/gke-c1-workers-1a2b3c4d-x9k2"));
        assertEquals(4, spec.cpus());
        assertEquals(16 * GIB, spec.memoryBytes());

        GkeNodes.NodeSpec ubuntuSpot = GkeNodes.spec("p1", "spot", "us-central1-b", "n",
                Map.of("imageType", "UBUNTU_CONTAINERD", "spot", true), 32, false, 6, 10 * GIB);
        assertEquals("ubuntu", ubuntuSpot.labels().get("cloud.google.com/gke-os-distribution"));
        assertEquals("spot", ubuntuSpot.labels().get("cloud.google.com/gke-provisioning"));
        assertEquals("true", ubuntuSpot.labels().get("cloud.google.com/gke-spot"));
        assertEquals("e2-medium", ubuntuSpot.labels().get("node.kubernetes.io/instance-type"));
        assertTrue(ubuntuSpot.kubeletArgs().contains("max-pods=32"));
    }

    @Test
    void gpuNodesCarryTheAcceleratorLabelTheirGpuCountAndGkesTaint() {
        GkeNodes.NodeSpec attached = GkeNodes.spec("p1", "gpu", "us-central1-a", "n",
                Map.of("machineType", "n1-standard-4",
                        "accelerators", List.of(Map.of("acceleratorType", "nvidia-tesla-t4", "acceleratorCount", "2"))),
                110, true, 6, 10 * GIB);
        assertEquals("nvidia-tesla-t4", attached.labels().get("cloud.google.com/gke-accelerator"));
        assertEquals("nvidia-tesla-t4", attached.acceleratorType());
        assertEquals(2, attached.acceleratorCount());
        assertEquals(List.of("nvidia.com/gpu=present:NoSchedule"), attached.taints());

        GkeNodes.NodeSpec a3 = GkeNodes.spec("p1", "h100", "us-central1-a", "n",
                Map.of("machineType", "a3-highgpu-8g"), 110, false, 6, 10 * GIB);
        assertEquals("nvidia-h100-80gb", a3.labels().get("cloud.google.com/gke-accelerator"));
        assertEquals(8, a3.acceleratorCount());
        assertEquals(List.of(), a3.taints());
        assertEquals("a3", a3.labels().get("cloud.google.com/machine-family"));

        GkeNodes.NodeSpec g2 = GkeNodes.spec("p1", "l4", "us-central1-a", "n",
                Map.of("machineType", "g2-standard-24"), 110, false, 6, 10 * GIB);
        assertEquals("nvidia-l4", g2.acceleratorType());
        assertEquals(2, g2.acceleratorCount());

        GkeNodes.NodeSpec g4 = GkeNodes.spec("p1", "rtx", "us-central1-a", "n",
                Map.of("machineType", "g4-standard-96"), 110, false, 6, 10 * GIB);
        assertEquals("nvidia-rtx-pro-6000", g4.acceleratorType());
        assertEquals(2, g4.acceleratorCount());
        assertEquals(360L * 1024 * 1024 * 1024, g4.memoryBytes());

        GkeNodes.NodeSpec cpu = GkeNodes.spec("p1", "cpu", "us-central1-a", "n",
                Map.of("machineType", "e2-standard-4"), 110, true, 6, 10 * GIB);
        assertEquals(null, cpu.labels().get("cloud.google.com/gke-accelerator"));
        assertEquals(0, cpu.acceleratorCount());
        assertEquals(List.of(), cpu.taints());
        assertTrue(GkeNodes.hasGpus(Map.of("machineType", "a2-highgpu-1g")));
        assertFalse(GkeNodes.hasGpus(Map.of("machineType", "n2-standard-2")));
    }

    @Test
    void kubeletReservationsLeaveTheMachineTypesGkeAllocatable() {
        GkeMachineTypes.Shape e2Medium = GkeMachineTypes.shape("e2-medium").orElseThrow();
        // 6 host CPUs, 10 GiB host: 4 CPUs and 6 GiB above the machine, plus GKE's 1060m and 1024 MiB.
        assertEquals("kube-reserved=cpu=5060m,memory=7168Mi", GkeNodes.kubeReserved(e2Medium, 6, 10 * GIB));

        GkeMachineTypes.Shape n2 = GkeMachineTypes.shape("n2-standard-8").orElseThrow();
        assertEquals(8, n2.cpus());
        assertEquals(32768, n2.memoryMb());
        assertEquals(90, GkeNodes.reservedCpuMillis(8));
        // 25% of 4 GiB + 20% of 4 GiB + 10% of 8 GiB + 6% of 16 GiB
        assertEquals(1024 + 819 + 819 + 983, GkeNodes.reservedMemoryMb(32768), 1);
        // A machine bigger than the host only gets GKE's own reservation.
        assertEquals("kube-reserved=cpu=90m,memory=3645Mi", GkeNodes.kubeReserved(n2, 6, 10 * GIB));
        assertEquals(255, GkeNodes.reservedMemoryMb(512));
    }

    @Test
    void onlyNodesRunningTheGkeMetadataServerCarryItsLabel() {
        GkeNodes.NodeSpec gke = GkeNodes.spec("p1", "wi", "us-central1-a", "n", Map.of(), 110, false, 0, 0, true);
        GkeNodes.NodeSpec gce = GkeNodes.spec("p1", "wi", "us-central1-a", "n", Map.of(), 110, false, 0, 0, false);

        assertEquals("true", gke.labels().get("iam.gke.io/gke-metadata-server-enabled"));
        assertFalse(gce.labels().containsKey("iam.gke.io/gke-metadata-server-enabled"));
    }

    @Test
    void theWorkloadMetadataModeReadsTheV1beta1NodeMetadataToo() {
        assertEquals("GKE_METADATA", GkeNodes.metadataMode(Map.of("workloadMetadataConfig", Map.of("mode", "GKE_METADATA"))));
        assertEquals("GKE_METADATA", GkeNodes.metadataMode(Map.of("workloadMetadataConfig",
                Map.of("nodeMetadata", "GKE_METADATA_SERVER"))));
        assertEquals("GCE_METADATA", GkeNodes.metadataMode(Map.of("workloadMetadataConfig", Map.of("nodeMetadata", "EXPOSE"))));
        assertEquals(null, GkeNodes.metadataMode(Map.of()));
        assertEquals(null, GkeNodes.metadataMode(null));
    }
}
