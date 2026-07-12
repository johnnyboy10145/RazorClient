package net.minecraftforge.fml.common;
import net.minecraftforge.fml.common.eventhandler.EventBus;
public final class FMLCommonHandler { private static final FMLCommonHandler INSTANCE = new FMLCommonHandler(); private final EventBus bus = new EventBus(); public static FMLCommonHandler instance(){return INSTANCE;} public EventBus bus(){return bus;} }
