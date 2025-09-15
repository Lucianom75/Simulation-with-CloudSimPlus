package org.cloudbus.cloudsim.examples.network;

import org.cloudsimplus.brokers.DatacenterBroker;
import org.cloudsimplus.brokers.DatacenterBrokerSimple;
import org.cloudsimplus.builders.tables.CloudletsTableBuilder;
import org.cloudsimplus.cloudlets.Cloudlet;
import org.cloudsimplus.cloudlets.CloudletSimple;
import org.cloudsimplus.core.CloudSimPlus;
import org.cloudsimplus.datacenters.Datacenter;
import org.cloudsimplus.datacenters.DatacenterSimple;
import org.cloudsimplus.hosts.Host;
import org.cloudsimplus.hosts.HostSimple;
import org.cloudsimplus.resources.Pe;
import org.cloudsimplus.resources.PeSimple;
import org.cloudsimplus.schedulers.cloudlet.CloudletSchedulerTimeShared;
import org.cloudsimplus.utilizationmodels.UtilizationModel;
import org.cloudsimplus.utilizationmodels.UtilizationModelDynamic;
import org.cloudsimplus.utilizationmodels.UtilizationModelFull;
import org.cloudsimplus.vms.Vm;
import org.cloudsimplus.vms.VmSimple;


import java.util.ArrayList;
import java.util.List;

public class NetworkExample1 {

    private static final int HOURS = 24;
    private static final int BASE_LOAD = 5;
    private static final int PEAK_LOAD = 20;

    public static void main(String[] args) {
        System.out.println("=== Cloud Architecture Comparison ===\n");

        ScenarioResult vmResult = runVmScenario();
        ScenarioResult containerResult = runContainerScenario();
        ScenarioResult serverlessResult = runServerlessScenario();

        printSummary(vmResult, containerResult, serverlessResult);
    }

    /* --------- Cenários ----------- */

    private static ScenarioResult runVmScenario() {
        System.out.println("### Scenario: VMs (IaaS)");
        return runScenario("VM", 4, 1.0, false);
    }

    private static ScenarioResult runContainerScenario() {
        System.out.println("\n### Scenario: Containers (CaaS)");
        return runScenario("Container", 2, 0.8, false);
    }

    private static ScenarioResult runServerlessScenario() {
        System.out.println("\n### Scenario: Serverless (FaaS)");
        return runScenario("Serverless", 1, 0.5, true);
    }

