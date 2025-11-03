package io.autosreguard.remediation;

/**
 * The three autonomy tiers. Autonomous SRE amplifies human judgment rather than replacing it:
 * handle the well-understood, repeatable majority of incidents automatically and route the
 * novel, high-stakes, ambiguous ones to people with context attached.
 */
public enum AutonomyTier {

    /** High confidence: execute the validated action immediately, notify after. */
    AUTO_REMEDIATE,

    /**
     * Medium confidence: propose the action and notify on-call. If no human vetoes within the
     * approval window, the system acts. A human can also approve early.
     */
    SUGGEST_AND_NOTIFY,

    /** Low confidence or guardrail tripped: page a human with correlated signals and hypotheses. */
    PAGE_AND_ASSIST
}
