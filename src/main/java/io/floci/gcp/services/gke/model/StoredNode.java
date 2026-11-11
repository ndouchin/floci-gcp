package io.floci.gcp.services.gke.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.quarkus.runtime.annotations.RegisterForReflection;

/**
 * Emulator-internal record of one node of a real-mode node pool: the k3s agent container that backs
 * it and the Kubernetes node name it registers under. Never returned to clients.
 */
@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public class StoredNode {

    private String name;
    private String zone;
    private String containerName;
    private String containerId;
    private String volumeName;
    private String image;

    public StoredNode() {
    }

    public StoredNode(String name, String zone) {
        this.name = name;
        this.zone = zone;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getZone() {
        return zone;
    }

    public void setZone(String zone) {
        this.zone = zone;
    }

    public String getContainerName() {
        return containerName;
    }

    public void setContainerName(String containerName) {
        this.containerName = containerName;
    }

    public String getContainerId() {
        return containerId;
    }

    public void setContainerId(String containerId) {
        this.containerId = containerId;
    }

    public String getVolumeName() {
        return volumeName;
    }

    public void setVolumeName(String volumeName) {
        this.volumeName = volumeName;
    }

    public String getImage() {
        return image;
    }

    public void setImage(String image) {
        this.image = image;
    }
}
