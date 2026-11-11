package io.floci.gcp.services.gke;

import io.floci.gcp.core.common.ProjectNumbers;
import io.floci.gcp.services.compute.ComputeMetadataTree;
import io.floci.gcp.services.compute.MetadataTreeRenderer;
import io.floci.gcp.services.gke.model.StoredCluster;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GkeMetadataTreeTest {

    private static final String NODE = "gke-c1-default-pool-1a2b3c4d-x9k2";

    private static ComputeMetadataTree.Dir tree() {
        StoredCluster cluster = new StoredCluster();
        cluster.setName("c1");
        cluster.setProject("p1");
        cluster.setLocation("us-central1");
        cluster.setId("a".repeat(64));
        return GkeMetadataTree.build(cluster, "us-central1-a", NODE, new GkeMetadataTree.Identity("app@p1.iam.gserviceaccount.com",
                List.of("https://www.googleapis.com/auth/cloud-platform")));
    }

    private static String value(String path) {
        return assertInstanceOf(MetadataTreeRenderer.Rendered.class,
                MetadataTreeRenderer.render(tree(), path, false, false, null, null, Set.of())).body();
    }

    @Test
    void servesTheKeysGkeDocumentsAndNothingElse() {
        assertEquals("us-central1", value("instance/attributes/cluster-location"));
        assertEquals("c1", value("instance/attributes/cluster-name"));
        assertEquals("a".repeat(64), value("instance/attributes/cluster-uid"));
        assertEquals(NODE + ".us-central1-a.c.p1.internal", value("instance/hostname"));
        assertTrue(value("instance/id").matches("^\\d+$"));
        assertEquals("projects/" + ProjectNumbers.of("p1") + "/zones/us-central1-a", value("instance/zone"));
        assertEquals("p1", value("project/project-id"));
        assertEquals(ProjectNumbers.of("p1"), value("project/numeric-project-id"));
        assertEquals("app@p1.iam.gserviceaccount.com", value("instance/service-accounts/default/email"));
        assertEquals("default\n", value("instance/service-accounts/default/aliases"));
        assertEquals("https://www.googleapis.com/auth/cloud-platform\n",
                value("instance/service-accounts/app@p1.iam.gserviceaccount.com/scopes"));
        assertEquals("googleapis.com", value("universe/universe-domain"));

        for (String unsupported : List.of("instance/attributes/kube-env", "instance/network-interfaces/0/ip",
                "instance/machine-type", "project/attributes/ssh-keys")) {
            assertInstanceOf(MetadataTreeRenderer.NotFound.class,
                    MetadataTreeRenderer.render(tree(), unsupported, false, false, null, null, Set.of()), unsupported);
        }
        assertEquals("app@p1.iam.gserviceaccount.com/\ndefault/\n", assertInstanceOf(MetadataTreeRenderer.Rendered.class,
                MetadataTreeRenderer.render(tree(), "instance/service-accounts/", true, false, null, null, Set.of()))
                .body());
        assertInstanceOf(MetadataTreeRenderer.Generated.class,
                MetadataTreeRenderer.render(tree(), "instance/service-accounts/default/token", false, false, null, null,
                        Set.of()));
    }
}
