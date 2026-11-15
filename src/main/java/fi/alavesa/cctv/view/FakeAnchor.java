package fi.alavesa.cctv.view;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Logger;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.jspecify.annotations.Nullable;

/**
 * The 26.2 camera look without a real monster: a creeper / spider / enderman that exists only on the viewer's client.
 * The client picks the post effect (creeper, spider, invert) from the type of the entity it spectates, so the server
 * keeps the player on a harmless item-display anchor ({@code setSpectatorTarget}) and then tells that one client to
 * spectate this fake mob instead, moving it along with the anchor.
 *
 * <p>Why: a real monster anchor is refused or removed by peaceful difficulty, by region plugins that deny mob spawning
 * (prison and spawn areas usually do) and by anti-mob plugins. The fake one is never added to the world, so nothing
 * ticks, despawns or sees it; nobody else gets its packets.
 *
 * <p>Built by reflection on the server's own (Mojang-named) classes, so the plugin still compiles against the plain
 * Paper API; if anything doesn't bind, {@link #available()} is false and real mobs are used as before.
 */
final class FakeAnchor {

    private static boolean bound;
    private static @Nullable Method worldHandle;
    private static Method create, getId, getUuid, getEyeHeight, send, teleport;
    private static Field connection;
    private static Constructor<?> addEntity, setCamera, removeEntities, rotateHead, pmr, vec3;
    private static @Nullable Constructor<?> changeDifficulty;
    private static @Nullable Method getLevelData, getDifficulty, isLocked;
    private static @Nullable Object easy;
    private static Object spawnReason, zero;
    /** EntitySpawnRequest(reason, ignoreChecks = true): the plain create() refuses monsters on peaceful */
    private static @Nullable Object spawnRequest;
    private static @Nullable Method createIgnoring;
    private static Object creeper, spider, enderman;

    private final Object entity;
    private final Object level;
    /** the viewer's client was told "easy" (the world is peaceful): put the real difficulty back on remove */
    private boolean difficultyFaked;
    private final Object type;
    private final int id;
    private final UUID uuid;
    private final double eyeHeight;
    private boolean shown;
    private double lx = Double.NaN, ly, lz;
    private float lyaw, lpitch;

    private FakeAnchor(Object entity, Object type, Object level) throws ReflectiveOperationException {
        this.entity = entity;
        this.level = level;
        this.type = type;
        this.id = (int) getId.invoke(entity);
        this.uuid = (UUID) getUuid.invoke(entity);
        this.eyeHeight = ((Number) getEyeHeight.invoke(entity)).doubleValue();
    }

    static boolean bind(Logger log) {
        try {
            ClassLoader cl = org.bukkit.Bukkit.getServer().getClass().getClassLoader();
            Class<?> entityC = Class.forName("net.minecraft.world.entity.Entity", false, cl);
            Class<?> typeC = Class.forName("net.minecraft.world.entity.EntityType", false, cl);
            Class<?> levelC = Class.forName("net.minecraft.world.level.Level", false, cl);
            Class<?> reasonC = Class.forName("net.minecraft.world.entity.EntitySpawnReason", false, cl);
            Class<?> vec3C = Class.forName("net.minecraft.world.phys.Vec3", false, cl);
            Class<?> pmrC = Class.forName("net.minecraft.world.entity.PositionMoveRotation", false, cl);
            Class<?> packetC = Class.forName("net.minecraft.network.protocol.Packet", false, cl);
            String pkg = "net.minecraft.network.protocol.game.";
            // 26.3 moved the EntityType constants to EntityTypes
            Class<?> constants;
            try {
                constants = Class.forName("net.minecraft.world.entity.EntityTypes", false, cl);
            } catch (ClassNotFoundException e) {
                constants = typeC;
            }
            creeper = constants.getField("CREEPER").get(null);
            spider = constants.getField("SPIDER").get(null);
            enderman = constants.getField("ENDERMAN").get(null);
            create = typeC.getMethod("create", levelC, reasonC);
            spawnReason = reasonC.getField("COMMAND").get(null);
            try {
                Class<?> requestC = Class.forName("net.minecraft.world.entity.EntitySpawnRequest", false, cl);
                spawnRequest = requestC.getConstructor(reasonC, boolean.class).newInstance(spawnReason, true);
                createIgnoring = typeC.getMethod("create", levelC, requestC);
            } catch (ReflectiveOperationException e) {
                spawnRequest = null;
                createIgnoring = null;
            }
            getId = entityC.getMethod("getId");
            getUuid = entityC.getMethod("getUUID");
            getEyeHeight = entityC.getMethod("getEyeHeight");
            vec3 = vec3C.getConstructor(double.class, double.class, double.class);
            zero = vec3C.getField("ZERO").get(null);
            pmr = pmrC.getConstructor(vec3C, vec3C, float.class, float.class);
            addEntity = Class.forName(pkg + "ClientboundAddEntityPacket", false, cl).getConstructor(int.class,
                    UUID.class, double.class, double.class, double.class, float.class, float.class, typeC, int.class,
                    vec3C, double.class);
            setCamera = Class.forName(pkg + "ClientboundSetCameraPacket", false, cl).getConstructor(entityC);
            removeEntities = Class.forName(pkg + "ClientboundRemoveEntitiesPacket", false, cl)
                    .getConstructor(int[].class);
            rotateHead = Class.forName(pkg + "ClientboundRotateHeadPacket", false, cl)
                    .getConstructor(entityC, byte.class);
            teleport = Class.forName(pkg + "ClientboundTeleportEntityPacket", false, cl).getMethod("teleport",
                    int.class, pmrC, Set.class, boolean.class);
            try {
                Class<?> difficultyC = Class.forName("net.minecraft.world.Difficulty", false, cl);
                easy = difficultyC.getField("EASY").get(null);
                changeDifficulty = Class.forName(pkg + "ClientboundChangeDifficultyPacket", false, cl)
                        .getConstructor(difficultyC, boolean.class);
                getLevelData = levelC.getMethod("getLevelData");
                Class<?> dataC = getLevelData.getReturnType();
                getDifficulty = dataC.getMethod("getDifficulty");
                isLocked = dataC.getMethod("isDifficultyLocked");
            } catch (ReflectiveOperationException e) {
                changeDifficulty = null;
            }
            Class<?> serverPlayer = Class.forName("net.minecraft.server.level.ServerPlayer", false, cl);
            connection = serverPlayer.getField("connection");
            send = connection.getType().getMethod("send", packetC);
            bound = true;
            log.info("26.2 camera looks: client-side anchors (work on peaceful and in no-mob regions)");
        } catch (Throwable t) {
            log.warning("Client-side camera anchors unavailable (" + t + "); the 26.2 camera look uses real mobs, "
                    + "which peaceful difficulty and mob-spawn protection block");
            bound = false;
        }
        return bound;
    }

