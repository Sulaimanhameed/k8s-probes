package com.example.hello.service;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.availability.AvailabilityChangeEvent;
import org.springframework.boot.availability.LivenessState;
import org.springframework.boot.availability.ReadinessState;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

@Service
public class ProbeSimulatorService {

    private final ApplicationEventPublisher eventPublisher;
    private volatile boolean livenessBroken = false;
    private volatile boolean readinessBroken = false;

    @Autowired
    public ProbeSimulatorService(ApplicationEventPublisher eventPublisher) {
        this.eventPublisher = eventPublisher;
    }

    public void breakLiveness() {
        livenessBroken = true;
        AvailabilityChangeEvent.publish(eventPublisher, this, LivenessState.BROKEN);
    }

    public void restoreLiveness() {
        livenessBroken = false;
        AvailabilityChangeEvent.publish(eventPublisher, this, LivenessState.CORRECT);
    }

    public void breakReadiness() {
        readinessBroken = true;
        AvailabilityChangeEvent.publish(eventPublisher, this, ReadinessState.REFUSING_TRAFFIC);
    }

    public void restoreReadiness() {
        readinessBroken = false;
        AvailabilityChangeEvent.publish(eventPublisher, this, ReadinessState.ACCEPTING_TRAFFIC);
    }

    public void restoreAll() {
        restoreLiveness();
        restoreReadiness();
    }

    public boolean isLivenessBroken()  { return livenessBroken; }
    public boolean isReadinessBroken() { return readinessBroken; }
}
