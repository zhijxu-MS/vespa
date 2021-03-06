// Copyright 2018 Yahoo Holdings. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.
package com.yahoo.vespa.service.health;

import com.yahoo.config.model.api.ApplicationInfo;
import com.yahoo.config.model.api.HostInfo;
import com.yahoo.config.model.api.PortInfo;
import com.yahoo.config.model.api.ServiceInfo;
import com.yahoo.config.provision.ApplicationId;
import com.yahoo.config.provision.HostName;
import com.yahoo.vespa.applicationmodel.ClusterId;
import com.yahoo.vespa.applicationmodel.ConfigId;
import com.yahoo.vespa.applicationmodel.ServiceStatus;
import com.yahoo.vespa.applicationmodel.ServiceType;
import com.yahoo.vespa.service.monitor.ServiceStatusProvider;
import com.yahoo.vespa.service.model.ApplicationInstanceGenerator;
import com.yahoo.vespa.service.model.ServiceId;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

/**
 * Responsible for monitoring a whole application using /state/v1/health.
 *
 * @author hakon
 */
public class ApplicationHealthMonitor implements ServiceStatusProvider, AutoCloseable {
    public static final String PORT_TAG_STATE = "STATE";
    public static final String PORT_TAG_HTTP = "HTTP";
    /** Port tags implying /state/v1/health is served */
    public static final List<String> PORT_TAGS_HEALTH =
            Collections.unmodifiableList(Arrays.asList(PORT_TAG_HTTP, PORT_TAG_STATE));

    private final Map<ServiceId, HealthMonitor> healthMonitors;

    public static ApplicationHealthMonitor startMonitoring(ApplicationInfo application) {
        return startMonitoring(application, HealthMonitor::new);
    }

    /** For testing. */
    static ApplicationHealthMonitor startMonitoring(ApplicationInfo application,
                                                    Function<HealthEndpoint, HealthMonitor> mapper) {
        return new ApplicationHealthMonitor(makeHealthMonitors(application, mapper));
    }

    private ApplicationHealthMonitor(Map<ServiceId, HealthMonitor> healthMonitors) {
        this.healthMonitors = healthMonitors;
    }

    @Override
    public boolean wouldMonitor(ApplicationId applicationId) {
        return true;
    }

    @Override
    public ServiceStatus getStatus(ApplicationId applicationId,
                                   ClusterId clusterId,
                                   ServiceType serviceType,
                                   ConfigId configId) {
        ServiceId serviceId = new ServiceId(applicationId, clusterId, serviceType, configId);
        HealthMonitor monitor = healthMonitors.get(serviceId);
        if (monitor == null) {
            return ServiceStatus.NOT_CHECKED;
        }

        return monitor.getStatus();
    }

    @Override
    public void close() {
        healthMonitors.values().forEach(HealthMonitor::close);
        healthMonitors.clear();
    }

    private static Map<ServiceId, HealthMonitor> makeHealthMonitors(
            ApplicationInfo application, Function<HealthEndpoint, HealthMonitor> monitorFactory) {
        Map<ServiceId, HealthMonitor> healthMonitors = new HashMap<>();
        for (HostInfo hostInfo : application.getModel().getHosts()) {
            for (ServiceInfo serviceInfo : hostInfo.getServices()) {
                for (PortInfo portInfo : serviceInfo.getPorts()) {
                    maybeCreateHealthMonitor(
                            application,
                            hostInfo,
                            serviceInfo,
                            portInfo,
                            monitorFactory)
                            .ifPresent(healthMonitor -> healthMonitors.put(
                                    ApplicationInstanceGenerator.getServiceId(application, serviceInfo),
                                    healthMonitor));
                }
            }
        }
        return healthMonitors;
    }

    private static Optional<HealthMonitor> maybeCreateHealthMonitor(
            ApplicationInfo applicationInfo,
            HostInfo hostInfo,
            ServiceInfo serviceInfo,
            PortInfo portInfo,
            Function<HealthEndpoint, HealthMonitor> monitorFactory) {
        if (portInfo.getTags().containsAll(PORT_TAGS_HEALTH)) {
            HostName hostname = HostName.from(hostInfo.getHostname());
            HealthEndpoint endpoint = HealthEndpoint.forHttp(hostname, portInfo.getPort());
            HealthMonitor healthMonitor = monitorFactory.apply(endpoint);
            healthMonitor.startMonitoring();
            return Optional.of(healthMonitor);
        }

        return Optional.empty();
    }
}
