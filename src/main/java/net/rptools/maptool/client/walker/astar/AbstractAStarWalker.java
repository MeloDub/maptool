/*
 * This software Copyright by the RPTools.net development team, and
 * licensed under the Affero GPL Version 3 or, at your option, any later
 * version.
 *
 * MapTool Source Code is distributed in the hope that it will be
 * useful, but WITHOUT ANY WARRANTY; without even the implied warranty
 * of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 *
 * You should have received a copy of the GNU Affero General Public
 * License * along with this source Code.  If not, please visit
 * <http://www.gnu.org/licenses/> and specifically the Affero license
 * text at <http://www.gnu.org/licenses/agpl.html>.
 */
package net.rptools.maptool.client.walker.astar;

import java.awt.Color;
import java.awt.EventQueue;
import java.awt.Rectangle;
import java.awt.geom.Area;
import java.text.DecimalFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import net.rptools.maptool.client.MapTool;
import net.rptools.maptool.client.walker.AbstractZoneWalker;
import net.rptools.maptool.model.CellPoint;
import net.rptools.maptool.model.Label;
import net.rptools.maptool.model.Token;
import net.rptools.maptool.model.TokenFootprint;
import net.rptools.maptool.model.Zone;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.locationtech.jts.awt.ShapeReader;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.LineString;
import org.locationtech.jts.geom.prep.PreparedGeometry;
import org.locationtech.jts.geom.prep.PreparedGeometryFactory;

public abstract class AbstractAStarWalker extends AbstractZoneWalker {
  private record TerrainModifier(Token.TerrainModifierOperation operation, double value) {}

  private static boolean isInteger(double d) {
    return (int) d == d;
  }

  private static final Logger log = LogManager.getLogger(AbstractAStarWalker.class);
  private final GeometryFactory geometryFactory = new GeometryFactory();
  // private List<GUID> debugLabels;
  protected int crossX = 0;
  protected int crossY = 0;
  private boolean debugCosts = false; // Manually set this to view H, G & F costs as rendered labels
  private Area vbl = new Area();
  private double cell_cost = zone.getUnitsPerCell();
  private double distance = -1;
  private ShapeReader shapeReader = new ShapeReader(geometryFactory);
  private PreparedGeometry vblGeometry = null;
  // private long avgRetrieveTime;
  // private long avgTestTime;
  // private long retrievalCount;
  // private long testCount;
  private TokenFootprint footprint = new TokenFootprint();
  private Map<CellPoint, Map<CellPoint, Boolean>> blockedMovesByGoal = new ConcurrentHashMap<>();
  private final Map<CellPoint, List<TerrainModifier>> terrainCells = new HashMap<>();

  public AbstractAStarWalker(Zone zone) {
    super(zone);

    // Get tokens on map that may affect movement
    for (Token token : zone.getTokensWithTerrainModifiers()) {
      // log.info("Token: " + token.getName() + ", " + token.getTerrainModifier());
      Set<CellPoint> cells = token.getOccupiedCells(zone.getGrid());
      for (CellPoint cell : cells) {
        terrainCells
            .computeIfAbsent(cell, ignored -> new ArrayList<>())
            .add(
                new TerrainModifier(
                    token.getTerrainModifierOperation(), token.getTerrainModifier()));
      }
    }
  }

  /**
   * Returns the list of neighbor cells that are valid for being movement-checked. This is an array
   * of (x,y) offsets (see the constants in this class) named as compass points.
   *
   * <p>It should be possible to query the current (x,y) CellPoint passed in to determine which
   * directions are feasible to move into. But it would require information about visibility (which
   * token is moving, does it have sight, and so on). Currently that information is not available
   * here, but perhaps an option Token parameter could be specified to the constructor? Or maybe as
   * the tree was scanned, since I believe all Grids share a common ZoneWalker.
   *
   * @param x the x of the CellPoint
   * @param y the y of the CellPoint
   * @return the array of (x,y) for the neighbor cells
   */
  protected abstract int[][] getNeighborMap(int x, int y);

  protected abstract double hScore(AStarCellPoint p1, CellPoint p2);

  protected abstract double getDiagonalMultiplier(int[] neighborArray);

  public double getDistance() {
    if (distance < 0) {
      return 0;
    } else {
      return distance;
    }
  }

  public Map<CellPoint, Set<CellPoint>> getBlockedMoves() {
    final Map<CellPoint, Set<CellPoint>> result = new HashMap<>();
    for (var entry : blockedMovesByGoal.entrySet()) {
      result.put(
          entry.getKey(),
          entry.getValue().entrySet().stream()
              .filter(Map.Entry::getValue)
              .map(Map.Entry::getKey)
              .collect(Collectors.toSet()));
    }
    return result;
  }

