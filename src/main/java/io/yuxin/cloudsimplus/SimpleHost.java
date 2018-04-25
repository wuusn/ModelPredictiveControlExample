/*
 * Title: CloudSim Toolkit Description: CloudSim (Cloud Simulation) Toolkit for Modeling and
 * Simulation of Clouds Licence: GPL - http://www.gnu.org/copyleft/gpl.html
 *
 * Copyright (c) 2009-2012, The University of Melbourne, Australia
 */
package io.yuxin.cloudsimplus;

import org.cloudbus.cloudsim.hosts.Host;
import org.cloudbus.cloudsim.hosts.HostStateHistoryEntry;
import org.cloudbus.cloudsim.power.supply.HostPowerSupply;
import org.cloudbus.cloudsim.power.supply.PowerSupply;
import org.cloudbus.cloudsim.resources.*;
import org.cloudbus.cloudsim.util.Log;
import org.cloudbus.cloudsim.util.MathUtil;
import org.cloudbus.cloudsim.vms.Vm;
import org.cloudbus.cloudsim.datacenters.Datacenter;
import org.cloudbus.cloudsim.schedulers.vm.VmScheduler;

import java.util.*;
import java.util.function.Predicate;

import org.cloudbus.cloudsim.core.Simulation;
import org.cloudbus.cloudsim.vms.VmStateHistoryEntry;
import org.cloudbus.cloudsim.vms.UtilizationHistory;
import org.cloudsimplus.listeners.EventListener;
import org.cloudsimplus.listeners.HostUpdatesVmsProcessingEventInfo;
import org.cloudbus.cloudsim.lists.PeList;
import org.cloudbus.cloudsim.provisioners.ResourceProvisioner;

import static java.util.stream.Collectors.toList;
/**
 * Almost like the HostSimple class,
 * fix the bug of idle state in the #addStateHistory,
 * add a bool method to check if the state is idle.
 *
 *
 * Change  history for each interval, not just for working state.
 *
 * just for cloudsim plus 2.1.0
 * @author yuxin wu
 */


/**
 * A Host class that implements the most basic features of a Physical Machine
 * (PM) inside a {@link Datacenter}. It executes actions related to management
 * of virtual machines (e.g., creation and destruction). A host has a defined
 * policy for provisioning memory and bw, as well as an allocation policy for
 * PEs to {@link Vm virtual machines}. A host is associated to a Datacenter and
 * can host virtual machines.
 *
 * @author Rodrigo N. Calheiros
 * @author Anton Beloglazov
 * @since CloudSim Toolkit 1.0
 */
public class SimpleHost implements Host {
    /**
     * @see #getStateHistory()
     */
    private final List<HostStateHistoryEntry> stateHistory;
    private final PowerSupply powerSupply;

    /**
     * @see #getId()
     */
    private int id;

    /**
     * @see #isFailed()
     */
    private boolean failed;

    private boolean active;
    private boolean stateHistoryEnabled;


    private Ram ram;

    private Bandwidth bw;

    /**
     * @see #getStorage()
     */
    private Storage storage;

    /**
     * @see #getRamProvisioner()
     */
    private ResourceProvisioner ramProvisioner;

    /**
     * @see #getBwProvisioner()
     */
    private ResourceProvisioner bwProvisioner;

    /**
     * @see #getVmScheduler()
     */
    private VmScheduler vmScheduler;

    /**
     * @see #getVmList()
     */
    private final List<Vm> vmList = new ArrayList<>();

    /**
     * @see #getPeList()
     */
    private List<Pe> peList;

    /**
     * @see #getVmsMigratingIn()
     */
    private final Set<Vm> vmsMigratingIn;

    /**
     * @see #getVmsMigratingOut()
     */
    private final Set<Vm> vmsMigratingOut;


    /**
     * @see #getDatacenter()
     */
    private Datacenter datacenter;

    /**
     * @see Host#removeOnUpdateProcessingListener(EventListener)
     */
    private Set<EventListener<HostUpdatesVmsProcessingEventInfo>> onUpdateProcessingListeners;

    /**
     * @see #getSimulation()
     */
    private Simulation simulation;

