package io.floci.gcp.services.gke;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.async.ResultCallback;
import com.github.dockerjava.api.command.ExecCreateCmdResponse;
import com.github.dockerjava.api.command.InspectContainerResponse;
import com.github.dockerjava.api.exception.NotFoundException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.dockerjava.api.model.Container;
import com.github.dockerjava.api.model.ContainerNetwork;
import com.github.dockerjava.api.model.Frame;
import com.github.dockerjava.api.model.Info;
import com.github.dockerjava.api.model.Network;
import io.floci.gcp.config.EmulatorConfig;
import io.floci.gcp.core.common.docker.ContainerBuilder;
import io.floci.gcp.core.common.docker.ContainerDetector;
import io.floci.gcp.core.common.docker.ContainerLifecycleManager;
import io.floci.gcp.core.common.docker.ContainerLifecycleManager.ContainerInfo;
import io.floci.gcp.core.common.docker.ContainerSpec;
import io.floci.gcp.core.common.docker.ContainerStorageHelper;
import io.floci.gcp.core.common.docker.DockerHostResolver;
import io.floci.gcp.core.common.docker.PortAllocator;
import io.floci.gcp.core.common.docker.RetryingTarCopier;
import io.floci.gcp.services.gke.model.StoredCluster;
import io.floci.gcp.services.gke.model.StoredNode;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * Manages the Docker lifecycle of {@code rancher/k3s} containers for real-mode GKE clusters.
 * Mirrors the sibling AWS emulator's EKS cluster manager: it talks to the Docker daemon via
 * the docker-java API (no {@code k3d}/{@code docker} CLI in the image) and spawns k3s as a
 * sibling container. Not used when {@code floci-gcp.services.gke.mock=true}.
 */
@ApplicationScoped
public class GkeClusterManager {

    private static final Logger LOG = Logger.getLogger(GkeClusterManager.class);
    private static final int K3S_API_SERVER_PORT = 6443;
    private static final String ENDPOINT_MODE_NETWORK = "network";

    /**
     * Volume-name prefix used before names were persisted. Pinned for backfill only —
     * pre-upgrade volumes must keep resolving to this exact prefix regardless of what the
     * live naming helper produces.
     */
    private static final String LEGACY_VOLUME_PREFIX = "floci-gke-";

    static final String ROLE_LABEL = "io.floci.gke-role";
    static final String ROLE_CONTROL_PLANE = "control-plane";
    static final String ROLE_NODE = "node";
    static final String NODE_POOL_LABEL = "io.floci.gke-node-pool";
    static final String NODE_LABEL = "io.floci.gke-node";
    private static final String K3S_DATA_DIR = "/var/lib/rancher/k3s";
    private static final String TOKEN_PATH = K3S_DATA_DIR + "/server/token";
    private static final int SERVER_HOST_OFFSET = 10;
    private static final ObjectMapper JSON = new ObjectMapper();

    private static final String WEBHOOK_CONFIG_DIR = "/etc";
    private static final String WEBHOOK_CONFIG_FILE = "gke-token-webhook.yaml";
    private static final String WEBHOOK_CONFIG_PATH = WEBHOOK_CONFIG_DIR + "/" + WEBHOOK_CONFIG_FILE;

    /** How {@link #restoreCluster} brought a persisted cluster's control plane back. */
    public enum RestoreOutcome {
        ADOPTED,
        RECREATED_ON_VOLUME,
        RECREATED_FRESH
    }

    private final ContainerBuilder containerBuilder;
    private final ContainerLifecycleManager lifecycleManager;
    private final ContainerDetector containerDetector;
    private final PortAllocator portAllocator;
    private final DockerHostResolver dockerHostResolver;
    private final EmulatorConfig config;

    @Inject
    public GkeClusterManager(ContainerBuilder containerBuilder,
                             ContainerLifecycleManager lifecycleManager,
                             ContainerDetector containerDetector,
                             PortAllocator portAllocator,
                             DockerHostResolver dockerHostResolver,
                             EmulatorConfig config) {
        this.containerBuilder = containerBuilder;
        this.lifecycleManager = lifecycleManager;
        this.containerDetector = containerDetector;
        this.portAllocator = portAllocator;
        this.dockerHostResolver = dockerHostResolver;
        this.config = config;
    }

    /**
     * Starts a k3s container for the given cluster, setting its container id, host port and
     * endpoints. The cluster status stays {@code PROVISIONING} until {@link #isReady} returns
     * true and {@link #finalizeCluster} extracts the CA.
     */
    public void startCluster(StoredCluster cluster) {
        String image = imageFor(cluster.getCurrentMasterVersion());
        String containerName = ContainerStorageHelper.dockerName(config,
                "gke-" + cluster.getProject() + "-" + cluster.getName());

        LOG.infov("Starting k3s container for GKE cluster {0} using image {1}",
                cluster.getName(), image);

        int hostPort = allocateHostPort(cluster.getHostPort());
        cluster.setHostPort(hostPort);
        try {
            launchContainer(cluster, image, containerName, hostPort);
        } catch (RuntimeException e) {
            removeFailedContainer(cluster);
            portAllocator.release(hostPort);
            cluster.setHostPort(0);
            throw e;
        }
    }

