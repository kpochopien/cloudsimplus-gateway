package pl.edu.agh.csg;

import com.google.gson.Gson;
import org.apache.commons.math3.stat.StatUtils;
import org.cloudbus.cloudsim.cloudlets.Cloudlet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Arrays;
import java.util.List;
import java.util.stream.DoubleStream;

import static org.apache.commons.math3.stat.StatUtils.percentile;

public class WrappedSimulation {

    private static final Logger logger = LoggerFactory.getLogger(WrappedSimulation.class.getName());
    private static final int HISTORY_LENGTH = 30 * 60; // 30 minutes * 60s

    private final CloudSimProxy cloudSimProxy;

    private List<String> metricsNames = Arrays.asList(
            "vmCountHistory",
            "p99LatencyHistory",
            "p90LatencyHistory",
            "avgCPUUtilizationHistory",
            "p90CPUUtilizationHistory",
            "totalLatencyHistory"
    );

    private MetricsStorage metricsStorage = new MetricsStorage(HISTORY_LENGTH, metricsNames);

    private final Gson gson = new Gson();
    private final double INTERVAL = 1.0;
    private final String identifier;
    private final int initialVmsCount;
    private final SimulationSettings settings = new SimulationSettings();

    public WrappedSimulation(String identifier, int initialVmsCount, List<Cloudlet> jobs) {
        this.identifier = identifier;
        this.initialVmsCount = initialVmsCount;

        cloudSimProxy = new CloudSimProxy(settings, initialVmsCount, jobs);
    }


    public String getIdentifier() {
        return identifier;
    }

    public void reset() {
        metricsStorage.clear();
    }

    public void close() {
        logger.info("Simulation is synchronous - doing nothing");
    }

    public String render() {
        double[][] renderedEnv = {
                metricsStorage.metricValuesAsPrimitives("vmCountHistory"),
                metricsStorage.metricValuesAsPrimitives("p99LatencyHistory"),
                metricsStorage.metricValuesAsPrimitives("p90LatencyHistory"),
                metricsStorage.metricValuesAsPrimitives("avgCPUUtilizationHistory"),
                metricsStorage.metricValuesAsPrimitives("p90CPUUtilizationHistory"),
                metricsStorage.metricValuesAsPrimitives("totalLatencyHistory")
        };
        return gson.toJson(renderedEnv);
    }

    public SimulationStepResult step(int action) {
        executeAction(action);
        cloudSimProxy.runFor(INTERVAL);
        collectMetrics();

        boolean done = !cloudSimProxy.isRunning();
        double[] observation = getObservation();
        double reward = calculateReward();

        logger.debug("Step finished (action: " + action + ") is done: " + done);

        return new SimulationStepResult(
                done,
                observation,
                reward
        );
    }

    private void executeAction(int action) {
        switch (action) {
            case 0:
                // nothing happens
                break;
            case 1:
                // adding a new vm
                cloudSimProxy.addNewVM();
                break;
            case 2:
                // removing randomly one of the vms
                cloudSimProxy.removeRandomlyVM();
                break;
        }
    }

    private double percentileWithDefault(double[] values, double percentile, double defaultValue) {
        if(values.length == 0) {
            return defaultValue;
        }

        return percentile(values, percentile);
    }

    private void collectMetrics() {
        final double[] latencies = cloudSimProxy.getWaitTimesFromLastInterval();
        Arrays.sort(latencies);

        double[] cpuPercentUsage = cloudSimProxy.getVmCpuUsage();
        Arrays.sort(cpuPercentUsage);

        double totalLatency = DoubleStream.of(latencies).sum();

        metricsStorage.updateMetric("vmCountHistory", cloudSimProxy.getNumberOfActiveVMs());
        metricsStorage.updateMetric("p99LatencyHistory", percentileWithDefault(latencies, 0.99, 0));
        metricsStorage.updateMetric("p90LatencyHistory", percentileWithDefault(latencies, 0.90, 0));
        metricsStorage.updateMetric("avgCPUUtilizationHistory", safeMean(cpuPercentUsage));
        metricsStorage.updateMetric("p90CPUUtilizationHistory", percentileWithDefault(cpuPercentUsage, 0.90, 0));
        metricsStorage.updateMetric("totalLatencyHistory", totalLatency);
    }


    private double[] getObservation() {
        return new double[]{
                metricsStorage.getLastMetricValue("vmCountHistory"),
                metricsStorage.getLastMetricValue("p99LatencyHistory"),
                metricsStorage.getLastMetricValue("p90LatencyHistory"),
                metricsStorage.getLastMetricValue("avgCPUUtilizationHistory"),
                metricsStorage.getLastMetricValue("p90CPUUtilizationHistory"),
                metricsStorage.getLastMetricValue("totalLatencyHistory")
        };
    }

    private double safeMean(double[] cpuPercentUsage) {
        if (cpuPercentUsage.length == 0) {
            return 0.0;
        }

        if (cpuPercentUsage.length == 1) {
            return cpuPercentUsage[0];
        }

        return StatUtils.mean(cpuPercentUsage);
    }

    private double calculateReward() {
        return 0;
    }

    public void seed() {
        // there is no randomness so far...
    }

    public double clock() {
        return cloudSimProxy.clock();
    }
}
