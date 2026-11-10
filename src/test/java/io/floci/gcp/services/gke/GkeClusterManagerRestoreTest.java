package io.floci.gcp.services.gke;

import com.github.dockerjava.api.model.Container;
import io.floci.gcp.config.EmulatorConfig;
import io.floci.gcp.core.common.docker.ContainerBuilder;
import io.floci.gcp.core.common.docker.ContainerDetector;
import io.floci.gcp.core.common.docker.ContainerLifecycleManager;
import io.floci.gcp.core.common.docker.ContainerLifecycleManager.ContainerInfo;
import io.floci.gcp.core.common.docker.ContainerLifecycleManager.EndpointInfo;
import io.floci.gcp.core.common.docker.DockerHostResolver;
import io.floci.gcp.core.common.docker.PortAllocator;
import io.floci.gcp.services.gke.model.StoredCluster;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.ServerSocket;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class GkeClusterManagerRestoreTest {

    private ContainerLifecycleManager lifecycleManager;
    private EmulatorConfig config;
    private PortAllocator portAllocator;
    private int port;

    @BeforeEach
    void setUp() throws Exception {
        try (ServerSocket socket = new ServerSocket(0)) {
            port = socket.getLocalPort();
        }
        lifecycleManager = mock(ContainerLifecycleManager.class);
        config = mock(EmulatorConfig.class, RETURNS_DEEP_STUBS);
        when(config.services().gke().defaultImage()).thenReturn(Optional.of("rancher/k3s:test"));
        when(config.services().gke().apiServerBasePort()).thenReturn(port);
        when(config.services().gke().apiServerMaxPort()).thenReturn(port);
        when(config.services().gke().endpointMode()).thenReturn("host");
        when(config.docker().resourceNamespace()).thenReturn(Optional.empty());
        portAllocator = new PortAllocator();
    }

    @Test
    void aSurvivingControlPlaneContainerIsAdoptedOnItsPublishedPort() {
        Container container = mock(Container.class);
        when(container.getId()).thenReturn("survivor");
        when(container.getLabels()).thenReturn(Map.of(GkeClusterManager.ROLE_LABEL, GkeClusterManager.ROLE_CONTROL_PLANE));
        when(lifecycleManager.listContainersByLabels(anyString(), anyMap())).thenReturn(List.of(container));
        when(lifecycleManager.adopt("survivor", List.of(6443))).thenReturn(new ContainerInfo("survivor",
                Map.of(6443, new EndpointInfo("localhost", port)), Map.of(6443, port)));
        StoredCluster cluster = cluster();
        cluster.setServerIp("172.18.0.10");

        GkeClusterManager.RestoreOutcome outcome = manager().restoreCluster(cluster);

        assertEquals(GkeClusterManager.RestoreOutcome.ADOPTED, outcome);
        assertEquals("survivor", cluster.getContainerId());
        assertEquals(port, cluster.getHostPort());
        assertEquals("localhost:" + port, cluster.getEndpoint());
        assertThrows(RuntimeException.class, () -> portAllocator.allocate(port, port));
        verify(lifecycleManager, never()).create(any());
    }

    @Test
    void aControlPlaneThatAlsoRanWorkloadsIsReplacedOnItsVolume() {
        Container legacy = mock(Container.class);
        when(legacy.getId()).thenReturn("legacy");
        when(legacy.getLabels()).thenReturn(Map.of());
        when(lifecycleManager.listContainersByLabels(anyString(), anyMap())).thenReturn(List.of(legacy));
        when(lifecycleManager.volumeExists("floci-gcp-gke-p1-c1")).thenReturn(true);
        when(lifecycleManager.create(any())).thenReturn("agentless");
        when(lifecycleManager.startCreated(anyString(), any())).thenReturn(new ContainerInfo("agentless", Map.of()));
        StoredCluster cluster = cluster();

        GkeClusterManager.RestoreOutcome outcome = manager().restoreCluster(cluster);

        assertEquals(GkeClusterManager.RestoreOutcome.RECREATED_ON_VOLUME, outcome);
        verify(lifecycleManager).stopAndRemove("legacy", null);
        assertEquals("agentless", cluster.getContainerId());
    }

    @Test
    void aMissingContainerIsRecreatedOnTheSurvivingVolumeAndKeepsItsPort() {
        when(lifecycleManager.listContainersByLabels(anyString(), anyMap())).thenReturn(List.of());
        when(lifecycleManager.volumeExists("floci-gcp-gke-p1-c1")).thenReturn(true);
        when(lifecycleManager.create(any())).thenReturn("recreated");
        when(lifecycleManager.startCreated(anyString(), any())).thenReturn(new ContainerInfo("recreated", Map.of()));
        StoredCluster cluster = cluster();
        cluster.setHostPort(port);

        GkeClusterManager.RestoreOutcome outcome = manager().restoreCluster(cluster);

        assertEquals(GkeClusterManager.RestoreOutcome.RECREATED_ON_VOLUME, outcome);
        assertEquals("recreated", cluster.getContainerId());
        assertEquals(port, cluster.getHostPort());
        verify(lifecycleManager).ensureVolume("floci-gcp-gke-p1-c1");
    }

    @Test
    void aClusterWithNeitherContainerNorVolumeStartsFresh() {
        when(lifecycleManager.listContainersByLabels(anyString(), anyMap())).thenReturn(List.of());
        when(lifecycleManager.volumeExists("floci-gcp-gke-p1-c1")).thenReturn(false);
        when(lifecycleManager.create(any())).thenReturn("fresh");
        when(lifecycleManager.startCreated(anyString(), any())).thenReturn(new ContainerInfo("fresh", Map.of()));
        StoredCluster cluster = cluster();

        GkeClusterManager.RestoreOutcome outcome = manager().restoreCluster(cluster);

        assertEquals(GkeClusterManager.RestoreOutcome.RECREATED_FRESH, outcome);
        assertEquals("fresh", cluster.getContainerId());
    }

    private GkeClusterManager manager() {
        return new GkeClusterManager(mock(ContainerBuilder.class, RETURNS_DEEP_STUBS), lifecycleManager,
                mock(ContainerDetector.class), portAllocator, mock(DockerHostResolver.class), config);
    }

    private static StoredCluster cluster() {
        StoredCluster cluster = new StoredCluster();
        cluster.setName("c1");
        cluster.setProject("p1");
        cluster.setLocation("us-central1");
        cluster.setVolumeName("floci-gcp-gke-p1-c1");
        cluster.setContainerId("gone");
        return cluster;
    }
}