    /**
     * The k3s image for a GKE version: the release pinned for its minor, unless an image is forced for
     * every cluster. A version whose minor is no longer supported (a cluster persisted by an older
     * build) runs on the default release.
     */
    String imageFor(String version) {
        Optional<String> forced = config.services().gke().defaultImage().filter(i -> !i.isBlank());
        if (forced.isPresent()) {
            return forced.get();
        }
        GkeReleases.Release release = GkeReleases.forVersion(version).orElseGet(() -> {
            GkeReleases.Release fallback = GkeReleases.defaultRelease(Optional.empty());
            LOG.warnv("GKE version {0} has no pinned k3s release; running {1}", version, fallback.k3sTag());
            return fallback;
        });
        return config.services().gke().imageRepository() + ":" + release.k3sTag();
    }

    /**
     * Restarts on the port a persisted cluster already advertised when it is still free, so a
     * kubeconfig written before an emulator restart keeps working; any free port otherwise.
     */
    private int allocateHostPort(int previous) {
        if (previous > 0) {
            try {
                return portAllocator.allocate(previous, previous);
            } catch (RuntimeException e) {
                LOG.infov("Port {0} of a restored GKE cluster is taken, allocating another", String.valueOf(previous));
            }
        }
        return portAllocator.allocate(config.services().gke().apiServerBasePort(),
                config.services().gke().apiServerMaxPort());
    }

    /**
     * Brings a persisted cluster's control plane back after an emulator restart: the surviving
     * container is adopted (started if stopped) on the port it publishes, otherwise a new one
     * starts on the surviving data volume, which keeps the cluster's CA and state, otherwise a new
     * one starts on a fresh volume.
     */
    public RestoreOutcome restoreCluster(StoredCluster cluster) {
        Optional<Container> survivor = lifecycleManager.listContainersByLabels("list GKE control plane containers",
                        identityFilter(cluster)).stream()
                .filter(c -> !ROLE_NODE.equals(labelOf(c, ROLE_LABEL)))
                .findFirst();
        if (survivor.isPresent() && ROLE_CONTROL_PLANE.equals(labelOf(survivor.get(), ROLE_LABEL))
                && cluster.getServerIp() != null) {
            ensureNetwork(cluster);
            adoptControlPlane(cluster, survivor.get().getId());
            return RestoreOutcome.ADOPTED;
        }
        if (survivor.isPresent()) {
            LOG.infov("GKE cluster {0} still runs a control plane that schedules its own workloads; replacing it "
                    + "with one whose node pools run as separate nodes, on the same data volume", cluster.getName());
            lifecycleManager.stopAndRemove(survivor.get().getId(), null);
        }
        boolean volumeSurvived = lifecycleManager.volumeExists(volumeName(cluster));
        cluster.setContainerId(null);
        startCluster(cluster);
        if (!volumeSurvived) {
            LOG.warnv("GKE cluster {0} had neither a container nor a data volume left; it starts fresh with a "
                    + "new CA, so kubeconfigs written before the restart no longer work", cluster.getName());
            return RestoreOutcome.RECREATED_FRESH;
        }
        return RestoreOutcome.RECREATED_ON_VOLUME;
    }

    private void adoptControlPlane(StoredCluster cluster, String containerId) {
        ContainerInfo info = lifecycleManager.adopt(containerId, List.of(K3S_API_SERVER_PORT));
        int hostPort = info.publishedHostPort(K3S_API_SERVER_PORT).orElse(cluster.getHostPort());
        if (hostPort > 0) {
            portAllocator.markReserved(hostPort);
        }
        cluster.setContainerId(containerId);
        cluster.setHostPort(hostPort);
        String containerName = ContainerStorageHelper.dockerName(config,
                "gke-" + cluster.getProject() + "-" + cluster.getName());
        cluster.setEndpoint(resolvePublicEndpoint(containerDetector.isRunningInContainer(),
                config.services().gke().endpointMode(), containerName, hostPort));
        ContainerLifecycleManager.EndpointInfo ep = info.getEndpoint(K3S_API_SERVER_PORT);
        cluster.setInternalEndpoint(containerDetector.isRunningInContainer() && ep != null
                ? "https://" + ep.host() + ":" + ep.port()
                : "https://localhost:" + hostPort);
        LOG.infov("Adopted k3s container {0} for GKE cluster {1} on port {2}",
                containerId, cluster.getName(), String.valueOf(hostPort));
    }

    private static Map<String, String> identityFilter(StoredCluster cluster) {
        return ContainerStorageHelper.resourceIdentityLabels(
                "gke", cluster.getName(), cluster.getProject(), cluster.getLocation());
    }

    private static String labelOf(Container container, String key) {
        return container.getLabels() == null ? null : container.getLabels().get(key);
    }