  @Override
  public void setFootprint(TokenFootprint footprint) {
    this.footprint = footprint;
  }

  @Override
  protected List<CellPoint> calculatePath(CellPoint start, CellPoint goal) {
    crossX = start.x - goal.x;
    crossY = start.y - goal.y;

    Queue<AStarCellPoint> openList =
        new PriorityQueue<>(Comparator.comparingDouble(AStarCellPoint::fCost));
    Map<AStarCellPoint, AStarCellPoint> openSet = new HashMap<>(); // For faster lookups
    Set<AStarCellPoint> closedSet = new HashSet<>();

    // Current fail safe... bail out after 10 seconds of searching just in case, shouldn't hang UI
    // as this is off the AWT thread
    long timeOut = System.currentTimeMillis();
    double estimatedTimeoutNeeded = 10000;

    // if (start.equals(end))
    // log.info("NO WORK!");

    var startNode = new AStarCellPoint(start, !isInteger(start.distanceTraveledWithoutTerrain));
    openList.add(startNode);
    openSet.put(startNode, startNode);

    AStarCellPoint currentNode = null;

    // Get current VBL for map...
    // Using JTS because AWT Area can only intersect with Area and we want to use simple lines here.
    // Render VBL to Geometry class once and store.
    // Note: zoneRenderer will be null if map is not visible to players.
    if (MapTool.getFrame().getCurrentZoneRenderer() != null) {
      if (MapTool.getServerPolicy().getVblBlocksMove()) {
        vbl = MapTool.getFrame().getCurrentZoneRenderer().getZoneView().getTopologyTree().getArea();

        if (tokenVBL != null) {
          vbl.subtract(tokenVBL);
        }

        // Finally, add the Move Blocking Layer!
        vbl.add(zone.getTopologyTerrain());
      } else {
        vbl = zone.getTopologyTerrain();
      }
    }

    if (!vbl.isEmpty()) {
      try {
        var vblGeometry =
            shapeReader
                .read(new ReverseShapePathIterator(vbl.getPathIterator(null)))
                .buffer(1); // .buffer helps creating valid geometry and prevent self-intersecting

        // polygons
        if (!vblGeometry.isValid()) {
          log.info(
              "vblGeometry is invalid! May cause issues. Check for self-intersecting polygons.");
        }

        this.vblGeometry = PreparedGeometryFactory.prepare(vblGeometry);
      } catch (Exception e) {
        log.info("vblGeometry oh oh: ", e);
      }

      // log.info("vblGeometry bounds: " + vblGeometry.toString());
    }

    // Erase previous debug labels, this actually erases ALL labels! Use only when debugging!
    EventQueue.invokeLater(
        () -> {
          if (!zone.getLabels().isEmpty() && debugCosts) {
            for (Label label : zone.getLabels()) {
              zone.removeLabel(label.getId());
            }
          }
        });

    // Timeout quicker for GM cause reasons
    if (MapTool.getPlayer().isGM()) {
      estimatedTimeoutNeeded = estimatedTimeoutNeeded / 2;
    }

    // log.info("A* Path timeout estimate: " + estimatedTimeoutNeeded);

    while (!openList.isEmpty()) {
      if (System.currentTimeMillis() > timeOut + estimatedTimeoutNeeded) {
        log.info("Timing out after " + estimatedTimeoutNeeded);
        break;
      }

      currentNode = openList.remove();
      openSet.remove(currentNode);
      if (currentNode.position.equals(goal)) {
        break;
      }

      for (AStarCellPoint currentNeighbor : getNeighbors(currentNode, closedSet)) {
        currentNeighbor.h = hScore(currentNeighbor, goal);
        showDebugInfo(currentNeighbor);

        if (openSet.containsKey(currentNeighbor)) {
          // check if it is cheaper to get here the way that we just came, versus the previous path
          AStarCellPoint oldNode = openSet.get(currentNeighbor);
          if (currentNeighbor.g < oldNode.g) {
            // We're about to modify the node cost, so we have to reinsert the node.
            openList.remove(oldNode);

            oldNode.replaceG(currentNeighbor);
            oldNode.parent = currentNode;

            openList.add(oldNode);
          }
          continue;
        }

        openList.add(currentNeighbor);
        openSet.put(currentNeighbor, currentNeighbor);
      }

      closedSet.add(currentNode);
      currentNode = null;

      /*
        We now calculate paths off the main UI thread but only one at a time.
        If the token moves, we cancel the thread and restart so we're only calculating the most
        recent path request. Clearing the list effectively finishes this thread gracefully.
      */
      if (Thread.interrupted()) {
        // log.info("Thread interrupted!");
        openList.clear();
      }
    }

    List<CellPoint> returnedCellPointList = new LinkedList<>();
    while (currentNode != null) {
      returnedCellPointList.add(currentNode.position);
      currentNode = currentNode.parent;
    }

    // We don't need to "calculate" distance after the fact as it's already stored as the G cost...
    if (!returnedCellPointList.isEmpty()) {
      distance = returnedCellPointList.get(0).getDistanceTraveled(zone);
    } else { // if path finding was interrupted because of timeout
      distance = 0;
      goal.setAStarCanceled(true);

      returnedCellPointList.add(goal);
      returnedCellPointList.add(start);
    }

    Collections.reverse(returnedCellPointList);
    timeOut = (System.currentTimeMillis() - timeOut);
    if (timeOut > 500) {
      log.debug("Time to calculate A* path warning: " + timeOut + "ms");
    }

    // if (retrievalCount > 0)
    // log.info("avgRetrieveTime: " + Math.floor(avgRetrieveTime / retrievalCount)/1000 + " micro");
    // if (testCount > 0)
    // log.info("avgTestTime: " + Math.floor(avgTestTime / testCount)/1000 + " micro");

    return returnedCellPointList;
  }

