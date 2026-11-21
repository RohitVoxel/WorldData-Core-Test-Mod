package net.worlddata.benchmark.common.mode;

import java.util.Map;

public interface ModeRun {

    String mode();

    String runId();

    void onServerTickStart(long serverTick);

    void onServerTickEnd(long serverTick);

    boolean isFinished();

    void stop();

    void abort();

    String statusLine();

    ModeReport report();

    Map<String, String> storeValues();

    void otherArm(Map<String, String> values);
}