    String controlPlaneContainerName(StoredCluster cluster) {
        return ContainerStorageHelper.dockerName(config, "gke-" + cluster.getProject() + "-" + cluster.getName());
    }

    /**
     * Creates the cluster's own Docker network, holding its control plane and its nodes, unless it
     * exists. The subnet Docker picks is recreated as a user-defined one so the control plane can keep
     * a fixed address (the tenth of the subnet) it advertises to its nodes, whichever other networks
     * it is attached to; dynamic addresses come from the upper half of the subnet only.
     */
    void ensureNetwork(StoredCluster cluster) {
        String name = cluster.getNetworkName() != null ? cluster.getNetworkName() : controlPlaneContainerName(cluster);
        cluster.setNetworkName(name);
        Network existing = inspectNetwork(name);
        if (existing != null) {
            readSubnet(cluster, existing);
            return;
        }
        for (int attempt = 1; attempt <= 3; attempt++) {
            String subnet = cluster.getNetworkSubnet();
            String gateway = cluster.getNetworkGateway();
            if (subnet == null || gateway == null) {
                lifecycleManager.runDockerApi("create Docker network " + name, () -> lifecycleManager.getDockerClient()
                        .createNetworkCmd().withName(name).withDriver("bridge")
                        .withLabels(ContainerStorageHelper.defaultLabels(config)).exec());
                Network probe = inspectNetwork(name);
                removeNetwork(name);
                if (probe == null || probe.getIpam() == null || probe.getIpam().getConfig() == null
                        || probe.getIpam().getConfig().isEmpty()) {
                    return;
                }
                subnet = probe.getIpam().getConfig().get(0).getSubnet();
                gateway = probe.getIpam().getConfig().get(0).getGateway();
            }
            String finalSubnet = subnet;
            String finalGateway = gateway;
            try {
                lifecycleManager.runDockerApi("create Docker network " + name, () -> lifecycleManager.getDockerClient()
                        .createNetworkCmd().withName(name).withDriver("bridge")
                        .withLabels(ContainerStorageHelper.defaultLabels(config))
                        .withIpam(new Network.Ipam().withConfig(new Network.Ipam.Config()
                                .withSubnet(finalSubnet).withGateway(finalGateway)
                                .withIpRange(upperHalf(finalSubnet))))
                        .exec());
                cluster.setNetworkSubnet(subnet);
                cluster.setNetworkGateway(gateway);
                cluster.setServerIp(hostAddress(subnet, SERVER_HOST_OFFSET));
                LOG.infov("Created Docker network {0} ({1}) for GKE cluster {2}", name, subnet, cluster.getName());
                return;
            } catch (RuntimeException e) {
                LOG.infov("Docker network {0} could not take subnet {1} (attempt {2}): {3}", name, subnet,
                        String.valueOf(attempt), e.getMessage());
                cluster.setNetworkSubnet(null);
                cluster.setNetworkGateway(null);
            }
        }
        throw new IllegalStateException("Could not create Docker network " + name + " for GKE cluster "
                + cluster.getName());
    }

    private void readSubnet(StoredCluster cluster, Network network) {
        if (network.getIpam() == null || network.getIpam().getConfig() == null
                || network.getIpam().getConfig().isEmpty()) {
            return;
        }
        Network.Ipam.Config ipam = network.getIpam().getConfig().get(0);
        cluster.setNetworkSubnet(ipam.getSubnet());
        cluster.setNetworkGateway(ipam.getGateway());
        if (cluster.getServerIp() == null && ipam.getIpRange() != null) {
            cluster.setServerIp(hostAddress(ipam.getSubnet(), SERVER_HOST_OFFSET));
        }
    }

    private Network inspectNetwork(String name) {
        try {
            return lifecycleManager.runDockerApi("inspect Docker network " + name,
                    () -> lifecycleManager.getDockerClient().inspectNetworkCmd().withNetworkId(name).exec());
        } catch (NotFoundException e) {
            return null;
        }
    }

    private void removeNetwork(String name) {
        if (name == null) {
            return;
        }
        try {
            lifecycleManager.runDockerApi("remove Docker network " + name, () -> {
                lifecycleManager.getDockerClient().removeNetworkCmd(name).exec();
                return null;
            });
        } catch (NotFoundException e) {
            LOG.debugv("Docker network {0} already removed", name);
        } catch (RuntimeException e) {
            LOG.warnv("Could not remove Docker network {0}: {1}", name, e.getMessage());
        }
    }

    static String upperHalf(String cidr) {
        int prefix = Integer.parseInt(cidr.substring(cidr.indexOf('/') + 1));
        long base = ipToLong(cidr.substring(0, cidr.indexOf('/')));
        long half = 1L << (32 - prefix - 1);
        return longToIp(base + half) + "/" + (prefix + 1);
    }

    static String hostAddress(String cidr, int offset) {
        return longToIp(ipToLong(cidr.substring(0, cidr.indexOf('/'))) + offset);
    }

    private static long ipToLong(String ip) {
        long value = 0;
        for (String part : ip.split("\\.")) {
            value = (value << 8) | Integer.parseInt(part);
        }
        return value;
    }

