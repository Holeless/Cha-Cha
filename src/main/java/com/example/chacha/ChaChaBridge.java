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
 * Cha cha bridging helper for Forge 1.8.9 (client side only).
 *
 * Session:
 *  - Starts when you jump while moving backwards (away from where you look) with |pitch| in 64..74.
 *  - Ends (and right click is released) when |pitch| leaves 64..74, you turn more than
 *    TURN_AROUND_DEGREES away from the starting yaw, or you start moving forward.
 *
 * During a session right click is held ONLY on ticks where the click would be a good placement,
 * which is worked out from where your crosshair ray actually lands:
 *  - it hits the side face of a block (never a top/bottom face),
 *  - the new block would sit exactly on the bridge row (one below your feet level),
 *  - that spot is empty, is behind you, and doesn't overlap your hitbox,
 *  - and you're either on the ground or already falling (not still rising).
 * Because a failed click in 1.8.9 still burns the 4 tick right-click delay, skipping bad
 * moments means every click that does fire is a real placement.
 */
@Mod(modid = "chachabridge", name = "ChaCha Bridge", version = "1.1",
        clientSideOnly = true, acceptedMinecraftVersions = "[1.8.9]")
public class ChaChaBridge {

    // ---- tweakables ----
    static final float PITCH_MIN = 64f;
    static final float PITCH_MAX = 74f;
    static final float TURN_AROUND_DEGREES = 90f;
    static final double MIN_SPEED = 0.05;             // blocks/tick to count as moving
    static final double PLACE_BELOW_MOTION_Y = 0.0;   // in the air, only place once motionY <= this. Use 10 to allow rising placements.
    static final boolean ONLY_BEHIND = true;          // only place blocks behind you (not to the sides)

    private final Minecraft mc = Minecraft.getMinecraft();
    private final KeyBinding toggleKey = new KeyBinding("Toggle ChaCha Bridge", Keyboard.KEY_V, "ChaCha Bridge");

    private boolean enabled = true;
    private boolean session = false;
    private boolean holding = false;
    private boolean wasOnGround = true;
    private int bridgeY = 0;
    private float startYaw = 0f;

    @Mod.EventHandler
    public void init(FMLInitializationEvent e) {
        ClientRegistry.registerKeyBinding(toggleKey);
        FMLCommonHandler.instance().bus().register(this);
    }

    @SubscribeEvent
    public void onKey(InputEvent.KeyInputEvent e) {
        if (toggleKey.isPressed()) {
            enabled = !enabled;
            if (!enabled) endSession();
            if (mc.thePlayer != null) {
                mc.thePlayer.addChatMessage(new ChatComponentText(
                        "\u00a7bChaCha Bridge: " + (enabled ? "\u00a7aON" : "\u00a7cOFF")));
            }
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
        boolean movingForwards = speed > MIN_SPEED && dot > 0.3;

        boolean justJumped = wasOnGround && !p.onGround && p.motionY > 0;

        if (session) {
            float yawDiff = Math.abs(wrap(p.rotationYaw - startYaw));
            if (!pitchOk || yawDiff > TURN_AROUND_DEGREES || movingForwards) {
                endSession();
            } else if (p.onGround) {
                bridgeY = MathHelperFloor(p.posY + 0.01) - 1;
            }
        } else if (justJumped && pitchOk && movingBackwards) {
            session = true;
            startYaw = p.rotationYaw;
            bridgeY = MathHelperFloor(p.prevPosY + 0.01) - 1;
        }

        wasOnGround = p.onGround;
        if (!session) return;

        ItemStack held = p.getHeldItem();
        boolean hasBlock = held != null && held.getItem() instanceof ItemBlock;
        boolean heightOk = p.onGround || p.motionY <= PLACE_BELOW_MOTION_Y;

        setUse(hasBlock && heightOk && validPlacement(p, lookX, lookZ));
    }

    /** True if a right click right now would put a block on the bridge row. */
    private boolean validPlacement(EntityPlayerSP p, double lookX, double lookZ) {
        // Own ray trace from the current eye position/rotation (mc.objectMouseOver is a tick stale).
        MovingObjectPosition hit = p.rayTrace(mc.playerController.getBlockReachDistance(), 1.0f);
        if (hit == null || hit.typeOfHit != MovingObjectPosition.MovingObjectType.BLOCK) return false;

        EnumFacing side = hit.sideHit;
        if (side.getAxis() == EnumFacing.Axis.Y) return false;           // top/bottom face = wrong spot

        BlockPos target = hit.getBlockPos().offset(side);
        if (target.getY() != bridgeY) return false;                       // must be the bridge row
        if (!mc.theWorld.isAirBlock(target)) return false;                // already filled

        AxisAlignedBB box = new AxisAlignedBB(target, target.add(1, 1, 1));
        if (box.intersectsWith(p.getEntityBoundingBox())) return false;   // would be blocked by you

        if (ONLY_BEHIND) {
            double rx = target.getX() + 0.5 - p.posX;
            double rz = target.getZ() + 0.5 - p.posZ;
            if (rx * lookX + rz * lookZ > 0) return false;
        }
        return true;
    }

    private void setUse(boolean down) {
        if (down == holding) return;
        holding = down;
        KeyBinding.setKeyBindState(mc.gameSettings.keyBindUseItem.getKeyCode(), down);
    }

    private void endSession() {
        if (holding) setUse(false);
        session = false;
    }

    private static int MathHelperFloor(double v) {
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
