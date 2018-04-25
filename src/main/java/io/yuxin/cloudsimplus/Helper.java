package io.yuxin.cloudsimplus;

import org.cloudbus.cloudsim.cloudlets.Cloudlet;
import org.cloudbus.cloudsim.cloudlets.CloudletSimple;
import org.cloudbus.cloudsim.hosts.Host;
import org.cloudbus.cloudsim.hosts.HostSimple;
import org.cloudbus.cloudsim.hosts.HostStateHistoryEntry;
import org.cloudbus.cloudsim.power.models.PowerModelSpecPowerHpProLiantMl110G5Xeon3075;
import org.cloudbus.cloudsim.provisioners.PeProvisionerSimple;
import org.cloudbus.cloudsim.provisioners.ResourceProvisioner;
import org.cloudbus.cloudsim.provisioners.ResourceProvisionerSimple;
import org.cloudbus.cloudsim.resources.Pe;
import org.cloudbus.cloudsim.resources.PeSimple;
import org.cloudbus.cloudsim.schedulers.cloudlet.CloudletSchedulerSpaceShared;
import org.cloudbus.cloudsim.schedulers.cloudlet.CloudletSchedulerTimeShared;
import org.cloudbus.cloudsim.schedulers.vm.VmScheduler;
import org.cloudbus.cloudsim.schedulers.vm.VmSchedulerTimeShared;
import org.cloudbus.cloudsim.utilizationmodels.UtilizationModelFull;
import org.cloudbus.cloudsim.vms.Vm;
import org.cloudbus.cloudsim.vms.VmSimple;

import java.util.ArrayList;
import java.util.List;

public class Helper {

    public static Host createALazzySimpleHost() {
        List<Pe> peList = new ArrayList<>(4);
        //List of Host's CPUs (Processing Elements, PEs)
        for (int i = 0; i < 4; i++) {
            peList.add(new PeSimple(1000, new PeProvisionerSimple()));
        }
        Host host = new SimpleHost(2048, 10000, 1000000, peList);
        //Host host = new HostSimple(2048, 10000, 1000000, peList);
        ResourceProvisioner ramProvisioner = new ResourceProvisionerSimple();
        ResourceProvisioner bwProvisioner = new ResourceProvisionerSimple();
        VmScheduler vmScheduler = new VmSchedulerTimeShared();
        host
                .setRamProvisioner(ramProvisioner)
                .setBwProvisioner(bwProvisioner)
                .setVmScheduler(vmScheduler);
        host.getPowerSupply().setPowerModel(new PowerModelSpecPowerHpProLiantMl110G5Xeon3075());
        host.enableStateHistory();
        return host;
    }

    public static Vm createALazzyVm(int i) {
        Vm vm =  new VmSimple(i, 1000, 2);
        vm.setRam(512).setBw(1000).setSize(10000)
                .setCloudletScheduler(new CloudletSchedulerSpaceShared());
        vm.getUtilizationHistory().enable();
        return vm;
    }

    public static Cloudlet createALazzyCloudlet(int i, int numberOfCloudlets) {
        final long CLOUDLET_LENGTH = 40000;
        final long length = i < numberOfCloudlets/2 ? CLOUDLET_LENGTH : CLOUDLET_LENGTH*2;
        return new CloudletSimple(i, length, 1)
                   .setFileSize(1024)
                   .setOutputSize(1024)
                   .setUtilizationModel(new UtilizationModelFull());
    }

    double getSlaViolationTimePercentageForHosts(final List<Host> hostList) {
        double totalHostsActiveTime = 0;
        double totalHostsSlaViolationTime = 0;

        for (Host host : hostList) {
            HostSlaMetrics sla = new HostSlaMetrics(host);
            totalHostsActiveTime += sla.totalActiveTime;
            totalHostsSlaViolationTime += sla.totalSlaViolationTime;
        }

        return totalHostsSlaViolationTime / totalHostsActiveTime;
    }

    private class HostSlaMetrics {
        Host host;
        private double previousTime;
        double previousAllocatedMips;
        double previousRequestedMips;
        double totalSlaViolationTime;
        double totalActiveTime;
        boolean previousIsActive;

        /**
         * Creates a Host SLA Object to compute SLA metrics for the given Host.
         * @param host the Host to compute SLA metrics.
         */
        HostSlaMetrics(Host host) {
            this.host = host;
            previousTime = -1;
            previousIsActive = true;
            compute();
        }

        /**
         * Computes some SLA metrics for the Host, based on its MIPS history,
         * considering just the times when the Host was active.
         */
        private void compute() {
            for (HostStateHistoryEntry entry : host.getStateHistory()) {
                if (previousTime != -1 && previousIsActive) {
                    final double timeDiff = entry.getTime() - previousTime;
                    totalActiveTime += timeDiff;
                    if (previousAllocatedMips < previousRequestedMips) {
                        totalSlaViolationTime += timeDiff;
                    }
                }

                previousRequestedMips = entry.getRequestedMips();
                previousAllocatedMips = entry.getAllocatedMips();
                previousTime = entry.getTime();
                previousIsActive = entry.isActive();

                //System.out.printf("totalActive Time: %.2f, totoalSlatime: %.2f \n", totalActiveTime, totalSlaViolationTime);
            }
        }

    }
}
