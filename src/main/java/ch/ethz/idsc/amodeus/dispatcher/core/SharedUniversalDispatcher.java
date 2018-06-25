package ch.ethz.idsc.amodeus.dispatcher.core;

import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.network.Link;
import org.matsim.contrib.dvrp.data.Vehicle;
import org.matsim.contrib.dvrp.schedule.Schedule;
import org.matsim.contrib.dvrp.schedule.Schedules;
import org.matsim.contrib.dvrp.schedule.Task;
import org.matsim.core.api.experimental.events.EventsManager;
import org.matsim.core.config.Config;
import org.matsim.core.router.util.TravelTime;

import ch.ethz.idsc.amodeus.matsim.SafeConfig;
import ch.ethz.idsc.amodeus.net.SimulationDistribution;
import ch.ethz.idsc.amodeus.net.SimulationObject;
import ch.ethz.idsc.amodeus.net.SimulationObjectCompiler;
import ch.ethz.idsc.amodeus.net.SimulationObjects;
import ch.ethz.idsc.amodeus.net.StorageUtils;
import ch.ethz.idsc.amodeus.util.math.GlobalAssert;
import ch.ethz.matsim.av.config.AVDispatcherConfig;
import ch.ethz.matsim.av.dispatcher.AVDispatcher;
import ch.ethz.matsim.av.passenger.AVRequest;
import ch.ethz.matsim.av.plcpc.ParallelLeastCostPathCalculator;
import ch.ethz.matsim.av.schedule.AVDriveTask;
import ch.ethz.matsim.av.schedule.AVStayTask;

/** purpose of {@link UniversalDispatcher} is to collect and manage {@link AVRequest}s alternative
 * implementation of {@link AVDispatcher}; supersedes
 * {@link AbstractDispatcher}. */
/* added new requestRegister which does hold information from request to dropoff of each request
 * which is written to the simulationObject
 * implementation is not very clean yet but functionally stable, depending on the publishPeriod, andya jan '18 */
public abstract class SharedUniversalDispatcher extends SharedRoboTaxiMaintainer {

    private final FuturePathFactory futurePathFactory;
    private final Set<AVRequest> pendingRequests = new LinkedHashSet<>();
    private final Map<List<AVRequest>, SharedRoboTaxi> pickupRegister = new HashMap<>(); // new RequestRegister
    private final Map<List<AVRequest>, SharedRoboTaxi> requestRegister = new HashMap<>();
    private final Map<AVRequest, SharedRoboTaxi> periodFulfilledRequests = new HashMap<>(); // new temporaryRequestRegister for fulfilled requests
    private final Map<Id<Vehicle>, RoboTaxiStatus> oldRoboTaxis = new HashMap<>();
    private final double pickupDurationPerStop;
    private final double dropoffDurationPerStop;
    protected int publishPeriod; // not final, so that dispatchers can disable, or manipulate
    private int total_matchedRequests = 0;

    protected SharedUniversalDispatcher( //
            Config config, //
            AVDispatcherConfig avDispatcherConfig, //
            TravelTime travelTime, //
            ParallelLeastCostPathCalculator parallelLeastCostPathCalculator, //
            EventsManager eventsManager //
    ) {
        super(eventsManager, config, avDispatcherConfig);
        futurePathFactory = new FuturePathFactory(parallelLeastCostPathCalculator, travelTime);
        pickupDurationPerStop = avDispatcherConfig.getParent().getTimingParameters().getPickupDurationPerStop();
        dropoffDurationPerStop = avDispatcherConfig.getParent().getTimingParameters().getDropoffDurationPerStop();
        SafeConfig safeConfig = SafeConfig.wrap(avDispatcherConfig);
        publishPeriod = safeConfig.getInteger("publishPeriod", 10);
    }

    // ===================================================================================
    // Methods to use EXTERNALLY in derived dispatchers

    /** @return {@Collection} of all {@AVRequests} which are currently open. Requests are removed from list in setAcceptRequest function. */
    protected synchronized final Collection<AVRequest> getAVRequests() {
        return Collections.unmodifiableCollection(pendingRequests);
    }

    /** @return AVRequests which are currently not assigned to a vehicle */
    protected synchronized final List<AVRequest> getUnassignedAVRequests() {
        return pendingRequests.stream() //
                .filter(r -> !pickupRegister.containsKey(r)) //
                .collect(Collectors.toList());
    }

