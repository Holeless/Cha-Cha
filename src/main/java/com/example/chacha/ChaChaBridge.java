package com.example.chacha;

import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.client.settings.KeyBinding;
import net.minecraft.item.ItemBlock;
import net.minecraft.item.ItemStack;
import net.minecraft.util.AxisAlignedBB;
import net.minecraft.util.BlockPos;
import net.minecraft.util.ChatComponentText;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.MovingObjectPosition;
import net.minecraftforge.fml.client.registry.ClientRegistry;
import net.minecraftforge.fml.common.FMLCommonHandler;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.event.FMLInitializationEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.InputEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;
import org.lwjgl.input.Keyboard;

/**
 * Cha cha bridging helper for Forge 1.8.9 (client side only).  v1.2
 *
 * Keys:  V = mod on/off,  B = debug chat on/off,  N = switch mode (SMART / HOLD)
 *
 * Session:
 *  - Starts when you jump while moving backwards (away from where you look) with |pitch| in 64..74.
 *  - Ends (right click released) when |pitch| stays outside 64..74, you stay turned more than
 *    TURN_AROUND_DEGREES from the starting yaw, or you keep moving forward (relative to the starting
 *    direction). Short wobbles of a few ticks are ignored, so swinging your mouse to slow down is fine.
 *
 * Modes (during a session):
 *  - HOLD : once you're falling (past the jump apex) and your crosshair is on a block, right click
 *           is held until the session ends. (This is how v1.0 behaved.)
 *  - SMART: right click is held only while a click would put a block on or below your bridge row
 *           (never above it), into an empty spot that doesn't overlap you. Otherwise it releases.
 *
 * Turn on debug (B) and the mod prints in chat why it is or isn't pressing right click.
 */
@Mod(modid = "chachabridge", name = "ChaCha Bridge", version = "1.2",
        clientSideOnly = true, acceptedMinecraftVersions = "[1.8.9]")
public class ChaChaBridge {

    // ---- tweakables ----
    static final float PITCH_MIN = 64f;
    static final float PITCH_MAX = 74f;
    static final float TURN_AROUND_DEGREES = 120f;    // yaw change from where the session began that counts as turned around
    static final int PITCH_GRACE_TICKS = 3;           // ticks the pitch may sit outside the range before the session ends
    static final int TURN_GRACE_TICKS = 4;            // ticks you must stay turned around / moving forward before it ends
    static final double MIN_SPEED = 0.05;             // blocks/tick to count as moving
    static final double PLACE_BELOW_MOTION_Y = 0.0;   // in the air, only place once motionY <= this
    static final boolean ONLY_BEHIND = false;         // SMART: only place blocks behind you (can be too strict)

    private final Minecraft mc = Minecraft.getMinecraft();
    private final KeyBinding toggleKey = new KeyBinding("Toggle ChaCha Bridge", Keyboard.KEY_V, "ChaCha Bridge");
    private final KeyBinding debugKey  = new KeyBinding("ChaCha debug chat", Keyboard.KEY_B, "ChaCha Bridge");
    private final KeyBinding modeKey   = new KeyBinding("ChaCha mode (SMART/HOLD)", Keyboard.KEY_N, "ChaCha Bridge");

    private boolean enabled = true;
    private boolean smart = true;
    private boolean debug = false;
    private boolean session = false;
    private boolean latched = false;     // HOLD mode: first drop happened
    private boolean holding = false;
    private boolean wasOnGround = true;
    private int bridgeY = 0;
    private float startYaw = 0f;
    private int pitchBadTicks = 0, turnTicks = 0, fwdTicks = 0;
    private String lastDebug = "";

    @Mod.EventHandler
    public void init(FMLInitializationEvent e) {
        ClientRegistry.registerKeyBinding(toggleKey);
        ClientRegistry.registerKeyBinding(debugKey);
        ClientRegistry.registerKeyBinding(modeKey);
        FMLCommonHandler.instance().bus().register(this);
    }

    @SubscribeEvent
    public void onKey(InputEvent.KeyInputEvent e) {
        if (toggleKey.isPressed()) {
            enabled = !enabled;
            if (!enabled) endSession();
            say("\u00a7bChaCha Bridge: " + (enabled ? "\u00a7aON" : "\u00a7cOFF"));
        }
        if (debugKey.isPressed()) {
            debug = !debug;
            lastDebug = "";
            say("\u00a7bChaCha debug: " + (debug ? "\u00a7aON" : "\u00a7cOFF"));
        }
        if (modeKey.isPressed()) {
            smart = !smart;
            latched = false;
            say("\u00a7bChaCha mode: \u00a7e" + (smart ? "SMART" : "HOLD"));
        }
    }

