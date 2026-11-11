package io.floci.gcp.services.gke;

import com.fasterxml.jackson.core.type.TypeReference;
import io.floci.gcp.config.EmulatorConfig;
import io.floci.gcp.core.common.GcpException;
import io.floci.gcp.core.common.LocationCatalog;
import io.floci.gcp.core.common.ServiceDescriptor;
import io.floci.gcp.core.common.ServiceProtocol;
import io.floci.gcp.core.common.ServiceRegistry;
import io.floci.gcp.core.storage.StorageBackend;
import io.floci.gcp.core.storage.StorageFactory;
import io.floci.gcp.services.gke.model.StoredCluster;
import io.floci.gcp.services.gke.model.StoredNode;
import io.floci.gcp.services.gke.model.StoredNodePool;
import io.floci.gcp.services.gke.operations.GkeOperationService;
import io.floci.gcp.services.gke.operations.OperationType;
import io.floci.gcp.services.gke.operations.StoredOperation;
import io.grpc.Status;
import io.quarkus.runtime.StartupEvent;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

@ApplicationScoped
public class GkeService {

    private static final Logger LOG = Logger.getLogger(GkeService.class);

    private static final String DEFAULT_NODE_POOL_NAME = "default-pool";
    private static final Pattern VALID_CLUSTER_NAME =
            Pattern.compile("^[a-z](?:[a-z0-9-]{0,38}[a-z0-9])?$");
    /** {@code 1.X} / {@code 1.X.Y}: the two prefix aliases a version field documents. */
    private static final Pattern VERSION_PREFIX_ALIAS = Pattern.compile("^\\d+\\.\\d+(?:\\.\\d+)?$");
    /** {@code 1.X.Y-gke.N}: an explicit GKE version. */
    private static final Pattern EXPLICIT_VERSION = Pattern.compile("^\\d+\\.\\d+\\.\\d+-gke\\.\\d+$");
    private static final Pattern VALID_NODE_POOL_NAME =
            Pattern.compile("^[a-z](?:[a-z0-9-]{0,38}[a-z0-9])?$");
    /** How long a k3s control plane may stay {@code PROVISIONING} before its create fails. It
     * counts from {@code createTime}, so it also covers pulling the image on a first start. */
    static final Duration STARTUP_TIMEOUT = Duration.ofMinutes(10);

    /** Cluster-level fields with dedicated typed storage — everything else in a create/update
     * body is stored verbatim under {@link StoredCluster#getExtraConfig()}. */
    private static final List<String> TYPED_CLUSTER_FIELDS = List.of(
            "name", "description", "initialClusterVersion", "network", "subnetwork",
            "clusterIpv4Cidr", "locations", "resourceLabels", "loggingService", "monitoringService",
            "nodePools", "initialNodeCount", "nodeConfig", "workloadIdentityConfig", "id");

