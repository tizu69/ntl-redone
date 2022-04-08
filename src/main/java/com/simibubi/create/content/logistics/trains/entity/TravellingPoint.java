package com.simibubi.create.content.logistics.trains.entity;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map.Entry;
import java.util.Objects;
import java.util.Set;
import java.util.Vector;
import java.util.function.BiConsumer;
import java.util.function.BiFunction;
import java.util.function.BiPredicate;
import java.util.function.Consumer;

import javax.annotation.Nullable;

import com.simibubi.create.Create;
import com.simibubi.create.content.logistics.trains.GraphLocation;
import com.simibubi.create.content.logistics.trains.TrackEdge;
import com.simibubi.create.content.logistics.trains.TrackGraph;
import com.simibubi.create.content.logistics.trains.TrackNode;
import com.simibubi.create.content.logistics.trains.TrackNodeLocation;
import com.simibubi.create.content.logistics.trains.management.edgePoint.EdgeData;
import com.simibubi.create.content.logistics.trains.management.edgePoint.signal.TrackEdgePoint;
import com.simibubi.create.foundation.utility.Couple;
import com.simibubi.create.foundation.utility.Pair;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.Tag;
import net.minecraft.world.phys.Vec3;

public class TravellingPoint {

	public TrackNode node1, node2;
	public TrackEdge edge;
	public double position;
	public boolean blocked;

	public static enum SteerDirection {
		NONE(0), LEFT(-1), RIGHT(1);

		float targetDot;

		private SteerDirection(float targetDot) {
			this.targetDot = targetDot;
		}
	}

	public static interface ITrackSelector
		extends BiFunction<TrackGraph, Pair<Boolean, List<Entry<TrackNode, TrackEdge>>>, Entry<TrackNode, TrackEdge>> {
	};

	public static interface IEdgePointListener extends BiPredicate<Double, Pair<TrackEdgePoint, Couple<TrackNode>>> {
	};

	public static interface ITurnListener extends BiConsumer<Double, TrackEdge> {
	};

	public TravellingPoint() {}

	public TravellingPoint(TrackNode node1, TrackNode node2, TrackEdge edge, double position) {
		this.node1 = node1;
		this.node2 = node2;
		this.edge = edge;
		this.position = position;
	}

	public IEdgePointListener ignoreEdgePoints() {
		return (d, c) -> false;
	}

	public ITurnListener ignoreTurns() {
		return (d, c) -> {
		};
	}

	public ITrackSelector random() {
		return (graph, pair) -> pair.getSecond()
			.get(Create.RANDOM.nextInt(pair.getSecond()
				.size()));
	}

	public ITrackSelector follow(TravellingPoint other) {
		return follow(other, null);
	}

	public ITrackSelector follow(TravellingPoint other, @Nullable Consumer<Boolean> success) {
		return (graph, pair) -> {
			List<Entry<TrackNode, TrackEdge>> validTargets = pair.getSecond();
			boolean forward = pair.getFirst();
			TrackNode target = forward ? other.node1 : other.node2;
			TrackNode secondary = forward ? other.node2 : other.node1;

			for (Entry<TrackNode, TrackEdge> entry : validTargets)
				if (entry.getKey() == target || entry.getKey() == secondary) {
					if (success != null)
						success.accept(true);
					return entry;
				}

			Vector<List<Entry<TrackNode, TrackEdge>>> frontiers = new Vector<>(validTargets.size());
			Vector<Set<TrackEdge>> visiteds = new Vector<>(validTargets.size());

			for (int j = 0; j < validTargets.size(); j++) {
				ArrayList<Entry<TrackNode, TrackEdge>> e = new ArrayList<>();
				Entry<TrackNode, TrackEdge> entry = validTargets.get(j);
				e.add(entry);
				frontiers.add(e);
				HashSet<TrackEdge> e2 = new HashSet<>();
				e2.add(entry.getValue());
				visiteds.add(e2);
			}

			for (int i = 0; i < 20; i++) {
				for (int j = 0; j < validTargets.size(); j++) {
					Entry<TrackNode, TrackEdge> entry = validTargets.get(j);
					List<Entry<TrackNode, TrackEdge>> frontier = frontiers.get(j);
					if (frontier.isEmpty())
						continue;

					Entry<TrackNode, TrackEdge> currentEntry = frontier.remove(0);
					for (Entry<TrackNode, TrackEdge> nextEntry : graph.getConnectionsFrom(currentEntry.getKey())
						.entrySet()) {
						TrackEdge nextEdge = nextEntry.getValue();
						if (!visiteds.get(j)
							.add(nextEdge))
							continue;

						TrackNode nextNode = nextEntry.getKey();
						if (nextNode == target) {
							if (success != null)
								success.accept(true);
							return entry;
						}

						frontier.add(nextEntry);
					}
				}
			}

			if (success != null)
				success.accept(false);
			return validTargets.get(0);
		};
	}

