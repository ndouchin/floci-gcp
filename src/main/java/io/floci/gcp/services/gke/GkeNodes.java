package io.floci.gcp.services.gke;

import io.floci.gcp.core.common.GcpException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * How a GKE node pool maps onto nodes: the zones it spans, the names, provider IDs, labels and taints
 * its nodes carry, the kubelet reservations that give them their machine type's allocatable
 * resources, and how a per-cluster cap on node containers is shared between pools.
 */
final class GkeNodes {

    static final String DEFAULT_POOL = "default-pool";
    static final int DEFAULT_MAX_PODS = 110;
    static final String GPU_TAINT = "nvidia.com/gpu=present:NoSchedule";
    /** The label GKE puts on nodes that run the GKE metadata server, which its DaemonSet selects on. */
    static final String GKE_METADATA_SERVER_LABEL = "iam.gke.io/gke-metadata-server-enabled";
    static final String GKE_METADATA = "GKE_METADATA";
    static final String GCE_METADATA = "GCE_METADATA";
    private static final Pattern ZONE = Pattern.compile("^[a-z]+-[a-z]+\\d+-[a-z]$");
    private static final int MAX_NAME_LENGTH = 63;
    private static final int EVICTION_MB = 100;

    record PoolDemand(String pool, int nodeCount, List<String> zones) {
    }

    /** The zones a pool's nodes go to, in order, and how many of its nodes the cap leaves it. */
    record PoolAllocation(String pool, int requested, List<String> nodeZones) {

        boolean shortOfNodes() {
            return nodeZones.size() < requested;
        }
    }

    record NodeSpec(Map<String, String> labels, List<String> taints, List<String> kubeletArgs, int cpus,
                    long memoryBytes, String acceleratorType, int acceleratorCount) {
    }

    private GkeNodes() {
    }

    static boolean isZone(String location) {
        return location != null && ZONE.matcher(location).matches();
    }

    static String region(String zone) {
        return isZone(zone) ? zone.substring(0, zone.lastIndexOf('-')) : zone;
    }

    /**
     * The zones a pool's nodes are spread over: its own locations, or the cluster's location, where a
     * region stands for its {@code -a}, {@code -b} and {@code -c} zones as a regional cluster's
     * default node locations do.
     */
    static List<String> zones(List<String> locations, String clusterLocation) {
        List<String> source = locations == null || locations.isEmpty() ? List.of(clusterLocation) : locations;
        List<String> zones = new ArrayList<>();
        for (String location : source) {
            if (isZone(location)) {
                if (!zones.contains(location)) {
                    zones.add(location);
                }
            } else {
                for (String suffix : List.of("a", "b", "c")) {
                    String zone = location + "-" + suffix;
                    if (!zones.contains(zone)) {
                        zones.add(zone);
                    }
                }
            }
        }
        return zones;
    }

    /**
     * Shares {@code cap} node containers between pools in the order given (the default pool first,
     * then by creation). A pool asks for {@code nodeCount} nodes in each of its zones, as GKE node
     * counts are per zone; its nodes go round the zones so a capped pool still covers as many zones
     * as it can.
     */
    static List<PoolAllocation> allocate(List<PoolDemand> demands, int cap) {
        int left = Math.max(0, cap);
        List<PoolAllocation> allocations = new ArrayList<>();
        for (PoolDemand demand : demands) {
            int requested = Math.max(0, demand.nodeCount()) * demand.zones().size();
            List<String> nodeZones = new ArrayList<>();
            for (int i = 0; i < Math.max(0, demand.nodeCount()) && nodeZones.size() < left; i++) {
                for (String zone : demand.zones()) {
                    if (nodeZones.size() < left) {
                        nodeZones.add(zone);
                    }
                }
            }
            left -= nodeZones.size();
            allocations.add(new PoolAllocation(demand.pool(), requested, nodeZones));
        }
        return allocations;
    }

