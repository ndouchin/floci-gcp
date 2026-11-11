package io.floci.gcp.services.gke;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.not;

/**
 * {@code ClusterManager.UpdateNodePool} over REST ({@code PUT .../nodePools/{id}}), with the
 * request shapes gcloud sends for {@code container clusters upgrade --node-pool} and
 * {@code container node-pools update}.
 */
@QuarkusTest
class GkeUpdateNodePoolRestIntegrationTest {

    private static final String PROJECT = "gke-update-node-pool-it";
    private static final String LOCATION = "us-central1";
    private static final String BASE = "/container/v1/projects/" + PROJECT + "/locations/" + LOCATION;

    @Test
    void versionAliasesResolveToARealVersionOnThePool() {
        String cluster = "pool-upgrade";
        String poolPath = BASE + "/clusters/" + cluster + "/nodePools/default-pool";

        given()
                .contentType("application/json")
                .body("{\"cluster\":{\"name\":\"" + cluster + "\",\"initialClusterVersion\":\"1.36.1-gke.1\","
                        + "\"nodePools\":[{\"name\":\"default-pool\",\"version\":\"1.34.0-gke.1\"}]}}")
                .when().post(BASE + "/clusters")
                .then()
                .statusCode(200);

        String advertised = given()
                .when().get(BASE + "/serverConfig")
                .then()
                .statusCode(200)
                .extract().path("defaultClusterVersion");

        given()
                .contentType("application/json")
                .body("{\"nodeVersion\":\"1.35\"}")
                .when().put(poolPath)
                .then()
                .statusCode(200)
                .body("status", equalTo("DONE"));

        given()
                .when().get(poolPath)
                .then()
                .statusCode(200)
                .body("version", equalTo(advertised))
                .body("version", not(equalTo("1.35")));

        // #233: the cluster aggregate follows its pools, so it moves with the only pool.
        given()
                .when().get(BASE + "/clusters/" + cluster)
                .then()
                .statusCode(200)
                .body("currentNodeVersion", equalTo(advertised))
                .body("nodePools[0].version", equalTo(advertised));

        // `gcloud container clusters upgrade C --node-pool default-pool` with no --cluster-version:
        // "-" means the cluster's master version.
        given()
                .contentType("application/json")
                .body("{\"nodeVersion\":\"-\"}")
                .when().put(poolPath)
                .then()
                .statusCode(200)
                .body("status", equalTo("DONE"));

        given()
                .when().get(poolPath)
                .then()
                .statusCode(200)
                .body("version", equalTo("1.36.1-gke.1"));
    }

    @Test
    void aNonStringVersionIsABadRequestNotAServerError() {
        String cluster = "pool-shape";
        String poolPath = BASE + "/clusters/" + cluster + "/nodePools/default-pool";

        given()
                .contentType("application/json")
                .body("{\"cluster\":{\"name\":\"" + cluster + "\"}}")
                .when().post(BASE + "/clusters")
                .then()
                .statusCode(200);

        String versionBefore = given()
                .when().get(poolPath)
                .then()
                .statusCode(200)
                .extract().path("version");

        // "1.27" is a well-formed alias that matches no valid version (#231): a 400, not a stored alias.
        for (String body : new String[] {"{\"nodeVersion\":123}", "{\"nodeVersion\":{\"v\":\"1\"}}",
                "{\"nodeVersion\":\"1\"}", "{\"nodeVersion\":\"1.27\"}"}) {
            given()
                    .contentType("application/json")
                    .body(body)
                    .when().put(poolPath)
                    .then()
                    .statusCode(400)
                    .body("error.status", equalTo("INVALID_ARGUMENT"));
        }

        given()
                .when().get(poolPath)
                .then()
                .statusCode(200)
                .body("version", equalTo(versionBefore));
    }

    /** The body terraform-provider-google sends for a {@code workload_metadata_config} change (node_config.go). */
    @Test
    void terraformsWorkloadMetadataUpdateIsAppliedToThePool() {
        String cluster = "wi-metadata";
        String poolPath = BASE + "/clusters/" + cluster + "/nodePools/default-pool";
        given().contentType("application/json")
                .body("{\"cluster\":{\"name\":\"" + cluster + "\",\"workloadIdentityConfig\":"
                        + "{\"workloadPool\":\"" + PROJECT + ".svc.id.goog\"}}}")
                .when().post(BASE + "/clusters")
                .then().statusCode(200);
        given().when().get(poolPath).then().statusCode(200)
                .body("config.workloadMetadataConfig.mode", equalTo("GKE_METADATA"));

        given().contentType("application/json")
                .body("{\"name\":\"projects/" + PROJECT + "/locations/" + LOCATION + "/clusters/" + cluster
                        + "/nodePools/default-pool\",\"workloadMetadataConfig\":{\"mode\":\"GCE_METADATA\"}}")
                .when().put(poolPath)
                .then().statusCode(200);

        given().when().get(poolPath).then().statusCode(200)
                .body("config.workloadMetadataConfig.mode", equalTo("GCE_METADATA"));
    }
}
