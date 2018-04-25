package io.yuxin.cloudsimplus;

import org.cloudbus.cloudsim.hosts.Host;
import org.cloudbus.cloudsim.power.models.PowerModel;
import org.cloudbus.cloudsim.power.supply.PowerSupply;

import java.util.Objects;

/**
 * fix not considering the idle state
 * just use for cloudsim plus 2.1.0
 * @author yuxin wu
 *
 * Provides data about a PM power consumption,
 * according to a defined {@link PowerModel}.
 *
 * @author Manoel Campos da Silva Filho
 * @author Anton Beloglazov
 * @since CloudSim Plus 1.4
 */
public class SimpleHostPowerSupply implements PowerSupply {
    private final Host host;
    private PowerModel powerModel;

    public SimpleHostPowerSupply(final Host host) {
        this(host, PowerModel.NULL);
    }

    public SimpleHostPowerSupply(final Host host, final PowerModel powerModel) {
        this.host = host;
        this.powerModel = powerModel;
    }

    @Override
    public double getPower() {
        return getPower(host.getUtilizationOfCpu());
    }

    @Override
    public double getPower(final double utilization) {
        try {
            return powerModel.getPower(utilization);
        } catch (IllegalArgumentException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public double getMaxPower() {
        return powerModel.getPower(1);
    }

    @Override
    public final Host setPowerModel(final PowerModel powerModel) {
        Objects.requireNonNull(powerModel);
        this.powerModel = powerModel;
        this.powerModel.setHost(host);
        return host;
    }

    @Override
    public PowerModel getPowerModel() {
        return powerModel;
    }

    @Override
    public double getEnergyLinearInterpolation(
            final double fromUtilization,
            final double toUtilization,
            final double time)
    {
        /*
        if (fromUtilization == 0) {
            return 0;
        }*/
        // fix the idle state
        if(!host.isActive()) return 0;
        final double fromPower = getPower(fromUtilization);
        final double toPower = getPower(toUtilization);
        return (fromPower + (toPower - fromPower) / 2) * time;
    }
}

