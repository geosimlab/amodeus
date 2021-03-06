/* amodeus - Copyright (c) 2018, ETH Zurich, Institute for Dynamic Systems and Control */
package amodeus.amodeus.linkspeed;

import org.matsim.api.core.v01.network.Link;

public interface TaxiTrafficData {

    /** @return speed in [m/s] on {@link Link} @param link
     *         at time @param now (must return freespeed if no recordings present) */
    public double getTrafficSpeed(Link link, double now);

    /** @return {@link LinkSpeedDataContainer} loaded. */
    public LinkSpeedDataContainer getLSData();

}
