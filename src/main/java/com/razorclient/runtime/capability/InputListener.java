package com.razorclient.runtime.capability;

import com.razorclient.feature.module.ModuleResetReason;
import com.razorclient.runtime.ModuleContext;

public interface InputListener {
    void onInputUnavailable(ModuleContext context, ModuleResetReason reason);
}
