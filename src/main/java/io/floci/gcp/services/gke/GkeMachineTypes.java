package io.floci.gcp.services.gke;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The Compute Engine machine types GKE node pools use, as far as a node's size and attached GPUs go.
 * Accelerator-optimized types carry their GPUs; general-purpose types are read from their name
 * ({@code <series>-<class>-<vCPUs>}) with the memory per vCPU of their class.
 */
final class GkeMachineTypes {

    static final String DEFAULT_MACHINE_TYPE = "e2-medium";

    record Shape(String name, String family, int cpus, int memoryMb, boolean sharedCore,
                 String acceleratorType, int acceleratorCount) {

        boolean hasGpus() {
            return acceleratorType != null && acceleratorCount > 0;
        }
    }

    private static final Pattern GENERAL = Pattern.compile("^([a-z][a-z0-9]*)-(standard|highmem|highcpu)-(\\d+)$");
    private static final Map<String, Shape> FIXED = fixedShapes();

    private GkeMachineTypes() {
    }

    /** The shape of a machine type, or empty when its name follows no known pattern. */
    static Optional<Shape> shape(String machineType) {
        String name = machineType == null || machineType.isBlank() ? DEFAULT_MACHINE_TYPE : machineType;
        Shape fixed = FIXED.get(name);
        if (fixed != null) {
            return Optional.of(fixed);
        }
        Matcher m = GENERAL.matcher(name);
        if (!m.matches()) {
            return Optional.empty();
        }
        String series = m.group(1);
        int cpus = Integer.parseInt(m.group(3));
        int perCpuMb = switch (m.group(2)) {
            case "highmem" -> "n1".equals(series) ? 6656 : 8192;
            case "highcpu" -> "n1".equals(series) ? 922 : "c3".equals(series) ? 2048 : 1024;
            default -> "n1".equals(series) ? 3840 : 4096;
        };
        if ("g2".equals(series)) {
            return Optional.of(new Shape(name, series, cpus, cpus * 4096, false, "nvidia-l4", l4Count(cpus)));
        }
        return Optional.of(new Shape(name, series, cpus, cpus * perCpuMb, false, null, 0));
    }

    /** The machine series a node label names ({@code cloud.google.com/machine-family}), such as {@code e2}. */
    static String family(String machineType) {
        String name = machineType == null || machineType.isBlank() ? DEFAULT_MACHINE_TYPE : machineType;
        int dash = name.indexOf('-');
        return dash > 0 ? name.substring(0, dash) : name;
    }

    private static int l4Count(int cpus) {
        return switch (cpus) {
            case 24 -> 2;
            case 48 -> 4;
            case 96 -> 8;
            default -> 1;
        };
    }

    private static Map<String, Shape> fixedShapes() {
        Map<String, Shape> shapes = new HashMap<>();
        shared(shapes, "e2-micro", 1024);
        shared(shapes, "e2-small", 2048);
        shared(shapes, "e2-medium", 4096);
        gpu(shapes, "a2-highgpu-1g", 12, 85, "nvidia-tesla-a100", 1);
        gpu(shapes, "a2-highgpu-2g", 24, 170, "nvidia-tesla-a100", 2);
        gpu(shapes, "a2-highgpu-4g", 48, 340, "nvidia-tesla-a100", 4);
        gpu(shapes, "a2-highgpu-8g", 96, 680, "nvidia-tesla-a100", 8);
        gpu(shapes, "a2-megagpu-16g", 96, 1360, "nvidia-tesla-a100", 16);
        gpu(shapes, "a2-ultragpu-1g", 12, 170, "nvidia-a100-80gb", 1);
        gpu(shapes, "a2-ultragpu-2g", 24, 340, "nvidia-a100-80gb", 2);
        gpu(shapes, "a2-ultragpu-4g", 48, 680, "nvidia-a100-80gb", 4);
        gpu(shapes, "a2-ultragpu-8g", 96, 1360, "nvidia-a100-80gb", 8);
        gpu(shapes, "a3-highgpu-1g", 26, 234, "nvidia-h100-80gb", 1);
        gpu(shapes, "a3-highgpu-2g", 52, 468, "nvidia-h100-80gb", 2);
        gpu(shapes, "a3-highgpu-4g", 104, 936, "nvidia-h100-80gb", 4);
        gpu(shapes, "a3-highgpu-8g", 208, 1872, "nvidia-h100-80gb", 8);
        gpu(shapes, "a3-megagpu-8g", 208, 1872, "nvidia-h100-mega-80gb", 8);
        gpu(shapes, "a3-ultragpu-8g", 224, 2952, "nvidia-h200-141gb", 8);
        gpu(shapes, "a4-highgpu-8g", 224, 3968, "nvidia-b200", 8);
        gpu(shapes, "g4-standard-48", 48, 180, "nvidia-rtx-pro-6000", 1);
        gpu(shapes, "g4-standard-96", 96, 360, "nvidia-rtx-pro-6000", 2);
        gpu(shapes, "g4-standard-192", 192, 720, "nvidia-rtx-pro-6000", 4);
        gpu(shapes, "g4-standard-384", 384, 1440, "nvidia-rtx-pro-6000", 8);
        return shapes;
    }

    private static void shared(Map<String, Shape> shapes, String name, int memoryMb) {
        shapes.put(name, new Shape(name, "e2", 2, memoryMb, true, null, 0));
    }

    private static void gpu(Map<String, Shape> shapes, String name, int cpus, int memoryGb, String accelerator,
                            int count) {
        shapes.put(name, new Shape(name, family(name), cpus, memoryGb * 1024, false, accelerator, count));
    }
}