    /**
     * A list of resources the VM has, that represent virtual resources corresponding to physical resources
     * from the Host where the VM is placed.
     *
     * @see #getResource(Class)
     */
    private List<ResourceManageable> resources;
    private List<ResourceProvisioner> provisioners;
    private List<Vm> vmCreatedList;
    /**
     * The previous utilization mips.
     */
    private double previousUtilizationMips;

    /**
     * Creates a Host without a pre-defined ID.
     * The ID is automatically set when a List of Hosts is attached
     * to a {@link Datacenter}.
     *
     * @param ram the RAM capacity in Megabytes
     * @param bw the Bandwidth (BW) capacity in Megabits/s
     * @param storage the storage capacity in Megabytes
     * @param peList the host's {@link Pe} list
     * @see #setId(int)
     */
    public SimpleHost(final long ram, final long bw, final long storage, final List<Pe> peList) {
        this.setId(-1);
        this.setActive(true);
        this.setSimulation(Simulation.NULL);

        this.ram = new Ram(ram);
        this.bw = new Bandwidth(bw);
        this.setStorage(storage);
        this.setRamProvisioner(ResourceProvisioner.NULL);
        this.setBwProvisioner(ResourceProvisioner.NULL);

        this.setVmScheduler(VmScheduler.NULL);
        this.setPeList(peList);
        this.setFailed(false);
        this.setDatacenter(Datacenter.NULL);
        this.onUpdateProcessingListeners = new HashSet<>();
        this.resources = new ArrayList<>();
        this.vmCreatedList = new ArrayList<>();
        this.provisioners = new ArrayList<>();
        this.vmsMigratingIn = new HashSet<>();
        this.vmsMigratingOut = new HashSet<>();
        this.powerSupply = new SimpleHostPowerSupply(this);
        stateHistory = new LinkedList<>();
    }

    /**
     * Creates a Host with the given parameters.
     *
     * @param ramProvisioner the ram provisioner with capacity in Megabytes
     * @param bwProvisioner the bw provisioner with capacity in Megabits/s
     * @param storage the storage capacity in Megabytes
     * @param peList the host's PEs list
     * @param vmScheduler the vm scheduler
     */
    public SimpleHost(
            final ResourceProvisioner ramProvisioner,
            final ResourceProvisioner bwProvisioner,
            final long storage,
            final List<Pe> peList,
            final VmScheduler vmScheduler)
    {
        this(ramProvisioner.getCapacity(), bwProvisioner.getCapacity(), storage, peList);
        setRamProvisioner(ramProvisioner);
        setBwProvisioner(bwProvisioner);
        setPeList(peList);
        setVmScheduler(vmScheduler);
    }

    @Override
    public double getTotalMipsCapacity() {
        return peList.stream()
                .filter(Pe::isWorking)
                .mapToDouble(Pe::getCapacity)
                .sum();
    }

    @Override
    public double updateProcessing(final double currentTime) {
        setPreviousUtilizationMips(getUtilizationOfCpuMips());
        double nextSimulationTime = Double.MAX_VALUE;
        /* Uses a traditional for to avoid ConcurrentModificationException,
         * e.g., in cases when Vm is destroyed during simulation execution.*/
        for (int i = 0; i < vmList.size(); i++) {
            final Vm vm = vmList.get(i);
            final double nextTime = vm.updateProcessing(currentTime, vmScheduler.getAllocatedMips(vm));
            nextSimulationTime = Math.min(nextTime, nextSimulationTime);
        }

        notifyOnUpdateProcessingListeners(nextSimulationTime);
        addStateHistory(currentTime);

        return nextSimulationTime;
    }

    private void notifyOnUpdateProcessingListeners(final double nextSimulationTime) {
        onUpdateProcessingListeners.forEach(l -> l.update(HostUpdatesVmsProcessingEventInfo.of(l,this, nextSimulationTime)));
    }

    @Override
    public boolean removeVmMigratingIn(final Vm vm){
        return vmsMigratingIn.remove(vm);
    }

    @Override
    public boolean createVm(final Vm vm) {
        final boolean result = createVmInternal(vm);
        if(result) {
            vmCreatedList.add(vm);
            vm.setHost(this);
            vm.notifyOnHostAllocationListeners();
            if(vm.getStartTime() < 0) {
                vm.setStartTime(getSimulation().clock());
            }
        }

        return result;
    }

    @Override
    public boolean createTemporaryVm(final Vm vm) {
        return createVmInternal(vm);
    }

