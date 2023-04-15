/*
 * This file is part of Baritone.
 *
 * Baritone is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Baritone is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with Baritone.  If not, see <https://www.gnu.org/licenses/>.
 */

package baritone.builder;

import baritone.api.utils.BetterBlockPos;
import baritone.builder.DependencyGraphScaffoldingOverlay.CollapsedDependencyGraph;
import baritone.builder.DependencyGraphScaffoldingOverlay.CollapsedDependencyGraph.CollapsedDependencyGraphComponent;
import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.LongList;
import it.unimi.dsi.fastutil.longs.LongSets;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Given a DependencyGraphScaffoldingOverlay, put in scaffolding blocks until the entire graph is navigable from the root.
 * <p>
 * In other words, add scaffolding blocks to the schematic until the entire thing can theoretically be built from one
 * starting point, just by placing blocks against blocks. So like, anything floating in the air will get a connector down to the
 * ground (or over to something that's eventually connected to the ground). After this is done, nothing will be left floating in
 * midair with no connection to the rest of the build.
 */
public class Scaffolder {

    private final IScaffolderStrategy strategy;
    private final DependencyGraphScaffoldingOverlay overlayGraph;
    // NOTE: these next three fields are updated in-place as the overlayGraph is updated :)
    private final CollapsedDependencyGraph collapsedGraph;
    private final Int2ObjectMap<CollapsedDependencyGraphComponent> components;
    private final Long2ObjectMap<CollapsedDependencyGraphComponent> componentLocations;

    private final List<CollapsedDependencyGraphComponent> rootComponents;

    private Scaffolder(PlaceOrderDependencyGraph graph, IScaffolderStrategy strategy) {
        this.strategy = strategy;
        this.overlayGraph = new DependencyGraphScaffoldingOverlay(graph);
        this.collapsedGraph = overlayGraph.getCollapsedGraph();
        this.components = collapsedGraph.getComponents();
        this.componentLocations = collapsedGraph.getComponentLocations();

        this.rootComponents = calcRoots();
    }

    public static Output run(PlaceOrderDependencyGraph graph, IScaffolderStrategy strategy) {
        Scaffolder scaffolder = new Scaffolder(graph, strategy);
        while (scaffolder.rootComponents.size() > 1) {
            scaffolder.loop();
        }
        return scaffolder.new Output();
    }

    private List<CollapsedDependencyGraphComponent> calcRoots() {
        // since the components form a DAG (because all strongly connected components, and therefore all cycles, have been collapsed)
        // we can locate all root components by simply finding the ones with no incoming edges
        return components.values().stream().filter(component -> component.getIncoming().isEmpty()).collect(Collectors.toCollection(ArrayList::new)); // ensure arraylist since we will be mutating the list
    }

    private void loop() {
        if (rootComponents.size() <= 1) {
            throw new IllegalStateException();
        }
        for (CollapsedDependencyGraphComponent root : rootComponents) {
            // don't remove from rootComponents yet since we aren't sure which way it'll merge (in theory, in practice it'll stop being a root when STRICT_Y is true, since it'll become a descendant, but in theory with STRICT_Y false it could merge on equal footing with another component)
            if (!root.getIncoming().isEmpty()) {
                throw new IllegalStateException();
            }
            LongList path = strategy.scaffoldTo(root, overlayGraph);
            if (path == null) {
                continue;
            }
            if (!root.getPositions().contains(path.get(path.size() - 1))) {
                throw new IllegalStateException();
            }
            if (root.getPositions().contains(path.get(0))) {
                throw new IllegalStateException();
            }
            internalEnable(path);
            return;
        }
        throw new IllegalStateException("unconnectable");
    }

    private void internalEnable(LongList path) {
        int cid = collapsedGraph.lastComponentID().getAsInt();
        applyScaffoldingConnection(overlayGraph, path);
        int newCID = collapsedGraph.lastComponentID().getAsInt();
        for (int i = cid + 1; i <= newCID; i++) {
            if (components.get(i) != null && components.get(i).getIncoming().isEmpty()) {
                rootComponents.add(components.get(i));
                System.out.println("Adding");
            }
        }
        // why is this valid?
        // for this to be valid, we need to be confident that no component from cid 0 to old lastcid could have had incomings become empty
        // consider the case root -> descendant
        // what if scaffolding created descendant -> root, then they were merged together, but descendant won?
        // then, descendant would have cid less than last cid, and it wouldn't be added to rootComponents by the previous line perhaps?
        // but, dijkstra strategy skips merging roots with their descendants intentionally since it's useless to do so
        rootComponents.removeIf(root -> {
            if (root.deleted()) {
                if (!rootComponents.contains(root.deletedIntoRecursive())) {
                    throw new IllegalStateException(); // sanity check the above - if this throws, i suspect it would mean that a root component was merged into one of its descendants by useless scaffolding
                    // if this ends up being unavoidable, then iterating over all deletedIntoRecursive of rootComponents should find all new rootComponents
                    // this is because all new scaffoldings have their own component, so the only way for an old component to have no incomings is if it was merged "the wrong way" with the root, which is easily locatable by deletedIntoRecursive
                }
                return true;
            }
            if (!root.getIncoming().isEmpty()) { // handle the actual root itself that we just connected (hopefully)
                return true;
            }
            return false;
        });

        /*rootComponents.clear();
        rootComponents.addAll(calcRoots());*/
        if (Main.DEBUG) {
            if (!new HashSet<>(rootComponents).equals(new HashSet<>(calcRoots()))) { // equal ignoring order
                // TODO rootComponents should be a Set instead of a List anyway
                System.out.println(rootComponents);
                System.out.println(calcRoots());
                throw new IllegalStateException();
            }
        }
    }

    public static void applyScaffoldingConnection(DependencyGraphScaffoldingOverlay overlayGraph, LongList path) {
        CollapsedDependencyGraph collapsedGraph = overlayGraph.getCollapsedGraph();
        Long2ObjectMap<CollapsedDependencyGraphComponent> componentLocations = collapsedGraph.getComponentLocations();
        if (!componentLocations.containsKey(path.getLong(0))) {
            throw new IllegalStateException();
        }
        if (!componentLocations.containsKey(path.getLong(path.size() - 1))) {
            throw new IllegalStateException();
        }
        if (componentLocations.get(path.getLong(0)) == componentLocations.get(path.getLong(path.size() - 1))) {
            throw new IllegalStateException();
        }
        if (!componentLocations.get(path.getLong(path.size() - 1)).getIncoming().isEmpty()) {
            throw new IllegalStateException();
        }
        // componentLocations.get(path.getLong(path.size() - 1)).getIncoming() can be either empty or nonempty
        for (int i = 1; i < path.size(); i++) {
            if (!overlayGraph.hypotheticalScaffoldingIncomingEdge(path.getLong(i), Face.between(path.getLong(i), path.getLong(i - 1)))) {
                throw new IllegalStateException();
            }
        }
        LongList positions = path.subList(1, path.size() - 1);
        positions.forEach(pos -> {
            if (componentLocations.containsKey(pos)) {
                throw new IllegalStateException();
            }
        });
        System.out.println("Enabling " + positions.stream().map(BetterBlockPos::fromLong).collect(Collectors.toList()));
        positions.forEach(overlayGraph::enable); // TODO more performant to enable in reverse order maybe?
    }

    public class Output {
        public void enableAncillaryScaffoldingAndRecomputeRoot(LongList positions) {
            throw new UnsupportedOperationException("mutable components after scaffolding is not worth it");
        }

        public CollapsedDependencyGraphComponent getRoot() { // TODO this should probably return a new class that is not mutable in-place
            if (rootComponents.size() != 1) {
                throw new IllegalStateException(); // this is okay because this can only possibly be called after Scaffolder.run is completed
            }
            CollapsedDependencyGraphComponent root = rootComponents.get(0);
            if (!root.getIncoming().isEmpty() || root.deleted()) {
                throw new IllegalStateException();
            }
            return root;
        }

        public boolean real(long pos) {
            return overlayGraph.real(pos);
        }

        public void forEachReal(Bounds.BoundsLongConsumer consumer) {
            overlayGraph.forEachReal(consumer);
        }

        public LongSets.UnmodifiableSet scaffolding() {
            return overlayGraph.scaffolding();
        }

        public boolean air(long pos) {
            return overlayGraph.air(pos);
        }

        DependencyGraphScaffoldingOverlay secretInternalForTesting() {
            return overlayGraph;
        }
    }
}