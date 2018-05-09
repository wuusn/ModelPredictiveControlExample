/*
 * CloudSim Plus: A modern, highly-extensible and easier-to-use Framework for
 * Modeling and Simulation of Cloud Computing Infrastructures and Services.
 * http://cloudsimplus.org
 *
 *     Copyright (C) 2015-2018 Universidade da Beira Interior (UBI, Portugal) and
 *     the Instituto Federal de Educação Ciência e Tecnologia do Tocantins (IFTO, Brazil).
 *
 *     This file is part of CloudSim Plus.
 *
 *     CloudSim Plus is free software: you can redistribute it and/or modify
 *     it under the terms of the GNU General Public License as published by
 *     the Free Software Foundation, either version 3 of the License, or
 *     (at your option) any later version.
 *
 *     CloudSim Plus is distributed in the hope that it will be useful,
 *     but WITHOUT ANY WARRANTY; without even the implied warranty of
 *     MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *     GNU General Public License for more details.
 *
 *     You should have received a copy of the GNU General Public License
 *     along with CloudSim Plus. If not, see <http://www.gnu.org/licenses/>.
 */
/*
 * CloudSim Plus: A modern, highly-extensible and easier-to-use Framework for
 * Modeling and Simulation of Cloud Computing Infrastructures and Services.
 * http://cloudsimplus.org
 *
 *     Copyright (C) 2015-2016  Universidade da Beira Interior (UBI, Portugal) and
 *     the Instituto Federal de Educação Ciência e Tecnologia do Tocantins (IFTO, Brazil).
 *
 *     This file is part of CloudSim Plus.
 *
 *     CloudSim Plus is free software: you can redistribute it and/or modify
 *     it under the terms of the GNU General Public License as published by
 *     the Free Software Foundation, either version 3 of the License, or
 *     (at your option) any later version.
 *
 *     CloudSim Plus is distributed in the hope that it will be useful,
 *     but WITHOUT ANY WARRANTY; without even the implied warranty of
 *     MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *     GNU General Public License for more details.
 *
 *     You should have received a copy of the GNU General Public License
 *     along with CloudSim Plus. If not, see <http://www.gnu.org/licenses/>.
 */
package io.yuxin.cloudsimplus;

