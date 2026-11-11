package io.floci.gcp.services.gke;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.floci.gcp.config.EmulatorConfig;
import io.floci.gcp.core.storage.InMemoryStorage;
import io.floci.gcp.services.compute.ComputeService;
import io.floci.gcp.services.gke.model.StoredCluster;
import io.floci.gcp.services.gke.model.StoredLoadBalancer;
import io.floci.gcp.services.gke.model.StoredNode;
import io.floci.gcp.services.gke.model.StoredNodePool;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class GkeLoadBalancersTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Instant NOW = Instant.parse("2026-11-11T12:00:00Z");
    private static final String FINALIZER = "service.kubernetes.io/load-balancer-cleanup";

    private final InMemoryStorage<String, StoredLoadBalancer> store = new InMemoryStorage<>();
    private final GkeService gke = mock(GkeService.class);
    private final GkeLoadBalancerKubectl kubectl = mock(GkeLoadBalancerKubectl.class);
    private final GkeLoadBalancerAppliances appliances = mock(GkeLoadBalancerAppliances.class);
    private final ComputeService compute = mock(ComputeService.class);
    private final EmulatorConfig config = mock(EmulatorConfig.class, RETURNS_DEEP_STUBS);
    private StoredCluster cluster;
    private List<JsonNode> objects;

    @BeforeEach
    void setUp() throws Exception {
        cluster = new StoredCluster();
        cluster.setName("c1");
        cluster.setProject("p1");
        cluster.setLocation("us-central1-a");
        cluster.setId("d".repeat(64));
        cluster.setStatus("RUNNING");
        cluster.setLoadBalancerMode("emulated");
        cluster.setCurrentMasterVersion("1.35.10-gke.1000000");
        cluster.setNetworkSubnet("172.18.0.0/16");
        cluster.setContainerId("server");
        StoredNodePool pool = new StoredNodePool();
        pool.setName("default-pool");
        pool.setNodes(new ArrayList<>(List.of(new StoredNode("n1", "us-central1-a"))));
        cluster.setNodePools(List.of(pool));
        when(gke.allClusters()).thenAnswer(inv -> List.of(cluster));
        when(gke.findCluster("p1", "us-central1-a", "c1")).thenAnswer(inv -> Optional.of(cluster));
        when(config.services().gke().loadBalancer().provisioningDelay()).thenReturn(Duration.ZERO);
        when(config.services().gke().loadBalancer().publicCidr()).thenReturn("203.0.113.0/24");
        when(config.services().gke().loadBalancer().hostPorts()).thenReturn("dynamic");
        when(compute.reserveLoadBalancerAddress(anyString(), anyString(), anyString(), any(), anySet(), anyString(),
                anyList(), anyString())).thenReturn("203.0.113.2");
        when(appliances.ensure(any(), any(), anyList(), anyList())).thenReturn(Map.of("TCP/80", 41000));
        when(appliances.applianceName(any(), anyString())).thenAnswer(inv -> "appliance-" + inv.getArgument(1));
        objects = new ArrayList<>(List.of(node("n1", "172.18.128.2", true), node("stranger", "172.18.128.9", true),
                service("web", "u1", "", "")));
        when(kubectl.list(any())).thenAnswer(inv -> objects);
    }

    private GkeLoadBalancers controller() {
        return controller(Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private GkeLoadBalancers controller(Clock clock) {
        return new GkeLoadBalancers(store, gke, kubectl, appliances, compute, config, clock);
    }

    private static JsonNode node(String name, String ip, boolean ready) throws Exception {
        return JSON.readTree("""
                {"kind": "Node", "metadata": {"name": "%s"}, "status": {
                  "addresses": [{"type": "InternalIP", "address": "%s"}, {"type": "Hostname", "address": "%s"}],
                  "conditions": [{"type": "Ready", "status": "%s"}]}}""".formatted(name, ip, name, ready ? "True" : "False"));
    }

    private static JsonNode service(String name, String uid, String extraMetadata, String extraSpec) throws Exception {
        return JSON.readTree("""
                {"kind": "Service", "metadata": {"name": "%s", "namespace": "shop", "uid": "%s" %s},
                 "spec": {"type": "LoadBalancer", "ports": [{"protocol": "TCP", "port": 80, "nodePort": 31080}] %s},
                 "status": {"loadBalancer": {}}}""".formatted(name, uid, extraMetadata, extraSpec));
    }

    @Test
    void addsTheCleanupFinalizerBeforeItWritesTheAddress() {
        controller().reconcile(cluster);

        InOrder order = inOrder(kubectl, compute, appliances);
        order.verify(kubectl).setFinalizers(any(), eq("shop"), eq("web"), eq(List.of(FINALIZER)));
        order.verify(kubectl).event(any(), any(), eq("Normal"), eq("EnsuringLoadBalancer"), anyString());
        order.verify(compute).reserveLoadBalancerAddress(eq("p1"), eq("us-central1"), eq("a" + "u1"), isNull(), anySet(),
                eq("TCP"), eq(List.of(80)), eq("{\"kubernetes.io/service-name\":\"shop/web\"}"));
        order.verify(appliances).ensure(any(), any(), eq(List.of(new GkeLoadBalancerSpec.Port("TCP", 80, 31080))),
                eq(List.of("172.18.128.2")));
        order.verify(kubectl).patchStatus(any(), eq("shop"), eq("web"),
                eq("{\"status\":{\"loadBalancer\":{\"ingress\":[{\"ip\":\"203.0.113.2\"}]}}}"));
        order.verify(kubectl).event(any(), any(), eq("Normal"), eq("EnsuredLoadBalancer"), anyString());
        verify(kubectl).annotate(any(), eq("shop"), eq("web"), eq("floci.io/load-balancer-host-ports"),
                eq("TCP/80=127.0.0.1:41000"));
        StoredLoadBalancer record = store.scan(k -> true).get(0);
        assertEquals("203.0.113.2", record.getAddress());
        assertEquals("au1", record.getForwardingRule());
    }

    @Test
    void servicesOfAnotherClassAreLeftAlone() throws Exception {
        objects.set(2, service("web", "u1", "", ", \"loadBalancerClass\": \"example.com/lb\""));

        controller().reconcile(cluster);

        verify(kubectl, never()).setFinalizers(any(), anyString(), anyString(), anyList());
        verify(kubectl, never()).patchStatus(any(), anyString(), anyString(), anyString());
        assertTrue(store.scan(k -> true).isEmpty());
    }

    @Test
    void aServiceTheEmulatorCannotServeStaysPendingWithAWarning() throws Exception {
        objects.set(2, service("web", "u1", "", ", \"loadBalancerIP\": \"10.9.9.9\""));

        controller().reconcile(cluster);
        controller().reconcile(cluster);

        verify(kubectl, times(1)).event(any(), any(), eq("Warning"), eq("SyncLoadBalancerFailed"), anyString());
        verify(kubectl, never()).patchStatus(any(), anyString(), anyString(), anyString());
        verify(appliances, never()).ensure(any(), any(), anyList(), anyList());
    }

    @Test
    void deletingTheServiceRemovesItsLoadBalancerAndThenTheFinalizer() throws Exception {
        controller().reconcile(cluster);
        objects.set(2, service("web", "u1", ", \"deletionTimestamp\": \"2026-11-11T12:01:00Z\", \"finalizers\": [\""
                + FINALIZER + "\", \"other/finalizer\"]", ""));

        controller().reconcile(cluster);

        InOrder order = inOrder(appliances, compute, kubectl);
        order.verify(appliances).remove(anyString());
        order.verify(compute).releaseLoadBalancerAddress("p1", "us-central1", "au1");
        order.verify(kubectl).setFinalizers(any(), eq("shop"), eq("web"), eq(List.of("other/finalizer")));
        verify(kubectl).event(any(), any(), eq("Normal"), eq("DeletedLoadBalancer"), anyString());
        assertTrue(store.scan(k -> true).isEmpty());
    }

    @Test
    void changingTheTypeAwayFromLoadBalancerCleansUpAndClearsTheStatus() throws Exception {
        controller().reconcile(cluster);
        JsonNode clusterIp = JSON.readTree("""
                {"kind": "Service", "metadata": {"name": "web", "namespace": "shop", "uid": "u1",
                  "finalizers": ["%s"]}, "spec": {"type": "ClusterIP"}}""".formatted(FINALIZER));
        objects.set(2, clusterIp);

        controller().reconcile(cluster);

        verify(compute).releaseLoadBalancerAddress("p1", "us-central1", "au1");
        verify(kubectl).patchStatus(any(), eq("shop"), eq("web"), eq("{\"status\":{\"loadBalancer\":{}}}"));
        verify(kubectl).setFinalizers(any(), eq("shop"), eq("web"), eq(List.of()));
    }

    @Test
    void addressesSurviveARestartOfTheEmulator() {
        controller().reconcile(cluster);
        controller().reconcile(cluster);

        verify(compute, times(1)).reserveLoadBalancerAddress(anyString(), anyString(), anyString(), any(), anySet(),
                anyString(), anyList(), anyString());
        verify(appliances, times(2)).ensure(any(), any(), anyList(), anyList());
    }

    @Test
    void theProvisioningDelayKeepsTheServicePendingAtFirst() {
        when(config.services().gke().loadBalancer().provisioningDelay()).thenReturn(Duration.ofSeconds(60));

        controller().reconcile(cluster);
        verify(kubectl, never()).patchStatus(any(), anyString(), anyString(), anyString());

        controller(Clock.fixed(NOW.plusSeconds(61), ZoneOffset.UTC)).reconcile(cluster);
        verify(kubectl).patchStatus(any(), eq("shop"), eq("web"), anyString());
    }

    @Test
    void internalLoadBalancersTakeAnAddressOnTheClusterNetwork() throws Exception {
        objects.set(2, service("web", "u1", ", \"annotations\": {\"networking.gke.io/load-balancer-type\": \"Internal\"}", ""));

        controller().reconcile(cluster);

        verify(compute, never()).reserveLoadBalancerAddress(anyString(), anyString(), anyString(), any(), anySet(),
                anyString(), anyList(), anyString());
        verify(kubectl).patchStatus(any(), eq("shop"), eq("web"),
                eq("{\"status\":{\"loadBalancer\":{\"ingress\":[{\"ip\":\"172.18.0.20\"}]}}}"));
    }

    @Test
    void aNamedAddressMustBeReservedInCompute() throws Exception {
        objects.set(2, service("web", "u1",
                ", \"annotations\": {\"networking.gke.io/load-balancer-ip-addresses\": \"web-ip\"}", ""));
        when(compute.reservedAddress("p1", "us-central1", "web-ip")).thenReturn(Optional.of("203.0.113.50"));

        controller().reconcile(cluster);

        verify(compute).reserveLoadBalancerAddress(anyString(), anyString(), anyString(), eq("203.0.113.50"), anySet(),
                anyString(), anyList(), anyString());
    }

    @Test
    void theLoadBalancersOfADeletedClusterAreRemoved() {
        controller().reconcile(cluster);
        when(gke.allClusters()).thenReturn(List.of());

        controller().reconcileAll();

        verify(appliances).remove(anyString());
        verify(compute).releaseLoadBalancerAddress("p1", "us-central1", "au1");
        assertTrue(store.scan(k -> true).isEmpty());
        verify(appliances).removePublicNetworkIfUnused();
    }

    @Test
    void clustersThatKeepServicelbAreNotTouched() {
        cluster.setLoadBalancerMode(null);
        controller().reconcile(cluster);
        cluster.setLoadBalancerMode("off");
        controller().reconcile(cluster);

        verify(kubectl, never()).list(any());
        verify(appliances, never()).ensure(any(), any(), anyList(), anyList());
        verify(kubectl, never()).patchStatus(any(), anyString(), anyString(), anyString());
        verify(kubectl, never()).setFinalizers(any(), anyString(), anyString(), anyList());
        verify(compute, never()).releaseLoadBalancerAddress(anyString(), anyString(), anyString());
        verify(appliances, never()).remove(anyString());
        verify(kubectl, never()).annotate(any(), anyString(), anyString(), anyString(), anyString());
        verify(kubectl, never()).event(any(), any(), anyString(), anyString(), anyString());
        verify(compute, never()).reservedAddress(anyString(), anyString(), anyString());
        verify(gke, never()).findCluster(anyString(), anyString(), anyString());
        assertEquals(0, store.scan(k -> true).size());
    }

    @Test
    void nodesExcludedFromExternalLoadBalancersAreNotBackends() throws Exception {
        objects.set(0, JSON.readTree("""
                {"kind": "Node", "metadata": {"name": "n1", "labels": {
                  "node.kubernetes.io/exclude-from-external-load-balancers": ""}}, "status": {
                  "addresses": [{"type": "InternalIP", "address": "172.18.128.2"}],
                  "conditions": [{"type": "Ready", "status": "True"}]}}"""));

        controller().reconcile(cluster);

        verify(appliances).ensure(any(), any(), anyList(), eq(List.of()));
    }

    @Test
    void anApplianceStartedWhileTheClusterIsBeingDeletedIsRemovedAgain() {
        StoredCluster stopping = new StoredCluster(cluster);
        stopping.setStatus("STOPPING");
        when(gke.findCluster("p1", "us-central1-a", "c1")).thenReturn(Optional.of(stopping));

        controller().reconcile(cluster);

        verify(appliances).remove("appliance-u1");
        verify(kubectl, never()).patchStatus(any(), anyString(), anyString(), anyString());
    }
}
