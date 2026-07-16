package com.razorclient.runtime.capability;

import com.razorclient.runtime.FrameContext;
import com.razorclient.runtime.ModuleContext;

public interface RenderListener {
    enum Phase { FRAME, WORLD, OVERLAY }
    void onRender(ModuleContext context, FrameContext frame, Phase phase);
}
