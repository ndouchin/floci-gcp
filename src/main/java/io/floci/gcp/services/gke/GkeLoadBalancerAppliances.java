package io.floci.gcp.services.gke;

import com.github.dockerjava.api.command.CreateContainerCmd;
import com.github.dockerjava.api.command.InspectContainerResponse;
import com.github.dockerjava.api.exception.NotFoundException;
import com.github.dockerjava.api.model.Capability;
import com.github.dockerjava.api.model.ExposedPort;
import com.github.dockerjava.api.model.HostConfig;
import com.github.dockerjava.api.model.InternetProtocol;
import com.github.dockerjava.api.model.Network;
import com.github.dockerjava.api.model.Ports;
import io.floci.gcp.config.EmulatorConfig;
import io.floci.gcp.core.common.docker.ContainerLifecycleManager;
import io.floci.gcp.core.common.docker.ContainerStorageHelper;
import io.floci.gcp.core.common.docker.ImageCacheService;
import io.floci.gcp.services.gke.model.StoredCluster;
import io.floci.gcp.services.gke.model.StoredLoadBalancer;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The container that stands in for a cloud load balancer: one per Service, from the cluster's own k3s image, holding the
 * load balancer's address (on the cluster network for an internal one, on a shared network of the public range for an
 * external one) and forwarding each Service port to the node port on the cluster's Ready nodes, as GKE's passthrough
 * Network Load Balancer forwards to nodes. Every port is also published on {@code 127.0.0.1} so the host reaches it.
 */
@ApplicationScoped
public class GkeLoadBalancerAppliances {

    private static final Logger LOG = Logger.getLogger(GkeLoadBalancerAppliances.class);
    static final String SIGNATURE_LABEL = "io.floci.gke-load-balancer-signature";
    static final String SERVICE_LABEL = "io.floci.gke-load-balancer-service";

    private final ContainerLifecycleManager lifecycleManager;
    private final ImageCacheService images;
    private final GkeClusterManager clusterManager;
    private final EmulatorConfig config;

    @Inject
    public GkeLoadBalancerAppliances(ContainerLifecycleManager lifecycleManager, ImageCacheService images,
                                     GkeClusterManager clusterManager, EmulatorConfig config) {
        this.lifecycleManager = lifecycleManager;
        this.images = images;
        this.clusterManager = clusterManager;
        this.config = config;
    }

    public String applianceName(StoredCluster cluster, String uid) {
        return ContainerStorageHelper.dockerName(config, "gke-" + cluster.getProject() + "-" + cluster.getName() + "-lb-"
                + uid.replace("-", "").substring(0, Math.min(12, uid.replace("-", "").length())));
    }

    String publicNetworkName() {
        return ContainerStorageHelper.dockerName(config, "gke-lb-public");
    }

