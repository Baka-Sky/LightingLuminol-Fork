package net.minecraft.world.level.pathfinder;

import com.google.common.collect.Lists;
import com.google.common.collect.Sets;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.function.Function;
import java.util.stream.Collectors;
import net.minecraft.core.BlockPos;
import net.minecraft.util.profiling.Profiler;
import net.minecraft.util.profiling.ProfilerFiller;
import net.minecraft.util.profiling.metrics.MetricCategory;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.PathNavigationRegion;
import org.jspecify.annotations.Nullable;

public class PathFinder {
    private static final float FUDGING = 1.5F;
    private final Node[] neighbors = new Node[32];
    private int maxVisitedNodes;
    public final NodeEvaluator nodeEvaluator;
    private final BinaryHeap openSet = new BinaryHeap();
    private BooleanSupplier captureDebug = () -> false;

    public PathFinder(final NodeEvaluator nodeEvaluator, final int maxVisitedNodes) {
        this.nodeEvaluator = nodeEvaluator;
        this.maxVisitedNodes = maxVisitedNodes;
    }

    public void setCaptureDebug(final BooleanSupplier captureDebug) {
        this.captureDebug = captureDebug;
    }

    public void setMaxVisitedNodes(final int maxVisitedNodes) {
        this.maxVisitedNodes = maxVisitedNodes;
    }

    public @Nullable Path findPath(
        final PathNavigationRegion level,
        final Mob entity,
        final Set<BlockPos> targets,
        final float maxPathLength,
        final int reachRange,
        final float maxVisitedNodesMultiplier
    ) {
        this.openSet.clear();
        this.nodeEvaluator.prepare(level, entity);
        Node from = this.nodeEvaluator.getStart();
        if (from == null) {
            return null;
        }

        // Paper start - Perf: remove streams and optimize collection
        List<Map.Entry<Target, BlockPos>> tos = Lists.newArrayList();
        for (BlockPos pos : targets) {
            tos.add(new java.util.AbstractMap.SimpleEntry<>(this.nodeEvaluator.getTarget(pos.getX(), pos.getY(), pos.getZ()), pos));
        }
        // Paper end - Perf: remove streams and optimize collection
        Path path = this.findPath(from, tos, maxPathLength, reachRange, maxVisitedNodesMultiplier);
        this.nodeEvaluator.done();
        return path;
    }

    private @Nullable Path findPath(
        final Node from, final List<Map.Entry<Target, BlockPos>> targets, final float maxPathLength, final int reachRange, final float maxVisitedNodesMultiplier // Paper - optimize collection
    ) {
        ProfilerFiller profiler = Profiler.get();
        profiler.push("find_path");
        profiler.markForCharting(MetricCategory.PATH_FINDING);
        //Set<Target> targets = targetMap.keySet(); // Paper - unused
        from.g = 0.0F;
        from.h = this.getBestH(from, targets);
        from.f = from.h;
        this.openSet.clear();
        this.openSet.insert(from);
        boolean captureDebug = this.captureDebug.getAsBoolean();
        Set<Node> closedSet = captureDebug ? new HashSet<>() : Set.of();
        int count = 0;
        List<Map.Entry<Target, BlockPos>> reachedTargets = Lists.newArrayListWithExpectedSize(targets.size()); // Paper - optimize collection
        int maxVisitedNodesAdjusted = (int)(this.maxVisitedNodes * maxVisitedNodesMultiplier);

        while (!this.openSet.isEmpty()) {
            if (++count >= maxVisitedNodesAdjusted) {
                break;
            }

            Node current = this.openSet.pop();
            current.closed = true;

            // Paper start - Perf: remove streams and optimize collection
            for (int positionIndex = 0, size = targets.size(); positionIndex < size; positionIndex++) {
                final Map.Entry<Target, BlockPos> entry = targets.get(positionIndex);
                Target target = entry.getKey();
                if (current.distanceManhattan(target) <= reachRange) {
                    target.setReached();
                    reachedTargets.add(entry);
                    // Paper end - Perf: remove streams and optimize collection
                }
            }

            if (!reachedTargets.isEmpty()) {
                break;
            }

            if (captureDebug) {
                closedSet.add(current);
            }

            if (!(current.distanceTo(from) >= maxPathLength)) {
                int neighborCount = this.nodeEvaluator.getNeighbors(this.neighbors, current);

                for (int i = 0; i < neighborCount; i++) {
                    Node neighbor = this.neighbors[i];
                    float distance = this.distance(current, neighbor);
                    neighbor.walkedDistance = current.walkedDistance + distance;
                    float tentativeGScore = current.g + distance + neighbor.costMalus;
                    if (neighbor.walkedDistance < maxPathLength && (!neighbor.inOpenSet() || tentativeGScore < neighbor.g)) {
                        neighbor.cameFrom = current;
                        neighbor.g = tentativeGScore;
                        neighbor.h = this.getBestH(neighbor, targets) * 1.5F;
                        if (neighbor.inOpenSet()) {
                            this.openSet.changeCost(neighbor, neighbor.g + neighbor.h);
                        } else {
                            neighbor.f = neighbor.g + neighbor.h;
                            this.openSet.insert(neighbor);
                        }
                    }
                }
            }
        }

        // Paper start - Perf: remove streams and optimize collection
        Path best = null;
        boolean entryListIsEmpty = reachedTargets.isEmpty();
        Comparator<Path> comparator = entryListIsEmpty
            ? Comparator.comparingInt(Path::getNodeCount)
            : Comparator.comparingDouble(Path::getDistToTarget).thenComparingInt(Path::getNodeCount);
        for (Map.Entry<Target, BlockPos> entry : entryListIsEmpty ? targets : reachedTargets) {
            Path path = this.reconstructPath(entry.getKey().getBestNode(), entry.getValue(), !entryListIsEmpty);
            if (best == null || comparator.compare(path, best) < 0) {
                best = path;
            }
        }
        profiler.pop();
        if (captureDebug && best != null) {
            Set<Target> set = Sets.newHashSet();
            for(Map.Entry<Target, BlockPos> entry : targets) {
                set.add(entry.getKey());
            }
            best.setDebug(this.openSet.getHeap(), closedSet.toArray(Node[]::new), set);
        }
        return best;
        // Paper end - Perf: remove streams and optimize collection
    }

    protected float distance(final Node from, final Node to) {
        return from.distanceTo(to);
    }

    private float getBestH(final Node from, final List<Map.Entry<Target, BlockPos>> targets) { // Paper - Perf: remove streams and optimize collection; Set<Target> -> List<Map.Entry<Target, BlockPos>>
        float bestH = Float.MAX_VALUE;

        // Paper start - Perf: remove streams and optimize collection
        for (int i = 0, targetsSize = targets.size(); i < targetsSize; i++) {
            final Target target = targets.get(i).getKey();
            // Paper end - Perf: remove streams and optimize collection
            float h = from.distanceTo(target);
            target.updateBest(h, from);
            bestH = Math.min(h, bestH);
        }

        return bestH;
    }

    private Path reconstructPath(final Node closest, final BlockPos target, final boolean reached) {
        List<Node> nodes = Lists.newArrayList();
        Node node = closest;
        nodes.add(0, node);

        while (node.cameFrom != null) {
            node = node.cameFrom;
            nodes.add(0, node);
        }

        return new Path(nodes, target, reached);
    }
}
