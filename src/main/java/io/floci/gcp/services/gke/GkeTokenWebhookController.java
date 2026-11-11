package io.floci.gcp.services.gke;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.jboss.logging.Logger;

import java.util.Map;
import java.util.Optional;

/**
 * Kubernetes token-authentication webhook for k3s-backed GKE clusters.
 *
 * <p>The k3s API server is configured (see {@code GkeClusterManager}) to POST a {@code TokenReview}
 * here for any bearer token it does not recognise — notably the GCP access token produced by
 * {@code gke-gcloud-auth-plugin}, i.e. the credential that {@code gcloud container clusters
 * get-credentials} wires into the kubeconfig. {@link GkeTokenReviewer} decides who the token is.
 * Each cluster calls its own address, which names it; clusters started by an older build call the
 * bare address.
 *
 * <p>This is floci-gcp plumbing under the {@code _floci-gcp/...} namespace, not a GCP API.
 */
@ApplicationScoped
@Path("_floci-gcp/gke/token-webhook")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class GkeTokenWebhookController {

    private static final Logger LOG = Logger.getLogger(GkeTokenWebhookController.class);

    private final GkeTokenReviewer reviewer;

    @Inject
    public GkeTokenWebhookController(GkeTokenReviewer reviewer) {
        this.reviewer = reviewer;
    }

    @POST
    public Response review(Map<String, Object> tokenReview) {
        return respond(tokenReview, null, null, null);
    }

    @POST
    @Path("projects/{project}/locations/{location}/clusters/{cluster}")
    public Response reviewForCluster(@PathParam("project") String project, @PathParam("location") String location,
                                     @PathParam("cluster") String cluster, Map<String, Object> tokenReview) {
        return respond(tokenReview, project, location, cluster);
    }

    private Response respond(Map<String, Object> tokenReview, String project, String location, String cluster) {
        // The response apiVersion MUST match the request's (the kube-apiserver sends v1beta1 by
        // default and cannot convert a v1 response back). Echo whatever the apiserver sent.
        String apiVersion = tokenReview != null && tokenReview.get("apiVersion") instanceof String v
                ? v : "authentication.k8s.io/v1";

        Optional<GkeTokenReviewer.User> user = reviewer.review(extractToken(tokenReview), project, location, cluster);
        if (user.isPresent()) {
            LOG.debugv("GKE token-webhook: authenticated {0} with groups {1}", user.get().username(), user.get().groups());
            return Response.ok(Map.of(
                    "apiVersion", apiVersion,
                    "kind", "TokenReview",
                    "status", Map.of(
                            "authenticated", true,
                            "user", Map.of(
                                    "username", user.get().username(),
                                    "uid", user.get().uid(),
                                    "groups", user.get().groups())))).build();
        }

        LOG.debug("GKE token-webhook: rejecting a token that names no principal");
        return Response.ok(Map.of(
                "apiVersion", apiVersion,
                "kind", "TokenReview",
                "status", Map.of("authenticated", false))).build();
    }

    private static String extractToken(Map<String, Object> tokenReview) {
        if (tokenReview != null && tokenReview.get("spec") instanceof Map<?, ?> spec
                && spec.get("token") instanceof String token) {
            return token;
        }
        return null;
    }
}
