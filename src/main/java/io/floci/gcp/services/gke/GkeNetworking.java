package io.floci.gcp.services.gke;

import io.floci.gcp.core.common.GcpException;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The Pod and Service address ranges of a cluster, as GKE picks them from a create request: VPC-native
 * unless {@code ipAllocationPolicy.useRoutes} is set, a Pod range chosen from {@code 10.0.0.0/8} when
 * none or only a size is given, Services from the GKE-managed {@code 34.118.224.0/20} on VPC-native
 * clusters and from the last {@code /20} of the Pod range on routes-based ones, and a per-node Pod
 * range sized from the maximum Pods per node.
 */
final class GkeNetworking {

    static final String MANAGED_SERVICES_RANGE = "34.118.224.0/20";
    static final String AUTO_POD_POOL = "10.0.0.0/8";
    static final int DEFAULT_POD_PREFIX = 14;
    static final int DEFAULT_NODE_MASK = 24;

    private static final Pattern CIDR = Pattern.compile("^(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})/(\\d{1,2})$");
    private static final Pattern NETMASK = Pattern.compile("^/(\\d{1,2})$");

    /** The ranges a cluster runs with; {@code nodeMaskSize} is the prefix of each node's Pod range. */
    record Ranges(String podCidr, String servicesCidr, boolean routesBased, int nodeMaskSize) {
    }

    private record Block(long base, int prefix) {

        long size() {
            return 1L << (32 - prefix);
        }

        boolean overlaps(Block other) {
            return base < other.base + other.size() && other.base < base + size();
        }

        @Override
        public String toString() {
            return longToIp(base) + "/" + prefix;
        }
    }

    private GkeNetworking() {
    }

    /**
     * Resolves the ranges of a new cluster. {@code taken} are ranges an automatically chosen one must
     * not overlap: those of the other clusters on the same network, and the address the cluster
     * reaches the emulator on.
     */
    static Ranges resolve(Map<String, Object> clusterMap, int maxPodsPerNode, List<String> taken) {
        Map<?, ?> policy = clusterMap.get("ipAllocationPolicy") instanceof Map<?, ?> m ? m : Map.of();
        boolean routes = Boolean.TRUE.equals(policy.get("useRoutes"));
        if (routes && Boolean.TRUE.equals(policy.get("useIpAliases"))) {
            throw GcpException.invalidArgument("ipAllocationPolicy.useIpAliases cannot be true if useRoutes is true.");
        }
        List<Block> avoid = taken.stream().map(GkeNetworking::parseTaken).filter(b -> b != null).toList();
        if (routes) {
            Block pods = pick("clusterIpv4Cidr", string(clusterMap.get("clusterIpv4Cidr")), avoid);
            if (pods.prefix() < 9 || pods.prefix() > 19) {
                throw GcpException.invalidArgument("clusterIpv4Cidr \"" + pods
                        + "\" must be between /9 and /19 on a routes-based cluster.");
            }
            Block services = new Block(pods.base() + pods.size() - (1L << 12), 20);
            return new Ranges(pods.toString(), services.toString(), true, DEFAULT_NODE_MASK);
        }
        String podField = "ipAllocationPolicy.clusterIpv4CidrBlock";
        String podRequest = string(policy.get("clusterIpv4CidrBlock"));
        if (podRequest == null) {
            podField = "ipAllocationPolicy.clusterIpv4Cidr";
            podRequest = string(policy.get("clusterIpv4Cidr"));
        }
        if (podRequest == null) {
            podField = "clusterIpv4Cidr";
            podRequest = string(clusterMap.get("clusterIpv4Cidr"));
        }
        Block pods = pick(podField, podRequest, avoid);
        int nodeMask = nodeMaskSize(maxPodsPerNode);
        if (pods.prefix() > nodeMask) {
            throw GcpException.invalidArgument(podField + " \"" + pods + "\" is smaller than the /" + nodeMask
                    + " Pod range each node needs for " + maxPodsPerNode + " Pods.");
        }
        String servicesField = "ipAllocationPolicy.servicesIpv4CidrBlock";
        String servicesRequest = string(policy.get("servicesIpv4CidrBlock"));
        if (servicesRequest == null) {
            servicesField = "ipAllocationPolicy.servicesIpv4Cidr";
            servicesRequest = string(policy.get("servicesIpv4Cidr"));
        }
        Block services = services(servicesField, servicesRequest, avoidingPods(avoid, pods));
        if (services.prefix() < 12 || services.prefix() > 28) {
            throw GcpException.invalidArgument(servicesField + " \"" + services + "\" must be between /12 and /28.");
        }
        if (services.overlaps(pods)) {
            throw GcpException.invalidArgument(servicesField + " \"" + services + "\" overlaps the Pod range \""
                    + pods + "\".");
        }
        return new Ranges(pods.toString(), services.toString(), false, nodeMask);
    }

