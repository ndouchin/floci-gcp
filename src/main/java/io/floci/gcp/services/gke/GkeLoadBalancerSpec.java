package io.floci.gcp.services.gke;

import com.fasterxml.jackson.databind.JsonNode;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * What GKE makes of a Service of type LoadBalancer ("GKE Service load balancer concepts" and "parameters"):
 * whether GKE's controller handles it (no {@code loadBalancerClass}, or one of GKE's L4 classes), external or internal
 * ({@code networking.gke.io/load-balancer-type: "Internal"} or the internal class), target-pool or backend-service
 * based (backend services by default from GKE 1.37, or with {@code cloud.google.com/l4-rbs: "enabled"} or the
 * external class; always for internal ones), the address it asks for, and its ports.
 */
record GkeLoadBalancerSpec(boolean handled, String uid, String namespace, String name, boolean internal,
                           boolean backendService, String requestedAddress, String requestedAddressName,
                           List<Port> ports, boolean deleting, String error) {

    static final String CLASS_EXTERNAL = "networking.gke.io/l4-regional-external";
    static final String CLASS_EXTERNAL_LEGACY = "networking.gke.io/l4-regional-external-legacy";
    static final String CLASS_INTERNAL = "networking.gke.io/l4-regional-internal";
    static final String ADDRESS_ANNOTATION = "networking.gke.io/load-balancer-ip-addresses";
    private static final Set<String> CLASSES = Set.of(CLASS_EXTERNAL, CLASS_EXTERNAL_LEGACY, CLASS_INTERNAL);
    private static final int MAX_NAME = 63;
    private static final int FIRST_INTERNAL_OFFSET = 20;

    record Port(String protocol, int port, int nodePort) {
    }

    static GkeLoadBalancerSpec from(JsonNode service, int clusterMinor) {
        JsonNode metadata = service.path("metadata");
        JsonNode spec = service.path("spec");
        JsonNode annotations = metadata.path("annotations");
        String uid = metadata.path("uid").asText();
        String namespace = metadata.path("namespace").asText();
        String name = metadata.path("name").asText();
        boolean deleting = metadata.hasNonNull("deletionTimestamp");
        String lbClass = spec.path("loadBalancerClass").asText(null);
        boolean handled = "LoadBalancer".equals(spec.path("type").asText()) && (lbClass == null || CLASSES.contains(lbClass));
        boolean internal = CLASS_INTERNAL.equals(lbClass)
                || "internal".equalsIgnoreCase(annotations.path("networking.gke.io/load-balancer-type").asText())
                || "internal".equalsIgnoreCase(annotations.path("cloud.google.com/load-balancer-type").asText());
        boolean backendService = internal || CLASS_EXTERNAL.equals(lbClass)
                || "enabled".equalsIgnoreCase(annotations.path("cloud.google.com/l4-rbs").asText())
                || (!CLASS_EXTERNAL_LEGACY.equals(lbClass) && clusterMinor >= 37);
        String addressName = firstName(annotations.path(ADDRESS_ANNOTATION).asText(""));
        String requested = addressName == null ? blankToNull(spec.path("loadBalancerIP").asText(null)) : null;
        List<Port> ports = new ArrayList<>();
        String error = null;
        for (JsonNode port : spec.path("ports")) {
            int nodePort = port.path("nodePort").asInt(0);
            if (nodePort == 0) {
                error = "Services with allocateLoadBalancerNodePorts: false are not supported by the emulated load "
                        + "balancer, which forwards to node ports";
            }
            ports.add(new Port(port.path("protocol").asText("TCP").toUpperCase(Locale.ROOT), port.path("port").asInt(),
                    nodePort));
        }
        if (ports.isEmpty() && handled) {
            error = "The Service has no ports";
        }
        return new GkeLoadBalancerSpec(handled, uid, namespace, name, internal, backendService, requested, addressName,
                List.copyOf(ports), deleting, error);
    }

    /**
     * The forwarding rule's name: {@code a<UID>} cut to 32 characters for target-pool load balancers
     * (cloud-provider {@code DefaultLoadBalancerName}), {@code k8s2-<protocol>-<cluster hash>-<namespace>-<name>-<hash>}
     * for backend-service based ones, the shape of the {@code service.kubernetes.io/tcp-forwarding-rule} GKE
     * annotates; the hashes are floci-gcp's, GKE's are not documented.
     */
    String forwardingRuleName(String clusterId) {
        if (!backendService) {
            String bare = "a" + uid.replace("-", "");
            return bare.length() > 32 ? bare.substring(0, 32) : bare;
        }
        String protocol = ports.isEmpty() ? "tcp" : ports.get(0).protocol().toLowerCase(Locale.ROOT);
        String prefix = "k8s2-" + protocol + "-" + hash8(clusterId) + "-";
        String suffix = "-" + hash8(uid);
        int budget = MAX_NAME - prefix.length() - suffix.length() - 1;
        String ns = namespace;
        String svc = name;
        if (ns.length() + svc.length() > budget) {
            ns = ns.substring(0, Math.min(ns.length(), budget / 2));
            svc = svc.substring(0, Math.min(svc.length(), budget - ns.length()));
        }
        return prefix + ns + "-" + svc + suffix;
    }

    /**
     * An internal address in the lower half of the cluster's Docker subnet (its nodes take the upper half, the
     * control plane the tenth address): {@code requested} when it is there and free, else the first free one from the
     * twentieth address; null when {@code requested} cannot be used.
     */
    static String internalAddress(String subnet, Set<String> taken, String requested) {
        int slash = subnet.indexOf('/');
        long base = toLong(subnet.substring(0, slash));
        long half = 1L << (32 - Integer.parseInt(subnet.substring(slash + 1)) - 1);
        if (requested != null) {
            long value = toLong(requested);
            return value >= base + FIRST_INTERNAL_OFFSET && value < base + half && !taken.contains(requested)
                    ? requested : null;
        }
        for (long value = base + FIRST_INTERNAL_OFFSET; value < base + half; value++) {
            String address = toIp(value);
            if (!taken.contains(address)) {
                return address;
            }
        }
        return null;
    }

    static String statusPatch(String address) {
        return "{\"status\":{\"loadBalancer\":{\"ingress\":[{\"ip\":\"" + address + "\"}]}}}";
    }

    static boolean inCidr(String ip, String cidr) {
        return GkeMetadataService.inCidr(ip, cidr);
    }

    private static String firstName(String names) {
        for (String candidate : names.split(",")) {
            if (!candidate.isBlank()) {
                return candidate.trim();
            }
        }
        return null;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private static String hash8(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest, 0, 4);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    static long toLong(String ip) {
        long value = 0;
        for (String part : ip.split("\\.")) {
            value = (value << 8) | Integer.parseInt(part);
        }
        return value;
    }

    static String toIp(long value) {
        return ((value >> 24) & 0xff) + "." + ((value >> 16) & 0xff) + "." + ((value >> 8) & 0xff) + "." + (value & 0xff);
    }
}
