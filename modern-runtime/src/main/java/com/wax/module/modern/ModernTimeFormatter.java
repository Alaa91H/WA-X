package com.wax.module.modern;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Calendar;
import java.util.Locale;

/**
 * Pure formatting policy shared by modern runtime and JVM regression tests.
 * It preserves legacy CustomTime's [TIME] substitution semantics.
 */
public final class ModernTimeFormatter {
    private ModernTimeFormatter() {}

    public static String render(Calendar calendar, boolean seconds, boolean amPm, String template) {
        if (calendar == null) throw new IllegalArgumentException("calendar is required");
        String pattern = amPm
                ? (seconds ? "hh:mm:ss a" : "hh:mm a")
                : (seconds ? "HH:mm:ss" : "HH:mm");
        String time = DateTimeFormatter.ofPattern(pattern, Locale.US)
                .withZone(ZoneId.systemDefault())
                .format(Instant.ofEpochMilli(calendar.getTimeInMillis()));
        String value = template == null ? "[TIME]" : template;
        if ("[TIME]".equals(value)) return time;
        if (value.contains("[TIME]")) return value.replace("[TIME]", time);
        return value.isEmpty() ? time : value + " " + time;
    }
}
