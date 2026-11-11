package io.floci.gcp.services.sts;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.floci.gcp.core.common.WorkloadIdentityIssuers;
import io.floci.gcp.services.iam.WorkloadIdentityPrincipals;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.AlgorithmParameters;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.ECParameterSpec;
import java.security.spec.ECPoint;
import java.security.spec.ECPublicKeySpec;
import java.security.spec.RSAPublicKeySpec;
import java.time.Clock;
import java.time.Duration;
import java.util.Base64;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Verifies a Kubernetes ServiceAccount token presented to STS for a Workload Identity Federation for GKE audience
 * ({@code identitynamespace:POOL:PROVIDER}), as the STS {@code token} reference describes a JWT subject token: a
 * {@code kid} and an {@code RS256} or {@code ES256} {@code alg} in the header, a signature by one of the provider's
 * keys, {@code iss} equal to the provider, {@code aud} naming the pool, {@code exp} in the future, and {@code iat}
 * in the past and no more than 24 hours old. The subject must be a ServiceAccount
 * ({@code system:serviceaccount:NS:KSA}).
 */
final class WorkloadIdentityTokenVerifier {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Set<String> ALGORITHMS = Set.of("RS256", "ES256");
    private static final Duration MAX_AGE = Duration.ofHours(24);
    private static final Duration SKEW = Duration.ofMinutes(5);
    private static final Pattern SUBJECT = Pattern.compile("^system:serviceaccount:([^:]+):([^:]+)$");
    private static final String AUDIENCE_PREFIX = "identitynamespace:";

    private final WorkloadIdentityIssuers issuers;
    private final Clock clock;

    WorkloadIdentityTokenVerifier(WorkloadIdentityIssuers issuers, Clock clock) {
        this.issuers = issuers;
        this.clock = clock;
    }

    /** The workload a valid token proves, for an audience naming a pool and a cluster this emulator runs. */
    WorkloadIdentityPrincipals.Workload verify(String token, String audience) {
        if (audience == null || audience.isBlank()) {
            throw invalidRequest("audience is required for a jwt subject token");
        }
        if (audience.startsWith("//iam.googleapis.com/")) {
            throw invalidRequest("Only Workload Identity Federation for GKE audiences (identitynamespace:POOL:PROVIDER) "
                    + "are emulated; workload identity pool providers are not");
        }
        if (!audience.startsWith(AUDIENCE_PREFIX) || audience.indexOf(':', AUDIENCE_PREFIX.length()) < 0) {
            throw invalidRequest("Invalid audience \"" + audience + "\": expected identitynamespace:POOL:PROVIDER");
        }
        int split = audience.indexOf(':', AUDIENCE_PREFIX.length());
        String pool = audience.substring(AUDIENCE_PREFIX.length(), split);
        String providerUrl = audience.substring(split + 1);
        WorkloadIdentityIssuers.Provider provider = (issuers == null ? Optional.<WorkloadIdentityIssuers.Provider>empty()
                : issuers.provider(providerUrl))
                .orElseThrow(() -> invalidRequest("Unknown identity provider \"" + providerUrl + "\""));

        String[] parts = token == null ? new String[0] : token.split("\\.", -1);
        if (parts.length != 3) {
            throw invalidGrant("The subject token is not a JWT");
        }
        JsonNode header = decode(parts[0], "header");
        JsonNode claims = decode(parts[1], "payload");
        String alg = header.path("alg").asText("");
        if (!ALGORITHMS.contains(alg)) {
            throw invalidGrant("Unsupported JWT signing algorithm \"" + alg + "\": expected RS256 or ES256");
        }
        String kid = header.path("kid").asText("");
        if (kid.isEmpty()) {
            throw invalidGrant("The JWT header has no kid");
        }
        if (!provider.issuer().equals(claims.path("iss").asText())) {
            throw invalidGrant("The JWT issuer \"" + claims.path("iss").asText() + "\" is not the provider \""
                    + provider.issuer() + "\"");
        }
        JsonNode key = key(provider.jwks(), kid);
        if (key == null && issuers != null) {
            key = issuers.refreshJwks(providerUrl).map(jwks -> key(jwks, kid)).orElse(null);
        }
        if (key == null) {
            throw invalidGrant("The JWT is signed with an unknown key \"" + kid + "\"");
        }
        if (!signatureValid(alg, key, parts)) {
            throw invalidGrant("The JWT signature is invalid");
        }
        if (!audiences(claims).contains(pool)) {
            throw invalidGrant("The JWT audience does not include the workload pool \"" + pool + "\"");
        }
        long now = clock.instant().getEpochSecond();
        if (!claims.path("exp").canConvertToLong() || claims.path("exp").asLong() <= now) {
            throw invalidGrant("The JWT has expired");
        }
        if (!claims.path("iat").canConvertToLong() || claims.path("iat").asLong() > now + SKEW.toSeconds()
                || claims.path("iat").asLong() < now - MAX_AGE.toSeconds()) {
            throw invalidGrant("The JWT iat must be in the past and no more than 24 hours old");
        }
        Matcher subject = SUBJECT.matcher(claims.path("sub").asText(""));
        if (!subject.matches()) {
            throw invalidGrant("The JWT subject \"" + claims.path("sub").asText() + "\" is not a Kubernetes ServiceAccount");
        }
        if (provider.pool() == null || !provider.pool().equals(pool)) {
            throw invalidGrant("The identity provider \"" + providerUrl + "\" is not in the workload pool \"" + pool + "\"");
        }
        String uid = claims.path("kubernetes.io").path("serviceaccount").path("uid").asText(null);
        return new WorkloadIdentityPrincipals.Workload(provider.projectNumber(), pool, subject.group(1), subject.group(2),
                uid, provider.issuer());
    }

