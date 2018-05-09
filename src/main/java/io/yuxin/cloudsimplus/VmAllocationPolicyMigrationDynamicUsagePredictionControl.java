package io.yuxin.cloudsimplus;

import org.cloudbus.cloudsim.allocationpolicies.VmAllocationPolicyAbstract;
import org.cloudbus.cloudsim.hosts.Host;
import org.cloudbus.cloudsim.util.Log;
import org.cloudbus.cloudsim.vms.Vm;

import java.util.*;

import com.github.signaflo.timeseries.TimeSeries;
import com.github.signaflo.timeseries.forecast.Forecast;
import com.github.signaflo.timeseries.model.arima.Arima;
import com.github.signaflo.timeseries.model.arima.ArimaOrder;
import com.github.signaflo.math.operations.DoubleFunctions;

/*
A simple implement of paper
Dynamic Energy-Aware Capacity Provisioning for Cloud Computing Environments
https://www.researchgate.net/profile/Mohamed_Faten_Zhani/publication/262290042_Dynamic_energy-aware_capacity_provisioning_for_cloud_computing_environments/links/5805835608aef87fbf3bc00e/Dynamic-energy-aware-capacity-provisioning-for-cloud-computing-environments.pdf
 */

public class VmAllocationPolicyMigrationDynamicUsagePredictionControl extends VmAllocationPolicyAbstract {

    private final Set<Host> runningHostSet;
    private final Set<Host> deadHostSet;
    private final Set<Host> idleHostSet;

    private final List<Double> allCpuutilizationHistory;

    private double averagePowerOffIdle;

    private int lag;


    public VmAllocationPolicyMigrationDynamicUsagePredictionControl() {
        this(2);
    }

    public VmAllocationPolicyMigrationDynamicUsagePredictionControl(int lag) {
        this.lag = lag;
        this.runningHostSet = new TreeSet<>();
        this.deadHostSet = new TreeSet<>();
        this.idleHostSet = new TreeSet<>();
        this.allCpuutilizationHistory = new ArrayList<>();
    }

    /**
     * Gets a map of optimized allocation for VMs according to current utilization
     * and Hosts under and overloaded conditions.
     * The conditions that will make a new VM placement map to be proposed
     * and returned is defined by each implementing class.
     *
     * @param vmList the list of VMs to be reallocated
     * @return the new vm placement map, where each key is a VM and each value is the host where such a Vm has to be placed
     *
     */
    @Override
    public Map<Vm, Host> getOptimizedAllocationMap(final List<? extends Vm> vmList) {
        updateHostStateSet();
        this.averagePowerOffIdle = computeAveragePowerOffIdle(getHostList());
        int nextNumberOfHost = predictNextNumberOfHost(getHostList());
        return reconfiguration(nextNumberOfHost);
    }

    private Map<Vm, Host> reconfiguration(int nextNumberOfHost) {
        if(nextNumberOfHost==-1) {
            System.out.println("predict error");
            return Collections.EMPTY_MAP;
        }
        int activedNumber = (int) getHostList().stream().filter(host -> host.isActive()).count();
        final Map<Vm, Host> migrationMap = new HashMap<>();
        if(activedNumber < nextNumberOfHost) {
            int need = nextNumberOfHost - activedNumber;
            for(Host host : deadHostSet) {
                host.setActive(true);
                deadHostSet.remove(host);
                need--;
                if(need==0) break;
            }
        }else if(activedNumber > nextNumberOfHost) {
            int reduce = activedNumber - nextNumberOfHost;
            for (Host host : idleHostSet) {
                host.setActive(false);
                idleHostSet.remove(host);
                deadHostSet.add(host);
                reduce--;
                if (reduce == 0) break;
            }
            if (reduce > 0) {
                //todo: migrate vms on low usage host
            }
        }
        return migrationMap;
    }