    private boolean createVmInternal(final Vm vm) {
        if(!allocateResourcesForVm(vm, false)){
            return false;
        }

        vmList.add(vm);
        return true;
    }

    /**
     * Try to allocate all resources that a VM requires (Storage, RAM, BW and MIPS) to be placed at this Host.
     *
     * @param vm the VM to try allocating resources to
     * @param inMigration If the VM is migrating into the Host or it is being just created for the first time
     * @return true if the Vm was placed into the host, false if the Host doesn't have enough resources to allocate the Vm
     */
    private boolean allocateResourcesForVm(final Vm vm, final boolean inMigration){
        final String msg = inMigration ? "VM Migration" : "VM Creation";
        if (!storage.isResourceAmountAvailable(vm.getStorage())) {
            Log.printFormattedLine(
                    "%.2f: %s: [%s] Allocation of %s to %s failed due to lack of storage. Required %d but there is just %d MB available.",
                    simulation.clock(), getClass().getSimpleName(),
                    msg, vm, this, vm.getStorage().getCapacity(), storage.getAvailableResource());
            return false;
        }

        if (!ramProvisioner.isSuitableForVm(vm, vm.getCurrentRequestedRam())) {
            Log.printFormattedLine(
                    "%.2f: %s: [%s] Allocation of %s to %s failed due to lack of RAM. Required %d but there is just %d MB available.",
                    simulation.clock(), getClass().getSimpleName(),
                    msg, vm, this, vm.getRam().getCapacity(), ram.getAvailableResource());
            return false;
        }

        if (!bwProvisioner.isSuitableForVm(vm, vm.getCurrentRequestedBw())) {
            Log.printFormattedLine(
                    "%.2f: %s: [%s] Allocation of %s to %s failed due to lack of BW. Required %d but there is just %d Mbps available.",
                    simulation.clock(), getClass().getSimpleName(),
                    msg, vm, this, vm.getBw().getCapacity(), bw.getAvailableResource());
            return false;
        }

        if (!vmScheduler.isSuitableForVm(vm)) {
            Log.printFormattedLine(
                    "%.2f: %s: [%s] Allocation of %s to %s failed due to lack of PEs.\n\t  "+
                            "Required %d PEs of %.0f MIPS (%.0f MIPS total). However, there are just %d working PEs of %.0f MIPS, from which %.0f MIPS are available.",
                    getSimulation().clock(), getClass().getSimpleName(), msg, vm, this,
                    vm.getNumberOfPes(), vm.getMips(), vm.getTotalMipsCapacity(),
                    vmScheduler.getWorkingPeList().size(), getMips(), vmScheduler.getAvailableMips());
            return false;
        }

        vm.setInMigration(inMigration);
        storage.allocateResource(vm.getStorage());
        ramProvisioner.allocateResourceForVm(vm, vm.getCurrentRequestedRam());
        bwProvisioner.allocateResourceForVm(vm, vm.getCurrentRequestedBw());
        vmScheduler.allocatePesForVm(vm, vm.getCurrentRequestedMips());

        return true;
    }

    @Override
    public void reallocateMigratingInVms() {
        for (final Vm vm : getVmsMigratingIn()) {
            if (!vmList.contains(vm)) {
                vmList.add(vm);
            }
            ramProvisioner.allocateResourceForVm(vm, vm.getCurrentRequestedRam());
            bwProvisioner.allocateResourceForVm(vm, vm.getCurrentRequestedBw());
            vmScheduler.allocatePesForVm(vm, vm.getCurrentRequestedMips());
            storage.allocateResource(vm.getStorage());
        }
    }

    @Override
    public boolean isSuitableForVm(final Vm vm) {
        return  active &&
                vmScheduler.getPeCapacity() >= vm.getCurrentRequestedMaxMips() &&
                vmScheduler.getAvailableMips() >= vm.getCurrentRequestedTotalMips() &&
                ramProvisioner.isSuitableForVm(vm, vm.getCurrentRequestedRam()) &&
                bwProvisioner.isSuitableForVm(vm, vm.getCurrentRequestedBw());
    }

    @Override
    public boolean isActive() {
        return this.active;
    }

    @Override
    public final Host setActive(final boolean active) {
        this.active = active;
        return this;
    }