	public ITrackSelector steer(SteerDirection direction, Vec3 upNormal) {
		return (graph, pair) -> {
			List<Entry<TrackNode, TrackEdge>> validTargets = pair.getSecond();
			double closest = Double.MAX_VALUE;
			Entry<TrackNode, TrackEdge> best = null;

			for (Entry<TrackNode, TrackEdge> entry : validTargets) {
				Vec3 trajectory = edge.getDirection(node1, node2, false);
				Vec3 entryTrajectory = entry.getValue()
					.getDirection(node2, entry.getKey(), true);
				Vec3 normal = trajectory.cross(upNormal);
				double dot = normal.dot(entryTrajectory);
				double diff = Math.abs(direction.targetDot - dot);
				if (diff > closest)
					continue;

				closest = diff;
				best = entry;
			}

			if (best == null) {
				Create.LOGGER.warn("Couldn't find steer target, choosing first");
				return validTargets.get(0);
			}

			return best;
		};
	}

	public double travel(TrackGraph graph, double distance, ITrackSelector trackSelector,
		IEdgePointListener signalListener, ITurnListener turnListener) {
		blocked = false;
		double edgeLength = edge.getLength(node1, node2);
		if (distance == 0)
			return 0;

		double prevPos = position;
		double traveled = distance;
		double currentT = position / edgeLength;
		double incrementT = edge.incrementT(node1, node2, currentT, distance);
		position = incrementT * edgeLength;

		// FIXME: using incrementT like this becomes inaccurate at medium-long distances
		// travelling points would travel only 50m instead of 100m due to the low
		// incrementT at their starting position (e.g. bezier turn)
		// In an ideal scenario the amount added to position would iterate the traversed
		// edges for context first
		
		// A workaround was added in TrackEdge::incrementT

		List<Entry<TrackNode, TrackEdge>> validTargets = new ArrayList<>();

		boolean forward = distance > 0;
		double collectedDistance = forward ? -prevPos : -edgeLength + prevPos;

		Double blockedLocation =
			edgeTraversedFrom(graph, forward, signalListener, turnListener, prevPos, collectedDistance);
		if (blockedLocation != null) {
			position = blockedLocation.doubleValue();
			traveled = position - prevPos;
			return traveled;
		}

		if (forward) {
			// Moving forward
			while (position > edgeLength) {
				validTargets.clear();

				for (Entry<TrackNode, TrackEdge> entry : graph.getConnectionsFrom(node2)
					.entrySet()) {
					TrackNode newNode = entry.getKey();
					if (newNode == node1)
						continue;

					TrackEdge newEdge = entry.getValue();
					Vec3 currentDirection = edge.getDirection(node1, node2, false);
					Vec3 newDirection = newEdge.getDirection(node2, newNode, true);
					if (currentDirection.dot(newDirection) < 3 / 4f)
						continue;

					validTargets.add(entry);
				}

				if (validTargets.isEmpty()) {
					traveled -= position - edgeLength;
					position = edgeLength;
					blocked = true;
					break;
				}

				Entry<TrackNode, TrackEdge> entry = validTargets.size() == 1 ? validTargets.get(0)
					: trackSelector.apply(graph, Pair.of(true, validTargets));

				node1 = node2;
				node2 = entry.getKey();
				edge = entry.getValue();
				position -= edgeLength;

				collectedDistance += edgeLength;
				if (edge.isTurn())
					turnListener.accept(collectedDistance, edge);

				blockedLocation = edgeTraversedFrom(graph, forward, signalListener, turnListener, 0, collectedDistance);

				if (blockedLocation != null) {
					traveled -= position;
					position = blockedLocation.doubleValue();
					traveled += position;
					break;
				}

				prevPos = 0;
				edgeLength = edge.getLength(node1, node2);
			}

		} else {
			// Moving backwards
			while (position < 0) {
				validTargets.clear();

				for (Entry<TrackNode, TrackEdge> entry : graph.getConnectionsFrom(node1)
					.entrySet()) {
					TrackNode newNode = entry.getKey();
					if (newNode == node2)
						continue;

					TrackEdge newEdge = graph.getConnectionsFrom(newNode)
						.get(node1);
					Vec3 currentDirection = edge.getDirection(node1, node2, true);
					Vec3 newDirection = newEdge.getDirection(newNode, node1, false);
					if (currentDirection.dot(newDirection) < 3 / 4f)
						continue;

					validTargets.add(entry);
				}

				if (validTargets.isEmpty()) {
					traveled += position;
					position = 0;
					blocked = true;
					break;
				}

				Entry<TrackNode, TrackEdge> entry = validTargets.size() == 1 ? validTargets.get(0)
					: trackSelector.apply(graph, Pair.of(false, validTargets));

				node2 = node1;
				node1 = entry.getKey();
				edge = graph.getConnectionsFrom(node1)
					.get(node2);
				collectedDistance += edgeLength;
				edgeLength = edge.getLength(node1, node2);
				position += edgeLength;

				blockedLocation =
					edgeTraversedFrom(graph, forward, signalListener, turnListener, edgeLength, collectedDistance);

				if (blockedLocation != null) {
					traveled -= position;
					position = blockedLocation.doubleValue();
					traveled += position;
					break;
				}
			}

		}

		return traveled;
	}

