package com.wax.module.modern;

import static org.junit.Assert.*;
import java.util.Calendar;
import java.util.TimeZone;
import org.junit.Test;

public final class ModernTimeFormatterTest {
    private static Calendar fixed() {
        Calendar calendar = Calendar.getInstance(TimeZone.getTimeZone("UTC"));
        calendar.set(2026, Calendar.OCTOBER, 9, 14, 7, 23);
        calendar.set(Calendar.MILLISECOND, 0);
        return calendar;
    }

    @Test public void default24hWithoutSecondsMatchesLegacy() {
        TimeZone old = TimeZone.getDefault();
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
            assertEquals("14:07", ModernTimeFormatter.render(fixed(), false, false, "[TIME]"));
            assertEquals("14:07:23", ModernTimeFormatter.render(fixed(), true, false, "[TIME]"));
        } finally { TimeZone.setDefault(old); }
    }

    @Test public void supportsAmPmAndUserTemplate() {
        TimeZone old = TimeZone.getDefault();
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
            assertEquals("02:07 PM", ModernTimeFormatter.render(fixed(), false, true, null));
            assertEquals("Clock: 02:07:23 PM",
                    ModernTimeFormatter.render(fixed(), true, true, "Clock: [TIME]"));
            assertEquals("today 14:07", ModernTimeFormatter.render(fixed(), false, false, "today"));
            assertEquals("14:07", ModernTimeFormatter.render(fixed(), false, false, ""));
            assertEquals("14:07 - 14:07",
                    ModernTimeFormatter.render(fixed(), false, false, "[TIME] - [TIME]"));
        } finally { TimeZone.setDefault(old); }
    }

    @Test public void rejectsNullCalendar() {
        assertThrows(IllegalArgumentException.class,
                () -> ModernTimeFormatter.render(null, false, false, "[TIME]"));
    }
}
