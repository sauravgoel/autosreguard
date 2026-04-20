package io.autosreguard.demo;

/**
 * A self-contained, deterministic demonstration of one reliability lesson.
 * Scenarios use a manual clock and seeded randomness, so output is reproducible and doubles
 * as an end-to-end smoke test in CI.
 */
interface Scenario {

    /** CLI name, e.g. {@code hidden-tenant}. */
    String name();

    void run(Console console) throws Exception;
}