  protected List<AStarCellPoint> getNeighbors(AStarCellPoint node, Set<AStarCellPoint> closedSet) {
    List<AStarCellPoint> neighbors = new ArrayList<>();
    int[][] neighborMap = getNeighborMap(node.position.x, node.position.y);

    // Find all the neighbors.
    for (int[] neighborArray : neighborMap) {
      double terrainMultiplier = 0;
      double terrainAdder = 0;
      boolean terrainIsFree = false;
      boolean blockNode = false;

      // Get diagonal cost multiplier, if any...
      double diagonalMultiplier = getDiagonalMultiplier(neighborArray);
      boolean invertEvenOddDiagonals = !isInteger(diagonalMultiplier);

      AStarCellPoint neighbor =
          new AStarCellPoint(
              node.position.x + neighborArray[0],
              node.position.y + neighborArray[1],
              node.isOddStepOfOneTwoOneMovement ^ invertEvenOddDiagonals);
      Set<CellPoint> occupiedCells = footprint.getOccupiedCells(node.position);

      if (closedSet.contains(neighbor)) {
        continue;
      }

      // Add the cell we're coming from
      neighbor.parent = node;

      // Don't count VBL or Terrain Modifiers
      if (restrictMovement) {
        for (CellPoint cellPoint : occupiedCells) {
          // VBL Check
          if (vblBlocksMovement(cellPoint, neighbor.position)) {
            blockNode = true;
            break;
          }
        }

        if (blockNode) {
          continue;
        }

        // Check for terrain modifiers
        for (TerrainModifier terrainModifier :
            terrainCells.getOrDefault(neighbor.position, Collections.emptyList())) {
          if (!terrainModifiersIgnored.contains(terrainModifier.operation)) {
            switch (terrainModifier.operation) {
              case MULTIPLY:
                terrainMultiplier += terrainModifier.value;
                break;
              case ADD:
                terrainAdder += terrainModifier.value;
                break;
              case BLOCK:
                // Terrain blocking applies equally regardless of even/odd diagonals.
                closedSet.add(new AStarCellPoint(neighbor.position, false));
                closedSet.add(new AStarCellPoint(neighbor.position, true));
                blockNode = true;
                continue;
              case FREE:
                terrainIsFree = true;
                break;
              case NONE:
                break;
            }
          }
        }
      }
      terrainAdder = terrainAdder / cell_cost;

      if (blockNode) {
        continue;
      }

      // If the total terrainMultiplier equals out to zero, or there were no multipliers,
      // set to 1 so we do math right...
      if (terrainMultiplier == 0) {
        terrainMultiplier = 1;
      }

      terrainMultiplier = Math.abs(terrainMultiplier); // net negative multipliers screw with the AI

      if (terrainIsFree) {
        neighbor.g = node.g;
        neighbor.position.distanceTraveled = node.position.distanceTraveled;
      } else {
        neighbor.position.distanceTraveledWithoutTerrain =
            node.position.distanceTraveledWithoutTerrain + diagonalMultiplier;

        if (neighbor.isOddStepOfOneTwoOneMovement()) {
          neighbor.g = node.g + terrainAdder + terrainMultiplier;

          neighbor.position.distanceTraveled =
              node.position.distanceTraveled + terrainAdder + terrainMultiplier;
        } else {
          neighbor.g = node.g + terrainAdder + terrainMultiplier * Math.ceil(diagonalMultiplier);

          neighbor.position.distanceTraveled =
              node.position.distanceTraveled
                  + terrainAdder
                  + terrainMultiplier * Math.ceil(diagonalMultiplier);
        }
      }

      neighbors.add(neighbor);
    }

    return neighbors;
  }