    /** The 8-hex-digit hash GKE puts in a pool's instance group and node names, one per zone. */
    static String instanceGroupHash(String project, String cluster, String pool, String zone) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest((project + "/" + zone + "/" + cluster + "/" + pool).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest, 0, 4);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * {@code gke-<cluster>-<pool>-<hash>}, the prefix shared by a pool's instance group
     * ({@code -grp}) and its nodes ({@code -<4 characters>}). Cluster and pool names are shortened,
     * keeping their first characters, so a node name fits the 63 characters of an instance name.
     */
    static String instanceGroupBase(String cluster, String pool, String hash) {
        int budget = MAX_NAME_LENGTH - "gke---".length() - hash.length() - "-xxxx".length();
        String c = cluster;
        String p = pool;
        if (c.length() + p.length() > budget) {
            int half = budget / 2;
            if (c.length() <= half) {
                p = p.substring(0, budget - c.length());
            } else if (p.length() <= budget - half) {
                c = c.substring(0, budget - p.length());
            } else {
                c = c.substring(0, half);
                p = p.substring(0, budget - half);
            }
        }
        return "gke-" + trimDash(c) + "-" + trimDash(p) + "-" + hash;
    }

    static String nodeName(String cluster, String pool, String hash, String suffix) {
        return instanceGroupBase(cluster, pool, hash) + "-" + suffix;
    }

    static String instanceGroupUrl(String project, String zone, String cluster, String pool) {
        String hash = instanceGroupHash(project, cluster, pool, zone);
        return "https://www.googleapis.com/compute/v1/projects/" + project + "/zones/" + zone
                + "/instanceGroupManagers/" + instanceGroupBase(cluster, pool, hash) + "-grp";
    }

    static String providerId(String project, String zone, String nodeName) {
        return "gce://" + project + "/" + zone + "/" + nodeName;
    }

    /**
     * The node a pool's config produces in a zone: GKE's node labels (those GKE writes into a node's
     * {@code kube-labels} plus the cloud provider's instance-type and topology labels), the pool's
     * own labels and taints, and kubelet settings that reserve what GKE reserves, so the node's
     * allocatable CPU and memory are its machine type's (when the Docker host is big enough).
     */
    static NodeSpec spec(String project, String pool, String zone, String nodeName, Map<String, Object> config,
                         int maxPods, boolean gpuTaint, int hostCpus, long hostMemoryBytes) {
        return spec(project, pool, zone, nodeName, config, maxPods, gpuTaint, hostCpus, hostMemoryBytes, false);
    }