    /** Example call: getRoboTaxiSubset(AVStatus.STAY, AVStatus.DRIVEWITHCUSTOMER)
     * 
     * @param status {@AVStatus} of desiredsRoboTaxis, e.g., STAY,DRIVETOCUSTOMER,...
     * @return list ofsRoboTaxis which are in {@AVStatus} status */
    public final List<SharedRoboTaxi> getRoboTaxiSubset(RoboTaxiStatus... status) {
        return getRoboTaxiSubset(EnumSet.copyOf(Arrays.asList(status)));
    }

    private List<SharedRoboTaxi> getRoboTaxiSubset(Set<RoboTaxiStatus> status) {
        return getRoboTaxis().stream().filter(rt -> status.contains(rt.getStatus())).collect(Collectors.toList());
    }

    /** @return divertablesRoboTaxis which currently not on a pickup drive */
    protected final Collection<SharedRoboTaxi> getDivertableUnassignedRoboTaxis() {
        Collection<SharedRoboTaxi> divertableUnassignedRoboTaxis = getDivertableRoboTaxis().stream() //
                .filter(rt -> !pickupRegister.containsValue(rt)) //
                .collect(Collectors.toList());
        GlobalAssert.that(!divertableUnassignedRoboTaxis.stream().anyMatch(pickupRegister::containsValue));
        GlobalAssert.that(divertableUnassignedRoboTaxis.stream().allMatch(SharedRoboTaxi::isWithoutCustomer));
        return divertableUnassignedRoboTaxis;
    }

    /** @return {@Collection} of {@RoboTaxi} which can be redirected during iteration */
    protected final Collection<SharedRoboTaxi> getDivertableRoboTaxis() {
        return getRoboTaxis().stream() //
                .filter(SharedRoboTaxi::isWithoutDirective) //
                .filter(SharedRoboTaxi::isWithoutCustomer) //
                .filter(SharedRoboTaxi::notDrivingOnLastLink) //
                .collect(Collectors.toList());
    }

    /** @return immutable and inverted copy of pickupRegister, displays which vehicles are currently scheduled to pickup which request */
    protected final Map<SharedRoboTaxi, List<AVRequest>> getPickupRoboTaxis() {
        Map<SharedRoboTaxi, List<AVRequest>> pickupPairs = pickupRegister.entrySet().stream()//
                .collect(Collectors.toMap(Map.Entry::getValue, Map.Entry::getKey));
        GlobalAssert.that(pickupPairs.keySet().stream().allMatch(rt -> rt.getStatus().equals(RoboTaxiStatus.DRIVETOCUSTOMER)));
        return pickupPairs;
    }

    public void setNewSharedRoboTaxiPickup(SharedRoboTaxi sRoboTaxi, AVRequest avRequest, int pickupIndex) {
        GlobalAssert.that(sRoboTaxi.canPickupNewCustomer());
        GlobalAssert.that(pendingRequests.contains(avRequest));

        // 1) enter information into pickup table
        // 2) also do everything for the full-time requestRegister
        AVRequest avRforPickup = null;
        if (!pickupRegister.containsValue(sRoboTaxi)) { // sRoboTaxi was not picking up
            List<AVRequest> avRList = Arrays.asList(avRequest);
            pickupRegister.put(avRList, sRoboTaxi);
            requestRegister.put(avRList, sRoboTaxi);
            avRforPickup = avRequest;
        } else {
            List<AVRequest> listToAugment = pickupRegister.entrySet().stream()//
                    .filter(e -> e.getValue().equals(sRoboTaxi)).findAny().get().getKey();
            pickupRegister.remove(listToAugment); // remove List of AVRequest/RoboTaxi pair served before bysRoboTaxi
            pickupRegister.remove(listToAugment); // remove List of AVRequest/RoboTaxi pair served before bysRoboTaxi

            // Augment list
            // TODO order!!!!!
            GlobalAssert.that(pickupIndex >= 0 && pickupIndex <= listToAugment.size());
            listToAugment.add(pickupIndex, avRequest);
            pickupRegister.put(listToAugment, sRoboTaxi); // add new pair
            requestRegister.put(listToAugment, sRoboTaxi);
            avRforPickup = listToAugment.get(0);
        }
        GlobalAssert.that(pickupRegister.size() == pickupRegister.values().stream().distinct().count());

        // 2) set vehicle diversion

        setRoboTaxiDiversion(sRoboTaxi, avRforPickup.getFromLink(), RoboTaxiStatus.DRIVETOCUSTOMER);
    }

