package net.minecraftforge.fml.common.gameevent;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraftforge.fml.common.eventhandler.Event;
public class TickEvent extends Event {
    public enum Phase { START, END }
    public final Phase phase;
    protected TickEvent(Phase phase){this.phase=phase;}
    public static final class ClientTickEvent extends TickEvent { public ClientTickEvent(Phase phase){super(phase);} }
    public static final class RenderTickEvent extends TickEvent { public final float renderTickTime; public RenderTickEvent(Phase phase,float time){super(phase);this.renderTickTime=time;} }
    public static final class PlayerTickEvent extends TickEvent { public final EntityPlayer player; public PlayerTickEvent(Phase phase,EntityPlayer player){super(phase);this.player=player;} }
}
