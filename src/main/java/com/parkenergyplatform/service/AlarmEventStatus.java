package com.parkenergyplatform.service;

/** Lifecycle states of an operational alarm event. */
public enum AlarmEventStatus {
    NEW,
    ACKNOWLEDGED,
    IN_PROGRESS,
    RECOVERED,
    CLOSED,
    FALSE_POSITIVE,
    SUPPRESSED;

    public boolean terminal() {
        return this == CLOSED || this == FALSE_POSITIVE;
    }
}