    /** Diverts {@link SharedRoboTaxi} to Link if {@link AVRequest} and adds pair to pickupRegister. If the {@roboTaxi} was scheduled to pickup another
     * {@AVRequest}, then
     * this
     * pair is silently revmoved from the pickup register which is a bijection of {@RoboTaxi} and open {@AVRequest}.
     * 
     * @paramsRoboTaxi
     * @param avRequest */
    public void setNewSingleRoboTaxiPickup(SharedRoboTaxi sRoboTaxi, AVRequest avRequest) {
        GlobalAssert.that(sRoboTaxi.canPickupNewCustomer());
        GlobalAssert.that(pendingRequests.contains(avRequest));

        List<AVRequest> avRList = Arrays.asList(avRequest);
        // 1) enter information into pickup table
        // 2) also do everything for the full-time requestRegister
        if (!pickupRegister.containsValue(sRoboTaxi)) { // sRoboTaxi was not picking up
            pickupRegister.put(avRList, sRoboTaxi);
            requestRegister.put(avRList, sRoboTaxi);
        } else {
            List<AVRequest> listToRemove = pickupRegister.entrySet().stream()//
                    .filter(e -> e.getValue().equals(sRoboTaxi)).findAny().get().getKey();
            pickupRegister.remove(listToRemove); // remove AVRequest/RoboTaxi pair served before bysRoboTaxi
            pickupRegister.remove(avRList); // remove AVRequest/RoboTaxi pair corresponding to avRequest
            requestRegister.remove(listToRemove);
            requestRegister.remove(avRList);
            pickupRegister.put(avRList, sRoboTaxi); // add new pair
            requestRegister.put(avRList, sRoboTaxi);
        }
        GlobalAssert.that(pickupRegister.size() == pickupRegister.values().stream().distinct().count());

        // 2) set vehicle diversion
        setRoboTaxiDiversion(sRoboTaxi, avRequest.getFromLink(), RoboTaxiStatus.DRIVETOCUSTOMER);
    }

    // ===================================================================================
    // INTERNAL Methods, do not call from derived dispatchers.

    /** For UniversalDispatcher, VehicleMaintainer internal use only. Use {@link UniveralDispatcher.setRoboTaxiPickup} or
     * {@link setRoboTaxiRebalance} from dispatchers. Assigns new destination to vehicle, if vehicle is already located at destination, nothing
     * happens. In one pass of {@redispatch(...)} in {@VehicleMaintainer}, the function setVehicleDiversion(...) may only be invoked
     * once for a single {@RoboTaxi} vehicle
     *
     * @paramsRoboTaxi {@link SharedRoboTaxi} supplied with a getFunction,e.g., {@link this.getDivertableRoboTaxis}
     * @param destination {@link Link} the {@link SharedRoboTaxi} should be diverted to
     * @param avstatus {@link} the {@link AVStatus} the {@link SharedRoboTaxi} has after the diversion, depends if used from {@link setRoboTaxiPickup} or
     *            {@link setRoboTaxiRebalance} */
    final void setRoboTaxiDiversion(SharedRoboTaxi sRoboTaxi, Link destination, RoboTaxiStatus avstatus) {
        // updated status ofsRoboTaxi
        GlobalAssert.that(sRoboTaxi.canPickupNewCustomer()); // FIXME
        GlobalAssert.that(sRoboTaxi.isWithoutDirective()); // FIXME

       sRoboTaxi.setStatus(avstatus);

        // udpate schedule ofsRoboTaxi
        final Schedule schedule = sRoboTaxi.getSchedule();
        Task task = schedule.getCurrentTask(); // <- implies that task is started
        new RoboTaxiTaskAdapter(task) {

    @Override
    public void handle(AVDriveTask avDriveTask) {
        if (!avDriveTask.getPath().getToLink().equals(destination)) { // ignore when vehicle is already going there
            FuturePathContainer futurePathContainer = futurePathFactory.createFuturePathContainer( //
                    sRoboTaxi.getDivertableLocation(), destination, sRoboTaxi.getDivertableTime());
            sRoboTaxi.assignDirective(new DriveVehicleDiversionDirective(sRobotaxi, destination, futurePathContainer));
        } else
            sRoboTaxi.assignDirective(EmptyDirective.INSTANCE);
    }

    @Override
    public void handle(AVStayTask avStayTask) {
        if (!avStayTask.getLink().equals(destination)) { // ignore request where location == target
            FuturePathContainer futurePathContainer = futurePathFactory.createFuturePathContainer( //
                    sRoboTaxi.getDivertableLocation(), destination, sRoboTaxi.getDivertableTime());
            sRoboTaxi.assignDirective(new StayVehicleDiversionDirective(robotaxi, destination, futurePathContainer));
        } else
            sRoboTaxi.assignDirective(EmptyDirective.INSTANCE);
    }

    };}

