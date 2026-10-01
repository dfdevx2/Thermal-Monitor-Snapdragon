package com.siliconfet.thermalmonitor;

interface ICommandService {
    String exec(String command);
    void startThermalStream();
    String getHealthStatus();
    void stopThermalStream();
    void destroy();
}
