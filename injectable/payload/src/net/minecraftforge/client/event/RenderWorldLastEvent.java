package net.minecraftforge.client.event;
import net.minecraftforge.fml.common.eventhandler.Event;
public final class RenderWorldLastEvent extends Event { public final float partialTicks; public RenderWorldLastEvent(float partialTicks){this.partialTicks=partialTicks;} }