    /** Function called from {@link UniversalDispatcher.executePickups} if asRoboTaxi scheduled for pickup has reached the
     * from link of the {@link AVRequest}.
     * 
     * @paramsRoboTaxi
     * @param avRequest */
    private synchronized final void setAcceptRequest(SharedRoboTaxi sRoboTaxi, AVRequest avRequest) {
       sRoboTaxi.setStatus(RoboTaxiStatus.DRIVEWITHCUSTOMER);
       sRoboTaxi.setCurrentDriveDestination(avRequest.getFromLink()); // TODO toLink
        {
            boolean statusPen = pendingRequests.remove(avRequest);
            GlobalAssert.that(statusPen);
        }
        {
            SharedRoboTaxi former = pickupRegister.remove(avRequest);
            GlobalAssert.that(roboTaxi == former);
        }

        consistencySubCheck();

        final Schedule schedule =sRoboTaxi.getSchedule();
        // check that current task is last task in schedule
        GlobalAssert.that(schedule.getCurrentTask() == Schedules.getLastTask(schedule));

        final double endPickupTime = getTimeNow() + pickupDurationPerStop;
        FuturePathContainer futurePathContainer = futurePathFactory.createFuturePathContainer(avRequest.getFromLink(), avRequest.getToLink(), endPickupTime);

       sRoboTaxi.assignDirective(new AcceptRequestDirective(roboTaxi, avRequest, futurePathContainer, getTimeNow(), dropoffDurationPerStop));

        ++total_matchedRequests;
    }

    /** Function called from {@link UniversalDispatcher.executeDropoffs} if asRoboTaxi scheduled for dropoff has reached the
     * from link of the {@link AVRequest}.
     * 
     * @paramsRoboTaxi
     * @param avRequest */
    private synchronized final void setPassengerDropoff(SharedRoboTaxisRoboTaxi, AVRequest avRequest) {
        SharedRoboTaxi former = requestRegister.remove(avRequest);
        GlobalAssert.that(roboTaxi == former);

        // save avRequests which are matched for one publishPeriod to ensure no requests are lost in the recording.
        periodFulfilledRequests.put(avRequest,sRoboTaxi);

        final Schedule schedule =sRoboTaxi.getSchedule();
        // check that current task is last task in schedule
        GlobalAssert.that(schedule.getCurrentTask() == Schedules.getLastTask(schedule));
    }

    @Override
    /* package */ final boolean isInPickupRegister(SharedRoboTaxisRoboTaxi) {
        return pickupRegister.containsValue(robotaxi);
    }

    /** @param avRequest
     * @returnsRoboTaxi assigned to given avRequest, or empty if no taxi is assigned to avRequest
     *         Used by BipartiteMatching in euclideanNonCyclic, there a comparison to the old av assignment is needed */
    public final Optional<SharedRoboTaxi> getPickupTaxi(AVRequest avRequest) {
        return Optional.ofNullable(pickupRegister.get(avRequest));
    }

    /** complete all matchings if a {@link SharedRoboTaxi} has arrived at the fromLink of an {@link AVRequest} */
    @Override
    void executePickups() {
        Map<AVRequest, SharedRoboTaxi> pickupRegisterCopy = new HashMap<>(pickupRegister);
        for (Entry<AVRequest, SharedRoboTaxi> entry : pickupRegisterCopy.entrySet()) {
            AVRequest avRequest = entry.getKey();
            GlobalAssert.that(pendingRequests.contains(avRequest));
            SharedRoboTaxi pickupVehicle = entry.getValue();
            Link pickupVehicleLink = pickupVehicle.getDivertableLocation();
            boolean isOk = pickupVehicle.getSchedule().getCurrentTask() == Schedules.getLastTask(pickupVehicle.getSchedule());
            if (avRequest.getFromLink().equals(pickupVehicleLink) && isOk) {
                setAcceptRequest(pickupVehicle, avRequest);
            }
        }
    }