    private static String longToIp(long value) {
        return ((value >> 24) & 0xff) + "." + ((value >> 16) & 0xff) + "." + ((value >> 8) & 0xff) + "." + (value & 0xff);
    }

    /**
     * Recreates the control plane container on the image of the cluster's current master version,
     * keeping its data volume and host port, as an in-place k3s upgrade does. The cluster is not
     * ready again until {@link #isReady} says so.
     */
    public void replaceControlPlane(StoredCluster cluster) {
        if (cluster.getContainerId() != null) {
            lifecycleManager.stopAndRemove(cluster.getContainerId(), null);
            cluster.setContainerId(null);
        }
        String image = imageFor(cluster.getCurrentMasterVersion());
        String containerName = ContainerStorageHelper.dockerName(config,
                "gke-" + cluster.getProject() + "-" + cluster.getName());
        LOG.infov("Recreating the control plane of GKE cluster {0} on image {1}", cluster.getName(), image);
        launchContainer(cluster, image, containerName, cluster.getHostPort());
    }

    /**
     * A container this start created would keep the published port, so it goes before the port is reused,
     * together with the volume: with no container ID left, stopCluster would never remove it.
     */
    private void removeFailedContainer(StoredCluster cluster) {
        if (cluster.getContainerId() == null) {
            return;
        }
        try {
            lifecycleManager.stopAndRemove(cluster.getContainerId(), null);
            lifecycleManager.removeVolume(volumeName(cluster));
            removeNetwork(cluster.getNetworkName());
        } catch (RuntimeException e) {
            LOG.warnv("Could not remove k3s container {0} after a failed start of cluster {1}: {2}",
                    cluster.getContainerId(), cluster.getName(), e.getMessage());
        }
        cluster.setContainerId(null);
    }

    private void launchContainer(StoredCluster cluster, String image, String containerName, int hostPort) {
        lifecycleManager.removeIfExists(containerName);

        // A named Docker volume (not a host bind mount) for the k3s data dir: bind mounts to a
        // macOS host path make kine's unix socket chmod fail (EINVAL); named volumes live in the
        // Docker VM's Linux filesystem. k3s v1.34+ manages embedded SQLite (kine) internally.
        String volumeName = volumeName(cluster);
        cluster.setVolumeName(volumeName);
        lifecycleManager.ensureVolume(volumeName);

        ensureNetwork(cluster);
        List<String> serverArgs = new ArrayList<>(List.of(
                "server", "--disable-agent", "--egress-selector-mode=cluster",
                "--disable=traefik", "--disable=local-storage",
                "--tls-san=localhost", "--tls-san=" + containerName,
                "--kube-apiserver-arg=enable-admission-plugins=ExtendedResourceToleration"));
        if (cluster.getServerIp() != null) {
            serverArgs.add("--advertise-address=" + cluster.getServerIp());
        }

        // Wire a token-authentication webhook so the bearer token from gke-gcloud-auth-plugin
        // (i.e. `gcloud container clusters get-credentials` + kubectl) is validated by floci-gcp
        // and mapped to cluster-admin — the native GKE auth flow. The kubeconfig is streamed into
        // the container via the Docker API (not bind-mounted) so it works in Docker-in-Docker.
        String webhookLocalFile = writeWebhookKubeconfig(cluster.getName());
        ContainerBuilder.Builder specBuilder = containerBuilder.newContainer(image)
                .withName(containerName)
                .withEnv("K3S_KUBECONFIG_MODE", "644")
                .withPortBinding(K3S_API_SERVER_PORT, hostPort)
                .withNamedVolume(volumeName, "/var/lib/rancher/k3s")
                .withLabels(ContainerStorageHelper.resourceIdentityLabels(
                        "gke", cluster.getName(), cluster.getProject(), cluster.getLocation()))
                .withLabel(ROLE_LABEL, ROLE_CONTROL_PLANE)
                .withDockerNetwork(config.services().gke().dockerNetwork())
                .withPrivileged(true)
                .withLogRotation();
        if (webhookLocalFile != null) {
            specBuilder.withHostDockerInternalOnLinux();
            serverArgs.add("--kube-apiserver-arg=authentication-token-webhook-config-file=" + WEBHOOK_CONFIG_PATH);
            serverArgs.add("--kube-apiserver-arg=authentication-token-webhook-version=v1");
            serverArgs.add("--kube-apiserver-arg=authentication-token-webhook-cache-ttl=30s");
        }
        ContainerSpec spec = specBuilder.withCmd(serverArgs).build();

        // create -> inject webhook kubeconfig -> start, so the file exists before the API server boots.
        String containerId = lifecycleManager.create(spec);
        cluster.setContainerId(containerId);
        connectToClusterNetwork(cluster, containerId);
        if (webhookLocalFile != null) {
            copyWebhookIntoContainer(containerId, webhookLocalFile, cluster.getName());
        }
        ContainerInfo info = lifecycleManager.startCreated(containerId, spec);

        cluster.setEndpoint(resolvePublicEndpoint(
                containerDetector.isRunningInContainer(),
                config.services().gke().endpointMode(),
                containerName, hostPort));

        if (containerDetector.isRunningInContainer()) {
            ContainerLifecycleManager.EndpointInfo ep = info.getEndpoint(K3S_API_SERVER_PORT);
            cluster.setInternalEndpoint(ep != null
                    ? "https://" + ep.host() + ":" + ep.port()
                    : "https://localhost:" + hostPort);
        } else {
            cluster.setInternalEndpoint("https://localhost:" + hostPort);
        }

        LOG.infov("k3s container {0} started for cluster {1} on port {2}",
                containerId, cluster.getName(), String.valueOf(hostPort));
    }

