package io.floci.gcp.services.gke;

import io.floci.gcp.config.EmulatorConfig;
import io.floci.gcp.core.common.docker.ContainerBuilder;
import io.floci.gcp.core.common.docker.ContainerDetector;
import io.floci.gcp.core.common.docker.ContainerLifecycleManager;
import io.floci.gcp.core.common.docker.DockerHostResolver;
import io.floci.gcp.core.common.docker.PortAllocator;
import io.floci.gcp.services.gke.GkeReleases.Channel;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class GkeReleasesTest {

    @Test
    void everyMinorIsAdvertisedOnItsK3sPatchWithAFixedGkeBuild() {
        assertEquals("1.35.10-gke.1000000", GkeReleases.forMinor(35).orElseThrow().gkeVersion());
        assertEquals("1.37.2-gke.1000000", GkeReleases.forMinor(37).orElseThrow().gkeVersion());
        for (GkeReleases.Release release : GkeReleases.RELEASES) {
            assertTrue(release.gkeVersion().matches("^1\\.\\d+\\.\\d+-gke\\.\\d+$"), release.gkeVersion());
        }
    }

    @Test
    void anyPatchOfASupportedMinorMapsToThatMinor() {
        assertEquals(36, GkeReleases.forVersion("1.36.1-gke.1234000").orElseThrow().minor());
        assertEquals(36, GkeReleases.forVersion("1.36").orElseThrow().minor());
        assertFalse(GkeReleases.forVersion("1.31.5-gke.1").isPresent());
        assertFalse(GkeReleases.forVersion("2.36.0-gke.1").isPresent());
        assertEquals(-1, GkeReleases.minorOf("banana"));
    }

    @Test
    void defaultsFollowTheReleaseChannel() {
        assertEquals(35, GkeReleases.defaultRelease(Optional.empty()).minor());
        assertEquals(35, GkeReleases.defaultRelease(Optional.of(Channel.REGULAR)).minor());
        assertEquals(36, GkeReleases.defaultRelease(Optional.of(Channel.RAPID)).minor());
        assertEquals(35, GkeReleases.defaultRelease(Optional.of(Channel.STABLE)).minor());
        assertEquals(37, GkeReleases.latestRelease(Optional.empty()).minor());
        assertEquals(36, GkeReleases.latestRelease(Optional.of(Channel.STABLE)).minor());
        assertEquals(Optional.of(Channel.RAPID), GkeReleases.channel(Map.of("channel", "RAPID")));
        assertEquals(Optional.empty(), GkeReleases.channel(Map.of("channel", "UNSPECIFIED")));
    }

    @Test
    void extendedOnlyMinorsAreValidForNodesButNotForNewControlPlanes() {
        List<String> master = GkeReleases.validMasterVersions();
        List<String> nodes = GkeReleases.validNodeVersions();
        assertEquals("1.37.2-gke.1000000", master.get(0));
        assertFalse(master.contains("1.33.13-gke.1000000"));
        assertTrue(nodes.contains("1.33.13-gke.1000000"));
        assertTrue(GkeReleases.channelVersions(Channel.EXTENDED).contains("1.32.13-gke.1000000"));
        assertFalse(GkeReleases.channelVersions(Channel.STABLE).contains("1.37.2-gke.1000000"));
    }

    @Test
    void theImageIsTheK3sReleasePinnedForTheVersionsMinor() {
        EmulatorConfig config = mock(EmulatorConfig.class, RETURNS_DEEP_STUBS);
        when(config.services().gke().defaultImage()).thenReturn(Optional.empty());
        when(config.services().gke().imageRepository()).thenReturn("rancher/k3s");
        GkeClusterManager manager = new GkeClusterManager(mock(ContainerBuilder.class),
                mock(ContainerLifecycleManager.class), mock(ContainerDetector.class), new PortAllocator(),
                mock(DockerHostResolver.class), config);

        assertEquals("rancher/k3s:v1.36.6-k3s1", manager.imageFor("1.36.1-gke.1"));
        assertEquals("rancher/k3s:v1.35.10-k3s1", manager.imageFor("1.30.5-gke.1014001"));

        when(config.services().gke().defaultImage()).thenReturn(Optional.of("mirror/k3s:pinned"));
        assertEquals("mirror/k3s:pinned", manager.imageFor("1.36.1-gke.1"));
    }
}
