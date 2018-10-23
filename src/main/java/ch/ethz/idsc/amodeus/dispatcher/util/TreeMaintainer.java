/* amodeus - Copyright (c) 2018, ETH Zurich, Institute for Dynamic Systems and Control */
package ch.ethz.idsc.amodeus.dispatcher.util;

import java.util.Collection;
import java.util.HashSet;
import java.util.Set;
import java.util.function.Function;

import org.matsim.api.core.v01.Coord;
import org.matsim.core.utils.collections.QuadTree;
import org.matsim.core.utils.collections.QuadTree.Rect;

import ch.ethz.idsc.amodeus.dispatcher.core.RoboTaxi;
import ch.ethz.idsc.amodeus.util.math.GlobalAssert;
import ch.ethz.matsim.av.passenger.AVRequest;

/** Maintains a list of {@link T}s in a {@link Set} and a
 * {@link QuadTree} to enable both fast "contains" searches and
 * "closest" searches, T might be for instance {@link AVRequest}s
 * or {@link RoboTaxi}s
 * 
 * @author clruch */
public class TreeMaintainer<T> {

    private final QuadTree<T> tree;
    /** data structures are used to enable fast "contains" searching */
    private final Set<T> set = new HashSet<>();
    private final Function<T, Coord> location;

    /** For the Checks */
    private final Rect outerRect;

    public TreeMaintainer(double[] networkBounds, Function<T, Coord> location) {
        this.location = location;
        this.outerRect = new Rect(networkBounds[0], networkBounds[1], networkBounds[2], networkBounds[3]);
        tree = new QuadTree<>(networkBounds[0], networkBounds[1], networkBounds[2], networkBounds[3]);
    }

    public TreeMaintainer(double minX, double minY, double maxX, double maxY, Function<T, Coord> location) {
        this.location = location;
        this.outerRect = new Rect(minX, minY, maxX, maxY);
        tree = new QuadTree<>(minX, minY, maxX, maxY);
    }

    /** @return closest {@link T} in tree from {@link Coord} @param coord */
    public T getClosest(Coord coord) {
        return tree.getClosest(coord.getX(), coord.getY());
    }

    /** Adds the {@link T} @param t to the Tree Maintainer. */
    public void add(T t) {
        if (!set.contains(t)) {
            Coord coord = location.apply(t);
            boolean setok = set.add(t);
            boolean treeok = tree.put(coord.getX(), coord.getY(), t);
            GlobalAssert.that(setok && treeok);
        }
    }

    /** Removes the {@link T} @param t from the Tree Maintainer. */
    public void remove(T t) {
        Coord coord = location.apply(t);
        boolean setok = set.remove(t);
        boolean treeok = tree.remove(coord.getX(), coord.getY(), t);
        GlobalAssert.that(setok && treeok);
    }

    public boolean contains(T t) {
        return set.contains(t);
    }

    public int size() {
        GlobalAssert.that(tree.size() == set.size());
        return tree.size();
    }

    public Collection<T> inFrame(Rect bounds) {
        return tree.getRectangle(bounds, new HashSet<>());
    }

    public boolean contains(Coord coord) {
        return outerRect.contains(coord.getX(), coord.getY());
    }

    public Set<T> getValues() {
        return set;
    }

}
