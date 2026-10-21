package com.example.chacha;

import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.client.settings.KeyBinding;
import net.minecraft.item.ItemBlock;
import net.minecraft.item.ItemStack;
import net.minecraft.util.MovingObjectPosition;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.client.registry.ClientRegistry;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.event.FMLInitializationEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.InputEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;
import org.lwjgl.input.Keyboard;

/**
 * Cha cha bridging helper for Forge 1.8.9 (client side only).
 *
 * Flow:
 *  1. You jump while moving backwards (away from where you look) with |pitch| in 64..74.
 *     -> a bridging "session" starts.
 *  2. Once you're past the apex of that jump (falling), the mod starts holding right click
 *     and KEEPS holding it (it never releases between jumps).
 *  3. The session ends, and right click is released, when |pitch| leaves 64..74, you turn
 *     around (yaw changes by more than TURN_AROUND_DEGREES from where the session began),
 *     or you start moving forward.
 */
@Mod(modid = "chachabridge", name = "ChaCha Bridge", version = "1.0",
        clientSideOnly = true, acceptedMinecraftVersions = "[1.8.9]")
public class ChaChaBridge {

    // ---- tweakables ----
    static final float PITCH_MIN = 64f;
    static final float PITCH_MAX = 74f;
    static final double PLACE_BELOW_MOTION_Y = 0.0;   // start holding once motionY <= this (past apex). Raise (e.g. 0.1) to place earlier.
    static final float TURN_AROUND_DEGREES = 90f;
    static final double MIN_SPEED = 0.05;             // blocks/tick needed to count as "moving"

    private final Minecraft mc = Minecraft.getMinecraft();
    private final KeyBinding toggleKey = new KeyBinding("Toggle ChaCha Bridge", Keyboard.KEY_V, "ChaCha Bridge");

    private boolean enabled = true;
    private boolean session = false;
    private boolean latched = false;     // first drop has happened; hold until session ends
    private boolean holding = false;     // we are currently forcing right click
    private boolean wasOnGround = true;
    private float startYaw = 0f;

    @Mod.EventHandler
    public void init(FMLInitializationEvent e) {
        ClientRegistry.registerKeyBinding(toggleKey);
        MinecraftForge.EVENT_BUS.register(this);
        net.minecraftforge.fml.common.FMLCommonHandler.instance().bus().register(this);
    }

    @SubscribeEvent
    public void onKey(InputEvent.KeyInputEvent e) {
        if (toggleKey.isPressed()) {
            enabled = !enabled;
            if (!enabled) endSession();
            if (mc.thePlayer != null) {
                mc.thePlayer.addChatMessage(new net.minecraft.util.ChatComponentText(
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

        // horizontal movement relative to where we're looking
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
            }
        } else if (justJumped && pitchOk && movingBackwards) {
            session = true;
            latched = false;
            startYaw = p.rotationYaw;
        }

        wasOnGround = p.onGround;
        if (!session) return;

        ItemStack held = p.getHeldItem();
        boolean hasBlock = held != null && held.getItem() instanceof ItemBlock;

        // first trigger: past the apex of the jump and aimed at a block
        if (!latched) {
            boolean lookingAtBlock = mc.objectMouseOver != null
                    && mc.objectMouseOver.typeOfHit == MovingObjectPosition.MovingObjectType.BLOCK;
            if (hasBlock && !p.onGround && p.motionY <= PLACE_BELOW_MOTION_Y && lookingAtBlock) {
                latched = true;
            }
        }

        // once latched, keep holding right click for the rest of the session
        setUse(latched && hasBlock);
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
    }

    private static float wrap(float deg) {
        deg %= 360f;
        if (deg >= 180f) deg -= 360f;
        if (deg < -180f) deg += 360f;
        return deg;
    }
}