    private final StorageBackend<String, StoredCluster> clusterStore;
    private final StorageBackend<String, StoredNodePool> nodePoolStore;
    private final EmulatorConfig config;
    private final GkeClusterManager clusterManager;
    private final GkeOperationService operationService;
    private final ServiceRegistry serviceRegistry;
    private final LocationCatalog locations;
    /** Serializes the writes that settle a cluster's lifecycle (storing a new cluster, deleting
     * one, and the readiness poller's outcome) so a pass that read an older record never writes
     * over a deleted or recreated one. Never held across Docker calls. */
    private final Object lifecycleLock = new Object();
    /** Serializes the Docker work on one cluster's nodes (reconciles, pool and cluster deletes). Taken
     * before {@link #lifecycleLock}, never while holding it. */
    private final Map<String, Object> clusterLocks = new ConcurrentHashMap<>();
    /** Clusters whose control plane container is being recreated; the readiness poller leaves them
     * alone until the new container is recorded, since the old one is gone by design. */
    private final Set<String> replacingControlPlanes = ConcurrentHashMap.newKeySet();
    /** How many times each cluster's nodes were put out of date, so a reconcile pass that started before
     * the latest change leaves them out of date. Guarded by {@link #lifecycleLock}. */
    private final Map<String, Long> nodeChanges = new HashMap<>();
    private final SecureRandom random = new SecureRandom();
    /** Create operations whose cluster has an unreadable {@code createTime}, already logged once. */
    private final Set<String> unreadableCreateTimes = ConcurrentHashMap.newKeySet();
    private final ScheduledExecutorService poller = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "gke-readiness-poller");
        t.setDaemon(true);
        return t;
    });

    @Inject
    public GkeService(StorageFactory storageFactory,
                      EmulatorConfig config,
                      GkeClusterManager clusterManager,
                      GkeOperationService operationService,
                      ServiceRegistry serviceRegistry,
                      LocationCatalog locations) {
        this(storageFactory.createGlobal("gke", "gke-clusters.json",
                        new TypeReference<Map<String, StoredCluster>>() {
                        }),
                storageFactory.createGlobal("gke", "gke-node-pools.json",
                        new TypeReference<Map<String, StoredNodePool>>() {
                        }),
                config, clusterManager, operationService, serviceRegistry, locations);
    }

    GkeService(StorageBackend<String, StoredCluster> clusterStore,
               StorageBackend<String, StoredNodePool> nodePoolStore,
               EmulatorConfig config,
               GkeClusterManager clusterManager,
               GkeOperationService operationService,
               ServiceRegistry serviceRegistry) {
        this(clusterStore, nodePoolStore, config, clusterManager, operationService, serviceRegistry,
                LocationCatalog.lenient());
    }

    GkeService(StorageBackend<String, StoredCluster> clusterStore,
               StorageBackend<String, StoredNodePool> nodePoolStore,
               EmulatorConfig config,
               GkeClusterManager clusterManager,
               GkeOperationService operationService,
               ServiceRegistry serviceRegistry,
               LocationCatalog locations) {
        this.locations = locations;
        this.clusterStore = clusterStore;
        this.nodePoolStore = nodePoolStore;
        this.config = config;
        this.clusterManager = clusterManager;
        this.operationService = operationService;
        this.serviceRegistry = serviceRegistry;
    }

    void onStart(@Observes StartupEvent ev) {
        serviceRegistry.register(ServiceDescriptor.builder("gke")
                .enabled(config.services().gke().enabled())
                .storageKey("gke")
                .protocol(ServiceProtocol.REST)
                .hostToken("container")
                .pathPrefix("/container")
                .apiVersions("v1", "v1beta1")
                .resourceClasses(KubernetesController.class, KubernetesProjectController.class)
                .api("container.googleapis.com", "Kubernetes Engine API")
                .build());
    }

    @PostConstruct
    public void init() {
        migrateEmbeddedNodePools();
        backfillTypedClusterFields();
        refreshPersistedNodeVersionAggregates();
        if (!mock()) {
            poller.schedule(this::restoreRuntime, 1, TimeUnit.SECONDS);
            poller.scheduleAtFixedRate(this::pollReadiness, 2, 3, TimeUnit.SECONDS);
        }
    }

    /**
     * Brings back the control plane of every persisted cluster after an emulator restart (persistent,
     * hybrid or wal storage; a memory store starts empty). Each cluster goes through
     * {@code RECONCILING} until its API server answers again, and its CA is read anew, since a cluster
     * whose data volume is gone starts with a new one. Clusters that had already failed stay as they were.
     */
    void restoreRuntime() {
        for (StoredCluster cluster : clusterStore.scan(k -> true)) {
            if (cluster.getName() == null || "ERROR".equals(cluster.getStatus())
                    || "STOPPING".equals(cluster.getStatus())) {
                continue;
            }
            String key = clusterKey(cluster.getProject(), cluster.getLocation(), cluster.getName());
            StoredCluster probe = new StoredCluster(cluster);
            String failure = null;
            GkeClusterManager.RestoreOutcome outcome = null;
            try {
                outcome = clusterManager.restoreCluster(probe);
            } catch (Exception e) {
                failure = "Could not restore the control plane of cluster " + cluster.getName() + ": " + e.getMessage();
            }
            synchronized (lifecycleLock) {
                StoredCluster current = clusterStore.get(key).orElse(null);
                if (current == null || !Objects.equals(current.getCreateOperation(), cluster.getCreateOperation())) {
                    continue;
                }
                current.setContainerId(probe.getContainerId());
                current.setHostPort(probe.getHostPort());
                current.setEndpoint(probe.getEndpoint());
                current.setInternalEndpoint(probe.getInternalEndpoint());
                current.setVolumeName(probe.getVolumeName());
                current.setNetworkName(probe.getNetworkName());
                current.setNetworkSubnet(probe.getNetworkSubnet());
                current.setNetworkGateway(probe.getNetworkGateway());
                current.setServerIp(probe.getServerIp());
                if (failure != null) {
                    current.setStatus("ERROR");
                    current.setStatusMessage(failure);
                    operationService.completeOperation(current.getCreateOperation(),
                            operationError(Status.Code.INTERNAL, failure));
                    LOG.warnv("GKE cluster {0}: {1}", cluster.getName(), failure);
                } else {
                    markNodesOutOfDate(current);
                    if (!"PROVISIONING".equals(current.getStatus())) {
                        current.setStatus("RECONCILING");
                        current.setReconcileSince(Instant.now().toString());
                        LOG.infov("Restoring GKE cluster {0} ({1})", cluster.getName(), outcome);
                    }
                    for (StoredNodePool pool : listNodePools(current.getProject(), current.getLocation(), current.getName())) {
                        if ("RUNNING".equals(pool.getStatus()) && pool.getInitialNodeCount() > 0) {
                            pool.setStatus("RECONCILING");
                            nodePoolStore.put(nodePoolKey(current.getProject(), current.getLocation(),
                                    current.getName(), pool.getName()), pool);
                        }
                    }
                }
                clusterStore.put(key, current);
            }
        }
    }

    /** Clusters persisted by a floci-gcp version before node pools had their own store embedded
     * their pools directly on the cluster record (just {@code name}/{@code status}, nothing
     * else). Without this, those pools would simply vanish after an upgrade — the node pool
     * store starts empty and nothing would ever populate it for a pre-existing cluster. Backfills
     * the fields the embedded shape never had — project/location/clusterId/selfLink/etag/status,
     * plus version/locations/initialNodeCount derived from the owning cluster — moves each pool
     * into the node pool store, then clears the embedded list so the cluster records that it has
     * been migrated. Idempotent and resumable: pools already present in the store are skipped
     * individually, so an interrupted run finishes on the next startup, and a pool deleted after
     * migration is not resurrected. */
    private void migrateEmbeddedNodePools() {
        for (StoredCluster cluster : clusterStore.scan(k -> true)) {
            List<StoredNodePool> embedded = cluster.getNodePools();
            if (embedded == null || embedded.isEmpty()) {
                continue;
            }
            int migrated = 0;
            for (StoredNodePool pool : embedded) {
                if (pool.getName() == null || pool.getName().isBlank()) {
                    continue;
                }
                String poolKey = nodePoolKey(
                        cluster.getProject(), cluster.getLocation(), cluster.getName(), pool.getName());
                if (nodePoolStore.get(poolKey).isPresent()) {
                    continue;
                }
                pool.setProject(cluster.getProject());
                pool.setLocation(cluster.getLocation());
                pool.setClusterId(cluster.getName());
                if (pool.getSelfLink() == null) {
                    pool.setSelfLink(nodePoolSelfLink(
                            cluster.getProject(), cluster.getLocation(), cluster.getName(), pool.getName()));
                }
                if (pool.getEtag() == null) {
                    pool.setEtag(newFingerprint());
                }
                if (pool.getStatus() == null) {
                    pool.setStatus("RUNNING");
                }
                if (pool.getInstanceGroupUrls() == null) {
                    pool.setInstanceGroupUrls(List.of());
                }
                if (pool.getConditions() == null) {
                    pool.setConditions(List.of());
                }
                // The embedded shape carried only name and status, so version, locations and
                // initialNodeCount arrive unset. Left alone they would surface through the
                // standalone node pool API as null, null and 0, which reads to a refreshing
                // client as real drift rather than as missing legacy data. Derive them from the
                // owning cluster, which is where a pool created today gets them from anyway.
                if (pool.getVersion() == null) {
                    pool.setVersion(cluster.getCurrentNodeVersion() != null
                            ? cluster.getCurrentNodeVersion() : defaultVersion());
                }
                if (pool.getLocations() == null || pool.getLocations().isEmpty()) {
                    pool.setLocations(cluster.getLocations() != null && !cluster.getLocations().isEmpty()
                            ? List.copyOf(cluster.getLocations())
                            : List.of(cluster.getLocation()));
                }
                if (pool.getInitialNodeCount() <= 0) {
                    pool.setInitialNodeCount(
                            cluster.getInitialNodeCount() > 0 ? cluster.getInitialNodeCount() : 3);
                }
                nodePoolStore.put(poolKey, pool);
                migrated++;
            }
            // Clearing the embedded list is what marks this cluster migrated, and it has to be
            // the marker rather than "the pool store has entries for this cluster": that signal
            // is erased by a later DeleteNodePool, which would let the stale embedded copy
            // resurrect a deleted pool on the next startup. Per-pool skipping above plus this
            // clear also make an interrupted migration resumable — whatever is still embedded is
            // exactly what has not been moved yet.
            cluster.setNodePools(null);
            clusterStore.put(
                    clusterKey(cluster.getProject(), cluster.getLocation(), cluster.getName()), cluster);
            if (migrated > 0) {
                LOG.infov("Migrated {0} embedded node pool(s) for cluster {1} to the node pool store",
                        migrated, cluster.getName());
            }
        }
    }

    /** Clusters stored before {@code id} and the workload pool were typed: an id, and the pool echoed from {@code extraConfig}. */
    private void backfillTypedClusterFields() {
        for (StoredCluster cluster : clusterStore.scan(k -> true)) {
            if (cluster.getName() == null || cluster.getProject() == null || cluster.getLocation() == null) {
                continue;
            }
            boolean changed = false;
            if (cluster.getId() == null) {
                cluster.setId(newClusterId());
                changed = true;
            }
            if (cluster.getExtraConfig() != null && cluster.getExtraConfig().containsKey("workloadIdentityConfig")) {
                Object echoed = cluster.getExtraConfig().get("workloadIdentityConfig");
                if (cluster.getWorkloadPool() == null && echoed instanceof Map<?, ?> m
                        && m.get("workloadPool") instanceof String pool && !pool.isBlank()) {
                    cluster.setWorkloadPool(pool);
                }
                putExtraConfig(cluster, "workloadIdentityConfig", null);
                changed = true;
            }
            if (changed) {
                clusterStore.put(clusterKey(cluster.getProject(), cluster.getLocation(), cluster.getName()), cluster);
            }
        }
    }

    @PreDestroy
    public void shutdown() {
        poller.shutdownNow();
        if (!mock()) {
            for (StoredCluster cluster : clusterStore.scan(k -> true)) {
                clusterManager.stopCluster(cluster);
            }
        }
    }

    // ── Clusters ─────────────────────────────────────────────────────────────

    public StoredOperation createCluster(String project, String location, Map<String, Object> clusterMap) {
        if (clusterMap == null) {
            throw GcpException.invalidArgument("Missing root 'cluster' object");
        }
        locations.requireLocation(location, LocationCatalog.Kind.REGION, LocationCatalog.Kind.ZONE);
        String name = (String) clusterMap.get("name");
        if (name == null || name.isBlank()) {
            throw GcpException.invalidArgument("Cluster name is required");
        }
        if (!VALID_CLUSTER_NAME.matcher(name).matches()) {
            throw GcpException.invalidArgument(
                    "Invalid cluster name: '" + name + "'. Must match " + VALID_CLUSTER_NAME.pattern());
        }
        logIgnoredBetaFields("cluster " + name, GkeBetaFields.setOnCluster(clusterMap));
        String key = clusterKey(project, location, name);
        if (clusterStore.get(key).isPresent()) {
            throw GcpException.alreadyExists("Already exists: cluster " + name);
        }
        // Validate every explicit node pool spec up front — before the cluster or any pool is
        // persisted — so an invalid/duplicate name partway through the list can't leave a
        // half-created cluster behind that a retry then finds AlreadyExists against.
        validateNodePoolSpecs(clusterMap.get("nodePools"));
        Optional<GkeReleases.Channel> channel = GkeReleases.channel(clusterMap.get("releaseChannel"));
        String requestedVersion = optionalStringField(clusterMap, "initialClusterVersion");
        String masterVersion = requestedVersion == null ? GkeReleases.defaultRelease(channel).gkeVersion()
                : resolveVersion("initialClusterVersion", requestedVersion, "Master", channel);
        validateNodePoolVersions(clusterMap.get("nodePools"), masterVersion, channel);
        String workloadPool = workloadPool(project, clusterMap.get("workloadIdentityConfig"));
        if (workloadPool == null && clusterMap.get("autopilot") instanceof Map<?, ?> autopilot
                && Boolean.TRUE.equals(autopilot.get("enabled"))) {
            workloadPool = projectWorkloadPool(project);
        }
        validateWorkloadMetadata(clusterMap, workloadPool);
        String network = stringField(clusterMap, "network", "default");
        GkeNetworking.Ranges ranges = GkeNetworking.resolve(clusterMap, defaultMaxPods(clusterMap),
                takenRanges(project, network));

        StoredCluster cluster = new StoredCluster();
        cluster.setId(newClusterId());
        cluster.setName(name);
        cluster.setProject(project);
        cluster.setLocation(location);
        cluster.setZone(location);
        cluster.setDescription(stringField(clusterMap, "description", null));
        cluster.setCreateTime(Instant.now().toString());
        cluster.setCurrentMasterVersion(masterVersion);
        // Seeds the pools created below; refreshCurrentNodeVersion then derives the aggregate
        // from them, so pools created with an explicit older version are reflected.
        cluster.setCurrentNodeVersion(cluster.getCurrentMasterVersion());
        cluster.setInitialClusterVersion(cluster.getCurrentMasterVersion());
        Object initialNodeCount = clusterMap.get("initialNodeCount");
        cluster.setInitialNodeCount(initialNodeCount instanceof Number n ? n.intValue() : 3);
        cluster.setNetwork(network);
        cluster.setSubnetwork(stringField(clusterMap, "subnetwork", "default"));
        cluster.setClusterIpv4Cidr(ranges.podCidr());
        cluster.setServicesIpv4Cidr(ranges.servicesCidr());
        cluster.setNodePodCidrSize(ranges.nodeMaskSize());
        cluster.setLocations(stringListField(clusterMap, "locations", List.of(location)));
        cluster.setLoggingService(stringField(clusterMap, "loggingService", "logging.googleapis.com/kubernetes"));
        cluster.setMonitoringService(
                stringField(clusterMap, "monitoringService", "monitoring.googleapis.com/kubernetes"));
        cluster.setResourceLabels(labels(clusterMap));
        cluster.setLabelFingerprint(newFingerprint());
        cluster.setCaCertificate("");
        cluster.setSelfLink(clusterSelfLink(project, location, name));
        cluster.setEtag(newFingerprint());
        cluster.setExtraConfig(extraFields(clusterMap, TYPED_CLUSTER_FIELDS));
        cluster.setWorkloadPool(workloadPool);
        if (!ranges.routesBased()) {
            putExtraConfig(cluster, "ipAllocationPolicy", vpcNativePolicy(clusterMap.get("ipAllocationPolicy"), ranges));
        }

        StoredOperation op;
        String startFailure = null;
        if (mock()) {
            cluster.setStatus("RUNNING");
            cluster.setEndpoint("127.0.0.1");
            op = operationService.createOperation(project, location, name, OperationType.CREATE_CLUSTER);
            cluster.setCreateOperation(op.getName());
        } else {
            cluster.setStatus("PROVISIONING");
            cluster.setNodesOutOfDate(true);
            // CREATE_CLUSTER: "The cluster should be assumed to be unusable until the operation
            // finishes" (cluster_service.proto), so the operation stays RUNNING until the readiness
            // poller has the endpoint and CA in place. It exists before the cluster is stored so
            // the poller can never finish the cluster ahead of it.
            op = operationService.createRunningOperation(project, location, name, OperationType.CREATE_CLUSTER);
            cluster.setCreateOperation(op.getName());
            try {
                clusterManager.startCluster(cluster);
            } catch (Exception e) {
                LOG.errorv("Failed to start k3s container for cluster {0}: {1}", name, e.getMessage());
                cluster.setStatus("ERROR");
                startFailure = "Failed to start the control plane for cluster " + name + ": " + e.getMessage();
            }
        }

        synchronized (lifecycleLock) {
            clusterStore.put(key, cluster);
        }
        createInitialNodePools(project, location, name, clusterMap);
        refreshCurrentNodeVersion(project, location, name, cluster);

        if (startFailure == null) {
            return op;
        }
        operationService.completeOperation(op.getName(), operationError(Status.Code.INTERNAL, startFailure));
        return operationService.getOperation(project, location, op.getName());
    }

    /** The {@code ipAllocationPolicy} a VPC-native cluster reports: the request's, with the ranges it got. */
    private static Map<String, Object> vpcNativePolicy(Object requested, GkeNetworking.Ranges ranges) {
        Map<String, Object> policy = new LinkedHashMap<>();
        if (requested instanceof Map<?, ?> m) {
            m.forEach((k, v) -> policy.put(String.valueOf(k), v));
        }
        policy.put("useIpAliases", true);
        policy.put("clusterIpv4Cidr", ranges.podCidr());
        policy.put("clusterIpv4CidrBlock", ranges.podCidr());
        policy.put("servicesIpv4Cidr", ranges.servicesCidr());
        policy.put("servicesIpv4CidrBlock", ranges.servicesCidr());
        policy.putIfAbsent("stackType", "IPV4");
        return policy;
    }

    /** {@code defaultMaxPodsConstraint.maxPodsPerNode}, else GKE's 110. */
    private static int defaultMaxPods(Map<String, Object> clusterMap) {
        Object max = clusterMap.get("defaultMaxPodsConstraint") instanceof Map<?, ?> m ? m.get("maxPodsPerNode") : null;
        return GkeNodes.intValue(max, GkeNodes.DEFAULT_MAX_PODS);
    }

    /** Ranges an automatically chosen Pod range avoids: the other clusters' on the network, and the emulator's address. */
    private List<String> takenRanges(String project, String network) {
        List<String> taken = new ArrayList<>();
        for (StoredCluster other : clusterStore.scan(k -> true)) {
            if (project.equals(other.getProject()) && network.equals(other.getNetwork())) {
                taken.add(other.getClusterIpv4Cidr());
                taken.add(other.getServicesIpv4Cidr());
            }
        }
        if (!mock()) {
            clusterManager.emulatorAddress().ifPresent(taken::add);
        }
        return taken;
    }

    /**
     * {@code workloadIdentityConfig.workloadPool}, or the deprecated v1beta1 {@code identityNamespace}, of a create or
     * update. "Currently, the only accepted workload pool is the workload pool of the Cloud project containing the
     * cluster" ({@code gcloud container clusters create --workload-pool}); an empty pool disables Workload Identity,
     * which is how Terraform disables it. Null when disabled.
     */
    static String workloadPool(String project, Object config) {
        if (config == null) {
            return null;
        }
        if (!(config instanceof Map<?, ?> map)) {
            throw GcpException.invalidArgument("workloadIdentityConfig must be an object");
        }
        Object value = map.get("workloadPool") != null ? map.get("workloadPool") : map.get("identityNamespace");
        if (value == null || "".equals(value)) {
            return null;
        }
        if (!(value instanceof String pool)) {
            throw GcpException.invalidArgument("workloadIdentityConfig.workloadPool must be a string");
        }
        String accepted = projectWorkloadPool(project);
        if (!accepted.equals(pool)) {
            throw GcpException.invalidArgument("Invalid workload pool \"" + pool + "\": the only accepted workload pool is \""
                    + accepted + "\", the workload pool of the cluster's project.");
        }
        return pool;
    }

    /** Every create-time pool's {@code workloadMetadataConfig}, checked before anything is stored. */
    private static void validateWorkloadMetadata(Map<String, Object> clusterMap, String workloadPool) {
        if (clusterMap.get("nodePools") instanceof List<?> pools && !pools.isEmpty()) {
            for (Object pool : pools) {
                if (pool instanceof Map<?, ?> poolMap) {
                    requireWorkloadMetadataMode(asMap(poolMap.get("config")), workloadPool);
                }
            }
            return;
        }
        requireWorkloadMetadataMode(asMap(clusterMap.get("nodeConfig")), workloadPool);
    }

    /** {@code GKE_METADATA} "can only be enabled if Workload Identity is enabled at the cluster level" (cluster_service.proto). */
    private static void requireWorkloadMetadataMode(Map<String, Object> config, String workloadPool) {
        if (GkeNodes.GKE_METADATA.equals(GkeNodes.metadataMode(config)) && workloadPool == null) {
            throw GcpException.invalidArgument("Workload metadata mode GKE_METADATA requires Workload Identity "
                    + "Federation for GKE to be enabled on the cluster (workloadIdentityConfig.workloadPool).");
        }
    }

    /** Whether a pool's nodes run the GKE metadata server: its mode says so and the cluster still has a workload pool. */
    static boolean runsGkeMetadataServer(StoredCluster cluster, StoredNodePool pool) {
        return cluster.getWorkloadPool() != null && GkeNodes.GKE_METADATA.equals(GkeNodes.metadataMode(pool.getConfig()));
    }

    static String projectWorkloadPool(String project) {
        return project + ".svc.id.goog";
    }

    private String newClusterId() {
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }

    private static Map<String, Object> operationError(Status.Code code, String message) {
        return Map.of("code", code.value(), "message", message);
    }

    /** Returns a detached copy carrying the cluster's node pools. The store hands back the live
     * record, so attaching pools to it directly would write a stale pool snapshot back to disk on
     * the next flush — which the startup migration could then replay, resurrecting deleted pools.
     * The read path has to stay side-effect free. */
    public StoredCluster getCluster(String project, String location, String clusterId) {
        StoredCluster stored = clusterStore.get(clusterKey(project, location, clusterId))
                .orElseThrow(() -> GcpException.notFound("Not found: cluster " + clusterId));
        return withNodePools(stored);
    }

    public List<StoredCluster> listClusters(String project, String location) {
        locations.requireListLocation(location, LocationCatalog.Kind.REGION, LocationCatalog.Kind.ZONE);
        return clusterStore.scan(k -> true).stream()
                .filter(c -> project.equals(c.getProject()) && ("-".equals(location) || location.equals(c.getLocation())))
                .map(this::withNodePools)
                .toList();
    }

    private StoredCluster withNodePools(StoredCluster stored) {
        StoredCluster view = new StoredCluster(stored);
        view.setNodePools(listNodePools(stored.getProject(), stored.getLocation(), stored.getName()));
        return view;
    }

    public StoredOperation deleteCluster(String project, String location, String clusterId) {
        String key = clusterKey(project, location, clusterId);
        StoredCluster cluster = clusterStore.get(key)
                .orElseThrow(() -> GcpException.notFound("Not found: cluster " + clusterId));
        // The records go while the cluster lock is held, so a node reconcile waiting on it finds no
        // cluster rather than one whose containers are gone.
        synchronized (clusterLock(key)) {
            if (!mock()) {
                clusterManager.deleteCluster(cluster);
            }
            for (StoredNodePool pool : listNodePools(project, location, clusterId)) {
                nodePoolStore.delete(nodePoolKey(project, location, clusterId, pool.getName()));
            }
            synchronized (lifecycleLock) {
                clusterStore.delete(key);
                operationService.completeOperation(cluster.getCreateOperation(), operationError(Status.Code.CANCELLED,
                        "Cluster " + clusterId + " was deleted before it finished creating"));
            }
        }
        return operationService.createOperation(project, location, clusterId, OperationType.DELETE_CLUSTER);
    }

    /** Generic {@code UpdateCluster} — {@code desired*} fields that back one of
     * {@link StoredCluster}'s own typed fields (locations, logging/monitoring service, node/master
     * version) are applied to that field directly; everything else merges into extraConfig.
     * Typed fields can't just flow through the generic extraConfig merge below: {@code
     * clusterToJson} always reads them from their typed getter, which would silently overwrite
     * whatever the merge wrote under the same JSON key, making the update a no-op that still
     * reports success. */
    public StoredOperation updateCluster(String project, String location, String clusterId,
                                         Map<String, Object> updateMap) {
        StoredCluster cluster = requireCluster(project, location, clusterId);
        StoredOperation upgrade = null;
        if (updateMap != null) {
            // Both version fields are read through optionalStringField, not a cast: Terraform sends
            // desiredMasterVersion on cluster updates, and {"update": {"desiredMasterVersion": 123}}
            // must be a 400 in the GCP error shape, not an unmapped ClassCastException (a 500).
            // Every rejection path for both fields runs before the first mutation: `cluster` and
            // the pools are the live stored objects, so a body carrying a valid desiredNodeVersion
            // and a bad desiredMasterVersion must not leave the node version behind on a 400.
            String desiredNodeVersion = optionalStringField(updateMap, "desiredNodeVersion");
            String desiredMasterVersion = optionalStringField(updateMap, "desiredMasterVersion");
            String desiredLoggingService = optionalStringField(updateMap, "desiredLoggingService");
            String desiredMonitoringService = optionalStringField(updateMap, "desiredMonitoringService");
            boolean workloadIdentityUpdate = updateMap.containsKey("desiredWorkloadIdentityConfig");
            String desiredWorkloadPool = workloadIdentityUpdate
                    ? workloadPool(project, updateMap.get("desiredWorkloadIdentityConfig")) : null;
            List<StoredNodePool> targets = desiredNodeVersion == null ? List.of()
                    : nodeVersionUpdateTargets(project, location, clusterId, updateMap);
            String nodeVersion = desiredNodeVersion == null ? null
                    : resolveNodeVersion("desiredNodeVersion", desiredNodeVersion, cluster);
            // Same aliases as UpdateMaster; gcloud sends "-" here for `clusters upgrade --master`
            // without --cluster-version, which stored verbatim left the cluster reporting "-".
            String masterVersion = desiredMasterVersion == null ? null
                    : resolveMasterVersion("desiredMasterVersion", desiredMasterVersion, cluster);
            if (masterVersion != null) {
                checkMasterUpgrade(cluster.getCurrentMasterVersion(), masterVersion);
                requireReplaceableControlPlane(cluster, masterVersion);
            }
            if (nodeVersion != null) {
                // The pool carries its own `version`, and GetNodePool/ListNodePools read it from
                // the pool store, so moving only the cluster aggregate would report the new
                // version on the cluster while the pool still reported the old one.
                for (StoredNodePool pool : targets) {
                    pool.setVersion(nodeVersion);
                    pool.setEtag(newFingerprint());
                    nodePoolStore.put(nodePoolKey(project, location, clusterId, pool.getName()), pool);
                }
                // currentNodeVersion is the minimum across pools, not the last version written:
                // upgrading one pool of two leaves the aggregate on the other's version.
                cluster.setCurrentNodeVersion(
                        GkeVersions.minimum(poolVersions(project, location, clusterId)).orElse(nodeVersion));
            }
            if (masterVersion != null) {
                upgrade = moveControlPlane(project, location, clusterId, cluster, masterVersion,
                        OperationType.UPDATE_CLUSTER);
            }
            if (updateMap.get("desiredLocations") != null) {
                cluster.setLocations(stringListField(updateMap, "desiredLocations", cluster.getLocations()));
                moveNodeLocations(project, location, clusterId, cluster);
            }
            if (desiredLoggingService != null) {
                cluster.setLoggingService(desiredLoggingService);
            }
            if (desiredMonitoringService != null) {
                cluster.setMonitoringService(desiredMonitoringService);
            }
            if (workloadIdentityUpdate && !Objects.equals(cluster.getWorkloadPool(), desiredWorkloadPool)) {
                Map<String, Boolean> before = new HashMap<>();
                List<StoredNodePool> pools = listNodePools(project, location, clusterId);
                pools.forEach(pool -> before.put(pool.getName(), runsGkeMetadataServer(cluster, pool)));
                cluster.setWorkloadPool(desiredWorkloadPool);
                for (StoredNodePool pool : pools) {
                    if (before.get(pool.getName()) != runsGkeMetadataServer(cluster, pool)) {
                        applyMetadataMode(cluster, pool);
                    }
                }
            }

            Map<String, Object> stripped = stripPrefix(updateMap, "desired");
            // Already applied above to their typed field — drop so they aren't also duplicated,
            // inertly but pointlessly, into extraConfig under the same key.
            stripped.remove("nodeVersion");
            stripped.remove("nodePoolId");
            stripped.remove("masterVersion");
            stripped.remove("locations");
            stripped.remove("loggingService");
            stripped.remove("monitoringService");
            stripped.remove("workloadIdentityConfig");
            mergeExtraConfig(cluster, stripped);
        }
        touch(cluster);
        clusterStore.put(clusterKey(project, location, clusterId), cluster);
        if (upgrade != null) {
            return upgrade;
        }
        return operationService.createOperation(project, location, clusterId, OperationType.UPDATE_CLUSTER);
    }

    /** {@code ClusterManager.UpdateMaster} ({@code POST .../clusters/{id}:updateMaster}).
     *
     * <p>Only the control plane moves: real GKE upgrades the master independently of node
     * pools, so {@code currentNodeVersion} and every pool's own {@code version} are left
     * untouched, unlike {@code UpdateCluster} with {@code desiredNodeVersion}. The proto marks
     * {@code master_version} REQUIRED, so an absent, blank or non-string value is rejected
     * before the cluster is touched. Reported as {@code UPGRADE_MASTER}, the
     * {@code Operation.Type} real GKE uses for a master upgrade. */
    public StoredOperation updateMaster(String project, String location, String clusterId,
                                        Map<String, Object> body) {
        StoredCluster cluster = requireCluster(project, location, clusterId);
        // stringField, not a cast: a syntactically valid body such as {"masterVersion": 123}
        // must come back as 400 INVALID_ARGUMENT, not as an unmapped ClassCastException.
        String masterVersion = body == null ? null : stringField(body, "masterVersion", null);
        if (masterVersion == null) {
            throw GcpException.invalidArgument("masterVersion is required and must be a string");
        }
        String target = resolveMasterVersion("masterVersion", masterVersion, cluster);
        checkMasterUpgrade(cluster.getCurrentMasterVersion(), target);
        requireReplaceableControlPlane(cluster, target);
        StoredOperation upgrade = moveControlPlane(project, location, clusterId, cluster, target,
                OperationType.UPGRADE_MASTER);
        if (upgrade != null) {
            return upgrade;
        }
        touch(cluster);
        clusterStore.put(clusterKey(project, location, clusterId), cluster);
        return operationService.createOperation(project, location, clusterId, OperationType.UPGRADE_MASTER);
    }

    /** A version whose image differs from the running one recreates the control plane, which only
     * a {@code RUNNING} cluster can do; GKE likewise refuses an upgrade while another operation runs. */
    private void requireReplaceableControlPlane(StoredCluster cluster, String target) {
        if (needsNewControlPlane(cluster, target) && !"RUNNING".equals(cluster.getStatus())) {
            throw GcpException.failedPrecondition("Cluster " + cluster.getName() + " is " + cluster.getStatus()
                    + "; its control plane can only be upgraded while it is RUNNING.");
        }
    }

    private boolean needsNewControlPlane(StoredCluster cluster, String target) {
        return !mock() && !clusterManager.imageFor(target).equals(clusterManager.imageFor(cluster.getCurrentMasterVersion()));
    }

    /** Sets the control plane version. When the version runs on another k3s image the control plane
     * container is recreated on it, keeping its data volume, and the returned operation stays
     * {@code RUNNING} while the cluster is {@code RECONCILING}, until its API server answers again.
     * Returns null when only the recorded version changes. */
    private StoredOperation moveControlPlane(String project, String location, String clusterId, StoredCluster cluster,
                                             String target, OperationType type) {
        boolean replace = needsNewControlPlane(cluster, target);
        cluster.setCurrentMasterVersion(target);
        if (!replace) {
            return null;
        }
        touch(cluster);
        StoredOperation op = operationService.createRunningOperation(project, location, clusterId, type);
        String key = clusterKey(project, location, clusterId);
        replacingControlPlanes.add(key);
        try {
            synchronized (clusterLock(key)) {
                synchronized (lifecycleLock) {
                    cluster.setStatus("RECONCILING");
                    cluster.setReconcileSince(Instant.now().toString());
                    cluster.setPendingOperation(op.getName());
                    clusterStore.put(key, cluster);
                }
                try {
                    clusterManager.replaceControlPlane(cluster);
                    clusterStore.put(key, cluster);
                } catch (Exception e) {
                    String failure = "Could not upgrade the control plane of cluster " + clusterId + ": "
                            + e.getMessage();
                    synchronized (lifecycleLock) {
                        cluster.setStatus("ERROR");
                        cluster.setStatusMessage(failure);
                        cluster.setReconcileSince(null);
                        cluster.setPendingOperation(null);
                        clusterStore.put(key, cluster);
                    }
                    operationService.completeOperation(op.getName(), operationError(Status.Code.INTERNAL, failure));
                }
            }
        } finally {
            replacingControlPlanes.remove(key);
        }
        return operationService.getOperation(project, location, op.getName());
    }

    public StoredOperation setLabels(String project, String location, String clusterId,
                                     Map<String, Object> body) {
        StoredCluster cluster = requireCluster(project, location, clusterId);
        cluster.setResourceLabels(labels(body));
        cluster.setLabelFingerprint(newFingerprint());
        touch(cluster);
        clusterStore.put(clusterKey(project, location, clusterId), cluster);
        return operationService.createOperation(project, location, clusterId, OperationType.SET_LABELS);
    }

    public StoredOperation setMasterAuth(String project, String location, String clusterId,
                                         Map<String, Object> body) {
        StoredCluster cluster = requireCluster(project, location, clusterId);
        putExtraConfig(cluster, "masterAuth", body == null ? null : body.get("update"));
        touch(cluster);
        clusterStore.put(clusterKey(project, location, clusterId), cluster);
        return operationService.createOperation(project, location, clusterId, OperationType.SET_MASTER_AUTH);
    }

    public StoredOperation setNetworkPolicy(String project, String location, String clusterId,
                                            Map<String, Object> body) {
        StoredCluster cluster = requireCluster(project, location, clusterId);
        putExtraConfig(cluster, "networkPolicy", body == null ? null : body.get("networkPolicy"));
        touch(cluster);
        clusterStore.put(clusterKey(project, location, clusterId), cluster);
        return operationService.createOperation(project, location, clusterId, OperationType.SET_NETWORK_POLICY);
    }

    public StoredOperation setAddonsConfig(String project, String location, String clusterId,
                                           Map<String, Object> body) {
        StoredCluster cluster = requireCluster(project, location, clusterId);
        putExtraConfig(cluster, "addonsConfig", body == null ? null : body.get("addonsConfig"));
        touch(cluster);
        clusterStore.put(clusterKey(project, location, clusterId), cluster);
        return operationService.createOperation(project, location, clusterId, OperationType.UPDATE_CLUSTER);
    }

    public StoredOperation setLoggingService(String project, String location, String clusterId,
                                             Map<String, Object> body) {
        StoredCluster cluster = requireCluster(project, location, clusterId);
        cluster.setLoggingService(body == null ? null : (String) body.get("loggingService"));
        touch(cluster);
        clusterStore.put(clusterKey(project, location, clusterId), cluster);
        return operationService.createOperation(project, location, clusterId, OperationType.UPDATE_CLUSTER);
    }

    public StoredOperation setMonitoringService(String project, String location, String clusterId,
                                                Map<String, Object> body) {
        StoredCluster cluster = requireCluster(project, location, clusterId);
        cluster.setMonitoringService(body == null ? null : (String) body.get("monitoringService"));
        touch(cluster);
        clusterStore.put(clusterKey(project, location, clusterId), cluster);
        return operationService.createOperation(project, location, clusterId, OperationType.UPDATE_CLUSTER);
    }

    public StoredOperation setLocations(String project, String location, String clusterId,
                                        Map<String, Object> body) {
        StoredCluster cluster = requireCluster(project, location, clusterId);
        cluster.setLocations(stringListField(body == null ? Map.of() : body, "locations", cluster.getLocations()));
        moveNodeLocations(project, location, clusterId, cluster);
        touch(cluster);
        clusterStore.put(clusterKey(project, location, clusterId), cluster);
        return operationService.createOperation(project, location, clusterId, OperationType.UPDATE_CLUSTER);
    }

    public StoredOperation setLegacyAbac(String project, String location, String clusterId,
                                        Map<String, Object> body) {
        StoredCluster cluster = requireCluster(project, location, clusterId);
        boolean enabled = body != null && Boolean.TRUE.equals(body.get("enabled"));
        putExtraConfig(cluster, "legacyAbac", Map.of("enabled", enabled));
        touch(cluster);
        clusterStore.put(clusterKey(project, location, clusterId), cluster);
        return operationService.createOperation(project, location, clusterId, OperationType.UPDATE_CLUSTER);
    }

    /** An empty {@code maintenancePolicy} in the request clears the existing policy, matching
     * real GKE's documented semantics for this RPC. */
    public StoredOperation setMaintenancePolicy(String project, String location, String clusterId,
                                                Map<String, Object> body) {
        StoredCluster cluster = requireCluster(project, location, clusterId);
        Object policy = body == null ? null : body.get("maintenancePolicy");
        putExtraConfig(cluster, "maintenancePolicy", (policy instanceof Map<?, ?> m && !m.isEmpty()) ? policy : null);
        touch(cluster);
        clusterStore.put(clusterKey(project, location, clusterId), cluster);
        return operationService.createOperation(project, location, clusterId, OperationType.SET_MAINTENANCE_POLICY);
    }

    /** floci-gcp does not serve a real dual-cert rotation window — there is no live client
     * traffic to migrate off the old certificate, so this only acknowledges the request
     * (bumping the fingerprint/etag) rather than performing an actual CA rotation. */
    public StoredOperation startIpRotation(String project, String location, String clusterId) {
        StoredCluster cluster = requireCluster(project, location, clusterId);
        touch(cluster);
        clusterStore.put(clusterKey(project, location, clusterId), cluster);
        return operationService.createOperation(project, location, clusterId, OperationType.UPDATE_CLUSTER);
    }

    public StoredOperation completeIpRotation(String project, String location, String clusterId) {
        requireCluster(project, location, clusterId);
        return operationService.createOperation(project, location, clusterId, OperationType.UPDATE_CLUSTER);
    }

    /** {@code CompleteNodePoolUpgrade} returns {@code google.protobuf.Empty}, not an Operation
     * (cluster_service.proto#L382-L388) — unlike {@code RollbackNodePoolUpgrade}, which really
     * does return one. No real node-version upgrade is in flight in this emulator (node pools
     * don't run real node VMs), so this is a validated acknowledgment with no state to change
     * and no operation to report. */
    public void completeNodePoolUpgrade(String project, String location, String clusterId,
                                        String nodePoolId) {
        requireNodePool(project, location, clusterId, nodePoolId);
    }

    public StoredOperation rollbackNodePoolUpgrade(String project, String location, String clusterId,
                                                   String nodePoolId) {
        requireNodePool(project, location, clusterId, nodePoolId);
        return operationService.createNodePoolOperation(
                project, location, clusterId, nodePoolId, OperationType.UPGRADE_NODES);
    }

    /** {@code container.v1.ClusterManager.GetServerConfig} — the version/channel info gcloud
     * and some SDKs check before create/upgrade calls. Every version listed is one the emulator can
     * run: one per Kubernetes minor {@link GkeReleases} pins a k3s release for. The default is the
     * REGULAR channel's, as on GKE. */
    public Map<String, Object> getServerConfig() {
        Map<String, Object> config = new HashMap<>();
        config.put("defaultClusterVersion", GkeReleases.defaultRelease(Optional.empty()).gkeVersion());
        config.put("validNodeVersions", GkeReleases.validNodeVersions());
        config.put("defaultImageType", "COS_CONTAINERD");
        config.put("validImageTypes", List.of("COS_CONTAINERD", "UBUNTU_CONTAINERD"));
        config.put("validMasterVersions", GkeReleases.validMasterVersions());
        List<Map<String, Object>> channels = new ArrayList<>();
        for (GkeReleases.Channel channel : GkeReleases.Channel.values()) {
            channels.add(releaseChannelConfig(channel));
        }
        config.put("channels", channels);
        return config;
    }

    private Map<String, Object> releaseChannelConfig(GkeReleases.Channel channel) {
        String defaultVersion = GkeReleases.defaultRelease(Optional.of(channel)).gkeVersion();
        Map<String, Object> config = new HashMap<>();
        config.put("channel", channel.name());
        config.put("defaultVersion", defaultVersion);
        config.put("validVersions", GkeReleases.channelVersions(channel));
        config.put("upgradeTargetVersion", defaultVersion);
        return config;
    }

    /** {@code GetJSONWebKeys} — floci-gcp does not issue real, independently-verifiable
     * workload-identity tokens (any bearer token is accepted, per the emulator's auth-bypass
     * model), so there is no real signing key to expose. Returns an empty key set rather than a
     * fabricated key, since a fabricated key would misleadingly imply real token verification is
     * possible against it. */
    public Map<String, Object> getJsonWebKeys(String project, String location, String clusterId) {
        requireCluster(project, location, clusterId);
        return Map.of("keys", List.of());
    }

    /** {@code ListUsableSubnetworks} — floci-gcp does not emulate Compute Engine/VPC, so there is
     * no real subnetwork inventory. Returns the single synthetic "default" network/subnetwork
     * every cluster in this emulator already defaults to, for tools that expect at least one
     * usable entry rather than an empty list. */
    public Map<String, Object> listUsableSubnetworks(String project) {
        Map<String, Object> subnetwork = new HashMap<>();
        subnetwork.put("subnetwork", "projects/" + project + "/regions/us-central1/subnetworks/default");
        subnetwork.put("network", "projects/" + project + "/global/networks/default");
        subnetwork.put("ipCidrRange", "10.0.0.0/20");
        subnetwork.put("secondaryIpRanges", List.of());
        Map<String, Object> result = new HashMap<>();
        result.put("subnetworks", List.of(subnetwork));
        return result;
    }

    /** {@code CheckAutopilotCompatibility} — floci-gcp does not perform real Autopilot
     * compatibility analysis (no real node scheduling, no policy engine); reports no issues
     * rather than fabricating findings it cannot actually verify. */
    public Map<String, Object> checkAutopilotCompatibility(String project, String location, String clusterId) {
        requireCluster(project, location, clusterId);
        Map<String, Object> result = new HashMap<>();
        result.put("issues", List.of());
        result.put("summary", "No compatibility issues found (floci-gcp does not perform real Autopilot "
                + "compatibility analysis; this cluster's configuration was not evaluated against a real "
                + "policy engine)");
        return result;
    }

    /** {@code FetchClusterUpgradeInfo} — floci-gcp has a single fixed master version, so there is
     * never a real upgrade target; reports the current version as both current and target with no
     * upgrade pending, rather than fabricating an upgrade path that doesn't exist. */
    public Map<String, Object> fetchClusterUpgradeInfo(String project, String location, String clusterId) {
        StoredCluster cluster = requireCluster(project, location, clusterId);
        return upgradeInfo(cluster.getCurrentMasterVersion());
    }

    public Map<String, Object> fetchNodePoolUpgradeInfo(String project, String location, String clusterId,
                                                        String nodePoolId) {
        StoredNodePool pool = requireNodePool(project, location, clusterId, nodePoolId);
        return upgradeInfo(pool.getVersion());
    }

    private Map<String, Object> upgradeInfo(String currentVersion) {
        Map<String, Object> info = new HashMap<>();
        info.put("minorTargetVersion", currentVersion);
        info.put("patchTargetVersion", currentVersion);
        info.put("autoUpgradeStatus", List.of());
        info.put("pausedReason", List.of());
        info.put("upgradeDetails", List.of());
        return info;
    }

    // ── Node pools ───────────────────────────────────────────────────────────

    public StoredOperation createNodePool(String project, String location, String clusterId,
                                          Map<String, Object> nodePoolMap) {
        StoredCluster cluster = requireCluster(project, location, clusterId);
        StoredNodePool pool = createNodePoolInternal(project, location, clusterId, nodePoolMap);
        logIgnoredBetaFields("node pool " + pool.getName(), GkeBetaFields.setOnNodePool(nodePoolMap));
        refreshCurrentNodeVersion(project, location, clusterId, cluster);
        if (mock()) {
            return operationService.createNodePoolOperation(
                    project, location, clusterId, pool.getName(), OperationType.CREATE_NODE_POOL);
        }
        return awaitNodes(project, location, clusterId, pool, OperationType.CREATE_NODE_POOL);
    }

    /** Hands a pool whose nodes must change to the reconciler, with an operation that stays {@code RUNNING}
     * until they are all Ready. */
    private StoredOperation awaitNodes(String project, String location, String clusterId, StoredNodePool pool,
                                       OperationType type) {
        StoredOperation op = operationService.createRunningNodePoolOperation(project, location, clusterId,
                pool.getName(), type);
        String clusterKey = clusterKey(project, location, clusterId);
        synchronized (lifecycleLock) {
            operationService.completeOperation(pool.getPendingOperation(), null);
            pool.setPendingOperation(op.getName());
            if (!"PROVISIONING".equals(pool.getStatus())) {
                pool.setStatus("RECONCILING");
            }
            nodePoolStore.put(nodePoolKey(project, location, clusterId, pool.getName()), pool);
            clusterStore.get(clusterKey).ifPresent(cluster -> {
                markNodesOutOfDate(cluster);
                clusterStore.put(clusterKey, cluster);
            });
        }
        return op;
    }

    /**
     * Brings a pool's running nodes to its metadata mode in place, as GKE applies a {@code workloadMetadataConfig}
     * change to a pool "immediately": the GKE metadata server label goes on or off. Nodes are not recreated.
     */
    private void applyMetadataMode(StoredCluster cluster, StoredNodePool pool) {
        if (mock() || !"RUNNING".equals(cluster.getStatus()) || pool.getNodes() == null) {
            return;
        }
        boolean gkeMetadata = runsGkeMetadataServer(cluster, pool);
        for (StoredNode node : pool.getNodes()) {
            try {
                if (gkeMetadata) {
                    clusterManager.labelNode(cluster, node.getName(), Map.of(GkeNodes.GKE_METADATA_SERVER_LABEL, "true"));
                } else {
                    clusterManager.unlabelNode(cluster, node.getName(), List.of(GkeNodes.GKE_METADATA_SERVER_LABEL));
                }
            } catch (Exception e) {
                LOG.warnv("Could not apply the metadata mode of node pool {0} to node {1}: {2}", pool.getName(),
                        node.getName(), e.getMessage());
            }
        }
    }

    /** {@code SetLocations} and {@code desiredLocations} move every node pool to the cluster's new node locations. */
    private void moveNodeLocations(String project, String location, String clusterId, StoredCluster cluster) {
        for (StoredNodePool pool : listNodePools(project, location, clusterId)) {
            pool.setLocations(cluster.getLocations() == null ? List.of(location) : List.copyOf(cluster.getLocations()));
            pool.setInstanceGroupUrls(instanceGroupUrls(project, location, clusterId, pool));
            if (!mock() && !"PROVISIONING".equals(pool.getStatus())) {
                pool.setStatus("RECONCILING");
            }
            nodePoolStore.put(nodePoolKey(project, location, clusterId, pool.getName()), pool);
        }
        if (!mock()) {
            markNodesOutOfDate(cluster);
        }
    }

    private static List<String> instanceGroupUrls(String project, String location, String clusterId,
                                                  StoredNodePool pool) {
        return GkeNodes.zones(pool.getLocations(), location).stream()
                .map(zone -> GkeNodes.instanceGroupUrl(project, zone, clusterId, pool.getName()))
                .toList();
    }

    public StoredNodePool getNodePool(String project, String location, String clusterId, String nodePoolId) {
        return nodePoolStore.get(nodePoolKey(project, location, clusterId, nodePoolId))
                .orElseThrow(() -> GcpException.notFound("Not found: nodePool " + nodePoolId));
    }

    public List<StoredNodePool> listNodePools(String project, String location, String clusterId) {
        return nodePoolStore.scan(k -> true).stream()
                .filter(p -> project.equals(p.getProject()) && location.equals(p.getLocation())
                        && clusterId.equals(p.getClusterId()))
                .toList();
    }

    public StoredOperation deleteNodePool(String project, String location, String clusterId, String nodePoolId) {
        String key = nodePoolKey(project, location, clusterId, nodePoolId);
        StoredNodePool doomed = nodePoolStore.get(key)
                .orElseThrow(() -> GcpException.notFound("Not found: nodePool " + nodePoolId));
        String clusterKey = clusterKey(project, location, clusterId);
        if (!mock()) {
            synchronized (clusterLock(clusterKey)) {
                removeNodes(clusterStore.get(clusterKey).orElse(null), doomed.getNodes());
                nodePoolStore.delete(key);
                clusterStore.get(clusterKey).ifPresent(cluster -> {
                    markNodesOutOfDate(cluster);
                    clusterStore.put(clusterKey, cluster);
                });
            }
            operationService.completeOperation(doomed.getPendingOperation(), operationError(Status.Code.CANCELLED,
                    "Node pool " + nodePoolId + " was deleted"));
        }
        nodePoolStore.delete(key);
        clusterStore.get(clusterKey(project, location, clusterId))
                .ifPresent(cluster -> refreshCurrentNodeVersion(project, location, clusterId, cluster));
        return operationService.createNodePoolOperation(
                project, location, clusterId, nodePoolId, OperationType.DELETE_NODE_POOL);
    }

    /** {@code ClusterManager.UpdateNodePool} ({@code PUT .../nodePools/{id}}).
     *
     * <p>{@code node_version} documents the same aliases as {@code desired_node_version}
     * ({@code "latest"}, {@code "-"}, {@code "1.X"}, {@code "1.X.Y"}), so it goes through the
     * same resolver: stored verbatim, a pool upgraded with {@code "latest"} or {@code "-"} read
     * back that alias as its version. The resolver needs the cluster because {@code "-"} picks
     * its master version, and the request is resolved before the pool is mutated so a rejected
     * value leaves the pool exactly as it was. */
    public StoredOperation updateNodePool(String project, String location, String clusterId, String nodePoolId,
                                          Map<String, Object> body) {
        StoredCluster cluster = requireCluster(project, location, clusterId);
        StoredNodePool pool = requireNodePool(project, location, clusterId, nodePoolId);
        boolean newImage = false;
        if (body != null) {
            Map<String, Object> metadataUpdate = null;
            if (body.get("workloadMetadataConfig") != null) {
                metadataUpdate = asMap(body.get("workloadMetadataConfig"));
                if (metadataUpdate == null) {
                    throw GcpException.invalidArgument("workloadMetadataConfig must be an object");
                }
                requireWorkloadMetadataMode(Map.of("workloadMetadataConfig", metadataUpdate), cluster.getWorkloadPool());
            }
            String nodeVersion = optionalStringField(body, "nodeVersion");
            if (nodeVersion != null) {
                String resolved = resolveNodeVersion("nodeVersion", nodeVersion, cluster);
                newImage = !mock() && !clusterManager.imageFor(resolved).equals(clusterManager.imageFor(pool.getVersion()));
                pool.setVersion(resolved);
            }
            if (body.get("upgradeSettings") != null) {
                pool.setUpgradeSettings(asMap(body.get("upgradeSettings")));
            }
            if (metadataUpdate != null) {
                boolean before = runsGkeMetadataServer(cluster, pool);
                Map<String, Object> config = pool.getConfig() == null ? new HashMap<>() : new HashMap<>(pool.getConfig());
                config.put("workloadMetadataConfig", metadataUpdate);
                pool.setConfig(config);
                if (before != runsGkeMetadataServer(cluster, pool)) {
                    applyMetadataMode(cluster, pool);
                }
            }
        }
        pool.setEtag(newFingerprint());
        nodePoolStore.put(nodePoolKey(project, location, clusterId, nodePoolId), pool);
        refreshCurrentNodeVersion(project, location, clusterId, cluster);
        if (newImage) {
            return awaitNodes(project, location, clusterId, pool, OperationType.UPGRADE_NODES);
        }
        return operationService.createNodePoolOperation(
                project, location, clusterId, nodePoolId, OperationType.UPGRADE_NODES);
    }

    public StoredOperation setNodePoolAutoscaling(String project, String location, String clusterId,
                                                  String nodePoolId, Map<String, Object> body) {
        StoredNodePool pool = requireNodePool(project, location, clusterId, nodePoolId);
        pool.setAutoscaling(asMap(body == null ? null : body.get("autoscaling")));
        pool.setEtag(newFingerprint());
        nodePoolStore.put(nodePoolKey(project, location, clusterId, nodePoolId), pool);
        return operationService.createNodePoolOperation(
                project, location, clusterId, nodePoolId, OperationType.UPDATE_CLUSTER);
    }

    public StoredOperation setNodePoolManagement(String project, String location, String clusterId,
                                                 String nodePoolId, Map<String, Object> body) {
        StoredNodePool pool = requireNodePool(project, location, clusterId, nodePoolId);
        pool.setManagement(asMap(body == null ? null : body.get("management")));
        pool.setEtag(newFingerprint());
        nodePoolStore.put(nodePoolKey(project, location, clusterId, nodePoolId), pool);
        return operationService.createNodePoolOperation(
                project, location, clusterId, nodePoolId, OperationType.SET_NODE_POOL_MANAGEMENT);
    }

    public StoredOperation setNodePoolSize(String project, String location, String clusterId,
                                           String nodePoolId, Map<String, Object> body) {
        StoredNodePool pool = requireNodePool(project, location, clusterId, nodePoolId);
        Object nodeCount = body == null ? null : body.get("nodeCount");
        if (nodeCount instanceof Number n && n.intValue() < 0) {
            throw GcpException.invalidArgument("nodeCount must not be negative");
        }
        boolean resized = nodeCount instanceof Number n && n.intValue() != pool.getInitialNodeCount();
        if (nodeCount instanceof Number n) {
            pool.setInitialNodeCount(n.intValue());
        }
        pool.setEtag(newFingerprint());
        nodePoolStore.put(nodePoolKey(project, location, clusterId, nodePoolId), pool);
        if (resized && !mock()) {
            return awaitNodes(project, location, clusterId, pool, OperationType.SET_NODE_POOL_SIZE);
        }
        return operationService.createNodePoolOperation(
                project, location, clusterId, nodePoolId, OperationType.SET_NODE_POOL_SIZE);
    }

    // ── Operations ───────────────────────────────────────────────────────────

    public List<StoredOperation> listOperations(String project, String location) {
        return operationService.listOperations(project, location);
    }

    public StoredOperation getOperation(String project, String location, String operationId) {
        return operationService.getOperation(project, location, operationId);
    }

    public String kubeConfig(String project, String location, String clusterId) {
        StoredCluster cluster = getCluster(project, location, clusterId);
        return mock() ? "" : clusterManager.kubeConfig(cluster);
    }

    // ── Internal ─────────────────────────────────────────────────────────────

    /** Validates every entry of an explicit {@code nodePools[]} create-time spec — name present,
     * pattern-valid, and unique within the list — without persisting anything, so a bad entry
     * partway through the list can be rejected before the cluster or any earlier pool exists. */
    private void validateNodePoolSpecs(Object requestedPools) {
        if (!(requestedPools instanceof List<?> list) || list.isEmpty()) {
            return;
        }
        Set<String> seen = new HashSet<>();
        for (Object poolObj : list) {
            if (!(poolObj instanceof Map<?, ?> poolMap)) {
                throw GcpException.invalidArgument("Each entry in 'nodePools' must be an object");
            }
            Object nameObj = poolMap.get("name");
            if (!(nameObj instanceof String poolName) || poolName.isBlank()) {
                throw GcpException.invalidArgument("Node pool name is required");
            }
            if (!VALID_NODE_POOL_NAME.matcher(poolName).matches()) {
                throw GcpException.invalidArgument(
                        "Invalid node pool name: '" + poolName + "'. Must match " + VALID_NODE_POOL_NAME.pattern());
            }
            if (!seen.add(poolName)) {
                throw GcpException.alreadyExists("Already exists: nodePool " + poolName);
            }
        }
    }

    /** Resolves the explicit {@code version} of every create-time node pool against the control plane
     * version before anything is persisted, so a pool GKE would reject fails the whole create. */
    private static void validateNodePoolVersions(Object requestedPools, String masterVersion,
                                                 Optional<GkeReleases.Channel> channel) {
        if (!(requestedPools instanceof List<?> list)) {
            return;
        }
        for (Object poolObj : list) {
            if (poolObj instanceof Map<?, ?> poolMap && poolMap.get("version") instanceof String version
                    && !version.isBlank()) {
                String resolved = "-".equals(version) ? masterVersion
                        : resolveVersion("version", version, "Node", channel);
                checkNodeSkew(resolved, masterVersion);
            }
        }
    }

    private void createInitialNodePools(String project, String location, String clusterName,
                                        Map<String, Object> clusterMap) {
        Object requestedPools = clusterMap.get("nodePools");
        if (requestedPools instanceof List<?> list && !list.isEmpty()) {
            for (Object poolObj : list) {
                createNodePoolInternal(project, location, clusterName, (Map<String, Object>) poolObj);
            }
            return;
        }

        Map<String, Object> defaultPool = new HashMap<>();
        defaultPool.put("name", DEFAULT_NODE_POOL_NAME);
        Object initialNodeCount = clusterMap.get("initialNodeCount");
        defaultPool.put("initialNodeCount", initialNodeCount != null ? initialNodeCount : 3);
        Object nodeConfig = clusterMap.get("nodeConfig");
        if (nodeConfig != null) {
            defaultPool.put("config", nodeConfig);
        }
        createNodePoolInternal(project, location, clusterName, defaultPool);
    }

    @SuppressWarnings("unchecked")
    private StoredNodePool createNodePoolInternal(String project, String location, String clusterId,
                                                  Map<String, Object> poolMap) {
        if (poolMap == null) {
            throw GcpException.invalidArgument("Missing node pool object");
        }
        String name = (String) poolMap.get("name");
        if (name == null || name.isBlank()) {
            throw GcpException.invalidArgument("Node pool name is required");
        }
        if (!VALID_NODE_POOL_NAME.matcher(name).matches()) {
            throw GcpException.invalidArgument(
                    "Invalid node pool name: '" + name + "'. Must match " + VALID_NODE_POOL_NAME.pattern());
        }
        String key = nodePoolKey(project, location, clusterId, name);
        if (nodePoolStore.get(key).isPresent()) {
            throw GcpException.alreadyExists("Already exists: nodePool " + name);
        }

        StoredNodePool pool = new StoredNodePool();
        pool.setName(name);
        pool.setProject(project);
        pool.setLocation(location);
        pool.setClusterId(clusterId);
        Object initialNodeCount = poolMap.get("initialNodeCount");
        pool.setInitialNodeCount(initialNodeCount instanceof Number n ? n.intValue() : 3);
        List<String> clusterLocations = clusterStore.get(clusterKey(project, location, clusterId))
                .map(StoredCluster::getLocations)
                .filter(l -> l != null && !l.isEmpty())
                .orElse(List.of(location));
        pool.setLocations(stringListField(poolMap, "locations", List.copyOf(clusterLocations)));
        // Default the version from the owning cluster, not the build-time constant: after an
        // UpdateCluster carrying desiredNodeVersion, the cluster and its existing pools have
        // moved off the default version, and a pool created afterwards without an explicit
        // version would otherwise come back on the old one and read as drift.
        String clusterNodeVersion = clusterStore.get(clusterKey(project, location, clusterId))
                .map(StoredCluster::getCurrentNodeVersion)
                .filter(v -> v != null && !v.isBlank())
                .orElse(defaultVersion());
        String requestedVersion = optionalStringField(poolMap, "version");
        pool.setVersion(requestedVersion == null ? clusterNodeVersion
                : clusterStore.get(clusterKey(project, location, clusterId))
                        .map(cluster -> resolveNodeVersion("version", requestedVersion, cluster))
                        .orElse(requestedVersion));
        pool.setStatus(mock() ? "RUNNING" : "PROVISIONING");
        pool.setCreateTime(Instant.now().toString());
        pool.setSelfLink(nodePoolSelfLink(project, location, clusterId, name));
        pool.setEtag(newFingerprint());
        pool.setInstanceGroupUrls(instanceGroupUrls(project, location, clusterId, pool));
        pool.setMaxPodsConstraint(asMap(poolMap.get("maxPodsConstraint")));
        pool.setConditions(List.of());
        pool.setConfig(asMap(poolMap.get("config")));
        String clusterWorkloadPool = clusterStore.get(clusterKey(project, location, clusterId))
                .map(StoredCluster::getWorkloadPool).orElse(null);
        requireWorkloadMetadataMode(pool.getConfig(), clusterWorkloadPool);
        if (clusterWorkloadPool != null && GkeNodes.metadataMode(pool.getConfig()) == null) {
            Map<String, Object> config = pool.getConfig() == null ? new HashMap<>() : pool.getConfig();
            config.put("workloadMetadataConfig", Map.of("mode", GkeNodes.GKE_METADATA));
            pool.setConfig(config);
        }
        pool.setGpuTaint(GkeNodes.hasGpus(pool.getConfig()) && listNodePools(project, location, clusterId).stream()
                .anyMatch(other -> !GkeNodes.hasGpus(other.getConfig())));
        pool.setAutoscaling(asMap(poolMap.get("autoscaling")));
        pool.setManagement(asMap(poolMap.get("management")));
        pool.setUpgradeSettings(asMap(poolMap.get("upgradeSettings")));
        pool.setPlacementPolicy(asMap(poolMap.get("placementPolicy")));
        pool.setNetworkConfig(asMap(poolMap.get("networkConfig")));

        nodePoolStore.put(key, pool);
        return pool;
    }

    private StoredCluster requireCluster(String project, String location, String clusterId) {
        return clusterStore.get(clusterKey(project, location, clusterId))
                .orElseThrow(() -> GcpException.notFound("Not found: cluster " + clusterId));
    }

    /**
     * {@code Cluster.currentNodeVersion} is an aggregate over the pools: "the minimum version of
     * all nodes" (cluster_service.proto, {@code Cluster.current_node_version}). Recomputed from the
     * stored pools whenever the pool set or a pool's version changes, so the cluster never reports
     * a version no pool runs. A cluster with no pools keeps whatever it already reports.
     */
    private void refreshCurrentNodeVersion(String project, String location, String clusterId, StoredCluster cluster) {
        GkeVersions.minimum(poolVersions(project, location, clusterId)).ifPresent(minimum -> {
            if (!minimum.equals(cluster.getCurrentNodeVersion())) {
                cluster.setCurrentNodeVersion(minimum);
                clusterStore.put(clusterKey(project, location, clusterId), cluster);
            }
        });
    }

    private List<String> poolVersions(String project, String location, String clusterId) {
        return listNodePools(project, location, clusterId).stream().map(StoredNodePool::getVersion).toList();
    }

    /** Clusters persisted by a build that wrote {@code currentNodeVersion} only from
     * {@code CreateCluster} and {@code UpdateCluster} may carry a stale aggregate. One pass at
     * startup, after the embedded pools have moved into the pool store, brings every cluster to
     * the minimum over its pools, so a read after the upgrade is right without waiting for the
     * next pool mutation. Idempotent: a cluster already at the minimum is not rewritten. */
    private void refreshPersistedNodeVersionAggregates() {
        for (StoredCluster cluster : clusterStore.scan(k -> true)) {
            if (cluster.getProject() == null || cluster.getLocation() == null || cluster.getName() == null) {
                continue;
            }
            refreshCurrentNodeVersion(cluster.getProject(), cluster.getLocation(), cluster.getName(), cluster);
        }
    }

    private StoredNodePool requireNodePool(String project, String location, String clusterId, String nodePoolId) {
        return nodePoolStore.get(nodePoolKey(project, location, clusterId, nodePoolId))
                .orElseThrow(() -> GcpException.notFound("Not found: nodePool " + nodePoolId));
    }

    /** Resolves the version spellings {@code master_version} accepts (cluster_service.proto,
     * {@code UpdateMasterRequest}): {@code "latest"} and {@code "-"} pick the highest valid and the
     * default version of the cluster's release channel (REGULAR without one), and {@code "1.X"} /
     * {@code "1.X.Y"} pick the version {@link GkeReleases} advertises for that minor when it matches.
     * An explicit {@code 1.X.Y-gke.N} of a supported minor is kept verbatim even when it is not the
     * advertised one, so clients pinning a specific version keep working; it runs on that minor's
     * image. A supported minor is one {@link GkeReleases} pins a k3s release for; anything else is
     * rejected the way GKE rejects a retired version.
     *
     * <p>Anything outside those five shapes is rejected. The prefix test alone would also accept
     * a bare major ({@code "1"} is a character prefix of {@code "1.30..."}), which the field does
     * not document and real GKE rejects. {@code field} is the request field the value came from,
     * so the rejection names the field the client actually sent. */
    private static String resolveVersion(String field, String requested, String kind,
                                         Optional<GkeReleases.Channel> channel) {
        if ("latest".equals(requested)) {
            return GkeReleases.latestRelease(channel).gkeVersion();
        }
        if ("-".equals(requested)) {
            return GkeReleases.defaultRelease(channel).gkeVersion();
        }
        if (VERSION_PREFIX_ALIAS.matcher(requested).matches()) {
            Optional<GkeReleases.Release> release = GkeReleases.forVersion(requested);
            if (release.isPresent() && (requested.equals(release.get().minorVersion())
                    || release.get().gkeVersion().startsWith(requested + "-"))) {
                return release.get().gkeVersion();
            }
            throw GcpException.invalidArgument("No valid versions with the prefix \"" + requested + "\" found.");
        }
        if (EXPLICIT_VERSION.matcher(requested).matches()) {
            if (GkeReleases.forVersion(requested).isPresent()) {
                return requested;
            }
            throw GcpException.invalidArgument(kind + " version \"" + requested + "\" is unsupported.");
        }
        throw GcpException.invalidArgument("Invalid " + field + " \"" + requested
                + "\": expected \"latest\", \"-\", \"1.X\", \"1.X.Y\" or \"1.X.Y-gke.N\"");
    }

    private static String resolveMasterVersion(String field, String requested, StoredCluster cluster) {
        return resolveVersion(field, requested, "Master", channelOf(cluster));
    }

    /** {@code desired_node_version} documents the same spellings as {@code master_version}
     * with one difference: {@code "-"} "picks the Kubernetes master version", the cluster's
     * current control plane version, not the server default that {@code "-"} means on the master
     * field. The proto allows one {@code ClusterUpdate} field per request, so this reads the
     * master version as stored, not one the same request might also be changing. */
    private static String resolveNodeVersion(String field, String requested, StoredCluster cluster) {
        String version = "-".equals(requested) ? cluster.getCurrentMasterVersion()
                : resolveVersion(field, requested, "Node", channelOf(cluster));
        checkNodeSkew(version, cluster.getCurrentMasterVersion());
        return version;
    }

    /** Nodes never run a newer minor than the control plane, nor one more than two minors older
     * (GKE "Upgrade a cluster": control planes are compatible with nodes up to two minor versions
     * earlier). Versions the emulator cannot parse are left alone. */
    private static void checkNodeSkew(String nodeVersion, String masterVersion) {
        int node = GkeReleases.minorOf(nodeVersion);
        int master = GkeReleases.minorOf(masterVersion);
        if (node < 0 || master < 0) {
            return;
        }
        if (node > master) {
            throw GcpException.invalidArgument("Node version \"" + nodeVersion
                    + "\" must not be newer than the control plane version \"" + masterVersion + "\".");
        }
        if (master - node > 2) {
            throw GcpException.invalidArgument("Node version \"" + nodeVersion
                    + "\" is more than two minor versions older than the control plane version \""
                    + masterVersion + "\".");
        }
    }

    /** The control plane moves up one minor at a time and never down a minor; a patch downgrade
     * within its minor is allowed (GKE "Upgrade a cluster"). A cluster still on a minor this build no
     * longer supports may move to any supported one. */
    private static void checkMasterUpgrade(String current, String target) {
        int from = GkeReleases.minorOf(current);
        int to = GkeReleases.minorOf(target);
        if (from < 0 || to < 0 || GkeReleases.forMinor(from).isEmpty()) {
            return;
        }
        if (to < from) {
            throw GcpException.invalidArgument("Master cannot be upgraded to \"" + target
                    + "\": specified version is not newer than the current version.");
        }
        if (to > from + 1) {
            throw GcpException.invalidArgument("Master cannot be upgraded to \"" + target
                    + "\": the control plane can only be upgraded one minor version at a time, and it runs \""
                    + current + "\".");
        }
    }

    private static Optional<GkeReleases.Channel> channelOf(StoredCluster cluster) {
        return GkeReleases.channel(cluster.getExtraConfig() == null ? null
                : cluster.getExtraConfig().get("releaseChannel"));
    }

    /** The node pools an {@code UpdateCluster} carrying {@code desiredNodeVersion} upgrades.
     *
     * <p>{@code desired_node_version} upgrades the single pool named by {@code
     * desired_node_pool_id}, which the proto makes "mandatory if 'desired_node_version' ... is
     * specified and there is more than one node pool on the cluster". Terraform's
     * {@code google_container_cluster} sends {@code desiredNodePoolId: "default-pool"}, so
     * upgrading every pool would move the versions of the standalone {@code
     * google_container_node_pool} resources nobody asked to touch, and show up as drift on the
     * next plan.
     *
     * <p>The fallback to the sole pool is what lets the field stay optional in the single-pool
     * case the proto carves out; with several pools and no id there is no defensible target, so
     * the request is rejected rather than guessed at. */
    private List<StoredNodePool> nodeVersionUpdateTargets(String project, String location, String clusterId,
            Map<String, Object> updateMap) {
        String desiredNodePoolId = optionalStringField(updateMap, "desiredNodePoolId");
        if (desiredNodePoolId != null) {
            return List.of(requireNodePool(project, location, clusterId, desiredNodePoolId));
        }
        List<StoredNodePool> pools = listNodePools(project, location, clusterId);
        if (pools.size() > 1) {
            throw GcpException.invalidArgument(
                    "desiredNodePoolId is required when desiredNodeVersion is specified and the "
                            + "cluster has more than one node pool");
        }
        return pools;
    }

    private void touch(StoredCluster cluster) {
        cluster.setLabelFingerprint(newFingerprint());
        cluster.setEtag(newFingerprint());
    }

    private void putExtraConfig(StoredCluster cluster, String key, Object value) {
        Map<String, Object> extra = cluster.getExtraConfig();
        if (extra == null) {
            extra = new HashMap<>();
        } else {
            extra = new HashMap<>(extra);
        }
        if (value == null) {
            extra.remove(key);
        } else {
            extra.put(key, value);
        }
        cluster.setExtraConfig(extra);
    }

    private void mergeExtraConfig(StoredCluster cluster, Map<String, Object> updates) {
        if (updates == null || updates.isEmpty()) {
            return;
        }
        Map<String, Object> extra = cluster.getExtraConfig();
        extra = extra == null ? new HashMap<>() : new HashMap<>(extra);
        extra.putAll(updates);
        cluster.setExtraConfig(extra);
    }

    void pollReadiness() {
        for (StoredCluster cluster : clusterStore.scan(k -> true)) {
            if (("PROVISIONING".equals(cluster.getStatus()) || "RECONCILING".equals(cluster.getStatus()))
                    && !replacingControlPlanes.contains(
                            clusterKey(cluster.getProject(), cluster.getLocation(), cluster.getName()))) {
                try {
                    pollCluster(cluster);
                } catch (Exception e) {
                    LOG.errorv(e, "Error polling readiness of GKE cluster {0}", cluster.getName());
                }
            }
        }
        for (StoredCluster cluster : clusterStore.scan(k -> true)) {
            if ("RUNNING".equals(cluster.getStatus())) {
                try {
                    reconcileNodes(cluster.getProject(), cluster.getLocation(), cluster.getName());
                } catch (Exception e) {
                    LOG.errorv(e, "Error reconciling the nodes of GKE cluster {0}", cluster.getName());
                }
            }
        }
    }

    private Object clusterLock(String clusterKey) {
        return clusterLocks.computeIfAbsent(clusterKey, k -> new Object());
    }

    private void markNodesOutOfDate(StoredCluster cluster) {
        synchronized (lifecycleLock) {
            cluster.setNodesOutOfDate(true);
            nodeChanges.merge(clusterKey(cluster.getProject(), cluster.getLocation(), cluster.getName()), 1L, Long::sum);
        }
    }

    private long nodeChanges(String clusterKey) {
        synchronized (lifecycleLock) {
            return nodeChanges.getOrDefault(clusterKey, 0L);
        }
    }

    /** Node pools in the order the node cap serves them: the default pool first, then by creation. */
    private List<StoredNodePool> orderedPools(String project, String location, String clusterId) {
        return listNodePools(project, location, clusterId).stream()
                .sorted(Comparator.comparing((StoredNodePool p) -> !GkeNodes.DEFAULT_POOL.equals(p.getName()))
                        .thenComparing(p -> p.getCreateTime() == null ? "" : p.getCreateTime())
                        .thenComparing(StoredNodePool::getName))
                .toList();
    }

    /**
     * Brings a running cluster's node containers in line with its node pools, then moves pools whose
     * nodes are all Ready to {@code RUNNING}. Nothing happens for a cluster whose pools are all
     * {@code RUNNING} and whose nodes nobody changed. Runs on the poller thread.
     */
    void reconcileNodes(String project, String location, String clusterId) {
        if (mock()) {
            return;
        }
        String key = clusterKey(project, location, clusterId);
        synchronized (clusterLock(key)) {
            StoredCluster cluster = clusterStore.get(key).orElse(null);
            if (cluster == null || !"RUNNING".equals(cluster.getStatus())) {
                return;
            }
            long changes = nodeChanges(key);
            List<StoredNodePool> pools = orderedPools(project, location, clusterId);
            if (cluster.isNodesOutOfDate() && !applyNodePlan(cluster, pools, changes)) {
                return;
            }
            if (clusterStore.get(key).map(StoredCluster::isNodesOutOfDate).orElse(true)) {
                return;
            }
            settleNodePools(cluster, orderedPools(project, location, clusterId));
        }
    }

    /** Creates, adopts and removes node containers so each pool runs its share of the node cap. False
     * when the control plane cannot hand out its join token yet. */
    private boolean applyNodePlan(StoredCluster cluster, List<StoredNodePool> pools, long changes) {
        String project = cluster.getProject();
        String location = cluster.getLocation();
        String clusterId = cluster.getName();
        String token = clusterManager.readToken(cluster);
        if (token == null) {
            return false;
        }
        if (cluster.getK3sToken() != null && !token.equals(cluster.getK3sToken())) {
            LOG.warnv("The control plane of GKE cluster {0} started over with a new identity; recreating its nodes",
                    clusterId);
            for (StoredNodePool pool : pools) {
                removeNodes(cluster, pool.getNodes());
                pool.setNodes(new ArrayList<>());
                nodePoolStore.put(nodePoolKey(project, location, clusterId, pool.getName()), pool);
            }
        }
        cluster.setK3sToken(token);

        List<GkeNodes.PoolDemand> demands = pools.stream()
                .map(p -> new GkeNodes.PoolDemand(p.getName(), p.getInitialNodeCount(),
                        GkeNodes.zones(p.getLocations(), location)))
                .toList();
        int cap = config.services().gke().maxNodesPerCluster();
        List<GkeNodes.PoolAllocation> allocations = GkeNodes.allocate(demands, cap);
        Set<String> keep = new HashSet<>();
        List<String> shortPools = new ArrayList<>();
        for (int i = 0; i < pools.size(); i++) {
            StoredNodePool pool = pools.get(i);
            GkeNodes.PoolAllocation allocation = allocations.get(i);
            if (allocation.shortOfNodes()) {
                shortPools.add(pool.getName() + " (" + allocation.nodeZones().size() + " of " + allocation.requested() + ")");
            }
            reconcilePool(cluster, pool, allocation.nodeZones());
            for (StoredNode node : pool.getNodes()) {
                keep.add(node.getContainerName());
            }
        }
        clusterManager.removeNodeContainers(cluster, keep);
        if (!shortPools.isEmpty()) {
            LOG.warnv("GKE cluster {0} runs at most {1} node containers (floci-gcp.services.gke.max-nodes-per-cluster); "
                    + "these node pools run fewer nodes than they report: {2}", clusterId, String.valueOf(cap),
                    String.join(", ", shortPools));
        }
        String key = clusterKey(project, location, clusterId);
        synchronized (lifecycleLock) {
            clusterStore.get(key).ifPresent(current -> {
                current.setK3sToken(token);
                current.setNodesOutOfDate(nodeChanges(key) != changes);
                clusterStore.put(key, current);
            });
        }
        return true;
    }

    /** Keeps the pool's live nodes that match its version and zones, removes the rest, and starts the missing ones. */
    private void reconcilePool(StoredCluster cluster, StoredNodePool pool, List<String> zones) {
        String poolKey = nodePoolKey(cluster.getProject(), cluster.getLocation(), cluster.getName(), pool.getName());
        String image = clusterManager.imageFor(pool.getVersion());
        Map<String, Integer> wanted = new LinkedHashMap<>();
        zones.forEach(zone -> wanted.merge(zone, 1, Integer::sum));
        List<StoredNode> kept = new ArrayList<>();
        List<StoredNode> dropped = new ArrayList<>();
        for (StoredNode node : pool.getNodes() == null ? List.<StoredNode>of() : pool.getNodes()) {
            int room = wanted.getOrDefault(node.getZone(), 0);
            if (room > 0 && image.equals(node.getImage()) && clusterManager.adoptNode(node)) {
                kept.add(node);
                wanted.put(node.getZone(), room - 1);
            } else {
                dropped.add(node);
            }
        }
        removeNodes(cluster, dropped);
        boolean changed = !dropped.isEmpty();
        pool.setNodes(new ArrayList<>(kept));
        GkeClusterManager.HostResources host = clusterManager.hostResources();
        for (Map.Entry<String, Integer> slot : wanted.entrySet()) {
            for (int i = 0; i < slot.getValue(); i++) {
                String zone = slot.getKey();
                String hash = GkeNodes.instanceGroupHash(cluster.getProject(), cluster.getName(), pool.getName(), zone);
                StoredNode node = new StoredNode(GkeNodes.nodeName(cluster.getName(), pool.getName(), hash,
                        uniqueSuffix(pool)), zone);
                GkeNodes.NodeSpec spec = GkeNodes.spec(cluster.getProject(), pool.getName(), zone, node.getName(),
                        pool.getConfig(), maxPods(cluster, pool), pool.isGpuTaint(), host.cpus(), host.memoryBytes(),
                        runsGkeMetadataServer(cluster, pool));
                pool.getNodes().add(node);
                try {
                    clusterManager.startNode(cluster, node, new GkeClusterManager.NodeLaunch(pool.getName(), image, spec));
                } catch (Exception e) {
                    pool.getNodes().remove(node);
                    removeNodes(cluster, List.of(node));
                    failPool(cluster, pool, "Could not start node " + node.getName() + ": " + e.getMessage());
                    return;
                }
                changed = true;
                nodePoolStore.put(poolKey, pool);
            }
        }
        if (changed && "RUNNING".equals(pool.getStatus())) {
            pool.setStatus("RECONCILING");
        }
        nodePoolStore.put(poolKey, pool);
    }

    private String uniqueSuffix(StoredNodePool pool) {
        String alphabet = "abcdefghijklmnopqrstuvwxyz0123456789";
        while (true) {
            StringBuilder suffix = new StringBuilder();
            for (int i = 0; i < 4; i++) {
                suffix.append(alphabet.charAt(random.nextInt(alphabet.length())));
            }
            String candidate = suffix.toString();
            if (pool.getNodes() == null || pool.getNodes().stream().noneMatch(n -> n.getName().endsWith("-" + candidate))) {
                return candidate;
            }
        }
    }

    private static int maxPods(StoredCluster cluster, StoredNodePool pool) {
        Object poolMax = pool.getMaxPodsConstraint() == null ? null : pool.getMaxPodsConstraint().get("maxPodsPerNode");
        Object clusterMax = cluster.getExtraConfig() != null
                && cluster.getExtraConfig().get("defaultMaxPodsConstraint") instanceof Map<?, ?> m
                ? m.get("maxPodsPerNode") : null;
        return GkeNodes.intValue(poolMax, GkeNodes.intValue(clusterMax, GkeNodes.DEFAULT_MAX_PODS));
    }

    private void removeNodes(StoredCluster cluster, List<StoredNode> nodes) {
        if (nodes == null || nodes.isEmpty()) {
            return;
        }
        List<String> names = new ArrayList<>();
        for (StoredNode node : nodes) {
            try {
                clusterManager.removeNode(node);
            } catch (Exception e) {
                LOG.warnv("Could not remove the container of node {0}: {1}", node.getName(), e.getMessage());
            }
            names.add(node.getName());
        }
        if (cluster != null && "RUNNING".equals(cluster.getStatus())) {
            try {
                clusterManager.deleteKubernetesNodes(cluster, names);
            } catch (Exception e) {
                LOG.warnv("Could not delete nodes {0} from GKE cluster {1}: {2}", names, cluster.getName(), e.getMessage());
            }
        }
    }

    private void failPool(StoredCluster cluster, StoredNodePool pool, String failure) {
        LOG.warnv("Node pool {0} of GKE cluster {1}: {2}", pool.getName(), cluster.getName(), failure);
        pool.setStatus("ERROR");
        pool.setStatusMessage(failure);
        String pending = pool.getPendingOperation();
        pool.setPendingOperation(null);
        nodePoolStore.put(nodePoolKey(cluster.getProject(), cluster.getLocation(), cluster.getName(), pool.getName()), pool);
        operationService.completeOperation(pending, operationError(Status.Code.INTERNAL, failure));
    }

    /** Moves each provisioning or reconciling pool to {@code RUNNING} once every one of its nodes is Ready. */
    private void settleNodePools(StoredCluster cluster, List<StoredNodePool> pools) {
        List<StoredNodePool> waiting = pools.stream()
                .filter(p -> "PROVISIONING".equals(p.getStatus()) || "RECONCILING".equals(p.getStatus()))
                .toList();
        if (waiting.isEmpty()) {
            return;
        }
        Map<String, GkeClusterManager.NodeState> states = null;
        for (StoredNodePool pool : waiting) {
            List<StoredNode> nodes = pool.getNodes() == null ? List.of() : pool.getNodes();
            if (!nodes.isEmpty()) {
                if (states == null) {
                    states = clusterManager.nodeStates(cluster);
                    if (states == null) {
                        return;
                    }
                }
                if (!nodesReady(cluster, pool, nodes, states)) {
                    continue;
                }
            }
            String pending = pool.getPendingOperation();
            pool.setStatus("RUNNING");
            pool.setStatusMessage(null);
            pool.setPendingOperation(null);
            nodePoolStore.put(nodePoolKey(cluster.getProject(), cluster.getLocation(), cluster.getName(), pool.getName()),
                    pool);
            operationService.completeOperation(pending, null);
            LOG.infov("Node pool {0} of GKE cluster {1} is RUNNING with {2} node(s)", pool.getName(), cluster.getName(),
                    String.valueOf(nodes.size()));
        }
    }

    /** Whether every node of the pool is registered and Ready, putting back the labels the k3s cloud
     * controller overwrites when a node registers. */
    private boolean nodesReady(StoredCluster cluster, StoredNodePool pool, List<StoredNode> nodes,
                               Map<String, GkeClusterManager.NodeState> states) {
        boolean ready = true;
        for (StoredNode node : nodes) {
            GkeClusterManager.NodeState state = states.get(node.getName());
            if (state == null || !state.ready()) {
                ready = false;
                continue;
            }
            GkeNodes.NodeSpec spec = GkeNodes.spec(cluster.getProject(), pool.getName(), node.getZone(),
                    node.getName(), pool.getConfig(), maxPods(cluster, pool), pool.isGpuTaint(), 0, 0,
                    runsGkeMetadataServer(cluster, pool));
            Map<String, String> drifted = new HashMap<>();
            spec.labels().forEach((k, v) -> {
                if (!v.equals(state.labels().get(k))) {
                    drifted.put(k, v);
                }
            });
            if (!drifted.isEmpty()) {
                clusterManager.labelNode(cluster, node.getName(), drifted);
            }
            if (spec.acceleratorCount() != state.gpuCapacity()) {
                clusterManager.setGpuCapacity(cluster, node.getName(), spec.acceleratorCount());
                ready = false;
            }
        }
        return ready;
    }

    /**
     * One readiness pass. The Docker and HTTP checks run on a copy without the lock, and only the
     * outcome is written, under {@link #lifecycleLock}, to the record that is current by then.
     */
    private void pollCluster(StoredCluster seen) {
        StoredCluster probe = new StoredCluster(seen);
        if (clusterManager.isReady(probe)) {
            clusterManager.finalizeCluster(probe);
            if (probe.getCaCertificate() != null && !probe.getCaCertificate().isBlank()) {
                settle(seen, probe.getCaCertificate(), null);
                return;
            }
        } else if (clusterManager.isControlPlaneStopped(probe)) {
            settle(seen, null, "The control plane of cluster " + seen.getName() + " stopped before it became ready");
            return;
        }
        if (startupExpired(seen)) {
            settle(seen, null, "The control plane of cluster " + seen.getName() + " did not become ready within "
                    + STARTUP_TIMEOUT.toMinutes() + " minutes");
        }
    }

    /** Moves a still-provisioning or reconciling cluster to {@code RUNNING} (with its CA) or, given a
     * failure, to {@code ERROR}, and finishes the create operation recorded on it. A pass whose cluster
     * was deleted, recreated or settled meanwhile writes nothing. */
    private void settle(StoredCluster seen, String caCertificate, String failure) {
        String key = clusterKey(seen.getProject(), seen.getLocation(), seen.getName());
        synchronized (lifecycleLock) {
            Optional<StoredCluster> current = clusterStore.get(key);
            if (current.isEmpty()
                    || !Objects.equals(current.get().getCreateOperation(), seen.getCreateOperation())
                    || !Objects.equals(current.get().getStatus(), seen.getStatus())
                    || !Objects.equals(current.get().getReconcileSince(), seen.getReconcileSince())) {
                return;
            }
            StoredCluster cluster = current.get();
            if (failure == null) {
                cluster.setCaCertificate(caCertificate);
                cluster.setStatus("RUNNING");
                cluster.setStatusMessage(null);
            } else {
                cluster.setStatus("ERROR");
                cluster.setStatusMessage(failure);
            }
            cluster.setReconcileSince(null);
            String pending = cluster.getPendingOperation();
            cluster.setPendingOperation(null);
            clusterStore.put(key, cluster);
            Map<String, Object> error = failure == null ? null : operationError(Status.Code.INTERNAL, failure);
            operationService.completeOperation(cluster.getCreateOperation(), error);
            operationService.completeOperation(pending, error);
        }
        if (failure == null) {
            LOG.infov("GKE cluster {0} is now RUNNING", seen.getName());
        } else {
            LOG.warnv("GKE cluster {0} failed to start: {1}", seen.getName(), failure);
        }
    }

    /** A cluster without a readable {@code createTime} is never expired: the emulator always
     * writes an RFC3339 instant, so only a hand-edited store gets here, and failing a create that
     * may still be starting is worse than waiting. The stopped-container check still applies. */
    boolean startupExpired(StoredCluster cluster) {
        Instant created = null;
        String since = cluster.getReconcileSince() != null ? cluster.getReconcileSince() : cluster.getCreateTime();
        if (since != null) {
            try {
                created = Instant.parse(since);
            } catch (DateTimeParseException e) {
                // Logged below, once per cluster.
            }
        }
        if (created == null) {
            if (unreadableCreateTimes.add(cluster.getCreateOperation() + "|" + cluster.getName())) {
                LOG.warnv("Cluster {0} has an unreadable createTime \"{1}\"; its startup deadline is not enforced",
                        cluster.getName(), cluster.getCreateTime());
            }
            return false;
        }
        return created.plus(STARTUP_TIMEOUT).isBefore(Instant.now());
    }

    private static String defaultVersion() {
        return GkeReleases.defaultRelease(Optional.empty()).gkeVersion();
    }

    private boolean mock() {
        return config.services().gke().mock();
    }

    private static String clusterKey(String project, String location, String name) {
        return "projects/" + project + "/locations/" + location + "/clusters/" + name;
    }

    private static String nodePoolKey(String project, String location, String clusterId, String nodePoolId) {
        return clusterKey(project, location, clusterId) + "/nodePools/" + nodePoolId;
    }

    private static void logIgnoredBetaFields(String resource, List<String> fields) {
        if (!fields.isEmpty()) {
            LOG.debugv("Ignoring v1beta1 fields not modelled by the emulator on {0}: {1}", resource, fields);
        }
    }

    private String clusterSelfLink(String project, String location, String name) {
        return config.baseUrl() + "/container/v1/" + clusterKey(project, location, name);
    }

    private String nodePoolSelfLink(String project, String location, String clusterId, String name) {
        return config.baseUrl() + "/container/v1/" + nodePoolKey(project, location, clusterId, name);
    }

    private static String newFingerprint() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 16);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, String> labels(Map<String, Object> map) {
        if (map == null) {
            return Map.of();
        }
        Object labels = map.containsKey("resourceLabels") ? map.get("resourceLabels") : map.get("labels");
        if (labels instanceof Map<?, ?> m) {
            Map<String, String> result = new HashMap<>();
            m.forEach((k, v) -> result.put(String.valueOf(k), String.valueOf(v)));
            return result;
        }
        return Map.of();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object value) {
        if (value instanceof Map<?, ?> m) {
            Map<String, Object> result = new HashMap<>();
            m.forEach((k, v) -> result.put(String.valueOf(k), v));
            return result;
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private static List<String> stringListField(Map<String, Object> map, String field, List<String> fallback) {
        Object v = map.get(field);
        if (v instanceof List<?> list) {
            List<String> result = new ArrayList<>();
            for (Object o : list) {
                result.add(String.valueOf(o));
            }
            return result;
        }
        return fallback;
    }

    private static String stringField(Map<String, Object> map, String field, String fallback) {
        Object v = map.get(field);
        return v instanceof String s && !s.isBlank() ? s : fallback;
    }

    /** An optional string field: absent or blank is {@code null} (proto3 JSON treats {@code ""}
     * as unset), and a value of another type is a 400 naming the field, where a raw cast escaped
     * as an unmapped {@code ClassCastException} and a 500. */
    private static String optionalStringField(Map<String, Object> map, String field) {
        Object v = map.get(field);
        if (v != null && !(v instanceof String)) {
            throw GcpException.invalidArgument(field + " must be a string");
        }
        return stringField(map, field, null);
    }

    /** Everything in {@code map} not in {@code typedFields}, stored verbatim for read-back fidelity. */
    private static Map<String, Object> extraFields(Map<String, Object> map, List<String> typedFields) {
        Map<String, Object> extra = new HashMap<>(map);
        typedFields.forEach(extra::remove);
        return extra;
    }

    /** Strips a leading {@code desired} prefix from ClusterUpdate field names, lower-camel-casing
     * the remainder to match the equivalent Cluster field name (e.g. {@code desiredAddonsConfig}
     * -&gt; {@code addonsConfig}). */
    private static Map<String, Object> stripPrefix(Map<String, Object> map, String prefix) {
        Map<String, Object> result = new HashMap<>();
        map.forEach((k, v) -> {
            if (k.startsWith(prefix) && k.length() > prefix.length()) {
                String rest = k.substring(prefix.length());
                String unwrapped = Character.toLowerCase(rest.charAt(0)) + rest.substring(1);
                result.put(unwrapped, v);
            }
        });
        return result;
    }
}
