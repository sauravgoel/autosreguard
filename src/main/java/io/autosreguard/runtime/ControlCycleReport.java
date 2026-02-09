package io.autosreguard.runtime;

import io.autosreguard.detection.Anomaly;
import io.autosreguard.diagnosis.Hypothesis;
import io.autosreguard.remediation.RemediationOutcome;
import io.autosreguard.slo.SloStatus;

import java.time.Instant;
import java.util.List;

/**
 * Everything one control-loop iteration saw and did. Returned for tests and the simulator,
 * and logged at debug level in production.
 *
 * @param at          cycle time
 * @param firingSlos  SLO statuses currently alerting (symptoms users feel)
 * @param anomalies   active anomalies fed to diagnosis
 * @param hypotheses  ranked diagnosis output
 * @param outcomes    remediation outcomes (new decisions plus proposals executed this tick)
 */
public record ControlCycleReport(Instant at, List<SloStatus> firingSlos, List<Anomaly> anomalies,
                                 List<Hypothesis> hypotheses, List<RemediationOutcome> outcomes) {

    public ControlCycleReport {
        firingSlos = List.copyOf(firingSlos);
        anomalies = List.copyOf(anomalies);
        hypotheses = List.copyOf(hypotheses);
        outcomes = List.copyOf(outcomes);
    }
}
