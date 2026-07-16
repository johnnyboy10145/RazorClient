package com.razorclient.runtime;

import com.razorclient.feature.module.Module;
import com.razorclient.combat.TargetPublicationService;

/** Runtime services exposed to one module without exposing shared mutable collections. */
public final class ModuleContext {
    private final Module module;
    private final RuntimeCore runtime;
    private final ModuleScope scope;

    public ModuleContext(Module module, RuntimeCore runtime, ModuleScope scope) {
        if (module == null || runtime == null || scope == null) throw new IllegalArgumentException();
        this.module = module;
        this.runtime = runtime;
        this.scope = scope;
    }

    public Module getModule() { return module; }
    public ModuleScope getScope() { return scope; }
    public OwnerToken getOwnerToken() { return scope.getOwnerToken(); }
    public TickContext getTick() { return runtime.getTickContext(); }
    public FrameContext getFrame() { return runtime.getFrameContext(); }
    public EntityFrame getEntities() { return runtime.getEntityFrame(); }
    public TargetPublicationService getTargetPublications() { return runtime.getTargetPublications(); }
    public boolean scheduleClientTask(Runnable action) {
        OwnerToken token = scope.getOwnerToken();
        return token != null && runtime.getClientSession().getScheduler().submit(token, action);
    }
    public boolean isClientThread() {
        return runtime.getClientSession().getScheduler().isClientThread();
    }
}