    /** What a node container is started with, besides its name and the cluster it joins. */
    public record NodeLaunch(String pool, String image, GkeNodes.NodeSpec spec) {
    }

    /** A Kubernetes node as the control plane reports it. */
    public record NodeState(boolean ready, Map<String, String> labels, int gpuCapacity) {
    }

    /** The CPUs and memory the Docker host gives containers, which every node's kubelet sees as its capacity. */
    public record HostResources(int cpus, long memoryBytes) {
    }

    private volatile HostResources hostResources;

    public HostResources hostResources() {
        HostResources cached = hostResources;
        if (cached != null) {
            return cached;
        }
        Info info = lifecycleManager.runDockerApi("docker info", () -> lifecycleManager.getDockerClient().infoCmd().exec());
        HostResources resources = info == null || info.getNCPU() == null || info.getMemTotal() == null
                ? new HostResources(0, 0)
                : new HostResources(info.getNCPU(), info.getMemTotal());
        hostResources = resources;
        return resources;
    }

    /** The token nodes join the cluster with, as the running control plane stores it. */
    public String readToken(StoredCluster cluster) {
        ContainerLifecycleManager.ExecResult result = lifecycleManager.exec(cluster.getContainerId(), List.of(),
                List.of("cat", TOKEN_PATH));
        if (result == null || result.exitCode() != 0 || result.stdout() == null || result.stdout().isBlank()) {
            return null;
        }
        return result.stdout().trim();
    }

    String nodeContainerName(StoredCluster cluster, String nodeName) {
        return ContainerStorageHelper.dockerName(config, "gke-" + cluster.getProject() + "-" + nodeName);
    }

    /**
     * Starts the k3s agent container backing one node: on the cluster network, joining the control
     * plane by its container name, registered under the node's GKE name with its labels, taints,
     * provider ID and kubelet reservations, and limited to its machine type's CPUs and memory.
     */
    public void startNode(StoredCluster cluster, StoredNode node, NodeLaunch launch) {
        String containerName = nodeContainerName(cluster, node.getName());
        String volumeName = containerName;
        node.setContainerName(containerName);
        node.setVolumeName(volumeName);
        node.setImage(launch.image());
        lifecycleManager.removeIfExists(containerName);
        lifecycleManager.ensureVolume(volumeName);

        List<String> args = new ArrayList<>(List.of("agent", "--node-name=" + node.getName()));
        launch.spec().labels().forEach((k, v) -> args.add("--node-label=" + k + "=" + v));
        launch.spec().taints().forEach(t -> args.add("--node-taint=" + t));
        launch.spec().kubeletArgs().forEach(a -> args.add("--kubelet-arg=" + a));

        Map<String, String> labels = new HashMap<>(ContainerStorageHelper.resourceIdentityLabels(
                "gke", cluster.getName(), cluster.getProject(), cluster.getLocation()));
        labels.put(ROLE_LABEL, ROLE_NODE);
        labels.put(NODE_POOL_LABEL, launch.pool());
        labels.put(NODE_LABEL, node.getName());
        ContainerBuilder.Builder builder = containerBuilder.newContainer(launch.image())
                .withName(containerName)
                .withEnv("K3S_URL", "https://" + controlPlaneContainerName(cluster) + ":" + K3S_API_SERVER_PORT)
                .withEnv("K3S_TOKEN", cluster.getK3sToken())
                .withNamedVolume(volumeName, K3S_DATA_DIR)
                .withLabels(labels)
                .withNetworkMode(cluster.getNetworkName())
                .withPrivileged(true)
                .withLogRotation()
                .withCmd(args);
        if (launch.spec().memoryBytes() > 0) {
            builder.withMemoryBytes(launch.spec().memoryBytes());
        }
        if (launch.spec().cpus() > 0) {
            builder.withNanoCpus(launch.spec().cpus() * 1_000_000_000L);
        }
        ContainerSpec spec = builder.build();
        String containerId = lifecycleManager.create(spec);
        node.setContainerId(containerId);
        lifecycleManager.startCreated(containerId, spec);
        LOG.infov("Started node {0} of GKE cluster {1} ({2})", node.getName(), cluster.getName(), launch.image());
    }

