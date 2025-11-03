package io.autosreguard.remediation;

import java.util.List;
import java.util.Objects;

/**
 * Outbound channel to humans: pager, chat, ticketing.
 *
 * <p>Every notification carries evidence. A page that says "error rate high" and nothing else
 * forces the on-call engineer to redo the diagnosis the system already did.
 */
@FunctionalInterface
public interface Notifier {

    void notify(Notification n);

    /** Writes notifications to {@link System.Logger}. Useful default and for local runs. */
    static Notifier logging() {
        System.Logger log = System.getLogger("io.autosreguard.notifications");
        return n -> log.log(n.urgency() == Urgency.PAGE ? System.Logger.Level.WARNING : System.Logger.Level.INFO,
                "[{0}] {1} :: {2}", n.urgency(), n.title(), String.join(" | ", n.evidence()));
    }

    enum Urgency { INFO, APPROVAL_REQUESTED, PAGE }

    /**
     * @param urgency  how fast a human must react
     * @param title    one-line summary
     * @param actionId audit id the notification relates to, so humans can approve/veto/revert it
     * @param evidence reasoning chain
     */
    record Notification(Urgency urgency, String title, String actionId, List<String> evidence) {
        public Notification {
            Objects.requireNonNull(urgency);
            Objects.requireNonNull(title);
            Objects.requireNonNull(actionId);
            evidence = List.copyOf(evidence);
        }
    }
}
