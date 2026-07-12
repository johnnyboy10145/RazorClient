package net.minecraftforge.fml.common;
import java.lang.reflect.Field;
public final class ObfuscationReflectionHelper {
    public static <T,E> void setPrivateValue(Class<? super T> type,T instance,E value,String... names){try{Field f=findField(type,names);f.setAccessible(true);f.set(instance,value);}catch(ReflectiveOperationException e){throw new IllegalStateException(e);}}
    @SuppressWarnings("unchecked")
    public static <T,E> E getPrivateValue(Class<? super T> type,T instance,String... names){try{Field f=findField(type,names);f.setAccessible(true);return (E)f.get(instance);}catch(ReflectiveOperationException e){throw new IllegalStateException(e);}}
    private static Field findField(Class<?> type,String[] names)throws NoSuchFieldException{for(String n:names){try{return type.getDeclaredField(n);}catch(NoSuchFieldException ignored){}}throw new NoSuchFieldException(String.join(",",names));}
    private ObfuscationReflectionHelper(){}
}
