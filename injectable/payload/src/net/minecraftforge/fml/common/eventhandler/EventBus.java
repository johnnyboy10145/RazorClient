package net.minecraftforge.fml.common.eventhandler;

import com.razorclient.inject.AgentLog;
import java.lang.reflect.Method;
import java.util.concurrent.CopyOnWriteArrayList;

public final class EventBus {
    private final CopyOnWriteArrayList<Object> listeners = new CopyOnWriteArrayList<>();
    public void register(Object listener) { if (!listeners.contains(listener)) listeners.add(listener); }
    public void unregister(Object listener) { listeners.remove(listener); }
    public boolean post(Event event) {
        for (EventPriority priority : EventPriority.values()) for (Object listener : listeners) {
            for (Method method : listener.getClass().getDeclaredMethods()) {
                SubscribeEvent annotation = method.getAnnotation(SubscribeEvent.class);
                if (annotation == null || annotation.priority() != priority || method.getParameterCount() != 1 || !method.getParameterTypes()[0].isInstance(event)) continue;
                try { method.setAccessible(true); method.invoke(listener, event); }
                catch (ReflectiveOperationException failure) { AgentLog.error("Event listener failed: " + method, failure); }
            }
        }
        return event.isCanceled();
    }
}