    @Override
    public void destroyVm(final Vm vm) {
        destroyVmInternal(vm);
        vm.notifyOnHostDeallocationListeners(this);
        vm.setStopTime(getSimulation().clock());
    }

    @Override
    public void destroyTemporaryVm(final Vm vm) {
        destroyVmInternal(vm);
    }

    private void destroyVmInternal(final Vm vm) {
        Objects.requireNonNull(vm);
        deallocateResourcesOfVm(vm);
        vmList.remove(vm);
    }

    /**
     * Deallocate all resources that a VM was using.
     *
     * @param vm the VM
     */
    protected void deallocateResourcesOfVm(final Vm vm) {
        vm.setCreated(false);
        ramProvisioner.deallocateResourceForVm(vm);
        bwProvisioner.deallocateResourceForVm(vm);
        vmScheduler.deallocatePesFromVm(vm);
        storage.deallocateResource(vm.getStorage());
    }

    @Override
    public void destroyAllVms() {
        deallocateResourcesOfAllVms();
        for (Vm vm : vmList) {
            vm.setCreated(false);
            storage.deallocateResource(vm.getStorage());
        }

        vmList.clear();
    }

    /**
     * Deallocate all resources that all VMs were using.
     */
    protected void deallocateResourcesOfAllVms() {
        ramProvisioner.deallocateResourceForAllVms();
        bwProvisioner.deallocateResourceForAllVms();
        vmScheduler.deallocatePesForAllVms();
    }

    @Override
    public Vm getVm(final int vmId, final int brokerId) {
        return vmList.stream()
                .filter(vm -> vm.getId() == vmId && vm.getBroker().getId() == brokerId)
                .findFirst().orElse(Vm.NULL);
    }

    /**
     * {@inheritDoc}
     * @return {@inheritDoc}
     * @see #getNumberOfWorkingPes()
     * @see #getNumberOfFreePes()
     * @see #getNumberOfFailedPes()
     */
    @Override
    public long getNumberOfPes() {
        return peList.size();
    }

    @Override
    public int getNumberOfFreePes() {
        return PeList.getNumberOfFreePes(getPeList());
    }

    @Override
    public void deallocatePesForVm(final Vm vm) {
        vmScheduler.deallocatePesFromVm(vm);
    }

    @Override
    public List<Double> getAllocatedMipsForVm(final Vm vm) {
        return vmScheduler.getAllocatedMips(vm);
    }

    @Override
    public double getTotalAllocatedMipsForVm(final Vm vm) {
        return vmScheduler.getTotalAllocatedMipsForVm(vm);
    }

    @Override
    public double getMaxAvailableMips() {
        return vmScheduler.getMaxAvailableMips();
    }

    @Override
    public double getMips() {
        return peList.stream().mapToDouble(Pe::getCapacity).findFirst().orElse(0);
    }

    @Override
    public double getAvailableMips() {
        return vmScheduler.getAvailableMips();
    }

    @Override
    public Resource getBw() {
        return bwProvisioner.getResource();
    }

    @Override
    public Resource getRam() {
        return ramProvisioner.getResource();
    }

    @Override
    public Resource getStorage() {
        return storage;
    }

    @Override
    public int getId() {
        return id;
    }

    @Override
    public final void setId(int id) {
        this.id = id;
    }

    @Override
    public ResourceProvisioner getRamProvisioner() {
        return ramProvisioner;
    }

    @Override
    public final Host setRamProvisioner(final ResourceProvisioner ramProvisioner) {
        checkSimulationIsRunningAndAttemptedToChangeHost("RAM");
        this.ramProvisioner = ramProvisioner;
        this.ramProvisioner.setResource(ram);
        return this;
    }

    private void checkSimulationIsRunningAndAttemptedToChangeHost(final String resourceName) {
        if(simulation.isRunning()){
            throw new UnsupportedOperationException("It is not allowed to change a Host's "+resourceName+" after the simulation started.");
        }
    }

    @Override
    public ResourceProvisioner getBwProvisioner() {
        return bwProvisioner;
    }

    @Override
    public final Host setBwProvisioner(final ResourceProvisioner bwProvisioner) {
        checkSimulationIsRunningAndAttemptedToChangeHost("BW");
        this.bwProvisioner = bwProvisioner;
        this.bwProvisioner.setResource(bw);
        return this;
    }

