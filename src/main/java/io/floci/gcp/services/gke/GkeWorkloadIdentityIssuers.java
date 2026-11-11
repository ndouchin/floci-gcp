package io.floci.gcp.services.gke;

import io.floci.gcp.core.common.ProjectNumbers;
import io.floci.gcp.core.common.WorkloadIdentityIssuers;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** GKE clusters as the identity providers STS federates, named by their issuer URL. */
@ApplicationScoped
public class GkeWorkloadIdentityIssuers implements WorkloadIdentityIssuers {

    private static final Pattern ISSUER = Pattern.compile(
            "^https://container\\.googleapis\\.com/v1/projects/([^/]+)/locations/([^/]+)/clusters/([^/]+)$");

    private final GkeService gke;

    @Inject
    public GkeWorkloadIdentityIssuers(GkeService gke) {
        this.gke = gke;
    }

    @Override
    public Optional<Provider> provider(String issuer) {
        Matcher matcher = issuer == null ? null : ISSUER.matcher(issuer);
        if (matcher == null || !matcher.matches()) {
            return Optional.empty();
        }
        return gke.findCluster(matcher.group(1), matcher.group(2), matcher.group(3)).map(cluster -> new Provider(
                GkeWorkloadIdentity.issuer(cluster), cluster.getWorkloadPool(), ProjectNumbers.of(cluster.getProject()),
                cluster.getJwks()));
    }

    @Override
    public Optional<String> refreshJwks(String issuer) {
        Matcher matcher = issuer == null ? null : ISSUER.matcher(issuer);
        if (matcher == null || !matcher.matches()) {
            return Optional.empty();
        }
        return gke.refreshJwks(matcher.group(1), matcher.group(2), matcher.group(3));
    }
}
