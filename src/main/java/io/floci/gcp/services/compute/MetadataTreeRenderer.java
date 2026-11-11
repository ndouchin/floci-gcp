package io.floci.gcp.services.compute;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.floci.gcp.services.compute.ComputeMetadataTree.Dir;
import io.floci.gcp.services.compute.ComputeMetadataTree.Leaf;
import io.floci.gcp.services.compute.ComputeMetadataTree.Node;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.TreeMap;

/**
 * Serves a metadata tree the way the Compute Engine and GKE metadata servers do (Compute docs, querying metadata):
 * a value as text, or as JSON with {@code alt=json}; a directory as a listing, or with {@code recursive=true} as one
 * JSON document ({@code alt=text} for {@code path value} lines); a directory named without its trailing slash as a
 * 301 to the slash form. Generated values ({@code token}, {@code identity}) are left to the caller.
 */
public final class MetadataTreeRenderer {

    public static final String TEXT = "application/text";
    public static final String JSON_TYPE = "application/json";
    private static final ObjectMapper JSON = new ObjectMapper();

    /** What a path under {@code /computeMetadata/v1/} answers. */
    public sealed interface Result {
    }

    public record NotFound() implements Result {
    }

    /** A directory asked for without its slash: the location to send the client to. */
    public record Redirect(String location) implements Result {
    }

    /** A generated value; {@code path} is its key path, ending in {@code token} or {@code identity}. */
    public record Generated(List<String> path) implements Result {
    }

    public record Rendered(String body, String contentType, String etag, boolean directory) implements Result {
    }

    private MetadataTreeRenderer() {
    }

    /**
     * Renders {@code path} (relative to {@code /computeMetadata/v1/}) of {@code tree}. {@code slash} says whether the
     * request path ended in a slash, which a framework path parameter loses; {@code jsonValues} are the value paths
     * served as JSON without {@code alt=json}.
     */
    public static Result render(Dir tree, String path, boolean slash, boolean recursive, String alt, String rawQuery,
                                Set<String> jsonValues) {
        String trimmed = path.endsWith("/") ? path.substring(0, path.length() - 1) : path;
        Node node = tree;
        if (!trimmed.isEmpty()) {
            for (String part : trimmed.split("/")) {
                if (!(node instanceof Dir dir) || !dir.children().containsKey(part)) {
                    return new NotFound();
                }
                node = dir.children().get(part);
            }
        }
        if (node instanceof Leaf leaf && leaf.generated()) {
            return new Generated(List.of(trimmed.split("/")));
        }
        if (node instanceof Dir dir) {
            if (!slash) {
                return new Redirect("/computeMetadata/v1/" + path + "/"
                        + (rawQuery == null || rawQuery.isEmpty() ? "" : "?" + rawQuery));
            }
            if (recursive) {
                String body = "text".equals(alt) ? ComputeMetadataTree.text(dir, "") : write(ComputeMetadataTree.json(dir));
                return rendered(body, "text".equals(alt) ? TEXT : JSON_TYPE, true);
            }
            StringBuilder listing = new StringBuilder();
            new TreeMap<>(dir.children()).forEach((key, child) ->
                    listing.append(key).append(child instanceof Dir ? "/" : "").append('\n'));
            return rendered(listing.toString(), TEXT, true);
        }
        Leaf leaf = (Leaf) node;
        boolean json = "json".equals(alt) || (alt == null && jsonValues.contains(trimmed));
        return rendered(json ? write(leaf.json()) : leaf.text(), json ? JSON_TYPE : TEXT, false);
    }

    /** The email of a service account directory, which is listed both as {@code default} and by its email. */
    public static String accountEmail(Dir tree, String account) {
        Node node = tree;
        for (String part : new String[] {"instance", "service-accounts", account, "email"}) {
            node = ((Dir) node).children().get(part);
        }
        return ((Leaf) node).text();
    }

    public static Rendered rendered(String body, String type, boolean directory) {
        return new Rendered(body, type, etag(body), directory);
    }

    public static String etag(String body) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(body.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest, 0, 8);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    public static String write(JsonNode node) {
        try {
            return JSON.writeValueAsString(node);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
