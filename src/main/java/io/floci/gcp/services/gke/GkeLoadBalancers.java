package io.floci.gcp.services.gke;

final class GkeLoadBalancers {

    static final String MODE_EMULATED = "emulated";
    static final String MODE_SERVICELB = "servicelb";
    static final String MODE_OFF = "off";

    private GkeLoadBalancers() {
    }

    /** Whether k3s's own servicelb is disabled: in {@code emulated} and {@code off} modes; clusters of earlier builds keep it. */
    static boolean replacesServiceLb(String mode) {
        return MODE_EMULATED.equals(mode) || MODE_OFF.equals(mode);
    }
}
