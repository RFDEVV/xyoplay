package id.rfdevv.xyoplay.client;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * One autonomous helper (a bot entity, or the player's own body in AFK mode). It walks, jumps, digs, bridges and
 * pillars along A* paths, cuts trees, mines ore, fights hostile mobs, crafts and upgrades its tools and armor, and
 * can raise a small shelter. All world changes go out through {@link IO}; all reading goes through {@link BotWorld}.
 */
final class BotAgent {
    interface IO {
        void tp(String sel, double x, double y, double z, float yaw);
        void setBlock(int x, int y, int z, String block);
        boolean damage(String sel, String uuid, double amount);
        void equip(String sel, String slot, String item);
        void give(String item, int count);
        void chat(String msg);
    }

    enum Mode {
        FOLLOW("Ikut"), STAY("Diam"), MINE("Tambang"), AUTO("Otomatis"), BUILD("Bangun base");
        final String label;
        Mode(String l) { label = l; }
    }

    /** Settings the owner can change at any time. */
    static final class Cfg {
        double speed = 0.22;
        double followDist = 3;
        boolean dig = true, place = true, fight = true, autoGear = true;
        int maxNodes = 600;
        String mineKind = "auto";   // auto | kayu | batu | batubara | besi | emas | berlian
    }

    // ---- identity
    final int id;
    final String sel;
    final boolean playerBody;
    final BotWorld world;
    final IO io;
    final Cfg cfg;
    final BotGear.Inv inv = new BotGear.Inv();
    final BotGear.Gear gear = new BotGear.Gear();

    // ---- state
    double x, y, z;
    float yaw;
    Mode mode = Mode.FOLLOW;
    String status = "siap";
    boolean alive = true;
    int home[];                       // base centre (x,y,z) or null
    private List<BotNav.Step> path;
    private int pi;
    private int[] pathGoalCell;       // cell the path was planned for
    private int replanCooldown, stuckCount, stuckTimer, failCount;
    private double lastX, lastY, lastZ;
    private int[] digCell; private int digLeft; private String digId;
    private int[] target;             // block being mined
    private final Map<Long, Long> blacklist = new HashMap<>();
    private BotWorld.Mob fightTarget; private int attackCd, mobScanCd;
    private int scanCd;
    private long tickNo;
    private double sentX = Double.NaN, sentY, sentZ; private float sentYaw;
    private final Random rng = new Random(42);
    int blocksMined, mobsKilled, crafted, placedBlocks, teleports, plans, planNodes;
    final List<String> log = new ArrayList<>();
    private final List<int[]> buildQueue = new ArrayList<>();
    private List<String> buildBlocks = new ArrayList<>();
    private int buildCd;
    private long nextBuildTry;
    private double wdX, wdY, wdZ;
    boolean returning;                // AFK: walking back to the death point
    boolean spawned; long lastCheck; boolean dirty;
    String uuid; int desync, missing, respawns, deaths; long spawnedAt, respawnAt; boolean seen;

    // goal given from outside (follow target / return point)
    double tx, ty, tz;
    boolean hasTarget;

    BotAgent(int id, String sel, boolean playerBody, BotWorld world, IO io, Cfg cfg) {
        this.id = id; this.sel = sel; this.playerBody = playerBody; this.world = world; this.io = io; this.cfg = cfg;
    }

    // ------------------------------------------------------------------ public control

    void place(double nx, double ny, double nz) { x = nx; y = ny; z = nz; sentX = Double.NaN; path = null; }

    void setTarget(double nx, double ny, double nz) { tx = nx; ty = ny; tz = nz; hasTarget = true; }

    void setMode(Mode m) {
        mode = m; path = null; target = null; fightTarget = null; digCell = null; stuckCount = 0;
        status = m.label;
        if (m == Mode.BUILD) startBuild();
    }

    void note(String s) { status = s; log.add(s); while (log.size() > 30) log.remove(0); }

    int tierPick() { return gear.tier("pickaxe"); }

    // ------------------------------------------------------------------ tick

    /** @param plan  how many path searches this agent may start this tick (shared budget) */
    int tick(int plan) {
        tickNo++;
        if (!alive) return 0;
        if (replanCooldown > 0) replanCooldown--;
        if (attackCd > 0) attackCd--;
        int used = 0;
        // watchdog: a follower that has not moved for 5 s although the owner is far away is moved next to the owner
        if (mode == Mode.FOLLOW && hasTarget && !playerBody && tickNo % 100 == 0) {
            double moved = Math.abs(x - wdX) + Math.abs(y - wdY) + Math.abs(z - wdZ);
            wdX = x; wdY = y; wdZ = z;
            double far = Math.sqrt((tx - x) * (tx - x) + (tz - z) * (tz - z));
            if (moved < 0.5 && digCell == null && fightTarget == null && far > cfg.followDist + 8) unstick(tx, ty, tz, "diam terlalu lama");
        }
        if (digCell != null) { stepDig(); sendPosition(); return 0; }
        switch (mode) {
            case STAY -> { status = "diam"; }
            case FOLLOW -> used = follow(plan);
            case MINE, AUTO -> used = work(plan);
            case BUILD -> used = build(plan);
        }
        sendPosition();
        return used;
    }

    // ------------------------------------------------------------------ modes

    private int follow(int plan) {
        if (!hasTarget) return 0;
        int used = fightIfNeeded(plan);
        if (used >= 0) return used < 0 ? 0 : used;
        return goTo(BotNav.near(tx, ty, tz, cfg.followDist + 0.7 * id), tx, ty, tz, plan, "ikut");
    }

    /** @return -1 when there is nothing to fight, otherwise plans used while handling a fight */
    private int fightIfNeeded(int plan) {
        if (!cfg.fight) return -1;
        if (fightTarget == null && --mobScanCd <= 0) {
            mobScanCd = 12;
            double cx = hasTarget ? tx : x, cy = hasTarget ? ty : y, cz = hasTarget ? tz : z;
            BotWorld.Mob best = null; double bd = 1e9;
            for (BotWorld.Mob m : world.hostiles(cx, cy, cz, 10)) {
                double d = dist(m.x(), m.y(), m.z());
                if (d < bd) { bd = d; best = m; }
            }
            fightTarget = best;
        }
        if (fightTarget == null) return -1;
        // refresh the target's position
        BotWorld.Mob cur = null;
        for (BotWorld.Mob m : world.hostiles(x, y, z, 16)) if (m.uuid().equals(fightTarget.uuid())) { cur = m; break; }
        if (cur == null) { mobsKilled++; fightTarget = null; return -1; }
        fightTarget = cur;
        double d = dist(cur.x(), cur.y(), cur.z());
        status = "melawan " + shortType(cur.type());
        faceTo(cur.x(), cur.z());
        if (d <= 3.4) {
            if (attackCd == 0) {
                attackCd = 12;
                double dmg = new double[] {2, 4, 5, 6, 7}[gear.tier("sword")];
                if (!io.damage(sel, cur.uuid(), dmg)) { cfg.fight = false; note("serang gagal: butuh OP/izin command"); }
            }
            return 0;
        }
        return goTo(BotNav.near(cur.x(), cur.y(), cur.z(), 2.2), cur.x(), cur.y(), cur.z(), plan, "mengejar mob");
    }

    private static String shortType(String t) { return t.replace("entity.minecraft.", "").replace("minecraft:", ""); }

    private int work(int plan) {
        int used = fightIfNeeded(plan);
        if (used >= 0) return used;
        if (cfg.autoGear) autoCraft();
        BotGear.Need need = needNow();
        if (mode == Mode.AUTO && home == null && ClientSettings.botAutoBase && inv.blocks() + inv.get("planks") >= BASE_COST && tickNo >= nextBuildTry) {
            nextBuildTry = tickNo + 600; setMode(Mode.BUILD); return 0;
        }
        if (need == BotGear.Need.NONE) {
            status = mode == Mode.AUTO ? "semua alat lengkap" : "tidak ada yang dicari";
            if (hasTarget && !playerBody) return goTo(BotNav.near(tx, ty, tz, cfg.followDist + 1.5), tx, ty, tz, plan, "ikut");
            return 0;
        }
        // a target block: find one
        if (target == null) {
            if (--scanCd <= 0) { scanCd = ClientSettings.botXray && (need == BotGear.Need.IRON || need == BotGear.Need.DIAMOND) ? 40 : 10; target = findBlock(need); }
            if (target == null) {
                status = "mencari " + needName(need) + "…";
                int w = wander(plan);
                if (w >= 0) return w;
                return goTo(BotNav.near(tx, ty, tz, cfg.followDist + 1.5), tx, ty, tz, plan, "mencari " + needName(need));
            }
            path = null;
        }
        int tid = target[0] * 31 + target[2];
        if (!world.solid(target[0], target[1], target[2])) { target = null; return 0; }   // someone else mined it
        status = "mengambil " + needName(need);
        BotNav.Goal g = BotNav.reach(target[0], target[1], target[2], 4.2);
        if (g.reached((int) Math.floor(x), (int) Math.floor(y), (int) Math.floor(z))) {
            String bid = world.id(target[0], target[1], target[2]);
            if (BotGear.tierNeeded(bid) > tierPick()) { blacklist.put(key(target), tickNo + 6000); target = null; return 0; }
            faceTo(target[0] + 0.5, target[2] + 0.5);
            digCell = target; digId = bid; digLeft = BotGear.digTicks(bid, gear);
            return 0;
        }
        int r = goTo(g, target[0], target[1], target[2], plan, "menuju " + needName(need));
        if (failCount >= 3) { blacklist.put(key(target), tickNo + 1200); target = null; failCount = 0; }
        return r;
    }

    /** Explores toward a random reachable spot (kept within 36 blocks of the owner). @return plans used, or -1 when there is nowhere to go */
    private int wander(int plan) {
        if (path != null && pi < path.size()) { followPath(); return 0; }
        if (plan <= 0) return 0;
        boolean leash = hasTarget && !playerBody;
        for (int i = 0; i < 8; i++) {
            double a = rng.nextDouble() * Math.PI * 2, r = 10 + rng.nextDouble() * 12;
            int gx = (int) Math.floor(x + Math.cos(a) * r), gz = (int) Math.floor(z + Math.sin(a) * r);
            if (leash && Math.hypot(gx - tx, gz - tz) > 36) continue;
            int gy = groundY(gx, (int) Math.floor(y), gz);
            if (gy == Integer.MIN_VALUE) continue;
            BotNav.Opts o = opts();
            BotNav.Result res = BotNav.search(world, (int) Math.floor(x), (int) Math.floor(y), (int) Math.floor(z), BotNav.near(gx + 0.5, gy, gz + 0.5, 1.5), o);
            plans++; planNodes += res.expanded();
            if (res.complete() && !res.path().isEmpty()) { path = res.path(); pi = 0; pathGoalCell = null; status = "menjelajah"; return 1; }
            return 1;
        }
        return leash ? -1 : 0;
    }

    private int groundY(int gx, int from, int gz) {
        for (int yy = from + 8; yy >= from - 12; yy--) if (BotNav.standable(world, gx, yy, gz)) return yy;
        return Integer.MIN_VALUE;
    }

    private BotGear.Need needNow() {
        if (mode == Mode.MINE) {
            return switch (cfg.mineKind) {
                case "kayu" -> BotGear.Need.LOGS;
                case "batu" -> BotGear.Need.STONE;
                case "besi" -> BotGear.Need.IRON;
                case "berlian" -> BotGear.Need.DIAMOND;
                case "tanah" -> BotGear.Need.BLOCKS;
                default -> BotGear.nextNeed(inv, gear, 24);
            };
        }
        int reserve = (home == null && ClientSettings.botAutoBase) ? BASE_COST : 24;
        return BotGear.nextNeed(inv, gear, reserve);
    }

    static String needName(BotGear.Need n) {
        return switch (n) { case LOGS -> "kayu"; case STONE -> "batu"; case IRON -> "besi"; case DIAMOND -> "berlian"; case BLOCKS -> "blok"; default -> "-"; };
    }

    /** Nearest exposed block that satisfies the need, scanned in a bounded cube. */
    private int[] findBlock(BotGear.Need need) {
        int bx = (int) Math.floor(x), by = (int) Math.floor(y), bz = (int) Math.floor(z);
        boolean hidden = ClientSettings.botXray && (need == BotGear.Need.IRON || need == BotGear.Need.DIAMOND);
        int R = hidden ? 12 : 16, H = 8;
        int[] best = null; double bd = 1e18;
        for (int dx = -R; dx <= R; dx++) for (int dz = -R; dz <= R; dz++) {
            int cx = bx + dx, cz = bz + dz;
            for (int dy = -H; dy <= H; dy++) {
                int cy = by + dy;
                if (world.kind(cx, cy, cz) != BotWorld.SOLID) continue;
                if (!hidden && !exposed(cx, cy, cz)) continue;
                double d = dx * dx + dz * dz + dy * dy * 2.0;
                if (d >= bd) continue;
                Long ban = blacklist.get(key(new int[] {cx, cy, cz}));
                if (ban != null && ban > tickNo) continue;
                if (!BotGear.matches(need, world.id(cx, cy, cz))) continue;
                if (need == BotGear.Need.BLOCKS && !world.pass(cx, cy + 1, cz)) continue;
                bd = d; best = new int[] {cx, cy, cz};
            }
        }
        return best;
    }

    private boolean exposed(int x, int y, int z) {
        return world.pass(x + 1, y, z) || world.pass(x - 1, y, z) || world.pass(x, y + 1, z) || world.pass(x, y - 1, z) || world.pass(x, y, z + 1) || world.pass(x, y, z - 1);
    }

    private static long key(int[] c) { return ((long) c[0] << 40) ^ ((long) c[2] << 16) ^ (c[1] + 1000L); }

    // ------------------------------------------------------------------ crafting

    void autoCraft() {
        List<String> done = BotGear.autoCraft(inv, gear);
        for (String d : done) {
            String slot = d.substring(0, d.indexOf(':'));
            int tier = Integer.parseInt(d.substring(d.indexOf(':') + 1));
            crafted++;
            io.equip(sel, BotGear.equipSlot(slot), BotGear.itemId(slot, tier));
            note("membuat " + slot + " " + BotGear.TIER_NAMES[tier]);
        }
    }

    /** Crafts for the player. @return null on success, otherwise what is still missing. */
    String craftFor(String slot, int tier) {
        BotGear.Inv probe = inv.copy();
        if (!BotGear.craft(probe, slot, tier)) {
            int need = BotGear.materialCost(slot, tier), have = BotGear.have(inv, tier);
            return "kurang " + Math.max(1, need - have) + " bahan " + BotGear.TIER_NAMES[tier];
        }
        inv.setFrom(probe);
        io.give(BotGear.itemId(slot, tier), 1);
        crafted++;
        return null;
    }

    // ------------------------------------------------------------------ digging

    private void stepDig() {
        if (digLeft-- > 0) return;
        int[] c = digCell; digCell = null;
        io.setBlock(c[0], c[1], c[2], "minecraft:air");
        if (world instanceof BotOverlay ov) ov.set(c[0], c[1], c[2], BotWorld.AIR, null);
        blocksMined++;
        String d = BotGear.drop(digId);
        if (d != null) inv.add(d, 1);
        if (target != null && target[0] == c[0] && target[1] == c[1] && target[2] == c[2]) target = null;
        stuckTimer = 0;
    }

    // ------------------------------------------------------------------ navigation

    BotNav.Opts opts() {
        BotNav.Opts o = new BotNav.Opts();
        o.dig = cfg.dig; o.place = cfg.place; o.blocks = inv.blocks(); o.digTier = tierPick(); o.maxNodes = cfg.maxNodes;
        return o;
    }

    /** Plans (if needed) and walks toward a goal. @return plans consumed (0/1) */
    private int goTo(BotNav.Goal goal, double gx, double gy, double gz, int plan, String label) {
        int cx = (int) Math.floor(x), cy = (int) Math.floor(y), cz = (int) Math.floor(z);
        if (goal.reached(cx, cy, cz)) { path = null; status = label + " (sampai)"; failCount = 0; faceTo(gx, gz); return 0; }
        boolean goalMoved = pathGoalCell != null && (Math.abs(pathGoalCell[0] - (int) gx) > 2 || Math.abs(pathGoalCell[2] - (int) gz) > 2 || Math.abs(pathGoalCell[1] - (int) gy) > 2);
        boolean need = path == null || pi >= path.size() || goalMoved;
        int used = 0;
        if (need && replanCooldown == 0 && plan > 0) {
            BotNav.Result r = BotNav.search(world, cx, cy, cz, goal, opts());
            plans++; planNodes += r.expanded(); used = 1;
            replanCooldown = 8;
            pathGoalCell = new int[] {(int) gx, (int) gy, (int) gz};
            if (r.path().isEmpty()) {
                failCount++;
                status = label + ": jalan buntu";
                if (failCount >= 3) unstick(gx, gy, gz, "tidak ada jalan");
                return used;
            }
            if (!r.complete()) failCount++; else failCount = 0;
            path = r.path(); pi = 0;
        }
        status = label;
        followPath();
        return used;
    }

    private void followPath() {
        if (path == null || pi >= path.size()) return;
        BotNav.Step s = path.get(pi);
        int ox = (int) Math.floor(x), oy = (int) Math.floor(y), oz = (int) Math.floor(z);
        // 1. clear the way (dig solid cells in the step's volume)
        for (int[] c : BotNav.cells(ox, oy, oz, s.x(), s.y(), s.z(), s.act())) {
            if (world.pass(c[0], c[1], c[2])) continue;
            String bid = world.id(c[0], c[1], c[2]);
            if (!cfg.dig || !BotNav.natural(bid) || BotGear.tierNeeded(bid) > tierPick()) { path = null; replanCooldown = 0; failCount++; return; }
            digCell = c; digId = bid; digLeft = BotGear.digTicks(bid, gear); faceTo(c[0] + 0.5, c[2] + 0.5);
            return;
        }
        // 2. place blocks the step needs
        if (s.act() == BotNav.Act.PILLAR) {
            if (!world.solid(ox, oy, oz)) {
                String b = inv.takeBlock();
                if (b == null) { path = null; return; }
                io.setBlock(ox, oy, oz, b);
                if (world instanceof BotOverlay ov) ov.set(ox, oy, oz, BotWorld.SOLID, b);
                placedBlocks++;
            }
        } else if (s.act() == BotNav.Act.BRIDGE) {
            if (!world.solid(s.x(), s.y() - 1, s.z())) {
                String b = inv.takeBlock();
                if (b == null) { path = null; return; }
                io.setBlock(s.x(), s.y() - 1, s.z(), b);
                if (world instanceof BotOverlay ov) ov.set(s.x(), s.y() - 1, s.z(), BotWorld.SOLID, b);
                placedBlocks++;
            }
        }
        // 3. move
        double gx = s.x() + 0.5, gz = s.z() + 0.5, gy = s.y();
        double dx = gx - x, dz = gz - z, h = Math.sqrt(dx * dx + dz * dz);
        double v = cfg.speed;
        boolean up = gy > y + 0.01;
        if (up) y = Math.min(gy, y + 0.35);
        else if (gy < y - 0.01 && h < 0.35) y = Math.max(gy, y - 0.5);
        boolean canWalk = !up || gy - y < 0.5 || s.act() == BotNav.Act.PILLAR;
        if (s.act() == BotNav.Act.PILLAR) { x = gx; z = gz; canWalk = false; }
        if (canWalk && h > 1e-6) {
            double m = Math.min(v, h);
            x += dx / h * m; z += dz / h * m;
            yaw = (float) (Math.toDegrees(Math.atan2(-dx, dz)));
        }
        if (Math.abs(x - gx) < 0.06 && Math.abs(z - gz) < 0.06 && Math.abs(y - gy) < 0.06) {
            x = gx; z = gz; y = gy; pi++;
            stuckTimer = 0;
        }
        // 4. stuck watchdog
        if (++stuckTimer >= 40) {
            stuckTimer = 0;
            double moved = Math.abs(x - lastX) + Math.abs(y - lastY) + Math.abs(z - lastZ);
            lastX = x; lastY = y; lastZ = z;
            if (moved < 0.3) { stuckCount++; path = null; replanCooldown = 0; if (stuckCount >= 2) unstick(tx, ty, tz, "tersangkut"); }
            else stuckCount = 0;
        }
    }

    /** Last resort: put the bot on a safe, standable cell near the owner (or where it stands). */
    void unstick(double gx, double gy, double gz, String why) {
        double ax = hasTarget && !playerBody ? tx : x, ay = hasTarget && !playerBody ? ty : y, az = hasTarget && !playerBody ? tz : z;
        int[] c = safeCellNear((int) Math.floor(ax), (int) Math.floor(ay), (int) Math.floor(az));
        stuckCount = 0; failCount = 0; path = null; target = null; digCell = null;
        if (c == null) return;
        place(c[0] + 0.5, c[1], c[2] + 0.5);
        teleports++;
        note("pindah ke tempat aman (" + why + ")");
    }

    int[] safeCellNear(int cx, int cy, int cz) {
        for (int r = 1; r <= 5; r++) {
            for (int i = 0; i < 24; i++) {
                double a = i / 24.0 * Math.PI * 2;
                int px = cx + (int) Math.round(Math.cos(a) * r), pz = cz + (int) Math.round(Math.sin(a) * r);
                for (int dy = 2; dy >= -4; dy--) if (BotNav.standable(world, px, cy + dy, pz)) return new int[] {px, cy + dy, pz};
            }
        }
        return BotNav.standable(world, cx, cy, cz) ? new int[] {cx, cy, cz} : null;
    }

    // ------------------------------------------------------------------ base building

    static final int BASE_COST = 72;

    /** Outer 5x5 hut: 3 high walls, a flat roof, a 1x2 door, light, crafting table, furnace, chest and a bed. */
    private void startBuild() {
        buildQueue.clear();
        int[] site = findSite();
        if (site == null) { note("tidak ada lahan datar untuk base"); mode = Mode.AUTO; return; }
        int ox = site[0], gy = site[1], oz = site[2];   // ox,oz = south-west corner, gy = floor level (first air layer)
        // clear the volume
        for (int dx = 0; dx < 5; dx++) for (int dz = 0; dz < 5; dz++) for (int dy = 0; dy <= 3; dy++) {
            if (dx > 0 && dx < 4 && dz > 0 && dz < 4 && dy >= 0 && dy <= 2) { if (!world.pass(ox + dx, gy + dy, oz + dz)) buildQueue.add(new int[] {ox + dx, gy + dy, oz + dz, 0}); }
        }
        for (int dx = 0; dx < 5; dx++) for (int dz = 0; dz < 5; dz++) {
            boolean wall = dx == 0 || dx == 4 || dz == 0 || dz == 4;
            for (int dy = 0; dy < 3; dy++) {
                if (!wall) continue;
                boolean door = dx == 2 && dz == 0 && dy < 2;
                if (door) continue;
                buildQueue.add(new int[] {ox + dx, gy + dy, oz + dz, 1});
            }
            buildQueue.add(new int[] {ox + dx, gy + 3, oz + dz, 1});
        }
        buildQueue.add(new int[] {ox + 1, gy, oz + 3, 2});   // crafting table
        buildQueue.add(new int[] {ox + 2, gy, oz + 3, 3});   // furnace
        buildQueue.add(new int[] {ox + 3, gy, oz + 3, 4});   // chest
        buildQueue.add(new int[] {ox + 3, gy, oz + 1, 5});   // bed (foot)
        buildQueue.add(new int[] {ox + 3, gy, oz + 2, 6});   // bed (head)
        buildQueue.add(new int[] {ox + 1, gy + 1, oz + 3, 7});   // torch on top of the crafting table
        home = new int[] {ox + 2, gy, oz + 2};
        note("membangun base di " + (ox + 2) + "," + gy + "," + (oz + 2));
        buildBlocks = new ArrayList<>();
        buildCd = 0;
    }

    private int[] findSite() {
        int bx = (int) Math.floor(x), by = (int) Math.floor(y), bz = (int) Math.floor(z);
        int[] best = null; double bd = 1e18;
        for (int r = 4; r <= 18; r += 2) {
            for (int i = 0; i < 16; i++) {
                double a = i / 16.0 * Math.PI * 2;
                int ox = bx + (int) Math.round(Math.cos(a) * r) - 2, oz = bz + (int) Math.round(Math.sin(a) * r) - 2;
                int gy = groundY(ox + 2, by, oz + 2);
                if (gy == Integer.MIN_VALUE) continue;
                boolean ok = true; int worst = 0;
                for (int dx = 0; dx < 5 && ok; dx++) for (int dz = 0; dz < 5 && ok; dz++) {
                    int g2 = groundY(ox + dx, gy, oz + dz);
                    if (g2 == Integer.MIN_VALUE || Math.abs(g2 - gy) > 1) ok = false;
                    else worst += Math.abs(g2 - gy);
                    if (ok && world.kind(ox + dx, gy - 1, oz + dz) != BotWorld.SOLID) ok = false;
                }
                if (ok) { double d = r + worst * 0.5; if (d < bd) { bd = d; best = new int[] {ox, gy, oz}; } }
            }
            if (best != null) break;
        }
        return best;
    }

    private int build(int plan) {
        if (buildQueue.isEmpty()) { note("base selesai"); mode = Mode.AUTO; return 0; }
        // stay near the site
        int[] c = buildQueue.get(0);
        if (dist(c[0] + 0.5, c[1], c[2] + 0.5) > 7.5) return goTo(BotNav.near(home[0] + 0.5, home[1], home[2] + 0.5, 1.5), home[0] + 0.5, home[1], home[2] + 0.5, plan, "menuju lokasi base");
        if (--buildCd > 0) return 0;
        buildCd = 2;
        for (int n = 0; n < 2 && !buildQueue.isEmpty(); n++) {
            int[] b = buildQueue.get(0);
            String block;
            switch (b[3]) {
                case 0 -> block = "minecraft:air";
                case 1 -> {
                    block = inv.takeBlock();
                    if (block == null && inv.take("planks", 1)) block = "minecraft:oak_planks";
                    if (block == null) { note("bahan base kurang, mencari lagi"); mode = Mode.AUTO; return 0; }
                }
                case 2 -> block = "minecraft:crafting_table";
                case 3 -> block = "minecraft:furnace";
                case 4 -> block = "minecraft:chest";
                case 5 -> block = "minecraft:red_bed[part=foot,facing=south]";
                case 6 -> block = "minecraft:red_bed[part=head,facing=south]";
                default -> block = "minecraft:torch";
            }
            // the bot and the owner may stand inside: move out of the way first
            io.setBlock(b[0], b[1], b[2], block);
            if (world instanceof BotOverlay ov) ov.set(b[0], b[1], b[2], block.equals("minecraft:air") || block.contains("torch") ? BotWorld.AIR : BotWorld.SOLID, block);
            placedBlocks++;
            buildQueue.remove(0);
        }
        status = "membangun base (" + buildQueue.size() + " blok lagi)";
        return 0;
    }

    // ------------------------------------------------------------------ helpers

    private double dist(double ax, double ay, double az) {
        double dx = ax - x, dy = ay - y, dz = az - z;
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    private void faceTo(double px, double pz) { yaw = (float) Math.toDegrees(Math.atan2(-(px - x), pz - z)); }

    /** Sends the body to its new place, but only when it really moved (idle bots cost nothing). */
    private void sendPosition() {
        if (!Double.isNaN(sentX) && Math.abs(sentX - x) < 0.04 && Math.abs(sentY - y) < 0.04 && Math.abs(sentZ - z) < 0.04 && Math.abs(sentYaw - yaw) < 8) return;
        if (tickNo % ClientSettings.botSendEvery != 0 && !Double.isNaN(sentX)) return;
        sentX = x; sentY = y; sentZ = z; sentYaw = yaw;
        io.tp(sel, x, y, z, yaw);
    }

    String summary() {
        return BotGear.TIER_NAMES[gear.tier("pickaxe")] + " pickaxe · " + inv.m.getOrDefault("log", 0) + " kayu · " + inv.m.getOrDefault("cobblestone", 0) + " batu · " + inv.m.getOrDefault("iron_ingot", 0) + " besi";
    }
}