    /** As above, for a node that runs the GKE metadata server when {@code gkeMetadataServer} is set. */
    static NodeSpec spec(String project, String pool, String zone, String nodeName, Map<String, Object> config,
                         int maxPods, boolean gpuTaint, int hostCpus, long hostMemoryBytes, boolean gkeMetadataServer) {
        Map<String, Object> cfg = config == null ? Map.of() : config;
        String machineType = stringValue(cfg.get("machineType"), GkeMachineTypes.DEFAULT_MACHINE_TYPE);
        GkeMachineTypes.Shape shape = GkeMachineTypes.shape(machineType).orElse(null);
        Accelerator accelerator = accelerator(cfg);

        Map<String, String> labels = new LinkedHashMap<>();
        labels.put("cloud.google.com/gke-boot-disk", stringValue(cfg.get("diskType"), "pd-balanced"));
        labels.put("cloud.google.com/gke-container-runtime", "containerd");
        if (shape != null) {
            labels.put("cloud.google.com/gke-cpu-scaling-level", String.valueOf(shape.cpus()));
        }
        labels.put("cloud.google.com/gke-logging-variant", "DEFAULT");
        labels.put("cloud.google.com/gke-max-pods-per-node", String.valueOf(maxPods));
        labels.put("cloud.google.com/gke-nodepool", pool);
        labels.put("cloud.google.com/gke-os-distribution", osDistribution(stringValue(cfg.get("imageType"), "COS_CONTAINERD")));
        labels.put("cloud.google.com/gke-provisioning", provisioning(cfg));
        if (Boolean.TRUE.equals(cfg.get("spot"))) {
            labels.put("cloud.google.com/gke-spot", "true");
        } else if (Boolean.TRUE.equals(cfg.get("preemptible"))) {
            labels.put("cloud.google.com/gke-preemptible", "true");
        }
        labels.put("cloud.google.com/gke-stack-type", "IPV4");
        labels.put("cloud.google.com/machine-family", GkeMachineTypes.family(machineType));
        labels.put("cloud.google.com/private-node", "false");
        labels.put("node.kubernetes.io/instance-type", machineType);
        labels.put("topology.kubernetes.io/region", region(zone));
        labels.put("topology.kubernetes.io/zone", zone);
        if (accelerator != null) {
            labels.put("cloud.google.com/gke-accelerator", accelerator.type());
        }
        if (gkeMetadataServer) {
            labels.put(GKE_METADATA_SERVER_LABEL, "true");
        }
        if (cfg.get("labels") instanceof Map<?, ?> userLabels) {
            userLabels.forEach((k, v) -> labels.put(String.valueOf(k), String.valueOf(v)));
        }

        List<String> taints = new ArrayList<>();
        if (accelerator != null && gpuTaint) {
            taints.add(GPU_TAINT);
        }
        if (cfg.get("taints") instanceof List<?> configured) {
            for (Object entry : configured) {
                if (entry instanceof Map<?, ?> taint && taint.get("key") instanceof String key) {
                    String value = taint.get("value") instanceof String v ? v : "";
                    taints.add(key + (value.isEmpty() ? "" : "=" + value) + ":" + effect(taint.get("effect")));
                }
            }
        }

        List<String> kubeletArgs = new ArrayList<>();
        kubeletArgs.add("provider-id=" + providerId(project, zone, nodeName));
        kubeletArgs.add("max-pods=" + maxPods);
        kubeletArgs.add("eviction-hard=memory.available<" + EVICTION_MB + "Mi");
        int cpus = 0;
        long memoryBytes = 0;
        if (shape != null) {
            cpus = shape.cpus();
            memoryBytes = shape.memoryMb() * 1024L * 1024L;
            kubeletArgs.add(kubeReserved(shape, hostCpus, hostMemoryBytes));
        }
        return new NodeSpec(labels, taints, kubeletArgs, cpus, memoryBytes,
                accelerator == null ? null : accelerator.type(), accelerator == null ? 0 : accelerator.count());
    }

    private record Accelerator(String type, int count) {
    }

    /** The GPUs a pool's nodes get: {@code config.accelerators}, else those its machine type comes with. */
    private static Accelerator accelerator(Map<String, Object> cfg) {
        if (cfg.get("accelerators") instanceof List<?> accelerators) {
            for (Object entry : accelerators) {
                if (entry instanceof Map<?, ?> attached && attached.get("acceleratorType") instanceof String type
                        && !type.isBlank()) {
                    int count = intValue(attached.get("acceleratorCount"), 1);
                    if (count > 0) {
                        return new Accelerator(type, count);
                    }
                }
            }
        }
        return GkeMachineTypes.shape(stringValue(cfg.get("machineType"), GkeMachineTypes.DEFAULT_MACHINE_TYPE))
                .filter(GkeMachineTypes.Shape::hasGpus)
                .map(shape -> new Accelerator(shape.acceleratorType(), shape.acceleratorCount()))
                .orElse(null);
    }

    /**
     * A pool config's {@code workloadMetadataConfig}: {@code mode} ({@code GKE_METADATA} or {@code GCE_METADATA}), else
     * the deprecated v1beta1 {@code nodeMetadata} ({@code GKE_METADATA_SERVER} runs the GKE metadata server,
     * {@code EXPOSE} and {@code SECURE} expose the Compute Engine one). Null when unspecified.
     */
    static String metadataMode(Map<String, Object> config) {
        if (config == null || !(config.get("workloadMetadataConfig") instanceof Map<?, ?> metadata)) {
            return null;
        }
        String mode = metadata.get("mode") instanceof String m ? m : "";
        String nodeMetadata = metadata.get("nodeMetadata") instanceof String n ? n : "";
        if (GKE_METADATA.equals(mode) || GCE_METADATA.equals(mode)) {
            return mode;
        }
        if (!mode.isEmpty() && !"MODE_UNSPECIFIED".equals(mode)) {
            throw GcpException.invalidArgument("Invalid workloadMetadataConfig.mode \"" + mode
                    + "\": expected GCE_METADATA or GKE_METADATA");
        }
        return switch (nodeMetadata) {
            case "GKE_METADATA_SERVER" -> GKE_METADATA;
            case "EXPOSE", "SECURE" -> GCE_METADATA;
            case "", "UNSPECIFIED" -> null;
            default -> throw GcpException.invalidArgument("Invalid workloadMetadataConfig.nodeMetadata \""
                    + nodeMetadata + "\"");
        };
    }