    /** complete all matchings if a {@link SharedRoboTaxi} has arrived at the toLink of an {@link AVRequest} */
    @Override
    void executeDropoffs() {
        Map<AVRequest, SharedRoboTaxi> requestRegisterCopy = new HashMap<>(requestRegister);
        for (Entry<AVRequest, SharedRoboTaxi> entry : requestRegisterCopy.entrySet()) {
            if (Objects.nonNull(entry.getValue())) {
                AVRequest avRequest = entry.getKey();
                SharedRoboTaxi dropoffVehicle = entry.getValue();
                Link dropoffVehicleLink = dropoffVehicle.getDivertableLocation();
                boolean isOk = dropoffVehicle.getSchedule().getCurrentTask() == Schedules.getLastTask(dropoffVehicle.getSchedule());
                if (avRequest.getToLink().equals(dropoffVehicleLink) && isOk) {
                    setPassengerDropoff(dropoffVehicle, avRequest);
                }
            }
        }
    }

    /** called when a new request enters the system, adds request to {@link pendingRequests}, needs to be public because called from
     * other not derived MATSim functions which are located in another package */
    @Override
    public final void onRequestSubmitted(AVRequest request) {
        boolean added = pendingRequests.add(request); // <- store request
        requestRegister.put(request, null);
        GlobalAssert.that(added);
    }

    /** function stops {@link SharedRoboTaxi} which are still heading towards an {@link AVRequest} but another {@link SharedRoboTaxi} was scheduled to pickup this
     * {@link AVRequest} in the meantime */
    @Override
    /* package */ final void stopAbortedPickupRoboTaxis() {

        // stop vehicles still driving to a request but other taxi serving that request already
        getRoboTaxis().stream()//
                .filter(rt -> rt.getStatus().equals(RoboTaxiStatus.DRIVETOCUSTOMER))//
                .filter(rt -> !pickupRegister.containsValue(rt))//
                .filter(SharedRoboTaxi::canPickupNewCustomer)//
                .filter(SharedRoboTaxi::isWithoutDirective)//
                .forEach(rt -> setRoboTaxiDiversion(rt, rt.getDivertableLocation(), RoboTaxiStatus.REBALANCEDRIVE));
        GlobalAssert.that(pickupRegister.size() <= pendingRequests.size());
    }

    /** Consistency checks to be called by {@linksRoboTaxiMaintainer.consistencyCheck} in each iteration. */
    @Override
    protected final void consistencySubCheck() {
        // TODO checked
        // there cannot be more pickup vehicles than open requests
        GlobalAssert.that(pickupRegister.size() <= pendingRequests.size());

        // pickupRegister needs to be a subset of requestRegister
        pickupRegister.keySet().forEach(a -> GlobalAssert.that(requestRegister.containsKey(a)));

        // containment check pickupRegister and pendingRequests
        pickupRegister.keySet().forEach(r -> GlobalAssert.that(pendingRequests.contains(r)));

        // ensure no RoboTaxi is scheduled to pickup two requests
        GlobalAssert.that(pickupRegister.size() == pickupRegister.values().stream().distinct().count());

    }

    /** save simulation data into {@link SimulationObject} for later analysis and visualization. */
    @Override
    protected final void notifySimulationSubscribers(long round_now, StorageUtils storageUtils) {
        if (publishPeriod > 0 && round_now % publishPeriod == 0) {
            SimulationObjectCompiler simulationObjectCompiler = SimulationObjectCompiler.create( //
                    round_now, getInfoLine(), total_matchedRequests);

            Map<AVRequest, SharedRoboTaxi> newRegister = requestRegister;
            List<SharedRoboTaxi> newRoboTaxis = getRoboTaxis();

            simulationObjectCompiler.insertFulfilledRequests(periodFulfilledRequests);
            simulationObjectCompiler.insertRequests(newRegister, oldRoboTaxis);

            simulationObjectCompiler.insertVehicles(newRoboTaxis);
            SimulationObject simulationObject = simulationObjectCompiler.compile();

            // in the first pass, the vehicles is typically empty
            // in that case, the simObj will not be stored or communicated
            if (SimulationObjects.hasVehicles(simulationObject)) {
                // store simObj and distribute to clients
                SimulationDistribution.of(simulationObject, storageUtils);
            }

            oldRoboTaxis.clear();
            newRoboTaxis.forEach(r -> oldRoboTaxis.put(r.getId(), r.getStatus()));

            periodFulfilledRequests.clear();
        }
    }

    /** adds information to InfoLine */
    @Override
    protected String getInfoLine() {
        return String.format("%s R=(%5d) MR=%6d", //
                super.getInfoLine(), //
                getAVRequests().size(), //
                total_matchedRequests);
    }

}
