package io.mapsmessaging.network.protocol.impl.v2x_step;

import com.vodafone.v2xsdk4javav2.facade.locationprovider.LocationProvider;
import com.vodafone.v2xsdk4javav2.facade.models.GnssLocation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A simple fake location provider that emits fixed GNSS coordinates periodically.
 */
public class FakeLocationProvider extends LocationProvider implements Runnable {
    private static final Logger sdkLogger = LoggerFactory.getLogger(FakeLocationProvider.class);
    private final double latitude;
    private final double longitude;
    private Thread thread;
    private volatile boolean running;

    public FakeLocationProvider(double latitude, double longitude) {
        this.latitude = latitude;
        this.longitude = longitude;
    }

    @Override
    public boolean turnOn() {
        if (!running) {
            running = true;
            thread = new Thread(this, "FakeLocationProvider");
            thread.start();
            sdkLogger.info("FakeLocationProvider started at ({}, {})", latitude, longitude);
        }
        return true;
    }

    @Override
    public void turnOff() {
        running = false;
        if (thread != null) {
            thread.interrupt();
            try { thread.join(2000); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        }
        sdkLogger.info("FakeLocationProvider stopped");
    }

    @Override
    public void run() {
        while (running) {
            try {
                long timestamp = System.currentTimeMillis();
                GnssLocation loc = new GnssLocation(latitude, longitude, 0.0, 0.0f, 0.0f, 0.0f, timestamp);
                notifyFreshLocation(loc);
                Thread.sleep(1000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (Exception e) {
                sdkLogger.error("Error in FakeLocationProvider", e);
            }
        }
    }
}