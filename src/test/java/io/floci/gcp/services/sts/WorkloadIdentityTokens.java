package io.floci.gcp.services.sts;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.security.Signature;
import java.security.interfaces.RSAPublicKey;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;

/** A local RSA key standing in for a cluster's ServiceAccount signing key, and the KSA tokens it signs. */
final class WorkloadIdentityTokens {

    static final String ISSUER = "https://container.googleapis.com/v1/projects/wi-proj/locations/us-central1/clusters/c1";
    static final String POOL = "wi-proj.svc.id.goog";
    static final String AUDIENCE = "identitynamespace:" + POOL + ":" + ISSUER;
    static final String KID = "k1";
    static final KeyPair KEYS = keys();
    private static final ObjectMapper JSON = new ObjectMapper();

    private WorkloadIdentityTokens() {
    }

    private static KeyPair keys() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            return generator.generateKeyPair();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    static String jwks() {
        RSAPublicKey key = (RSAPublicKey) KEYS.getPublic();
        return "{\"keys\":[{\"use\":\"sig\",\"kty\":\"RSA\",\"kid\":\"" + KID + "\",\"alg\":\"RS256\",\"n\":\""
                + b64(unsigned(key.getModulus())) + "\",\"e\":\"" + b64(unsigned(key.getPublicExponent())) + "\"}]}";
    }

    /** A TokenRequest token for {@code team/app} with the given audience and times, as k3s signs it. */
    static ObjectNode claims(Instant now) {
        ObjectNode claims = JSON.createObjectNode();
        claims.putArray("aud").add(POOL);
        claims.put("exp", now.plusSeconds(600).getEpochSecond());
        claims.put("iat", now.getEpochSecond());
        claims.put("nbf", now.getEpochSecond());
        claims.put("iss", ISSUER);
        claims.put("sub", "system:serviceaccount:team:app");
        ObjectNode kubernetes = claims.putObject("kubernetes.io");
        kubernetes.put("namespace", "team");
        kubernetes.putObject("serviceaccount").put("name", "app").put("uid", "uid-app");
        return claims;
    }

    static String sign(ObjectNode claims) {
        return sign(Map.of("alg", "RS256", "kid", KID), claims);
    }

    static String sign(Map<String, String> header, ObjectNode claims) {
        try {
            String input = b64(JSON.writeValueAsBytes(header)) + "." + b64(JSON.writeValueAsBytes(claims));
            Signature signature = Signature.getInstance("SHA256withRSA");
            signature.initSign(KEYS.getPrivate());
            signature.update(input.getBytes(StandardCharsets.US_ASCII));
            return input + "." + b64(signature.sign());
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static byte[] unsigned(BigInteger value) {
        byte[] bytes = value.toByteArray();
        if (bytes.length > 1 && bytes[0] == 0) {
            byte[] trimmed = new byte[bytes.length - 1];
            System.arraycopy(bytes, 1, trimmed, 0, trimmed.length);
            return trimmed;
        }
        return bytes;
    }

    static String b64(byte[] bytes) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
