package net.minecraftforge.client.event;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraftforge.fml.common.eventhandler.Event;
public class RenderGameOverlayEvent extends Event { public final float partialTicks; public final ScaledResolution resolution; protected RenderGameOverlayEvent(float p,ScaledResolution r){partialTicks=p;resolution=r;} public static final class Text extends RenderGameOverlayEvent { public Text(float p,ScaledResolution r){super(p,r);} } }
