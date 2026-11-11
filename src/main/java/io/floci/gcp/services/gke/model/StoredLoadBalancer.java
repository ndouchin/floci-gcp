package io.floci.gcp.services.gke.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Emulator-internal record of the load balancer floci-gcp runs for one Service of type LoadBalancer of a real
 * cluster: its address, the forwarding rule that reserves it, and the appliance container that holds it. Never
 * returned to clients.
 */
@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public class StoredLoadBalancer {

    private String project;
    private String location;
    private String cluster;
    /** The cluster's {@code id}, so a recreated cluster of the same name does not inherit the record. */
    private String clusterId;
    private String uid;
    private String namespace;
    private String name;
    private boolean internal;
    private String address;
    private String region;
    /** The Compute forwarding rule reserving an external address; null for internal ones. */
    private String forwardingRule;
    private String appliance;
    /** {@code PROTOCOL/port} to the host port the appliance publishes it on. */
    private Map<String, Integer> hostPorts = new LinkedHashMap<>();
    private String firstSeen;
    private boolean ensured;
    private String lastError;

    public String getProject() {
        return project;
    }

    public void setProject(String project) {
        this.project = project;
    }

    public String getLocation() {
        return location;
    }

    public void setLocation(String location) {
        this.location = location;
    }

    public String getCluster() {
        return cluster;
    }

    public void setCluster(String cluster) {
        this.cluster = cluster;
    }

    public String getClusterId() {
        return clusterId;
    }

    public void setClusterId(String clusterId) {
        this.clusterId = clusterId;
    }

    public String getUid() {
        return uid;
    }

    public void setUid(String uid) {
        this.uid = uid;
    }

    public String getNamespace() {
        return namespace;
    }

    public void setNamespace(String namespace) {
        this.namespace = namespace;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public boolean isInternal() {
        return internal;
    }

    public void setInternal(boolean internal) {
        this.internal = internal;
    }

    public String getAddress() {
        return address;
    }

    public void setAddress(String address) {
        this.address = address;
    }

    public String getRegion() {
        return region;
    }

    public void setRegion(String region) {
        this.region = region;
    }

    public String getForwardingRule() {
        return forwardingRule;
    }

    public void setForwardingRule(String forwardingRule) {
        this.forwardingRule = forwardingRule;
    }

    public String getAppliance() {
        return appliance;
    }

    public void setAppliance(String appliance) {
        this.appliance = appliance;
    }

    public Map<String, Integer> getHostPorts() {
        return hostPorts;
    }

    public void setHostPorts(Map<String, Integer> hostPorts) {
        this.hostPorts = hostPorts;
    }

    public String getFirstSeen() {
        return firstSeen;
    }

    public void setFirstSeen(String firstSeen) {
        this.firstSeen = firstSeen;
    }

    public boolean isEnsured() {
        return ensured;
    }

    public void setEnsured(boolean ensured) {
        this.ensured = ensured;
    }

    public String getLastError() {
        return lastError;
    }

    public void setLastError(String lastError) {
        this.lastError = lastError;
    }
}