    static boolean hasGpus(Map<String, Object> config) {
        return accelerator(config == null ? Map.of() : config) != null;
    }

    /**
     * {@code kube-reserved} for a node whose kubelet sees the whole Docker host: everything the host
     * has beyond the machine type, plus what GKE reserves on that machine type, so allocatable equals
     * GKE's. GKE reserves CPU as 6% of the first core, 1% of the second, 0.5% of the next two and
     * 0.25% of the rest (1060 millicores on shared-core E2 types), and memory as 255 MiB under 1 GiB,
     * else 25% of the first 4 GiB, 20% of the next 4, 10% of the next 8, 6% of the next 112 and 2%
     * above 128 GiB; the 100 MiB eviction threshold is set separately.
     */
    static String kubeReserved(GkeMachineTypes.Shape shape, int hostCpus, long hostMemoryBytes) {
        long cpuMillis = shape.sharedCore() ? 1060 : reservedCpuMillis(shape.cpus());
        long memoryMb = reservedMemoryMb(shape.memoryMb());
        long hostCpuMillis = hostCpus * 1000L;
        long hostMemoryMb = hostMemoryBytes / (1024 * 1024);
        if (hostCpuMillis > shape.cpus() * 1000L) {
            cpuMillis += hostCpuMillis - shape.cpus() * 1000L;
        }
        if (hostMemoryMb > shape.memoryMb()) {
            memoryMb += hostMemoryMb - shape.memoryMb();
        }
        return "kube-reserved=cpu=" + cpuMillis + "m,memory=" + memoryMb + "Mi";
    }

    static long reservedCpuMillis(int cpus) {
        double millis = 0;
        for (int core = 1; core <= cpus; core++) {
            millis += core == 1 ? 60 : core == 2 ? 10 : core <= 4 ? 5 : 2.5;
        }
        return Math.round(millis);
    }

    static long reservedMemoryMb(int memoryMb) {
        if (memoryMb < 1024) {
            return 255;
        }
        double reserved = 0;
        reserved += 0.25 * Math.min(memoryMb, 4096);
        reserved += 0.20 * Math.max(0, Math.min(memoryMb, 8192) - 4096);
        reserved += 0.10 * Math.max(0, Math.min(memoryMb, 16384) - 8192);
        reserved += 0.06 * Math.max(0, Math.min(memoryMb, 131072) - 16384);
        reserved += 0.02 * Math.max(0, memoryMb - 131072);
        return Math.round(reserved);
    }

    private static String osDistribution(String imageType) {
        return imageType.toUpperCase().startsWith("UBUNTU") ? "ubuntu"
                : imageType.toUpperCase().startsWith("WINDOWS") ? "windows" : "cos";
    }

    private static String provisioning(Map<String, Object> cfg) {
        if (Boolean.TRUE.equals(cfg.get("spot"))) {
            return "spot";
        }
        return Boolean.TRUE.equals(cfg.get("preemptible")) ? "preemptible" : "standard";
    }

    private static String effect(Object effect) {
        return switch (effect instanceof String s ? s : "") {
            case "PREFER_NO_SCHEDULE" -> "PreferNoSchedule";
            case "NO_EXECUTE" -> "NoExecute";
            default -> "NoSchedule";
        };
    }

    private static String trimDash(String value) {
        String trimmed = value;
        while (trimmed.endsWith("-")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        return trimmed;
    }

    static String stringValue(Object value, String fallback) {
        return value instanceof String s && !s.isBlank() ? s : fallback;
    }

    static int intValue(Object value, int fallback) {
        if (value instanceof Number n) {
            return n.intValue();
        }
        if (value instanceof String s) {
            try {
                return Integer.parseInt(s.trim());
            } catch (NumberFormatException e) {
                return fallback;
            }
        }
        return fallback;
    }
}
