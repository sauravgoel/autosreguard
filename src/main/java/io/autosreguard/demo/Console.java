package io.autosreguard.demo;

import java.io.PrintStream;
import java.util.Locale;

/** Tiny formatting helper so scenario output reads like a narrated incident timeline. */
final class Console {

    private final PrintStream out;

    Console(PrintStream out) {
        this.out = out;
    }

    void banner(String title, String lesson) {
        out.println();
        out.println("=".repeat(96));
        out.println("  " + title);
        out.println("  Lesson: " + lesson);
        out.println("=".repeat(96));
    }

    void section(String s) {
        out.println();
        out.println("-- " + s + " " + "-".repeat(Math.max(0, 92 - s.length())));
    }

    void line(String fmt, Object... args) {
        out.println("  " + String.format(Locale.ROOT, fmt, args));
    }

    void event(String time, String fmt, Object... args) {
        out.println(String.format(Locale.ROOT, "  [%s] ", time) + String.format(Locale.ROOT, fmt, args));
    }

    void takeaway(String s) {
        out.println();
        out.println("  >> " + s);
    }
}