  private boolean vblBlocksMovement(CellPoint start, CellPoint goal) {
    if (vblGeometry == null) {
      return false;
    }

    // Stopwatch stopwatch = Stopwatch.createStarted();
    Map<CellPoint, Boolean> blockedMoves =
        blockedMovesByGoal.computeIfAbsent(goal, pos -> new HashMap<>());
    Boolean test = blockedMoves.get(start);
    // if it's null then the test for that direction hasn't been set yet otherwise just return the
    // previous result
    if (test != null) {
      // log.info("Time to retrieve: " + stopwatch.elapsed(TimeUnit.NANOSECONDS));
      // avgRetrieveTime += stopwatch.elapsed(TimeUnit.NANOSECONDS);
      // retrievalCount++;
      return test;
    }

    Rectangle startBounds = zone.getGrid().getBounds(start);
    Rectangle goalBounds = zone.getGrid().getBounds(goal);

    if (goalBounds.isEmpty() || startBounds.isEmpty()) {
      return false;
    }

    // If there is no vbl within the footprints, we're good!
    if (!vbl.intersects(startBounds) && !vbl.intersects(goalBounds)) {
      return false;
    }

    // If the goal center point is in vbl, allow to maintain path through vbl (should be GM only?)
    /*
    if (vbl.contains(goal.toPoint())) {
      // Allow GM to move through VBL
       return !MapTool.getPlayer().isGM();
    }
    */

    // NEW WAY - use polygon test
    double x1 = startBounds.getCenterX();
    double y1 = startBounds.getCenterY();
    double x2 = goalBounds.getCenterX();
    double y2 = goalBounds.getCenterY();
    LineString centerRay =
        geometryFactory.createLineString(
            new Coordinate[] {new Coordinate(x1, y1), new Coordinate(x2, y2)});

    boolean blocksMovement;
    try {
      blocksMovement = vblGeometry.intersects(centerRay);
    } catch (Exception e) {
      log.info("clipped.intersects oh oh: ", e);
      return true;
    }

    // avgTestTime += stopwatch.elapsed(TimeUnit.NANOSECONDS);
    // testCount++;

    blockedMoves.put(start, blocksMovement);

    return blocksMovement;
  }

  protected void showDebugInfo(AStarCellPoint node) {
    if (!log.isDebugEnabled() && !debugCosts) {
      return;
    }

    final int basis = zone.getGrid().getSize() / 10;
    final int xOffset = basis * (node.isOddStepOfOneTwoOneMovement ? 7 : 3);

    // if (debugLabels == null) { debugLabels = new ArrayList<>(); }

    Rectangle cellBounds = zone.getGrid().getBounds(node.position);
    DecimalFormat f = new DecimalFormat("##.00");

    Label gScore = new Label();
    Label hScore = new Label();
    Label fScore = new Label();
    Label parent = new Label();

    gScore.setLabel(f.format(node.g));
    gScore.setX(cellBounds.x + xOffset);
    gScore.setY(cellBounds.y + 1 * basis);

    hScore.setLabel(f.format(node.h));
    hScore.setX(cellBounds.x + xOffset);
    hScore.setY(cellBounds.y + 3 * basis);

    fScore.setLabel(f.format(node.fCost()));
    fScore.setX(cellBounds.x + xOffset);
    fScore.setY(cellBounds.y + 5 * basis);
    fScore.setForegroundColor(Color.RED);

    if (node.parent != null) {
      parent.setLabel(
          String.format(
              "(%d, %d | %s)",
              node.parent.position.x,
              node.parent.position.y,
              node.parent.isOddStepOfOneTwoOneMovement() ? "O" : "E"));
    } else {
      parent.setLabel("(none)");
    }
    parent.setX(cellBounds.x + xOffset);
    parent.setY(cellBounds.y + 7 * basis);
    parent.setForegroundColor(Color.BLUE);

    EventQueue.invokeLater(
        () -> {
          zone.putLabel(gScore);
          zone.putLabel(hScore);
          zone.putLabel(fScore);
          zone.putLabel(parent);
        });

    // Track labels to delete later
    // debugLabels.add(gScore.getId());
    // debugLabels.add(hScore.getId());
    // debugLabels.add(fScore.getId());
  }
}