    static boolean available() {
        return bound;
    }

    /** A fake mob of the given Bukkit class's kind (Creeper, Spider, Enderman), or null if it can't be made. */
    static @Nullable FakeAnchor of(Class<?> bukkitMob, World w, Logger log) {
        if (!bound) return null;
        try {
            Object type = switch (bukkitMob.getSimpleName()) {
                case "Spider" -> spider;
                case "Enderman" -> enderman;
                default -> creeper;
            };
            if (worldHandle == null) worldHandle = w.getClass().getMethod("getHandle");
            Object level = worldHandle.invoke(w);
            // never added to the world, so the spawn rules (peaceful) don't apply to it
            Object e = createIgnoring != null ? createIgnoring.invoke(type, level, spawnRequest)
                    : create.invoke(type, level, spawnReason);
            if (e == null) log.warning("could not make a client-side camera anchor (" + type + ")");
            return e == null ? null : new FakeAnchor(e, type, level);
        } catch (Throwable t) {
            log.warning("could not make a client-side camera anchor: " + t);
            return null;
        }
    }

    /** Shows the mob to the viewer at the camera eye and makes the viewer's client spectate it. */
    void show(Player p, Location eye) {
        double y = eye.getY() - eyeHeight;
        if (!shown) {
            // the client also refuses to create monsters while it believes the world is peaceful
            if (!difficultyFaked && changeDifficulty != null && p.getWorld().getDifficulty() == org.bukkit.Difficulty.PEACEFUL) {
                send(p, newPacket(changeDifficulty, easy, locked()));
                difficultyFaked = true;
            }
            send(p, newPacket(addEntity, id, uuid, eye.getX(), y, eye.getZ(), eye.getPitch(), eye.getYaw(), type, 0,
                    zero, (double) eye.getYaw()));
            shown = true;
            remember(eye.getX(), y, eye.getZ(), eye.getYaw(), eye.getPitch());
        }
        send(p, newPacket(setCamera, entity));
    }

    /** Follows the camera (position for the dolly zoom, rotation for pan/tilt). */
    void move(Player p, Location eye) {
        if (!shown) return;
        double y = eye.getY() - eyeHeight;
        float yaw = eye.getYaw(), pitch = eye.getPitch();
        if (Math.abs(lx - eye.getX()) < 1e-4 && Math.abs(ly - y) < 1e-4 && Math.abs(lz - eye.getZ()) < 1e-4
                && Math.abs(lyaw - yaw) < 0.01f && Math.abs(lpitch - pitch) < 0.01f) return;
        remember(eye.getX(), y, eye.getZ(), yaw, pitch);
        try {
            Object pos = vec3.newInstance(eye.getX(), y, eye.getZ());
            send(p, teleport.invoke(null, id, pmr.newInstance(pos, zero, yaw, pitch), Set.of(), false));
        } catch (ReflectiveOperationException ignored) {
            return;
        }
        send(p, newPacket(rotateHead, entity, (byte) Math.floorMod((int) (yaw * 256 / 360), 256)));
    }

    /** Removes it from the viewer's client (after the server has given the camera back). */
    void remove(Player p) {
        if (!shown) return;
        shown = false;
        send(p, newPacket(removeEntities, (Object) new int[]{id}));
        if (difficultyFaked) {
            difficultyFaked = false;
            try {
                Object data = getLevelData.invoke(level);
                send(p, newPacket(changeDifficulty, getDifficulty.invoke(data), isLocked.invoke(data)));
            } catch (ReflectiveOperationException ignored) {
                // the next login or /difficulty resends it
            }
        }
    }

    private boolean locked() {
        try {
            return (boolean) isLocked.invoke(getLevelData.invoke(level));
        } catch (ReflectiveOperationException | RuntimeException e) {
            return false;
        }
    }

    private void remember(double x, double y, double z, float yaw, float pitch) {
        lx = x;
        ly = y;
        lz = z;
        lyaw = yaw;
        lpitch = pitch;
    }

    private static @Nullable Object newPacket(Constructor<?> c, Object... args) {
        try {
            return c.newInstance(args);
        } catch (ReflectiveOperationException e) {
            return null;
        }
    }

    private static void send(Player p, @Nullable Object packet) {
        if (packet == null || !p.isOnline()) return;
        try {
            Method h = p.getClass().getMethod("getHandle");
            send.invoke(connection.get(h.invoke(p)), packet);
        } catch (ReflectiveOperationException ignored) {
            // player left
        }
    }
}
