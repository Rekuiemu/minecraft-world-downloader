package game.data;

import static util.ExceptionHandling.attemptQuiet;

import config.Config;
import config.Version;
import game.data.coordinates.Coordinate2D;
import gui.ChunkImageState;
import gui.GuiManager;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import packets.builder.PacketBuilder;

public class RenderDistanceExtender {
    private static final Coordinate2D POS_INIT = new Coordinate2D(0, 0) {
        @Override
        public boolean isInRangeChebyshev(Coordinate2D other, int distance) {
            return false;
        }
    };

    private int extendedDistance;

    private Coordinate2D playerChunk;

    private List<List<Coordinate2D>> circles;

    private Set<Coordinate2D> extenderLoaded;
    private Set<Coordinate2D> gameLoaded;

    private final WorldManager worldManager;

    private ExecutorService executorService;

    private Status status;

    public RenderDistanceExtender(WorldManager worldManager) {
        this.worldManager = worldManager;
        this.extendedDistance = Config.getExtendedRenderDistance();
        generateCircles(this.extendedDistance);

        reset();
    }

    public void reset() {
        this.status = Status.WAITING;

        this.playerChunk = POS_INIT;
        this.gameLoaded = ConcurrentHashMap.newKeySet();
        this.extenderLoaded = ConcurrentHashMap.newKeySet();

        if (executorService != null) {
            executorService.shutdown();
            executorService = null;
        }
    }

    private void start() {
        executorService = Executors.newSingleThreadExecutor((r) -> new Thread(r,"Render Distance Extender"));
        delay();

        this.status = Status.ACTIVE;
    }

    /**
     * When teleporting, wait a bit to avoid sending chunks also sent by the server.
     */
    private void delay() {
        executorService.execute(() -> attemptQuiet(() -> Thread.sleep(1500)));
    }

    public void updatePlayerPos(Coordinate2D newPos) {
        if (status != Status.ACTIVE || extendedDistance == 0) { return; }

        Coordinate2D newChunkPos = newPos.globalToChunk();

        if (playerChunk.equals(newChunkPos)) {
            return;
        }

        Coordinate2D oldPos = this.playerChunk;
        this.playerChunk = newChunkPos;

        int dist = this.extendedDistance;
        if (oldPos.isInRangeChebyshev(newChunkPos, 1)) {
            executorService.execute(() -> updateOuter(newChunkPos, dist));
        } else {
            executorService.execute(() -> {
                // after teleport, wait a bit so server can send its own chunks
                if (!oldPos.isInRangeManhattan(newChunkPos, 2)) {
                    delay();
                }
                updateFull(newChunkPos, dist);
            });
        }
    }

    /**
     * In case of teleports or spawns we will have to consider all the chunks.
     */
    private void updateFull(Coordinate2D playerChunk, int distance) {
        checkAllLoaded();
        for (int i = 0; i < distance + 1; i++) {
            updateCircle(i, playerChunk);
        }
    }

    private void checkAllLoaded() {
        Collection<Coordinate2D> toUnload = new ArrayList<>();
        extenderLoaded.removeIf((coord) -> {
            if (!isInRange(coord)) {
                toUnload.add(coord);
                return true;
            }
            return false;
        });
        worldManager.unloadChunks(toUnload);
    }

    private void updateOuter(Coordinate2D playerChunk, int distance) {
        updateCircle(distance, playerChunk);
        unloadOuter(distance, playerChunk);
    }

    private void unloadOuter(int distance, Coordinate2D center) {
        List<Coordinate2D> coords = circles.get(distance + 1);

        Collection<Coordinate2D> toUnload = new ArrayList<>(coords.size());
        for (Coordinate2D c : coords) {
            Coordinate2D toCheck = center.add(c);
            if (!isInRange(toCheck) && extenderLoaded.contains(toCheck)) {
                toUnload.add(toCheck);
                extenderLoaded.remove(toCheck);
            }
        }
        worldManager.unloadChunks(toUnload);
    }

    private void updateCircle(int radius, Coordinate2D center) {
        List<Coordinate2D> coords = circles.get(radius);
        if (coords.isEmpty()) { return; }

        Collection<Coordinate2D> desired = new ArrayList<>(coords.size());
        for (Coordinate2D c : coords) {
            Coordinate2D toLoad = center.add(c);
            if (isLoaded(toLoad)) {
                continue;
            }
            desired.add(toLoad);
        }
        extenderLoaded.addAll( worldManager.sendChunksToPlayer(desired));
    }

    public void setExtendedDistance(int newDistance) {
        if (this.extendedDistance == newDistance) { return; }

        if (newDistance > this.circles.size() - 2) {
            generateCircles(newDistance);

            sendNewRenderDistancePacket(newDistance);
        }

        this.extendedDistance = newDistance;

        if (this.status != Status.WAITING) {
            executorService.execute(() -> updateFull(this.playerChunk, newDistance));
        }
    }

    private void sendNewRenderDistancePacket(int newDistance) {
        if (!Config.versionReporter().isAtLeast(Version.V1_19_3)) {
            return;
        }

        PacketBuilder pb = new PacketBuilder("SetChunkCacheRadius");
        pb.writeVarInt(newDistance);

        Config.getPacketInjector().enqueuePacket(pb);
    }

    private void generateCircles(int distance) {
        CircleGenerator generator = new CircleGenerator();
        generator.computeUpToRadius(distance + 1);

        this.circles = generator.getResult();
    }

    public void notifyLoaded(Coordinate2D coords) {
        this.extenderLoaded.remove(coords);
        this.gameLoaded.add(coords);

        if (status == Status.WAITING) {
            start();
        }
    }

    public boolean isLoaded(Coordinate2D coords) {
        return gameLoaded.contains(coords) || extenderLoaded.contains(coords);
    }

    public void notifyUnloaded(Coordinate2D coords) {
        gameLoaded.remove(coords);

        if (isInRange(coords)) {
            extenderLoaded.add(coords);
        }
    }

    private boolean isInRange(Coordinate2D coords) {
        return coords.isInRangeEuclidean(this.playerChunk, extendedDistance);
    }

    public boolean isStillNeeded(Coordinate2D coords) {
        return !isLoaded(coords) && isInRange(coords);
    }

    public boolean canUnload(Coordinate2D coords) {
        return !isInRange(coords);
    }

    public void drawAll() {
        extenderLoaded.forEach(el -> {
            GuiManager.setChunkState(el, ChunkImageState.DEBUG);
        });
    }
}

enum Status {
    WAITING, ACTIVE;
}