    private int predictNextNumberOfHost(List<Host> hostList) {
        // at the beginning of the time k

        final int M = hostList.size();   // total number of physical machines
        final int n = this.lag;               // number of lags; the last n observations
        final double acpu = 121;        // somewhat a weight factor
        final double Eidle = this.averagePowerOffIdle;

        int Nk = 1; // wight factor the severity of violation, like number of requests in scheduling
        // int Nk = broker.getCloudletWaitingList().size();

        double PPowerk = 0.6; // to-do: electricity price at time k, can be different between day and night.
        double GcpuLast = computeAndAddCurrentAllCpuMips(hostList);

        if(allCpuutilizationHistory.size()<this.lag) {
            System.out.println("utilization size is too small");
            return -1;
        }
        // predict Gcpu at time k
        double[] GcpuHistroy = getFixedLengthArrayFromList(allCpuutilizationHistory, n);
        double Gcpuk = predict(GcpuHistroy, 12,1);
        // get Wk; Wk = max(Gcpuk/Ccpuk)
        // Ccpuk, capacity for CPU  of a single machine at time k
        double Wk = 0;
        for(Host h : hostList) {
            if(h.isActive()) {
                double Ccpuk = h.getTotalMipsCapacity();
                double Wi = Gcpuk/Ccpuk;
                if(Wi > Wk) Wk = Wi;
            }
        }
        System.out.println("Wk="+Wk);
        // compute current SLA
        double PSLAk = new Helper().getSlaViolationTimePercentageForHosts(hostList);
        if(PSLAk == 0) {
            PSLAk = 1;
        }

        // compute the next number of host needed, formula from the paper mentioned above
        Double Xk = Wk + Math.sqrt((Nk * PSLAk * acpu * Wk)/(PPowerk * Eidle));
        if(Xk.isNaN()) {
            System.out.println("predict Xk is NAN");
            return -1;
        }
        return Xk.intValue()+1;
    }

    private double predict(double[] history, int steps, int index) {
        TimeSeries timeSeries = TimeSeries.from(DoubleFunctions.arrayFrom(history));
        ArimaOrder modelOrder = ArimaOrder.order(0, 1, 1, 0, 1, 1);
        Arima model = Arima.model(timeSeries, modelOrder);
        Forecast forecast = model.forecast(steps);
        return forecast.pointEstimates().at(index);
    }

    private double[] getFixedLengthArrayFromList(List<Double> list, int length) {
        double[] rtn = new double[length];
        for(int i=0;i<length;i++) {
            rtn[i] = list.get(length-i-1);
        }
        return rtn;
    }

    private double[] getFixedLengthArrayFromArray(double[] array, int length) {
        double[] rtn = new double[length];
        for(int i=0;i<length;i++) {
            rtn[i] = array[length-i-1];
        }
        return rtn;
    }


    private double computeAveragePowerOffIdle(List<Host> hostList) {
        double sum = 0;
        for (Host h : hostList) {
            sum += h.getPowerSupply().getPower(0);
        }
        System.out.println("sum="+sum);
        System.out.println("hostsize="+hostList.size());
        return sum / hostList.size();
    }

    private  double computeAndAddCurrentAllCpuMips(List<Host> hostList) {
        double rtn = 0;
        for(Host h : hostList) {
            if(h.isActive()) {
                rtn += h.getUtilizationOfCpuMips();
            }
        }

        allCpuutilizationHistory.add(0,rtn); // newest at first
        return rtn;
    }

    private void updateHostStateSet() {

        for(Host h : deadHostSet) {
            if(h.isActive()) deadHostSet.remove(h);
        }

        for(Host h : idleHostSet) {
            if( (!h.isActive()) || h.getUtilizationOfCpu() > 0) idleHostSet.remove(h);
        }

        for(Host h : runningHostSet) {
            if( h.getUtilizationOfCpu() <=0 ) runningHostSet.remove(h);
        }

        for(Host h : getHostList()) {
            if(!h.isActive()) {
                this.deadHostSet.add(h);
            } else if (h.getUtilizationOfCpu() == 0) {
                this.idleHostSet.add(h);
            } else {
                this.runningHostSet.add(h);
            }
        }
    }

    @Override
    public Optional<Host> findHostForVm(final Vm vm) {
        final Map<Host, Long> map = getHostFreePesMap();
        return map.entrySet()
                .stream()
                .filter(e -> e.getKey().isSuitableForVm(vm))
                .sorted(Comparator.comparingInt(e -> e.getKey().getId()))
                .max(Comparator.comparingLong(Map.Entry::getValue))
                .map(Map.Entry::getKey);
    }

    @Override
    public boolean allocateHostForVm(final Vm vm, final Host host) {
        if(super.allocateHostForVm(vm, host)){
            addUsedPes(vm);
            getHostFreePesMap().put(host, getHostFreePesMap().get(host) - vm.getNumberOfPes());
            return true;
        }

        return false;
    }

    @Override
    public void deallocateHostForVm(final Vm vm) {
        final Host previousHost = vm.getHost();
        super.deallocateHostForVm(vm);
        final long pes = removeUsedPes(vm);
        if (previousHost != Host.NULL) {
            getHostFreePesMap().compute(previousHost, (host, freePes) -> freePes == null ? pes : freePes + pes);
        }
    }
}