    @Override
    public VmScheduler getVmScheduler() {
        return vmScheduler;
    }

    @Override
    public final Host setVmScheduler(final VmScheduler vmScheduler) {
        Objects.requireNonNull(vmScheduler);

        vmScheduler.setHost(this);
        this.vmScheduler = vmScheduler;
        return this;
    }

    @Override
    public List<Pe> getPeList() {
        return peList;
    }

    /**
     * Sets the pe list.
     *
     * @param peList the new pe list
     * @return
     */
    protected final Host setPeList(final List<Pe> peList) {
        Objects.requireNonNull(peList);
        checkSimulationIsRunningAndAttemptedToChangeHost("List of PE");
        this.peList = peList;

        int peId = this.peList.stream().filter(pe -> pe.getId() > 0).mapToInt(Pe::getId).max().orElse(-1);
        final List<Pe> pesWithoutIds = this.peList.stream().filter(pe -> pe.getId() < 0).collect(toList());
        for(Pe pe: pesWithoutIds){
            pe.setId(++peId);
        }

        return this;
    }

    @Override
    public <T extends Vm> List<T> getVmList() {
        return (List<T>) Collections.unmodifiableList(vmList);
    }

    @Override
    public <T extends Vm> List<T> getVmCreatedList() {
        return (List<T>) Collections.unmodifiableList(vmCreatedList);
    }

    protected void addVmToList(Vm vm){
        Objects.requireNonNull(vm);
        vmList.add(vm);
    }

    protected void removeVmFromList(final Vm vm){
        Objects.requireNonNull(vm);
        vmList.remove(vm);
    }

    @Override
    public boolean isFailed() {
        return failed;
    }

    @Override
    public final boolean setFailed(final boolean failed) {
        this.failed = failed;
        PeList.setStatusFailed(peList, getId(), failed);
        return true;
    }

    @Override
    public boolean setPeStatus(final int peId, final Pe.Status status) {
        return PeList.setPeStatus(peList, peId, status);
    }

    @Override
    public <T extends Vm> Set<T> getVmsMigratingIn() {
        return (Set<T>)vmsMigratingIn;
    }

    @Override
    public boolean addMigratingInVm(final Vm vm) {
        if (vmsMigratingIn.contains(vm)) {
            return false;
        }

        vmsMigratingIn.add(vm);
        if(!allocateResourcesForVm(vm, true)){
            vmsMigratingIn.remove(vm);
            return false;
        }

        updateProcessing(simulation.clock());
        vm.getHost().updateProcessing(simulation.clock());

        return true;
    }

    @Override
    public void removeMigratingInVm(final Vm vm) {
        deallocateResourcesOfVm(vm);
        vmsMigratingIn.remove(vm);
        vmList.remove(vm);
        vm.setInMigration(false);
    }

    @Override
    public Set<Vm> getVmsMigratingOut() {
        return Collections.unmodifiableSet(vmsMigratingOut);
    }

    @Override
    public boolean addVmMigratingOut(final Vm vm) {
        return this.vmsMigratingOut.add(vm);
    }

    @Override
    public boolean removeVmMigratingOut(final Vm vm) {
        return this.vmsMigratingOut.remove(vm);
    }

    @Override
    public Datacenter getDatacenter() {
        return datacenter;
    }

    @Override
    public final void setDatacenter(final Datacenter datacenter) {
        checkSimulationIsRunningAndAttemptedToChangeHost("Datacenter");
        this.datacenter = datacenter;
    }

    @Override
    public String toString() {
        final String dc =
                Datacenter.NULL.equals(datacenter) ? "" :
                        String.format("/DC %d", datacenter.getId());
        return String.format("Host %d%s", getId(), dc);
    }

    @Override
    public boolean removeOnUpdateProcessingListener(final EventListener<HostUpdatesVmsProcessingEventInfo> listener) {
        return onUpdateProcessingListeners.remove(listener);
    }

    @Override
    public Host addOnUpdateProcessingListener(final EventListener<HostUpdatesVmsProcessingEventInfo> listener) {
        Objects.requireNonNull(listener);
        this.onUpdateProcessingListeners.add(listener);
        return this;
    }

    @Override
    public long getAvailableStorage() {
        return storage.getAvailableResource();
    }