	private Double edgeTraversedFrom(TrackGraph graph, boolean forward, IEdgePointListener edgePointListener,
		ITurnListener turnListener, double prevPos, double totalDistance) {
		if (edge.isTurn())
			turnListener.accept(Math.max(0, totalDistance), edge);

		EdgeData edgeData = edge.getEdgeData();
		double from = forward ? prevPos : position;
		double to = forward ? position : prevPos;
		List<TrackEdgePoint> edgePoints = edgeData.getPoints();

		double length = edge.getLength(node1, node2);
		for (int i = 0; i < edgePoints.size(); i++) {
			int index = forward ? i : edgePoints.size() - i - 1;
			TrackEdgePoint nextBoundary = edgePoints.get(index);
			double locationOn = nextBoundary.getLocationOn(node1, node2, edge);
			double distance = forward ? locationOn : length - locationOn;
			if (forward ? (locationOn < from || locationOn >= to) : (locationOn <= from || locationOn > to))
				continue;
			Couple<TrackNode> nodes = Couple.create(node1, node2);
			if (edgePointListener.test(totalDistance + distance, Pair.of(nextBoundary, forward ? nodes : nodes.swap())))
				return locationOn;
		}

		return null;
	}

	public void reverse(TrackGraph graph) {
		TrackNode n = node1;
		node1 = node2;
		node2 = n;
		position = edge.getLength(node1, node2) - position;
		edge = graph.getConnectionsFrom(node1)
			.get(node2);
	}

	public Vec3 getPosition() {
		double t = position / edge.getLength(node1, node2);
		return edge.getPosition(node1, node2, t)
			.add(edge.getNormal(node1, node2, t)
				.scale(1));
	}

	public void migrateTo(List<GraphLocation> locations) {
		GraphLocation location = locations.remove(0);
		TrackGraph graph = location.graph;
		node1 = graph.locateNode(location.edge.getFirst());
		node2 = graph.locateNode(location.edge.getSecond());
		position = location.position;
		edge = graph.getConnectionsFrom(node1)
			.get(node2);
	}

	public CompoundTag write() {
		CompoundTag tag = new CompoundTag();
		Couple<TrackNode> nodes = Couple.create(node1, node2);
		if (nodes.either(Objects::isNull))
			return tag;
		tag.put("Nodes", nodes.map(TrackNode::getLocation)
			.map(BlockPos::new)
			.serializeEach(NbtUtils::writeBlockPos));
		tag.putDouble("Position", position);
		return tag;
	}

	public static TravellingPoint read(CompoundTag tag, TrackGraph graph) {
		if (graph == null)
			return new TravellingPoint(null, null, null, 0);

		Couple<TrackNode> locs =
			tag.contains("Nodes")
				? Couple.deserializeEach(tag.getList("Nodes", Tag.TAG_COMPOUND), NbtUtils::readBlockPos)
					.map(TrackNodeLocation::fromPackedPos)
					.map(graph::locateNode)
				: Couple.create(null, null);

		if (locs.either(Objects::isNull))
			return new TravellingPoint(null, null, null, 0);

		double position = tag.getDouble("Position");
		return new TravellingPoint(locs.getFirst(), locs.getSecond(), graph.getConnectionsFrom(locs.getFirst())
			.get(locs.getSecond()), position);
	}

}