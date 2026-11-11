package io.floci.gcp.services.gke;

import io.smallrye.common.annotation.Blocking;
import io.smallrye.mutiny.Uni;
import io.vertx.core.MultiMap;
import io.vertx.core.http.HttpServerRequest;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.Response;

import java.net.URI;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The GKE metadata server's responder, called by the metadata relay next to each node of a real cluster. The relay
 * forwards every request it gets on the GKE metadata listener ({@code gke}) or the Compute Engine one ({@code gce}),
 * adding the caller's address and the cluster's relay key. This is floci-gcp plumbing under {@code _floci-gcp/...},
 * not a GCP API.
 */
@ApplicationScoped
@Path("_floci-gcp/gke/metadata/projects/{project}/locations/{location}/clusters/{cluster}/nodes/{node}")
public class GkeMetadataController {

    static final String PEER_HEADER = "X-Floci-Gke-Peer";
    static final String RELAY_KEY_HEADER = "X-Floci-Gke-Relay-Key";
    private static final Duration MAX_WAIT = Duration.ofSeconds(300);

    private final GkeMetadataService service;

    @Inject
    public GkeMetadataController(GkeMetadataService service) {
        this.service = service;
    }

    @GET
    @Blocking
    @Path("{listener: gke|gce}")
    public Uni<Response> root(@PathParam("project") String project, @PathParam("location") String location,
                              @PathParam("cluster") String cluster, @PathParam("node") String node,
                              @PathParam("listener") String listener, @Context HttpHeaders headers,
                              @Context HttpServerRequest request) {
        return answer(project, location, cluster, node, listener, "", headers, request);
    }

    @GET
    @Blocking
    @Path("{listener: gke|gce}/{path:.*}")
    public Uni<Response> get(@PathParam("project") String project, @PathParam("location") String location,
                             @PathParam("cluster") String cluster, @PathParam("node") String node,
                             @PathParam("listener") String listener, @PathParam("path") String path,
                             @Context HttpHeaders headers, @Context HttpServerRequest request) {
        return answer(project, location, cluster, node, listener, path, headers, request);
    }

    private Uni<Response> answer(String project, String location, String cluster, String node, String listener,
                                 String path, HttpHeaders headers, HttpServerRequest request) {
        MultiMap params = request.params();
        Map<String, String> query = new LinkedHashMap<>();
        params.names().forEach(name -> query.put(name, params.get(name)));
        GkeMetadataService.Answer answer = service.answer(new GkeMetadataService.Request(project, location, cluster,
                node, listener, path, path.isEmpty() || request.path().endsWith("/"),
                headers.getHeaderString(PEER_HEADER), headers.getHeaderString(RELAY_KEY_HEADER),
                headers.getHeaderString("Metadata-Flavor"), headers.getHeaderString("X-Forwarded-For"), query));
        Response.ResponseBuilder response = Response.status(answer.status()).header("Metadata-Flavor", "Google");
        if (answer.body() != null) {
            response.entity(answer.body()).type(answer.contentType());
        }
        if (answer.etag() != null) {
            response.header("ETag", answer.etag());
        }
        if (answer.location() != null) {
            response.location(URI.create(answer.location()));
        }
        Response built = response.build();
        if (answer.status() == 200 && answer.etag() != null && "true".equalsIgnoreCase(query.get("wait_for_change"))
                && (query.get("last_etag") == null || query.get("last_etag").equals(answer.etag()))) {
            return Uni.createFrom().item(built).onItem().delayIt().by(waitTimeout(query.get("timeout_sec")));
        }
        return Uni.createFrom().item(built);
    }

    /** GKE's metadata values do not change while a node runs, so a wait lasts its whole timeout. */
    private static Duration waitTimeout(String value) {
        try {
            long seconds = value == null ? MAX_WAIT.toSeconds() : Long.parseLong(value);
            return Duration.ofSeconds(Math.max(1, Math.min(seconds, MAX_WAIT.toSeconds())));
        } catch (NumberFormatException e) {
            return MAX_WAIT;
        }
    }
}
