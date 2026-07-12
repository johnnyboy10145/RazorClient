package net.minecraftforge.event.entity.living;
import net.minecraft.entity.EntityLivingBase;
import net.minecraftforge.fml.common.eventhandler.Event;
public class LivingEvent extends Event { public final EntityLivingBase entityLiving; protected LivingEvent(EntityLivingBase entity){entityLiving=entity;} public static final class LivingJumpEvent extends LivingEvent { public LivingJumpEvent(EntityLivingBase entity){super(entity);} } }