    /**
     * Brings the appliance of a load balancer in line with its address, ports and backends, recreating it when any of
     * them changed (or it is gone or stopped), and returns its host ports by {@code PROTOCOL/port}.
     */
    public Map<String, Integer> ensure(StoredCluster cluster, StoredLoadBalancer lb, List<GkeLoadBalancerSpec.Port> ports,
                                       List<String> backends) {
        String hostPortsMode = config.services().gke().loadBalancer().hostPorts();
        String signature = signature(lb, ports, backends, hostPortsMode);
        String name = applianceName(cluster, lb.getUid());
        lb.setAppliance(name);
        InspectContainerResponse existing = inspect(name);
        if (existing != null && Boolean.TRUE.equals(existing.getState().getRunning())
                && signature.equals(existing.getConfig().getLabels().get(SIGNATURE_LABEL))) {
            return hostPorts(existing, ports);
        }
        if (existing != null) {
            lifecycleManager.forceRemove(existing.getId(), null, true);
        }
        String image = clusterManager.imageFor(cluster.getCurrentMasterVersion());
        images.ensureImageExists(image);
        Map<String, String> labels = new HashMap<>(ContainerStorageHelper.defaultLabels(config));
        labels.putAll(ContainerStorageHelper.resourceIdentityLabels("gke", cluster.getName(), cluster.getProject(),
                cluster.getLocation()));
        labels.put(GkeClusterManager.ROLE_LABEL, GkeClusterManager.ROLE_LOAD_BALANCER);
        labels.put(SIGNATURE_LABEL, signature);
        labels.put(SERVICE_LABEL, lb.getNamespace() + "/" + lb.getName());
        Ports bindings = new Ports();
        List<ExposedPort> exposed = new ArrayList<>();
        for (GkeLoadBalancerSpec.Port port : ports) {
            ExposedPort exposedPort = new ExposedPort(port.port(), InternetProtocol.parse(port.protocol().toLowerCase(Locale.ROOT)));
            exposed.add(exposedPort);
            switch (hostPortsMode) {
                case "none" -> { }
                case "same-as-service" -> bindings.bind(exposedPort, Ports.Binding.bindIpAndPort("127.0.0.1", port.port()));
                default -> bindings.bind(exposedPort, Ports.Binding.bindIp("127.0.0.1"));
            }
        }
        HostConfig hostConfig = HostConfig.newHostConfig()
                .withCapAdd(Capability.NET_ADMIN)
                .withSysctls(Map.of("net.ipv4.ip_forward", "1"))
                .withPortBindings(bindings);
        String id = lifecycleManager.runDockerApi("create load balancer appliance " + name, () -> {
            CreateContainerCmd create = lifecycleManager.getDockerClient().createContainerCmd(image)
                    .withName(name)
                    .withLabels(labels)
                    .withEntrypoint("/bin/sh", "-c")
                    .withCmd(script(ports, backends))
                    .withExposedPorts(exposed)
                    .withHostConfig(hostConfig);
            return create.exec().getId();
        });
        try {
            clusterManager.connect(id, cluster.getNetworkName(), lb.isInternal() ? lb.getAddress() : null);
            if (!lb.isInternal()) {
                ensurePublicNetwork();
                clusterManager.connect(id, publicNetworkName(), lb.getAddress());
            }
            String emulatorNetwork = clusterManager.emulatorNetworkWhenInContainer();
            if (emulatorNetwork != null && !emulatorNetwork.equals(cluster.getNetworkName())) {
                clusterManager.connect(id, emulatorNetwork, null);
            }
            lifecycleManager.runDockerApi("start load balancer appliance " + name, () -> {
                lifecycleManager.getDockerClient().startContainerCmd(id).exec();
                return null;
            });
        } catch (RuntimeException e) {
            lifecycleManager.forceRemove(id, null, true);
            throw e;
        }
        LOG.infov("Started load balancer appliance {0} for Service {1}/{2} at {3}", name, lb.getNamespace(), lb.getName(),
                lb.getAddress());
        InspectContainerResponse started = inspect(name);
        return started == null ? Map.of() : hostPorts(started, ports);
    }

    public void remove(String name) {
        if (name != null) {
            lifecycleManager.removeIfExists(name, true);
        }
    }

    /** Removes the shared network of external addresses once no appliance uses it. */
    public void removePublicNetworkIfUnused() {
        String name = publicNetworkName();
        try {
            Network network = lifecycleManager.runDockerApi("inspect network " + name,
                    () -> lifecycleManager.getDockerClient().inspectNetworkCmd().withNetworkId(name).exec());
            if (network.getContainers() != null && !network.getContainers().isEmpty()) {
                return;
            }
            lifecycleManager.runDockerApi("remove network " + name, () -> {
                lifecycleManager.getDockerClient().removeNetworkCmd(name).exec();
                return null;
            });
            LOG.infov("Removed the load balancer network {0}", name);
        } catch (NotFoundException e) {
            LOG.debugv("Load balancer network {0} already gone", name);
        } catch (RuntimeException e) {
            LOG.debugv("Could not remove load balancer network {0}: {1}", name, e.getMessage());
        }
    }