    private static ScenarioResult runScenario(String name, int vmCount, double costFactor, boolean isServerless) {
        CloudSimPlus simulation = new CloudSimPlus();
        Datacenter datacenter = createDatacenter(simulation);
        DatacenterBroker broker = new DatacenterBrokerSimple(simulation);

        /* Criar VMs */
        List<Vm> vmList = new ArrayList<>();
        for (int i = 0; i < vmCount; i++) {
            Vm vm = new VmSimple(1000, 2)
                    .setRam(2048).setBw(1000).setSize(10000)
                    .setCloudletScheduler(new CloudletSchedulerTimeShared());
            vmList.add(vm);
        }
        broker.submitVmList(vmList);

        /* Criar cloudlets */
        List<Cloudlet> cloudletList = new ArrayList<>();
        UtilizationModel full = new UtilizationModelFull();
        UtilizationModel dynamic = new UtilizationModelDynamic(0.2);

        for (int hour = 0; hour < HOURS; hour++) {
            int users = (hour % 6 == 0) ? PEAK_LOAD : BASE_LOAD;
            for (int u = 0; u < users; u++) {
                long baseLength = isServerless ? 2000L : 10000L;
                long length = (long) (baseLength * costFactor);

                Cloudlet cloudlet = new CloudletSimple(length, 1)
                        .setUtilizationModel(isServerless ? dynamic : full)
                        .setFileSize(300).setOutputSize(300);

                cloudletList.add(cloudlet);
            }
        }
        broker.submitCloudletList(cloudletList);

        /* Rodar simulação */
        simulation.start();

        List<Cloudlet> finished = broker.getCloudletFinishedList();

        System.out.println("\nCloudlets table for scenario: " + name);
        new CloudletsTableBuilder(finished).build();

        /* Métricas */
        int finishedCount = finished.size();
        double totalResp = 0.0;
        double totalCpu = 0.0;

        for (Cloudlet c : finished) {
            // Tempo de resposta (fim - início da execução)
            totalResp += c.getTotalExecutionTime();
         // Isso assume que o cloudlet usou 100% da CPU durante sua execução
            double cpuTimeManual = c.getLength() / c.getVm().getMips();
            totalCpu += cpuTimeManual;
        }

        double avgResponse = finishedCount == 0 ? 0.0 : totalResp / finishedCount;
        double avgCpu = finishedCount == 0 ? 0.0 : totalCpu / finishedCount;

        /* Custos */
        double costPerSecondVm = datacenter.getCharacteristics().getCostPerSecond();
        double vmUptimeSeconds = simulation.clock();
        double vmCost = vmCount * vmUptimeSeconds * costPerSecondVm;

        double perRequestCost = isServerless ? 0.0002 : 0.00002;
        double execCost = finishedCount * perRequestCost;
        double totalCost = vmCost + execCost;

        ScenarioResult result = new ScenarioResult(name, vmCount, finishedCount, avgResponse, avgCpu, totalCost);

        System.out.printf("Results [%s]: finished=%d, avgResponse=%.4f, avgCpu=%.4f, totalCost=$%.6f\n",
                name, finishedCount, avgResponse, avgCpu, totalCost);

        return result;
    }

    private static Datacenter createDatacenter(CloudSimPlus simulation) {
        List<Host> hostList = new ArrayList<>();
        List<Pe> peList = new ArrayList<>();
        peList.add(new PeSimple(2000));

        Host host = new HostSimple(16384, 100000, 1000000, peList);
        hostList.add(host);

        Datacenter dc = new DatacenterSimple(simulation, hostList);
        dc.setSchedulingInterval(10);

        dc.getCharacteristics()
                .setCostPerSecond(0.1)
                .setCostPerMem(0.05)
                .setCostPerStorage(0.001)
                .setCostPerBw(0.01);

        return dc;
    }

    private static void printSummary(ScenarioResult vm, ScenarioResult container, ScenarioResult serverless) {
        System.out.println("\n=== Summary ===");
        System.out.printf("%-12s %-6s %-10s %-18s %-12s\n",
                "Scenario", "VMs", "Finished", "Avg Response (s)", "Total Cost $");
        System.out.printf("%-12s %-6d %-10d %-18.4f %-12.6f\n",
                vm.name, vm.vmCount, vm.finishedCloudlets, vm.avgResponseTime, vm.totalCost);
        System.out.printf("%-12s %-6d %-10d %-18.4f %-12.6f\n",
                container.name, container.vmCount, container.finishedCloudlets, container.avgResponseTime, container.totalCost);
        System.out.printf("%-12s %-6d %-10d %-18.4f %-12.6f\n",
                serverless.name, serverless.vmCount, serverless.finishedCloudlets, serverless.avgResponseTime, serverless.totalCost);
    }

    private static class ScenarioResult {
        final String name;
        final int vmCount;
        final int finishedCloudlets;
        final double avgResponseTime;
        final double avgCpuTime;
        final double totalCost;

        ScenarioResult(String name, int vmCount, int finishedCloudlets,
                       double avgResponseTime, double avgCpuTime, double totalCost) {
            this.name = name;
            this.vmCount = vmCount;
            this.finishedCloudlets = finishedCloudlets;
            this.avgResponseTime = avgResponseTime;
            this.avgCpuTime = avgCpuTime;
            this.totalCost = totalCost;
        }
    }
}