import org.cloudbus.cloudsim.allocationpolicies.VmAllocationPolicy;
import org.cloudbus.cloudsim.allocationpolicies.migration.*;
import org.cloudbus.cloudsim.allocationpolicies.VmAllocationPolicySimple;
import org.cloudbus.cloudsim.allocationpolicies.migration.*;
import org.cloudbus.cloudsim.brokers.DatacenterBroker;
import org.cloudbus.cloudsim.brokers.DatacenterBrokerSimple;
import org.cloudbus.cloudsim.cloudlets.Cloudlet;
import org.cloudbus.cloudsim.cloudlets.CloudletSimple;
import org.cloudbus.cloudsim.core.CloudSim;
import org.cloudbus.cloudsim.datacenters.Datacenter;
import org.cloudbus.cloudsim.datacenters.DatacenterSimple;
import org.cloudbus.cloudsim.hosts.Host;
import org.cloudbus.cloudsim.hosts.HostSimple;
import org.cloudbus.cloudsim.hosts.HostStateHistoryEntry;
import org.cloudbus.cloudsim.power.models.PowerModelLinear;
import org.cloudbus.cloudsim.power.models.PowerModelSpecPowerHpProLiantMl110G5Xeon3075;
import org.cloudbus.cloudsim.provisioners.PeProvisionerSimple;
import org.cloudbus.cloudsim.provisioners.ResourceProvisionerSimple;
import org.cloudbus.cloudsim.resources.Pe;
import org.cloudbus.cloudsim.resources.PeSimple;
import org.cloudbus.cloudsim.schedulers.cloudlet.CloudletSchedulerTimeShared;
import org.cloudbus.cloudsim.schedulers.vm.VmSchedulerTimeShared;
import org.cloudbus.cloudsim.selectionpolicies.power.PowerVmSelectionPolicyMinimumMigrationTime;
import org.cloudbus.cloudsim.selectionpolicies.power.PowerVmSelectionPolicyMinimumUtilization;
import org.cloudbus.cloudsim.selectionpolicies.power.PowerVmSelectionPolicyRandomSelection;
import org.cloudbus.cloudsim.util.Log;
import org.cloudbus.cloudsim.utilizationmodels.UtilizationModel;
import org.cloudbus.cloudsim.utilizationmodels.UtilizationModelDynamic;
import org.cloudbus.cloudsim.utilizationmodels.UtilizationModelFull;
import org.cloudbus.cloudsim.vms.Vm;
import org.cloudbus.cloudsim.vms.VmSimple;
import org.cloudsimplus.builders.tables.CloudletsTableBuilder;
import org.cloudsimplus.listeners.EventInfo;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public final class Example {
    private static final int SCHEDULE_INTERVAL = 10;

    private static final int HOSTS = 20;
    private static final int VMS = 10;
    private static final int CLOUDLETS =50;

    private static final double HOST_UTILIZATION_THRESHOLD_FOR_VM_MIGRATION = 0.7;


    private List<Host> hostList;
    private final List<Vm> vmList;
    private List<Cloudlet> cloudletList;

    private CloudSim simulation;
    private Datacenter datacenter0;
    private VmAllocationPolicy allocationPolicy;

    private int lastClock;

    /**
     * Starts the example.
     *
     * @param args
     */
    public static void main(String[] args) {
        new Example();
    }

    public Example(){
        Log.printConcatLine("Starting ", getClass().getSimpleName(), "...");

        simulation = new CloudSim();

        hostList = createHostList(HOSTS);
        vmList = createVmList(VMS);
        cloudletList = createCloudletList(CLOUDLETS);

        datacenter0 = createDatacenter(hostList);
        DatacenterBroker broker0 = new DatacenterBrokerSimple(simulation);
        broker0.submitVmList(vmList);
        broker0.submitCloudletList(cloudletList);

        lastClock = (int) simulation.start();

        final List<Cloudlet> finishedList = broker0.getCloudletFinishedList();

        new CloudletsTableBuilder(finishedList).build();

        //hostList.stream().forEach(this::printHistory);

        System.out.printf("\nEnergy consumption: %.2f joule\n", ((DatacenterSimple)datacenter0).getPower());

    }

    private void printHistory(Host host){
        System.out.printf("Host: %6d | State | CPU Usage | Power Consumption\n", host.getId());
        System.out.println("-------------------------------------------------------------------------------------------");
        final double[] utilizationHistory = host.getUtilizationHistory();
        final List<HostStateHistoryEntry> stateHistory = host.getStateHistory();
        int times = lastClock/SCHEDULE_INTERVAL;
        for (int i = 0; i < times; i++) {
            Double cpuUsage = utilizationHistory[times - i];
            if(cpuUsage.isInfinite()) cpuUsage = new Double(0);
            final int time = (int)stateHistory.get(i).getTime();
            boolean isActive = stateHistory.get(i).isActive();
            String state = getState(isActive, cpuUsage);
            System.out.printf("Time: %6d | %5s | %9.2f | %.2f watts\n", time, state, cpuUsage, computePower(host, cpuUsage, isActive));
        }
        System.out.println();
    }

    private String getState(boolean isActive, double cpuUsage) {
        if(!isActive) return "dead";
        if(cpuUsage==0) return "idle";
        return "work";
    }

    private double computePower(Host host, double cpuUsage, boolean isActive) {
        if(!isActive) return 0;

        // just for the bug #127
        if(!host.isActive()) host.setActive(true);

        return host.getPowerSupply().getPower(cpuUsage);
    }


    private Datacenter createDatacenter(List<Host> hostList) {


//        final VmAllocationPolicyMigrationStaticThreshold fallback =
//                new VmAllocationPolicyMigrationStaticThreshold(
//                        new PowerVmSelectionPolicyMinimumUtilization(), HOST_UTILIZATION_THRESHOLD_FOR_VM_MIGRATION);

//        this.allocationPolicy =
//                new VmAllocationPolicyMigrationMedianAbsoluteDeviation(
//                        new PowerVmSelectionPolicyMinimumUtilization());
//        this.allocationPolicy = new VmAllocationPolicyMigrationInterQuartileRange(
//                new PowerVmSelectionPolicyMinimumMigrationTime(),
//                HOST_UTILIZATION_THRESHOLD_FOR_VM_MIGRATION,
//                fallback);
//        this.allocationPolicy = new VmAllocationPolicyMigrationBestFitStaticThreshold(
//                new PowerVmSelectionPolicyRandomSelection(), HOST_UTILIZATION_THRESHOLD_FOR_VM_MIGRATION);



        this.allocationPolicy = new VmAllocationPolicyMigrationDynamicUsagePredictionControl();

//        this.allocationPolicy = new VmAllocationPolicySimple();

        Datacenter dc = new DatacenterSimple(simulation, hostList, allocationPolicy);
        dc.setSchedulingInterval(SCHEDULE_INTERVAL);
        return dc;
    }

    private List<Host> createHostList(int numberOfHosts) {
        List<Host> list = new ArrayList<>(numberOfHosts);
        for(int i = 0; i < numberOfHosts; i++) {
            Host host = Helper.createALazzySimpleHost();
            list.add(host);
        }
        return list;
    }

    private List<Vm> createVmList(int numberOfVms) {
        List<Vm> list = new ArrayList<>(numberOfVms);
        for (int i = 0; i < numberOfVms; i++) {
            Vm vm = Helper.createALazzyVm(i);
            list.add(vm);
        }
        return list;
    }

    private List<Cloudlet> createCloudletList(int numberOfCloudlets) {
        List<Cloudlet> list = new ArrayList<>(numberOfCloudlets);
        for (int i = 0; i < numberOfCloudlets; i++) {
            Cloudlet cloudlet = Helper.createALazzyCloudlet(i, numberOfCloudlets);
            list.add(cloudlet);
        }
        return list;
    }
}