    /** The prefix of a node's Pod range: the smallest block holding twice its maximum Pods, {@code 31 - ceil(log2(Q))}. */
    static int nodeMaskSize(int maxPodsPerNode) {
        int pods = Math.max(1, maxPodsPerNode);
        int bits = 32 - Integer.numberOfLeadingZeros(pods - 1);
        return 31 - bits;
    }

    /** The tenth address of a range, which k3s gives its DNS Service. */
    static String dnsAddress(String cidr) {
        Block block = parse("cidr", cidr);
        return longToIp(block.base() + 10);
    }

    private static List<Block> avoidingPods(List<Block> avoid, Block pods) {
        List<Block> all = new ArrayList<>(avoid);
        all.add(pods);
        return all;
    }

    private static Block services(String field, String request, List<Block> avoid) {
        if (request == null) {
            return parse(field, MANAGED_SERVICES_RANGE);
        }
        Matcher netmask = NETMASK.matcher(request);
        if (netmask.matches()) {
            int prefix = Integer.parseInt(netmask.group(1));
            if (prefix >= 20) {
                return new Block(parse(field, MANAGED_SERVICES_RANGE).base(), prefix);
            }
            return firstFree(field, prefix, avoid);
        }
        return parse(field, request);
    }

    private static Block pick(String field, String request, List<Block> avoid) {
        if (request == null) {
            return firstFree(field, DEFAULT_POD_PREFIX, avoid);
        }
        Matcher netmask = NETMASK.matcher(request);
        if (netmask.matches()) {
            return firstFree(field, Integer.parseInt(netmask.group(1)), avoid);
        }
        return parse(field, request);
    }

    /** The first block of the given size in {@code 10.0.0.0/8} that overlaps none of {@code avoid}. */
    private static Block firstFree(String field, int prefix, List<Block> avoid) {
        Block pool = parse(field, AUTO_POD_POOL);
        if (prefix < pool.prefix() || prefix > 30) {
            throw GcpException.invalidArgument(field + " \"/" + prefix + "\" is not a valid range size.");
        }
        long step = 1L << (32 - prefix);
        for (long base = pool.base(); base + step <= pool.base() + pool.size(); base += step) {
            Block candidate = new Block(base, prefix);
            if (avoid.stream().noneMatch(candidate::overlaps)) {
                return candidate;
            }
        }
        throw GcpException.resourceExhausted("No free /" + prefix + " range is left in " + AUTO_POD_POOL + " for " + field + ".");
    }

    private static Block parse(String field, String cidr) {
        Matcher m = CIDR.matcher(cidr.trim());
        if (!m.matches()) {
            throw GcpException.invalidArgument(field + " \"" + cidr + "\" is not a valid IPv4 CIDR range.");
        }
        long base = 0;
        for (int i = 1; i <= 4; i++) {
            int octet = Integer.parseInt(m.group(i));
            if (octet > 255) {
                throw GcpException.invalidArgument(field + " \"" + cidr + "\" is not a valid IPv4 CIDR range.");
            }
            base = (base << 8) | octet;
        }
        int prefix = Integer.parseInt(m.group(5));
        if (prefix > 32) {
            throw GcpException.invalidArgument(field + " \"" + cidr + "\" is not a valid IPv4 CIDR range.");
        }
        Block block = new Block(base, prefix);
        if (base % block.size() != 0) {
            throw GcpException.invalidArgument(field + " \"" + cidr + "\" has host bits set; did you mean \""
                    + new Block(base - base % block.size(), prefix) + "\"?");
        }
        return block;
    }

    private static Block parseTaken(String range) {
        if (range == null || range.isBlank()) {
            return null;
        }
        String cidr = range.contains("/") ? range : range + "/32";
        Matcher m = CIDR.matcher(cidr);
        if (!m.matches()) {
            return null;
        }
        long base = 0;
        for (int i = 1; i <= 4; i++) {
            base = (base << 8) | (Integer.parseInt(m.group(i)) & 0xff);
        }
        int prefix = Math.min(32, Integer.parseInt(m.group(5)));
        long size = 1L << (32 - prefix);
        return new Block(base - base % size, prefix);
    }

    private static String string(Object value) {
        return value instanceof String s && !s.isBlank() ? s.trim() : null;
    }

    private static String longToIp(long value) {
        return ((value >> 24) & 0xff) + "." + ((value >> 16) & 0xff) + "." + ((value >> 8) & 0xff) + "." + (value & 0xff);
    }
}