    @Override
    public long getNumberOfWorkingPes() {
        return peList.size() - getNumberOfFailedPes();
    }

    @Override
    public long getNumberOfFailedPes() {
        return peList.stream()
                .filter(Pe::isFailed)
                .count();
    }

    private Host setStorage(final long size) {
        this.storage = new Storage(size);
        return this;
    }

    @Override
    public Simulation getSimulation() {
        return this.simulation;
    }

    @Override
    public final Host setSimulation(final Simulation simulation) {
        this.simulation = simulation;
        return this;
    }

    /**
     * Compare this Host with another one based on {@link #getTotalMipsCapacity()}.
     *
     * @param o the Host to compare to
     * @return {@inheritDoc}
     */
    @Override
    public int compareTo(final Host o) {
        return Double.compare(getTotalMipsCapacity(), o.getTotalMipsCapacity());
    }

    @Override
    public boolean equals(final Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;

        SimpleHost that = (SimpleHost) o;

        if (id != that.id) return false;
        return simulation.equals(that.simulation);
    }

    @Override
    public int hashCode() {
        int result = id;
        result = 31 * result + simulation.hashCode();
        return result;
    }

    @Override
    public List<ResourceManageable> getResources() {
        if(simulation.isRunning() && resources.isEmpty()){
            resources = Arrays.asList(ramProvisioner.getResource(), bwProvisioner.getResource());
        }
        return Collections.unmodifiableList(resources);
    }

    @Override
    public ResourceProvisioner getProvisioner(final Class<? extends ResourceManageable> resourceClass) {
        if(simulation.isRunning() && provisioners.isEmpty()){
            provisioners = Arrays.asList(ramProvisioner, bwProvisioner);
        }

        return provisioners
                .stream()
                .filter(r -> r.getResource().isObjectSubClassOf(resourceClass))
                .findFirst()
                .orElse(ResourceProvisioner.NULL);
    }

    @Override
    public List<Pe> getWorkingPeList() {
        return getFilteredPeList(Pe::isWorking);
    }

    @Override
    public List<Pe> getBuzyPeList() {
        return getFilteredPeList(Pe::isBuzy);
    }

    @Override
    public List<Pe> getFreePeList() {
        return getFilteredPeList(Pe::isFree);
    }

    private List<Pe> getFilteredPeList(final Predicate<Pe> status) {
        return peList.stream().filter(status).collect(toList());
    }

    @Override
    public double getUtilizationOfCpu() {
        return computeCpuUtilizationPercent(getUtilizationOfCpuMips());
    }

    protected double computeCpuUtilizationPercent(final double mipsUsage){
        final double totalMips = getTotalMipsCapacity();
        if(totalMips == 0){
            return 0;
        }

        final double utilization = mipsUsage / totalMips;
        return (utilization > 1 && utilization < 1.01 ? 1 : utilization);
    }

    @Override
    public double getUtilizationOfCpuMips() {
        return vmList.stream()
                .mapToDouble(vm -> vmScheduler.getTotalAllocatedMipsForVm(vm))
                .sum();
    }

    @Override
    public long getUtilizationOfRam() {
        return ramProvisioner.getTotalAllocatedResource();
    }

    @Override
    public long getUtilizationOfBw() {
        return bwProvisioner.getTotalAllocatedResource();
    }

    @Override
    public double[] getUtilizationHistory() {
        // better for dynamic length
        final double[] utilizationHistory = new double[UtilizationHistory.DEF_MAX_HISTORY_ENTRIES];
        final double totalMipsCapacity = getTotalMipsCapacity();
        for (final Vm vm : this.getVmCreatedList()) {
            final List<Double> history = vm.getUtilizationHistory().getHistory();
            for (int i = 0; i < history.size(); i++) {
                utilizationHistory[i] += history.get(i) * vm.getTotalMipsCapacity() / totalMipsCapacity;
            }
        }
        //return MathUtil.trimZeroTail(utilizationHistory);
        // for idle state
        // check
        return utilizationHistory;
    }

    @Override
    public PowerSupply getPowerSupply() {
        return powerSupply;
    }

    @Override
    public double getPreviousUtilizationOfCpu() {
        return computeCpuUtilizationPercent(previousUtilizationMips);
    }

    @Override
    public void enableStateHistory() {
        this.stateHistoryEnabled = true;
    }

