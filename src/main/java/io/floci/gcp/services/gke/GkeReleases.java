package io.floci.gcp.services.gke;

import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The Kubernetes minor versions the emulator can run, each pinned to one {@code rancher/k3s} release,
 * and the GKE release channels that offer them. A minor is advertised as
 * {@code 1.<minor>.<k3s patch>-gke.<GKE_BUILD>}; any other patch or build of a supported minor runs on
 * the same image. Channel membership and defaults follow the GKE release schedule as of 2026-11-10.
 */
final class GkeReleases {

    static final String GKE_BUILD = "1000000";

    enum Channel {
        RAPID, REGULAR, STABLE, EXTENDED
    }

    record Release(int minor, String k3sTag, Set<Channel> channels, boolean standardSupport) {

        String gkeVersion() {
            Matcher m = K3S_TAG.matcher(k3sTag);
            if (!m.matches()) {
                throw new IllegalStateException("Malformed k3s tag " + k3sTag);
            }
            return "1." + minor + "." + m.group(1) + "-gke." + GKE_BUILD;
        }

        String minorVersion() {
            return "1." + minor;
        }
    }

    private static final Pattern K3S_TAG = Pattern.compile("^v1\\.\\d+\\.(\\d+)-k3s\\d+$");
    private static final Pattern MINOR = Pattern.compile("^1\\.(\\d{1,4})(?:[.-].*)?$");

    static final List<Release> RELEASES = List.of(
            new Release(32, "v1.32.13-k3s1", EnumSet.of(Channel.EXTENDED), false),
            new Release(33, "v1.33.13-k3s2", EnumSet.of(Channel.EXTENDED), false),
            new Release(34, "v1.34.13-k3s1", EnumSet.of(Channel.REGULAR, Channel.STABLE, Channel.EXTENDED), true),
            new Release(35, "v1.35.10-k3s1", EnumSet.allOf(Channel.class), true),
            new Release(36, "v1.36.6-k3s1", EnumSet.allOf(Channel.class), true),
            new Release(37, "v1.37.2-k3s1", EnumSet.of(Channel.RAPID, Channel.REGULAR, Channel.EXTENDED), true));

    private static final Map<Channel, Integer> CHANNEL_DEFAULT_MINOR = Map.of(
            Channel.RAPID, 36,
            Channel.REGULAR, 35,
            Channel.STABLE, 35,
            Channel.EXTENDED, 35);

    private GkeReleases() {
    }

    /** The minor of a {@code 1.X[.Y[-gke.N]]} version, or -1 when it has none. */
    static int minorOf(String version) {
        if (version == null) {
            return -1;
        }
        Matcher m = MINOR.matcher(version);
        return m.matches() ? Integer.parseInt(m.group(1)) : -1;
    }

    static Optional<Release> forMinor(int minor) {
        return RELEASES.stream().filter(r -> r.minor() == minor).findFirst();
    }

    static Optional<Release> forVersion(String version) {
        return forMinor(minorOf(version));
    }

    /** The release a cluster gets when it names no version: its channel's default, REGULAR's without one. */
    static Release defaultRelease(Optional<Channel> channel) {
        return forMinor(CHANNEL_DEFAULT_MINOR.get(channel.orElse(Channel.REGULAR))).orElseThrow();
    }

    /** The highest release {@code latest} resolves to: in the cluster's channel, or among the standard-support minors. */
    static Release latestRelease(Optional<Channel> channel) {
        return RELEASES.stream()
                .filter(r -> channel.map(c -> r.channels().contains(c)).orElse(r.standardSupport()))
                .max(Comparator.comparingInt(Release::minor))
                .orElseThrow();
    }

    static List<String> validMasterVersions() {
        return RELEASES.stream().filter(Release::standardSupport)
                .sorted(Comparator.comparingInt(Release::minor).reversed())
                .map(Release::gkeVersion).toList();
    }

    static List<String> validNodeVersions() {
        return RELEASES.stream()
                .sorted(Comparator.comparingInt(Release::minor).reversed())
                .map(Release::gkeVersion).toList();
    }

    static List<String> channelVersions(Channel channel) {
        return RELEASES.stream().filter(r -> r.channels().contains(channel))
                .sorted(Comparator.comparingInt(Release::minor).reversed())
                .map(Release::gkeVersion).toList();
    }

    static Optional<Channel> channel(Object releaseChannel) {
        if (releaseChannel instanceof Map<?, ?> map && map.get("channel") instanceof String name) {
            try {
                return Optional.of(Channel.valueOf(name));
            } catch (IllegalArgumentException e) {
                return Optional.empty();
            }
        }
        return Optional.empty();
    }
}