    /** Starts a node's surviving container if it is stopped; false when the container is gone. */
    public boolean adoptNode(StoredNode node) {
        String id = node.getContainerId() != null ? node.getContainerId() : node.getContainerName();
        if (id == null) {
            return false;
        }
        try {
            lifecycleManager.adopt(id, List.of());
            return true;
        } catch (NotFoundException e) {
            return false;
        }
    }

    /** Removes a node's container and volume. */
    public void removeNode(StoredNode node) {
        String id = node.getContainerId() != null ? node.getContainerId() : node.getContainerName();
        if (id != null) {
            lifecycleManager.forceRemove(id, null);
        }
        if (node.getVolumeName() != null) {
            lifecycleManager.removeVolume(node.getVolumeName());
        }
    }

    /** Removes every node container of the cluster whose container name is not in {@code keep}, with its volume. */
    public void removeNodeContainers(StoredCluster cluster, Set<String> keep) {
        Map<String, String> filter = new HashMap<>(identityFilter(cluster));
        filter.put(ROLE_LABEL, ROLE_NODE);
        List<Container> containers = lifecycleManager.listContainersByLabels("list GKE node containers", filter);
        if (containers == null) {
            return;
        }
        for (Container container : containers) {
            String name = container.getNames() == null || container.getNames().length == 0 ? null
                    : container.getNames()[0].replaceFirst("^/", "");
            if (name != null && keep.contains(name)) {
                continue;
            }
            lifecycleManager.forceRemove(container.getId(), null);
            if (name != null) {
                lifecycleManager.removeVolume(name);
            }
            LOG.infov("Removed node container {0} of GKE cluster {1}", name, cluster.getName());
        }
    }

    /** Runs kubectl in the control plane container. */
    public ContainerLifecycleManager.ExecResult kubectl(StoredCluster cluster, List<String> args) {
        List<String> command = new ArrayList<>();
        command.add("kubectl");
        command.addAll(args);
        return lifecycleManager.exec(cluster.getContainerId(), List.of(), command);
    }

    /** The cluster's Kubernetes nodes by name, or null when the control plane cannot list them. */
    public Map<String, NodeState> nodeStates(StoredCluster cluster) {
        ContainerLifecycleManager.ExecResult result = kubectl(cluster, List.of("get", "nodes", "-o", "json"));
        if (result == null || result.exitCode() != 0) {
            return null;
        }
        try {
            Map<String, NodeState> states = new HashMap<>();
            for (JsonNode item : JSON.readTree(result.stdout()).path("items")) {
                boolean ready = false;
                for (JsonNode condition : item.path("status").path("conditions")) {
                    if ("Ready".equals(condition.path("type").asText()) && "True".equals(condition.path("status").asText())) {
                        ready = true;
                    }
                }
                Map<String, String> labels = new HashMap<>();
                item.path("metadata").path("labels").fields()
                        .forEachRemaining(e -> labels.put(e.getKey(), e.getValue().asText()));
                int gpus = item.path("status").path("capacity").path("nvidia.com/gpu").asInt(0);
                states.put(item.path("metadata").path("name").asText(), new NodeState(ready, labels, gpus));
            }
            return states;
        } catch (IOException e) {
            LOG.debugv("Could not read the nodes of GKE cluster {0}: {1}", cluster.getName(), e.getMessage());
            return null;
        }
    }

    /** Deletes Kubernetes node objects whose agents are gone, with the node passwords k3s keeps for them. */
    public void deleteKubernetesNodes(StoredCluster cluster, List<String> nodeNames) {
        if (nodeNames.isEmpty() || cluster.getContainerId() == null) {
            return;
        }
        List<String> args = new ArrayList<>(List.of("delete", "node", "--ignore-not-found", "--wait=false"));
        args.addAll(nodeNames);
        kubectl(cluster, args);
        List<String> secrets = new ArrayList<>(List.of("-n", "kube-system", "delete", "secret", "--ignore-not-found",
                "--wait=false"));
        nodeNames.forEach(n -> secrets.add(n + ".node-password.k3s"));
        kubectl(cluster, secrets);
    }

    /** Overwrites labels on a registered node, for those the k3s cloud controller rewrites at registration. */
    public void labelNode(StoredCluster cluster, String nodeName, Map<String, String> labels) {
        if (labels.isEmpty()) {
            return;
        }
        List<String> args = new ArrayList<>(List.of("label", "node", nodeName, "--overwrite"));
        labels.forEach((k, v) -> args.add(k + "=" + v));
        kubectl(cluster, args);
    }

    /** Attaches the created, not yet started, control plane to the cluster network on its fixed address. */
    private void connectToClusterNetwork(StoredCluster cluster, String containerId) {
        if (cluster.getNetworkName() == null) {
            return;
        }
        ContainerNetwork endpoint = new ContainerNetwork();
        if (cluster.getServerIp() != null) {
            endpoint.withIpamConfig(new ContainerNetwork.Ipam().withIpv4Address(cluster.getServerIp()));
        }
        lifecycleManager.runDockerApi("connect container " + containerId + " to network " + cluster.getNetworkName(),
                () -> {
                    lifecycleManager.getDockerClient().connectToNetworkCmd()
                            .withContainerId(containerId)
                            .withNetworkId(cluster.getNetworkName())
                            .withContainerNetwork(endpoint)
                            .exec();
                    return null;
                });
    }

