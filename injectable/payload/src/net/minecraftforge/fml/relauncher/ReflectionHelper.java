package net.minecraftforge.fml.relauncher;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
public final class ReflectionHelper {
    public static Field findField(Class<?> type,String... names){for(String n:names)try{Field f=type.getDeclaredField(n);f.setAccessible(true);return f;}catch(NoSuchFieldException ignored){}throw new IllegalStateException("Missing field "+String.join(",",names));}
    public static Method findMethod(Class<?> type,Object instance,String[] names,Class<?>... parameters){for(String n:names)try{Method m=type.getDeclaredMethod(n,parameters);m.setAccessible(true);return m;}catch(NoSuchMethodException ignored){}throw new IllegalStateException("Missing method "+String.join(",",names));}
    private ReflectionHelper(){}
}
