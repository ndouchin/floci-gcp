package io.floci.gcp.core.common;

import java.util.Optional;

/**
 * The OIDC identity providers whose tokens STS federates: GKE clusters with Workload Identity, named by their issuer
 * URL. Lets STS verify a cluster's ServiceAccount tokens without depending on the service that runs the cluster.
 */
public interface WorkloadIdentityIssuers {

    /**
     * A provider: its issuer URL, the workload pool it belongs to (null when Workload Identity is disabled on it), the
     * number of the pool's project, and the JSON Web Key Set it signs tokens with.
     */
    record Provider(String issuer, String pool, String projectNumber, String jwks) {
    }

    /** The provider with this issuer URL, if this emulator runs it. */
    Optional<Provider> provider(String issuer);

    /** Reads the provider's keys again, for a token signed with a key the known set lacks. */
    Optional<String> refreshJwks(String issuer);
}
