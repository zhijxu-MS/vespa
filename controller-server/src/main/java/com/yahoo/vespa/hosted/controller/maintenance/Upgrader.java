// Copyright 2018 Yahoo Holdings. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.
package com.yahoo.vespa.hosted.controller.maintenance;

import com.yahoo.component.Version;
import com.yahoo.config.application.api.DeploymentSpec.UpgradePolicy;
import com.yahoo.vespa.curator.Lock;
import com.yahoo.vespa.hosted.controller.Application;
import com.yahoo.vespa.hosted.controller.Controller;
import com.yahoo.vespa.hosted.controller.application.ApplicationList;
import com.yahoo.vespa.hosted.controller.application.Change;
import com.yahoo.vespa.hosted.controller.deployment.DeploymentTrigger.ChangesToCancel;
import com.yahoo.vespa.hosted.controller.persistence.CuratorDb;
import com.yahoo.vespa.hosted.controller.versions.VespaVersion;
import com.yahoo.vespa.hosted.controller.versions.VespaVersion.Confidence;
import com.yahoo.yolean.Exceptions;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.logging.Level;
import java.util.logging.Logger;

import static com.yahoo.vespa.hosted.controller.deployment.DeploymentTrigger.ChangesToCancel.PLATFORM;

/**
 * Maintenance job which schedules applications for Vespa version upgrade
 *
 * @author bratseth
 * @author mpolden
 */
public class Upgrader extends Maintainer {

    private static final Logger log = Logger.getLogger(Upgrader.class.getName());

    private final CuratorDb curator;

    public Upgrader(Controller controller, Duration interval, JobControl jobControl, CuratorDb curator) {
        super(controller, interval, jobControl);
        this.curator = Objects.requireNonNull(curator, "curator cannot be null");
    }

    /**
     * Schedule application upgrades. Note that this implementation must be idempotent.
     */
    @Override
    public void maintain() {
        // Determine target versions for each upgrade policy
        Optional<Version> canaryTarget = controller().versionStatus().systemVersion().map(VespaVersion::versionNumber);
        Optional<Version> defaultTarget = newestVersionWithConfidence(Confidence.normal);
        Optional<Version> conservativeTarget = newestVersionWithConfidence(Confidence.high);

        // Cancel upgrades to broken targets (let other ongoing upgrades complete to avoid starvation)
        for (VespaVersion version : controller().versionStatus().versions()) {
            if (version.confidence() == Confidence.broken)
                cancelUpgradesOf(applications().without(UpgradePolicy.canary).upgradingTo(version.versionNumber()),
                                 version.versionNumber() + " is broken");
        }

        // Canaries should always try the canary target
        cancelUpgradesOf(applications().with(UpgradePolicy.canary).upgrading().notUpgradingTo(canaryTarget),
                         "Outdated target version for Canaries");

        // Cancel *failed* upgrades to earlier versions, as the new version may fix it
        String reason = "Failing on outdated version";
        cancelUpgradesOf(applications().with(UpgradePolicy.defaultPolicy).upgrading().failing().notUpgradingTo(defaultTarget), reason);
        cancelUpgradesOf(applications().with(UpgradePolicy.conservative).upgrading().failing().notUpgradingTo(conservativeTarget), reason);

        // Schedule the right upgrades

        canaryTarget.ifPresent(target -> upgrade(applications().with(UpgradePolicy.canary), target));
        defaultTarget.ifPresent(target -> upgrade(applications().with(UpgradePolicy.defaultPolicy), target));
        conservativeTarget.ifPresent(target -> upgrade(applications().with(UpgradePolicy.conservative), target));
    }

    private Optional<Version> newestVersionWithConfidence(Confidence confidence) {
        return reversed(controller().versionStatus().versions()).stream()
                                                                // Ensure we never pick a version newer than the system
                                                                .filter(v -> !v.versionNumber().isAfter(controller().systemVersion()))
                                                                .filter(v -> v.confidence().equalOrHigherThan(confidence))
                                                                .findFirst()
                                                                .map(VespaVersion::versionNumber);
    }

    private List<VespaVersion> reversed(List<VespaVersion> versions) {
        List<VespaVersion> reversed = new ArrayList<>(versions.size());
        for (int i = 0; i < versions.size(); i++)
            reversed.add(versions.get(versions.size() - 1 - i));
        return reversed;
    }

    /** Returns a list of all applications */
    private ApplicationList applications() { return ApplicationList.from(controller().applications().asList()); }

    private void upgrade(ApplicationList applications, Version version) {
        applications = applications.hasProductionDeployment();
        applications = applications.onLowerVersionThan(version);
        applications = applications.allowMajorVersion(version.getMajor(), targetMajorVersion().orElse(version.getMajor()));
        applications = applications.notDeploying(); // wait with applications deploying an application change or already upgrading
        applications = applications.notFailingOn(version); // try to upgrade only if it hasn't failed on this version
        applications = applications.canUpgradeAt(controller().clock().instant()); // wait with applications that are currently blocking upgrades
        applications = applications.byIncreasingDeployedVersion(); // start with lowest versions
        applications = applications.first(numberOfApplicationsToUpgrade()); // throttle upgrades
        for (Application application : applications.asList())
            controller().applications().deploymentTrigger().triggerChange(application.id(), Change.of(version));
    }

    private void cancelUpgradesOf(ApplicationList applications, String reason) {
        if (applications.isEmpty()) return;
        log.info("Cancelling upgrading of " + applications.asList().size() + " applications: " + reason);
        for (Application application : applications.asList())
            controller().applications().deploymentTrigger().cancelChange(application.id(), PLATFORM);
    }

    /** Returns the number of applications to upgrade in this run */
    private int numberOfApplicationsToUpgrade() {
        return Math.max(1, (int) (maintenanceInterval().getSeconds() * (upgradesPerMinute() / 60)));
    }

    /** Returns number of upgrades per minute */
    public double upgradesPerMinute() {
        return curator.readUpgradesPerMinute();
    }

    /** Sets the number of upgrades per minute */
    public void setUpgradesPerMinute(double n) {
        if (n < 0)
            throw new IllegalArgumentException("Upgrades per minute must be >= 0, got " + n);
        curator.writeUpgradesPerMinute(n);
    }

    /** Returns the target major version for applications not specifying one */
    public Optional<Integer> targetMajorVersion() {
        return curator.readTargetMajorVersion();
    }

    /** Sets the default target major version. Set to empty to determine target version normally (by confidence) */
    public void setTargetMajorVersion(Optional<Integer> targetMajorVersion) {
        curator.writeTargetMajorVersion(targetMajorVersion);
    }

    /** Override confidence for given version. This will cause the computed confidence to be ignored */
    public void overrideConfidence(Version version, Confidence confidence) {
        try (Lock lock = curator.lockConfidenceOverrides()) {
            Map<Version, Confidence> overrides = new LinkedHashMap<>(curator.readConfidenceOverrides());
            overrides.put(version, confidence);
            curator.writeConfidenceOverrides(overrides);
        }
    }

    /** Returns all confidence overrides */
    public Map<Version, Confidence> confidenceOverrides() {
        return curator.readConfidenceOverrides();
    }

    /** Remove confidence override for given version */
    public void removeConfidenceOverride(Version version) {
        controller().removeConfidenceOverride(v -> v.equals(version));
    }
}