    /** Polls the k3s API server's /readyz endpoint to check readiness. */
    public boolean isReady(StoredCluster cluster) {
        String endpoint = cluster.getInternalEndpoint() != null
                ? cluster.getInternalEndpoint()
                : cluster.getEndpoint();
        if (endpoint == null || cluster.getContainerId() == null) {
            return false;
        }
        try {
            HttpURLConnection conn = (HttpURLConnection) URI.create(endpoint + "/readyz").toURL().openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(2000);
            conn.setReadTimeout(2000);
            if (conn instanceof javax.net.ssl.HttpsURLConnection https) {
                disableSslVerification(https);
            }
            int code = conn.getResponseCode();
            return code == 200 || code == 401 || code == 403;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Whether the cluster's k3s container is gone or no longer running, for example after a
     * crash or after a restart removed it. A Docker error other than not-found counts as still
     * running, so a transient daemon hiccup never fails a create; the startup deadline covers it.
     */
    public boolean isControlPlaneStopped(StoredCluster cluster) {
        if (cluster.getContainerId() == null) {
            return true;
        }
        try {
            InspectContainerResponse inspect = lifecycleManager.getDockerClient()
                    .inspectContainerCmd(cluster.getContainerId()).exec();
            return !Boolean.TRUE.equals(inspect.getState().getRunning());
        } catch (NotFoundException e) {
            return true;
        } catch (Exception e) {
            LOG.debugv("Could not inspect k3s container of cluster {0}: {1}", cluster.getName(), e.getMessage());
            return false;
        }
    }

    /** Extracts the kubeconfig CA from the running k3s container and sets it on the cluster. */
    public void finalizeCluster(StoredCluster cluster) {
        String containerId = cluster.getContainerId();
        if (containerId == null) {
            return;
        }
        try {
            String kubeconfigYaml = execInContainer(containerId,
                    new String[]{"cat", "/etc/rancher/k3s/k3s.yaml"});
            String caData = extractYamlField(kubeconfigYaml, "certificate-authority-data");
            if (caData != null) {
                cluster.setCaCertificate(caData.trim());
            }
            LOG.infov("Finalized GKE cluster {0} with CA data extracted", cluster.getName());
        } catch (Exception e) {
            LOG.warnv("Could not extract kubeconfig for cluster {0}: {1}",
                    cluster.getName(), e.getMessage());
        }
    }

    /** Stops and removes the k3s container and its data volume at shutdown, unless clusters are kept running. */
    public void stopCluster(StoredCluster cluster) {
        if (cluster.getContainerId() == null) {
            return;
        }
        if (config.services().gke().keepRunningOnShutdown()) {
            LOG.infov("Leaving k3s container for cluster {0} running", cluster.getName());
            return;
        }
        removeCluster(cluster);
    }

    /** Removes the k3s container and its data volume of a deleted cluster, whatever the shutdown policy. */
    public void deleteCluster(StoredCluster cluster) {
        if (cluster.getContainerId() == null) {
            return;
        }
        removeCluster(cluster);
    }

    private void removeCluster(StoredCluster cluster) {
        removeNodeContainers(cluster, Set.of());
        lifecycleManager.stopAndRemove(cluster.getContainerId(), null);
        lifecycleManager.removeVolume(volumeName(cluster));
        if (cluster.getHostPort() > 0) {
            portAllocator.release(cluster.getHostPort());
        }
        removeNetwork(cluster.getNetworkName());
        LOG.infov("Stopped k3s container for cluster {0}", cluster.getName());
    }

    /**
     * Resolves the k3s data volume name. Pre-upgrade clusters used an unscoped
     * {@code floci-gke-<cluster>} name; an existing volume under that name is adopted so its
     * data survives the rename. New volumes are project-scoped, which also keeps clusters with
     * the same id in different projects apart. Long namespace+project+cluster combinations can
     * exceed the 63-character DNS label limit for in-network resolution of the container name.
     */
    String volumeName(StoredCluster cluster) {
        if (cluster.getVolumeName() != null && !cluster.getVolumeName().isBlank()) {
            return cluster.getVolumeName();
        }
        String legacy = LEGACY_VOLUME_PREFIX + cluster.getName();
        if (lifecycleManager.volumeExists(legacy)) {
            return legacy;
        }
        return ContainerStorageHelper.resourceName(config, "gke", null,
                cluster.getProject() + "-" + cluster.getName());
    }

    /** The raw k3s kubeconfig (server URL unmodified), for the internal kubeconfig endpoint. */
    public String kubeConfig(StoredCluster cluster) {
        if (cluster.getContainerId() == null) {
            return "";
        }
        try {
            return execInContainer(cluster.getContainerId(),
                    new String[]{"cat", "/etc/rancher/k3s/k3s.yaml"});
        } catch (Exception e) {
            LOG.warnv("Could not read kubeconfig for cluster {0}: {1}",
                    cluster.getName(), e.getMessage());
            return "";
        }
    }

    /**
     * The cluster's public {@code endpoint} — a bare {@code host:port} with no scheme, matching real
     * GKE (whose endpoint is a bare host). {@code gcloud container clusters get-credentials} prepends
     * {@code https://}, so a scheme here would produce a malformed {@code https://https://...} server.
     */
    static String resolvePublicEndpoint(boolean inContainer, String endpointMode,
                                        String containerName, int hostPort) {
        if (inContainer && ENDPOINT_MODE_NETWORK.equalsIgnoreCase(endpointMode)) {
            return containerName + ":" + K3S_API_SERVER_PORT;
        }
        return "localhost:" + hostPort;
    }

    /** floci-gcp's GKE token-webhook URL, reachable from inside the k3s container. */
    String webhookUrl() {
        return "http://" + dockerHostResolver.resolve() + ":" + config.port() + "/_floci-gcp/gke/token-webhook";
    }

    /**
     * Writes the token-webhook kubeconfig (pointing the k3s API server's webhook at floci-gcp) to a
     * temp file and returns its path, or {@code null} if it could not be written (the caller then
     * skips the webhook so cluster creation still succeeds).
     */
    private String writeWebhookKubeconfig(String clusterName) {
        try {
            Path dir = Files.createTempDirectory("floci-gke-webhook-");
            Path file = dir.resolve(WEBHOOK_CONFIG_FILE);
            Files.writeString(file, buildWebhookKubeconfig(webhookUrl()));
            return file.toString();
        } catch (IOException e) {
            LOG.warnv("GKE token-webhook disabled for cluster {0}: could not write kubeconfig: {1}",
                    clusterName, e.getMessage());
            return null;
        }
    }

    /** Streams the webhook kubeconfig into the (created, not-yet-started) k3s container at /etc. */
    private void copyWebhookIntoContainer(String containerId, String localFile, String clusterName) {
        try {
            RetryingTarCopier.copyHostResource(lifecycleManager.getDockerClient(), containerId,
                    WEBHOOK_CONFIG_DIR, localFile);
        } catch (Exception e) {
            LOG.warnv("GKE token-webhook may not authenticate for cluster {0}: could not copy kubeconfig "
                    + "into the k3s container: {1}", clusterName, e.getMessage());
        }
    }

    static String buildWebhookKubeconfig(String serverUrl) {
        return """
                apiVersion: v1
                kind: Config
                clusters:
                - name: floci-token-webhook
                  cluster:
                    server: %s
                users:
                - name: floci-token-webhook
                contexts:
                - name: floci-token-webhook
                  context:
                    cluster: floci-token-webhook
                    user: floci-token-webhook
                current-context: floci-token-webhook
                """.formatted(serverUrl);
    }

    private String execInContainer(String containerId, String[] cmd) throws Exception {
        DockerClient dockerClient = lifecycleManager.getDockerClient();
        ExecCreateCmdResponse exec = dockerClient
                .execCreateCmd(containerId)
                .withCmd(cmd)
                .withAttachStdout(true)
                .withAttachStderr(true)
                .exec();

        StringBuilder output = new StringBuilder();
        boolean completed = dockerClient.execStartCmd(exec.getId())
                .exec(new ResultCallback.Adapter<Frame>() {
                    @Override
                    public void onNext(Frame frame) {
                        output.append(new String(frame.getPayload(), StandardCharsets.UTF_8));
                    }
                })
                .awaitCompletion(10, TimeUnit.SECONDS);

        if (!completed) {
            throw new RuntimeException("exec timed out in container " + containerId);
        }
        return output.toString();
    }

    private String extractYamlField(String yaml, String fieldName) {
        for (String line : yaml.split("\n")) {
            String trimmed = line.trim();
            if (trimmed.startsWith(fieldName + ":")) {
                return trimmed.substring(fieldName.length() + 1).trim();
            }
        }
        return null;
    }

    @SuppressWarnings("java:S4830")
    private void disableSslVerification(javax.net.ssl.HttpsURLConnection conn) {
        try {
            javax.net.ssl.TrustManager[] trustAll = new javax.net.ssl.TrustManager[]{
                new javax.net.ssl.X509TrustManager() {
                    public java.security.cert.X509Certificate[] getAcceptedIssuers() {
                        return null;
                    }

                    public void checkClientTrusted(java.security.cert.X509Certificate[] c, String a) {
                    }

                    public void checkServerTrusted(java.security.cert.X509Certificate[] c, String a) {
                    }
                }
            };
            javax.net.ssl.SSLContext sc = javax.net.ssl.SSLContext.getInstance("TLS");
            sc.init(null, trustAll, new java.security.SecureRandom());
            conn.setSSLSocketFactory(sc.getSocketFactory());
            conn.setHostnameVerifier((h, s) -> true);
        } catch (Exception e) {
            LOG.debugv("Could not disable SSL verification: {0}", e.getMessage());
        }
    }
}
