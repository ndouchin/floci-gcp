package io.floci.gcp.services.gke;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import io.floci.gcp.config.EmulatorConfig;
import io.floci.gcp.core.common.GcpException;
import io.floci.gcp.core.storage.StorageBackend;
import io.floci.gcp.core.storage.StorageFactory;
import io.floci.gcp.services.compute.ComputeService;
import io.floci.gcp.services.gke.model.StoredCluster;
import io.floci.gcp.services.gke.model.StoredLoadBalancer;
import io.floci.gcp.services.gke.model.StoredNode;
import io.floci.gcp.services.gke.model.StoredNodePool;
import io.quarkus.runtime.StartupEvent;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * The cloud load balancer controller of real clusters whose load balancer mode is {@code emulated}: like the
 * cloud-provider service controller, it handles Services of type LoadBalancer GKE's controller would handle, adds the
 * {@code service.kubernetes.io/load-balancer-cleanup} finalizer before it creates anything, gives each one an address
 * (an external address reserved as a Compute forwarding rule, or an internal one on the cluster network) held by an
 * appliance container, writes it to {@code status.loadBalancer}, records the cloud provider's events, and undoes all of
 * it when the Service goes or stops being a LoadBalancer. Runs every few seconds per cluster.
 */
@ApplicationScoped
public class GkeLoadBalancers {

    private static final Logger LOG = Logger.getLogger(GkeLoadBalancers.class);
    static final String MODE_EMULATED = "emulated";
    static final String MODE_SERVICELB = "servicelb";
    static final String MODE_OFF = "off";
    static final String FINALIZER = "service.kubernetes.io/load-balancer-cleanup";
    static final String HOST_PORTS_ANNOTATION = "floci.io/load-balancer-host-ports";
    static final String EXCLUDE_LABEL = "node.kubernetes.io/exclude-from-external-load-balancers";
    private static final long INTERVAL_SECONDS = 3;

    private final StorageBackend<String, StoredLoadBalancer> store;
    private final GkeService gke;
    private final GkeLoadBalancerKubectl kubectl;
    private final GkeLoadBalancerAppliances appliances;
    private final ComputeService compute;
    private final EmulatorConfig config;
    private final Clock clock;
    private ScheduledExecutorService scheduler;

    @Inject
    public GkeLoadBalancers(StorageFactory storageFactory, GkeService gke, GkeLoadBalancerKubectl kubectl,
                            GkeLoadBalancerAppliances appliances, ComputeService compute, EmulatorConfig config) {
        this(storageFactory.createGlobal("gke", "gke-load-balancers.json",
                new TypeReference<Map<String, StoredLoadBalancer>>() {
                }), gke, kubectl, appliances, compute, config, Clock.systemUTC());
    }

    GkeLoadBalancers(StorageBackend<String, StoredLoadBalancer> store, GkeService gke, GkeLoadBalancerKubectl kubectl,
                     GkeLoadBalancerAppliances appliances, ComputeService compute, EmulatorConfig config, Clock clock) {
        this.store = store;
        this.gke = gke;
        this.kubectl = kubectl;
        this.appliances = appliances;
        this.compute = compute;
        this.config = config;
        this.clock = clock;
    }

    /** Whether k3s's own servicelb is disabled: in {@code emulated} and {@code off} modes; clusters of earlier builds keep it. */
    static boolean replacesServiceLb(String mode) {
        return MODE_EMULATED.equals(mode) || MODE_OFF.equals(mode);
    }

    static String gateway(String cidr) {
        return GkeLoadBalancerSpec.toIp(GkeLoadBalancerSpec.toLong(cidr.substring(0, cidr.indexOf('/'))) + 1);
    }