    @Override
    public void disableStateHistory() {
        this.stateHistoryEnabled = false;
    }

    @Override
    public boolean isStateHistoryEnabled() {
        return this.stateHistoryEnabled;
    }

    /**
     * Sets the previous utilization of CPU in mips.
     *
     * @param previousUtilizationMips the new previous utilization of CPU in
     * mips
     */
    private void setPreviousUtilizationMips(final double previousUtilizationMips) {
        this.previousUtilizationMips = previousUtilizationMips;
    }

    @Override
    public List<Vm> getFinishedVms() {
        return getVmList().stream()
                .filter(vm -> !vm.isInMigration())
                .filter(vm -> vm.getCurrentRequestedTotalMips() == 0)
                .collect(toList());
    }

    /**
     * Adds the VM resource usage to the History if the VM is not migrating into the Host.
     * @param vm the VM to add its usage to the history
     * @param currentTime the current simulation time
     * @return the total allocated MIPS for the given VM
     */
    private double addVmResourceUseToHistoryIfNotMigratingIn(final Vm vm, final double currentTime) {
        double totalAllocatedMips = getVmScheduler().getTotalAllocatedMipsForVm(vm);
        if (getVmsMigratingIn().contains(vm)) {
            Log.printFormattedLine("%.2f: [" + this + "] " + vm
                    + " is migrating in", getSimulation().clock());
            return totalAllocatedMips;
        }

        final double totalRequestedMips = vm.getCurrentRequestedTotalMips();
        if (totalAllocatedMips + 0.1 < totalRequestedMips) {
            final String reason = getVmsMigratingOut().contains(vm) ? "migration overhead" : "capacity unavailability";
            final double notAllocatedMipsByPe = (totalRequestedMips - totalAllocatedMips)/vm.getNumberOfPes();
            Log.printFormattedLine(
                    "%.2f: [%s] %.0f MIPS not allocated for each one of the %d PEs from %s due to %s.",
                    getSimulation().clock(), this, notAllocatedMipsByPe, vm.getNumberOfPes(), vm, reason);
        }

        final VmStateHistoryEntry entry = new VmStateHistoryEntry(
                currentTime,
                totalAllocatedMips,
                totalRequestedMips,
                vm.isInMigration() && !getVmsMigratingIn().contains(vm));
        vm.addStateHistoryEntry(entry);

        if (vm.isInMigration()) {
            Log.printFormattedLine(
                    "%.2f: [" + this + "] " + vm + " is migrating out ",
                    getSimulation().clock());
            totalAllocatedMips /= getVmScheduler().getMaxCpuUsagePercentDuringOutMigration();
        }

        return totalAllocatedMips;
    }

    private void addStateHistory(final double currentTime) {
        if(!stateHistoryEnabled){
            return;
        }

        double hostTotalRequestedMips = 0;

        for (final Vm vm : getVmList()) {
            final double totalRequestedMips = vm.getCurrentRequestedTotalMips();
            addVmResourceUseToHistoryIfNotMigratingIn(vm, currentTime);
            hostTotalRequestedMips += totalRequestedMips;
        }
        // fix the bug of idle state; when cpu utilization is 0, it may be active.
        addStateHistoryEntry(currentTime, getUtilizationOfCpuMips(), hostTotalRequestedMips,isActive());
    }

    /**
     * Adds a host state history entry.
     *
     * @param time the time
     * @param allocatedMips the allocated mips
     * @param requestedMips the requested mips
     * @param isActive the is active
     */
    private void addStateHistoryEntry(final double time, final double allocatedMips, final double requestedMips, final boolean isActive) {
        final HostStateHistoryEntry newState = new HostStateHistoryEntry(time, allocatedMips, requestedMips, isActive);
        if (!stateHistory.isEmpty()) {
            final HostStateHistoryEntry previousState = stateHistory.get(stateHistory.size() - 1);
            if (previousState.getTime() == time) {
                stateHistory.set(stateHistory.size() - 1, newState);
                return;
            }
        }

        stateHistory.add(newState);
    }

    @Override
    public List<HostStateHistoryEntry> getStateHistory() {
        return Collections.unmodifiableList(stateHistory);
    }

    /**
     * check if the host state is idle
     */
    public boolean isIdle() {
        return isActive() && getUtilizationOfCpu() == 0;
    }
}