    private static Set<String> audiences(JsonNode claims) {
        JsonNode aud = claims.path("aud");
        if (aud.isTextual()) {
            return Set.of(aud.asText());
        }
        Set<String> values = new HashSet<>();
        aud.forEach(value -> values.add(value.asText()));
        return values;
    }

    private static JsonNode decode(String part, String what) {
        try {
            return JSON.readTree(Base64.getUrlDecoder().decode(part));
        } catch (Exception e) {
            throw invalidGrant("The JWT " + what + " is not base64url-encoded JSON");
        }
    }

    private static JsonNode key(String jwks, String kid) {
        if (jwks == null || jwks.isBlank()) {
            return null;
        }
        try {
            for (JsonNode key : JSON.readTree(jwks).path("keys")) {
                if (kid.equals(key.path("kid").asText())) {
                    return key;
                }
            }
        } catch (Exception e) {
            return null;
        }
        return null;
    }

    private static boolean signatureValid(String alg, JsonNode jwk, String[] parts) {
        try {
            byte[] signature = Base64.getUrlDecoder().decode(parts[2]);
            Signature verifier;
            PublicKey key;
            if ("RS256".equals(alg)) {
                if (!"RSA".equals(jwk.path("kty").asText())) {
                    return false;
                }
                key = KeyFactory.getInstance("RSA").generatePublic(new RSAPublicKeySpec(
                        unsigned(jwk.path("n").asText()), unsigned(jwk.path("e").asText())));
                verifier = Signature.getInstance("SHA256withRSA");
            } else {
                if (!"EC".equals(jwk.path("kty").asText()) || !"P-256".equals(jwk.path("crv").asText())
                        || signature.length != 64) {
                    return false;
                }
                AlgorithmParameters parameters = AlgorithmParameters.getInstance("EC");
                parameters.init(new ECGenParameterSpec("secp256r1"));
                key = KeyFactory.getInstance("EC").generatePublic(new ECPublicKeySpec(new ECPoint(
                        unsigned(jwk.path("x").asText()), unsigned(jwk.path("y").asText())),
                        parameters.getParameterSpec(ECParameterSpec.class)));
                verifier = Signature.getInstance("SHA256withECDSAinP1363Format");
            }
            verifier.initVerify(key);
            verifier.update((parts[0] + "." + parts[1]).getBytes(StandardCharsets.US_ASCII));
            return verifier.verify(signature);
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            return false;
        }
    }

    private static BigInteger unsigned(String base64Url) {
        return new BigInteger(1, Base64.getUrlDecoder().decode(base64Url));
    }

    private static StsException invalidRequest(String message) {
        return new StsException("invalid_request", message);
    }

    private static StsException invalidGrant(String message) {
        return new StsException("invalid_grant", message);
    }
}