    private void ensurePublicNetwork() {
        String name = publicNetworkName();
        try {
            lifecycleManager.runDockerApi("inspect network " + name,
                    () -> lifecycleManager.getDockerClient().inspectNetworkCmd().withNetworkId(name).exec());
            return;
        } catch (NotFoundException e) {
            LOG.debugv("Creating load balancer network {0}", name);
        }
        String cidr = config.services().gke().loadBalancer().publicCidr();
        lifecycleManager.runDockerApi("create network " + name, () -> lifecycleManager.getDockerClient()
                .createNetworkCmd().withName(name).withDriver("bridge")
                .withLabels(ContainerStorageHelper.defaultLabels(config))
                .withIpam(new Network.Ipam().withConfig(new Network.Ipam.Config().withSubnet(cidr)
                        .withGateway(GkeLoadBalancers.gateway(cidr))))
                .exec());
    }

    private InspectContainerResponse inspect(String name) {
        try {
            return lifecycleManager.runDockerApi("inspect load balancer appliance " + name,
                    () -> lifecycleManager.getDockerClient().inspectContainerCmd(name).exec());
        } catch (NotFoundException e) {
            return null;
        }
    }

    private static Map<String, Integer> hostPorts(InspectContainerResponse container, List<GkeLoadBalancerSpec.Port> ports) {
        Map<String, Integer> result = new LinkedHashMap<>();
        Map<ExposedPort, Ports.Binding[]> bindings = container.getNetworkSettings() == null
                || container.getNetworkSettings().getPorts() == null ? Map.of()
                : container.getNetworkSettings().getPorts().getBindings();
        for (GkeLoadBalancerSpec.Port port : ports) {
            Ports.Binding[] bound = bindings.get(new ExposedPort(port.port(),
                    InternetProtocol.parse(port.protocol().toLowerCase(Locale.ROOT))));
            if (bound != null && bound.length > 0 && bound[0].getHostPortSpec() != null) {
                result.put(port.protocol() + "/" + port.port(), Integer.parseInt(bound[0].getHostPortSpec()));
            }
        }
        return result;
    }

    /**
     * The appliance's entrypoint: per Service port, DNAT to the node port of each backend node (spread at random,
     * evenly), masquerade so replies come back through the appliance, then wait.
     */
    static String script(List<GkeLoadBalancerSpec.Port> ports, List<String> backends) {
        StringBuilder script = new StringBuilder("""
                IPT=/bin/aux/iptables
                [ -x "$IPT" ] || IPT=iptables
                set -e
                """);
        for (GkeLoadBalancerSpec.Port port : ports) {
            String protocol = port.protocol().toLowerCase(Locale.ROOT);
            for (int i = 0; i < backends.size(); i++) {
                script.append("$IPT -t nat -A PREROUTING -p ").append(protocol).append(" --dport ").append(port.port());
                int remaining = backends.size() - i;
                if (remaining > 1) {
                    script.append(" -m statistic --mode random --probability ")
                            .append(String.format(Locale.ROOT, "%.5f", 1.0 / remaining));
                }
                script.append(" -j DNAT --to-destination ").append(backends.get(i)).append(':').append(port.nodePort())
                        .append('\n');
            }
        }
        script.append("$IPT -t nat -A POSTROUTING -j MASQUERADE\n");
        script.append("exec sleep infinity\n");
        return script.toString();
    }

    private static String signature(StoredLoadBalancer lb, List<GkeLoadBalancerSpec.Port> ports, List<String> backends,
                                    String hostPortsMode) {
        String value = lb.getAddress() + "|" + lb.isInternal() + "|" + ports + "|" + backends + "|" + hostPortsMode;
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)), 0, 12);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