    @SubscribeEvent
    public void onTick(TickEvent.ClientTickEvent e) {
        if (e.phase != TickEvent.Phase.END) return;
        EntityPlayerSP p = mc.thePlayer;
        if (p == null || mc.theWorld == null || mc.currentScreen != null || !enabled) {
            endSession();
            if (p != null) wasOnGround = p.onGround;
            return;
        }

        float absPitch = Math.abs(p.rotationPitch);
        boolean pitchOk = absPitch >= PITCH_MIN && absPitch <= PITCH_MAX;

        double dx = p.posX - p.prevPosX;
        double dz = p.posZ - p.prevPosZ;
        double speed = Math.sqrt(dx * dx + dz * dz);
        double yawRad = Math.toRadians(p.rotationYaw);
        double lookX = -Math.sin(yawRad);
        double lookZ = Math.cos(yawRad);
        double dot = speed > 1.0E-4 ? (dx * lookX + dz * lookZ) / speed : 0;
        boolean movingBackwards = speed > MIN_SPEED && dot < -0.5;

        boolean justJumped = wasOnGround && !p.onGround && p.motionY > 0;

        if (session) {
            float yawDiff = Math.abs(wrap(p.rotationYaw - startYaw));
            // "forward" is judged against the direction you were facing when the session began,
            // so swinging the mouse back and forth doesn't count as turning around.
            double sYaw = Math.toRadians(startYaw);
            double dotStart = speed > 1.0E-4 ? (dx * -Math.sin(sYaw) + dz * Math.cos(sYaw)) / speed : 0;
            boolean fwdFromStart = speed > MIN_SPEED && dotStart > 0.3;

            pitchBadTicks = pitchOk ? 0 : pitchBadTicks + 1;
            turnTicks = yawDiff > TURN_AROUND_DEGREES ? turnTicks + 1 : 0;
            fwdTicks = fwdFromStart ? fwdTicks + 1 : 0;

            String why = null;
            if (pitchBadTicks >= PITCH_GRACE_TICKS) why = "pitch " + (int) absPitch + " outside " + (int) PITCH_MIN + "-" + (int) PITCH_MAX;
            else if (turnTicks >= TURN_GRACE_TICKS) why = "turned " + (int) yawDiff + " deg from where you started";
            else if (fwdTicks >= TURN_GRACE_TICKS) why = "moving forward";

            if (why != null) {
                endSession();
                dbg("session ended: " + why);
            } else if (p.onGround) {
                bridgeY = floor(p.posY + 0.01) - 1;
            }
        } else if (justJumped) {
            if (pitchOk && movingBackwards) {
                session = true;
                latched = false;
                startYaw = p.rotationYaw;
                pitchBadTicks = 0; turnTicks = 0; fwdTicks = 0;
                bridgeY = floor(p.prevPosY + 0.01) - 1;
                dbg("session started (bridge row y=" + bridgeY + ", mode " + (smart ? "SMART" : "HOLD") + ")");
            } else {
                dbg("jump ignored: " + (!pitchOk ? "pitch " + (int) absPitch + " not in " + (int) PITCH_MIN + "-" + (int) PITCH_MAX
                        : "not moving backwards (speed " + String.format("%.2f", speed) + ", dot " + String.format("%.2f", dot) + ")"));
            }
        }

        wasOnGround = p.onGround;
        if (!session) return;

        ItemStack held = p.getHeldItem();
        boolean hasBlock = held != null && held.getItem() instanceof ItemBlock;
        boolean heightOk = p.onGround || p.motionY <= PLACE_BELOW_MOTION_Y;

        String reason;   // null = OK to press
        if (!hasBlock) {
            reason = "no block in hand";
        } else if (!pitchOk) {
            reason = "pitch " + (int) absPitch + " out of range (waiting)";
        } else if (smart) {
            reason = !heightOk ? "still rising" : checkPlacement(p, lookX, lookZ);
        } else {
            if (!latched && !p.onGround && p.motionY <= PLACE_BELOW_MOTION_Y) {
                MovingObjectPosition hit = p.rayTrace(mc.playerController.getBlockReachDistance(), 1.0f);
                if (hit != null && hit.typeOfHit == MovingObjectPosition.MovingObjectType.BLOCK) latched = true;
            }
            reason = latched ? null : "waiting for fall + block under crosshair";
        }

        setUse(reason == null);
        dbg(reason == null ? "HOLDING right click" : "released: " + reason);
    }

    /** null if a right click right now is a good bridge placement, otherwise the reason it isn't. */
    private String checkPlacement(EntityPlayerSP p, double lookX, double lookZ) {
        MovingObjectPosition hit = p.rayTrace(mc.playerController.getBlockReachDistance(), 1.0f);
        if (hit == null || hit.typeOfHit != MovingObjectPosition.MovingObjectType.BLOCK) return "crosshair on no block";

        EnumFacing side = hit.sideHit;
        BlockPos target = hit.getBlockPos().offset(side);
        if (target.getY() > bridgeY) return "would place above bridge row (face " + side + ", y " + target.getY() + " > " + bridgeY + ")";
        if (!mc.theWorld.isAirBlock(target)) return "target spot already filled";

        AxisAlignedBB box = new AxisAlignedBB(target, target.add(1, 1, 1));
        if (box.intersectsWith(p.getEntityBoundingBox())) return "target overlaps your hitbox";

        if (ONLY_BEHIND) {
            double rx = target.getX() + 0.5 - p.posX;
            double rz = target.getZ() + 0.5 - p.posZ;
            if (rx * lookX + rz * lookZ > 0) return "target not behind you";
        }
        return null;
    }

    private void setUse(boolean down) {
        if (down == holding) return;
        holding = down;
        KeyBinding.setKeyBindState(mc.gameSettings.keyBindUseItem.getKeyCode(), down);
    }

    private void endSession() {
        if (holding) setUse(false);
        session = false;
        latched = false;
        pitchBadTicks = 0; turnTicks = 0; fwdTicks = 0;
    }

    private void say(String s) {
        if (mc.thePlayer != null) mc.thePlayer.addChatMessage(new ChatComponentText(s));
    }

    /** Prints only when the message changes, so chat isn't spammed every tick. */
    private void dbg(String s) {
        if (!debug || s.equals(lastDebug)) return;
        lastDebug = s;
        say("\u00a78[CC] \u00a77" + s);
    }

    private static int floor(double v) {
        int i = (int) v;
        return v < i ? i - 1 : i;
    }

    private static float wrap(float deg) {
        deg %= 360f;
        if (deg >= 180f) deg -= 360f;
        if (deg < -180f) deg += 360f;
        return deg;
    }
}