    void onStart(@Observes StartupEvent event) {
        if (config.services().gke().mock()) {
            return;
        }
        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread thread = new Thread(r, "gke-load-balancers");
            thread.setDaemon(true);
            return thread;
        });
        scheduler.scheduleWithFixedDelay(this::reconcileAll, 5, INTERVAL_SECONDS, TimeUnit.SECONDS);
    }

    @PreDestroy
    void stop() {
        if (scheduler != null) {
            scheduler.shutdownNow();
        }
    }

    /** One pass over every cluster, then over the records of clusters that are gone. */
    void reconcileAll() {
        Map<String, StoredCluster> clusters = new HashMap<>();
        for (StoredCluster cluster : gke.allClusters()) {
            clusters.put(clusterKey(cluster.getProject(), cluster.getLocation(), cluster.getName()), cluster);
            try {
                reconcile(cluster);
            } catch (Exception e) {
                LOG.debugv("Could not reconcile the load balancers of GKE cluster {0}: {1}", cluster.getName(), e.getMessage());
            }
        }
        boolean removed = false;
        for (StoredLoadBalancer record : store.scan(k -> true)) {
            StoredCluster cluster = clusters.get(clusterKey(record.getProject(), record.getLocation(), record.getCluster()));
            if (cluster == null || !Objects.equals(cluster.getId(), record.getClusterId())) {
                teardown(null, record, null, false);
                removed = true;
            }
        }
        if (removed) {
            appliances.removePublicNetworkIfUnused();
        }
    }

    void reconcile(StoredCluster cluster) {
        if (!MODE_EMULATED.equals(cluster.getLoadBalancerMode()) || !"RUNNING".equals(cluster.getStatus())) {
            return;
        }
        List<JsonNode> objects = kubectl.list(cluster);
        List<String> backends = backends(cluster, objects);
        Map<String, StoredLoadBalancer> records = store.scan(k -> k.startsWith(clusterKey(cluster.getProject(),
                        cluster.getLocation(), cluster.getName()) + "/loadBalancers/")).stream()
                .collect(Collectors.toMap(StoredLoadBalancer::getUid, r -> r, (a, b) -> a));
        Set<String> present = new HashSet<>();
        int minor = GkeReleases.minorOf(cluster.getCurrentMasterVersion());
        for (JsonNode object : objects) {
            if (!"Service".equals(object.path("kind").asText())) {
                continue;
            }
            GkeLoadBalancerSpec spec = GkeLoadBalancerSpec.from(object, minor);
            present.add(spec.uid());
            StoredLoadBalancer record = records.get(spec.uid());
            boolean ours = finalizers(object).contains(FINALIZER);
            if (!spec.handled() || spec.deleting()) {
                if (record != null || (ours && spec.deleting())) {
                    teardown(cluster, record, object, !spec.deleting());
                }
                continue;
            }
            try {
                ensure(cluster, object, spec, record, backends);
            } catch (Exception e) {
                LOG.debugv("Load balancer of Service {0}/{1} not ensured yet: {2}", spec.namespace(), spec.name(),
                        e.getMessage());
            }
        }
        for (StoredLoadBalancer record : records.values()) {
            if (!present.contains(record.getUid())) {
                teardown(null, record, null, false);
            }
        }
    }

    private void ensure(StoredCluster cluster, JsonNode service, GkeLoadBalancerSpec spec, StoredLoadBalancer existing,
                        List<String> backends) {
        StoredLoadBalancer record = existing;
        if (record == null) {
            record = new StoredLoadBalancer();
            record.setProject(cluster.getProject());
            record.setLocation(cluster.getLocation());
            record.setCluster(cluster.getName());
            record.setClusterId(cluster.getId());
            record.setUid(spec.uid());
            record.setNamespace(spec.namespace());
            record.setName(spec.name());
            record.setInternal(spec.internal());
            record.setRegion(GkeNodes.region(cluster.getLocation()));
            record.setFirstSeen(clock.instant().toString());
            List<String> finalizers = new ArrayList<>(finalizers(service));
            if (!finalizers.contains(FINALIZER)) {
                finalizers.add(FINALIZER);
                kubectl.setFinalizers(cluster, spec.namespace(), spec.name(), finalizers);
            }
            store.put(key(record), record);
            kubectl.event(cluster, service, "Normal", "EnsuringLoadBalancer", "Ensuring load balancer");
        }
        String error = spec.error();
        if (error == null && record.getAddress() == null
                && Instant.parse(record.getFirstSeen()).plus(config.services().gke().loadBalancer().provisioningDelay())
                .isAfter(clock.instant())) {
            return;
        }
        if (error == null && record.getAddress() == null) {
            try {
                allocate(cluster, spec, record);
            } catch (GcpException | IllegalArgumentException e) {
                error = e.getMessage();
            }
        }
        if (error != null) {
            if (!error.equals(record.getLastError())) {
                record.setLastError(error);
                store.put(key(record), record);
                kubectl.event(cluster, service, "Warning", "SyncLoadBalancerFailed",
                        "Error syncing load balancer: failed to ensure load balancer: " + error);
            }
            return;
        }
        record.setLastError(null);
        record.setAppliance(appliances.applianceName(cluster, record.getUid()));
        store.put(key(record), record);
        Map<String, Integer> hostPorts = appliances.ensure(cluster, record, spec.ports(), backends);
        if (gke.findCluster(cluster.getProject(), cluster.getLocation(), cluster.getName())
                .filter(current -> Objects.equals(current.getId(), cluster.getId())).isEmpty()) {
            appliances.remove(record.getAppliance());
            return;
        }
        record.setHostPorts(hostPorts);
        store.put(key(record), record);
        if (!record.getAddress().equals(service.path("status").path("loadBalancer").path("ingress").path(0).path("ip").asText())) {
            kubectl.patchStatus(cluster, spec.namespace(), spec.name(), GkeLoadBalancerSpec.statusPatch(record.getAddress()));
        }
        String annotation = hostPorts.entrySet().stream().map(e -> e.getKey() + "=127.0.0.1:" + e.getValue())
                .collect(Collectors.joining(","));
        if (!annotation.isEmpty() && !annotation.equals(service.path("metadata").path("annotations")
                .path(HOST_PORTS_ANNOTATION).asText())) {
            kubectl.annotate(cluster, spec.namespace(), spec.name(), HOST_PORTS_ANNOTATION, annotation);
        }
        if (!record.isEnsured()) {
            record.setEnsured(true);
            store.put(key(record), record);
            kubectl.event(cluster, service, "Normal", "EnsuredLoadBalancer", "Ensured load balancer");
            LOG.infov("Service {0}/{1} of GKE cluster {2} has load balancer address {3}", spec.namespace(), spec.name(),
                    cluster.getName(), record.getAddress());
        }
    }

    /**
     * An internal address from the cluster network, or an external one from Compute's pool recorded as the forwarding
     * rule GKE creates. A requested address is {@code networking.gke.io/load-balancer-ip-addresses} (an address
     * resource name) or {@code spec.loadBalancerIP}.
     */
    private void allocate(StoredCluster cluster, GkeLoadBalancerSpec spec, StoredLoadBalancer record) {
        String requested = spec.requestedAddress();
        if (spec.requestedAddressName() != null) {
            requested = compute.reservedAddress(cluster.getProject(), record.getRegion(), spec.requestedAddressName())
                    .orElseThrow(() -> new IllegalArgumentException("address " + spec.requestedAddressName()
                            + " is not reserved in project " + cluster.getProject()));
        }
        if (spec.internal()) {
            if (cluster.getNetworkSubnet() == null) {
                throw new IllegalArgumentException("the cluster network has no subnet to take an internal address from");
            }
            Set<String> taken = store.scan(k -> true).stream()
                    .filter(r -> r.isInternal() && Objects.equals(r.getClusterId(), cluster.getId()) && r.getAddress() != null)
                    .map(StoredLoadBalancer::getAddress).collect(Collectors.toSet());
            String address = GkeLoadBalancerSpec.internalAddress(cluster.getNetworkSubnet(), taken, requested);
            if (address == null) {
                throw new IllegalArgumentException("internal address " + requested + " is not free in the lower half of "
                        + "the cluster subnet " + cluster.getNetworkSubnet());
            }
            record.setAddress(address);
            return;
        }
        String publicCidr = config.services().gke().loadBalancer().publicCidr();
        if (requested != null && !GkeLoadBalancerSpec.inCidr(requested, publicCidr)) {
            throw new IllegalArgumentException("requested address " + requested + " is outside the emulator's external "
                    + "range " + publicCidr);
        }
        Set<String> avoid = store.scan(k -> true).stream().map(StoredLoadBalancer::getAddress).filter(Objects::nonNull)
                .collect(Collectors.toCollection(HashSet::new));
        avoid.add(gateway(publicCidr));
        String ruleName = spec.forwardingRuleName(cluster.getId());
        List<Integer> ports = spec.ports().stream().map(GkeLoadBalancerSpec.Port::port).distinct().toList();
        String address = compute.reserveLoadBalancerAddress(cluster.getProject(), record.getRegion(), ruleName, requested,
                avoid, spec.ports().get(0).protocol(), ports,
                "{\"kubernetes.io/service-name\":\"" + spec.namespace() + "/" + spec.name() + "\"}");
        record.setForwardingRule(ruleName);
        record.setAddress(address);
    }

    /**
     * Removes a load balancer: its appliance, its forwarding rule and its record, then, on a Service still there, the
     * cleanup finalizer (and the address, when the Service merely stopped being a LoadBalancer).
     */
    private void teardown(StoredCluster cluster, StoredLoadBalancer record, JsonNode service, boolean clearStatus) {
        if (record != null) {
            appliances.remove(record.getAppliance());
            if (record.getForwardingRule() != null) {
                try {
                    compute.releaseLoadBalancerAddress(record.getProject(), record.getRegion(), record.getForwardingRule());
                } catch (RuntimeException e) {
                    LOG.warnv("Could not delete forwarding rule {0}: {1}", record.getForwardingRule(), e.getMessage());
                }
            }
            store.delete(key(record));
        }
        if (cluster == null || service == null) {
            return;
        }
        String namespace = service.path("metadata").path("namespace").asText();
        String name = service.path("metadata").path("name").asText();
        if (clearStatus && service.path("status").path("loadBalancer").has("ingress")) {
            kubectl.patchStatus(cluster, namespace, name, "{\"status\":{\"loadBalancer\":{}}}");
        } else if (clearStatus && record != null && record.getAddress() != null) {
            kubectl.patchStatus(cluster, namespace, name, "{\"status\":{\"loadBalancer\":{}}}");
        }
        if (record != null) {
            kubectl.event(cluster, service, "Normal", "DeletedLoadBalancer", "Deleted load balancer");
        }
        List<String> finalizers = finalizers(service);
        if (finalizers.contains(FINALIZER)) {
            List<String> kept = new ArrayList<>(finalizers);
            kept.remove(FINALIZER);
            kubectl.setFinalizers(cluster, namespace, name, kept);
        }
        if (record != null && !record.isInternal()) {
            appliances.removePublicNetworkIfUnused();
        }
    }

    /**
     * The InternalIP of each Ready node the emulator runs for the cluster, except those labelled
     * {@code node.kubernetes.io/exclude-from-external-load-balancers}; Nodes it does not run are not backends.
     */
    private static List<String> backends(StoredCluster cluster, List<JsonNode> objects) {
        Set<String> nodes = new HashSet<>();
        for (StoredNodePool pool : cluster.getNodePools() == null ? List.<StoredNodePool>of() : cluster.getNodePools()) {
            for (StoredNode node : pool.getNodes() == null ? List.<StoredNode>of() : pool.getNodes()) {
                nodes.add(node.getName());
            }
        }
        List<String> backends = new ArrayList<>();
        for (JsonNode object : objects) {
            if (!"Node".equals(object.path("kind").asText()) || !nodes.contains(object.path("metadata").path("name").asText())
                    || object.path("metadata").path("labels").has(EXCLUDE_LABEL)) {
                continue;
            }
            boolean ready = false;
            for (JsonNode condition : object.path("status").path("conditions")) {
                ready |= "Ready".equals(condition.path("type").asText()) && "True".equals(condition.path("status").asText());
            }
            Optional<String> ip = Optional.empty();
            for (JsonNode address : object.path("status").path("addresses")) {
                if ("InternalIP".equals(address.path("type").asText())) {
                    ip = Optional.of(address.path("address").asText());
                }
            }
            if (ready && ip.isPresent()) {
                backends.add(ip.get());
            }
        }
        backends.sort(String::compareTo);
        return backends;
    }

    private static List<String> finalizers(JsonNode service) {
        List<String> finalizers = new ArrayList<>();
        service.path("metadata").path("finalizers").forEach(f -> finalizers.add(f.asText()));
        return finalizers;
    }

    private static String clusterKey(String project, String location, String cluster) {
        return "projects/" + project + "/locations/" + location + "/clusters/" + cluster;
    }

    private static String key(StoredLoadBalancer record) {
        return clusterKey(record.getProject(), record.getLocation(), record.getCluster()) + "/loadBalancers/" + record.getUid();
    }
}
