/*
 * This file is licensed under the MIT License (MIT).
 *
 * Copyright (c) 2014 Daniel Ennis <http://aikar.co>
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */
package org.spigotmc;

import io.papermc.paper.plugin.provider.classloader.ConfiguredPluginClassLoader;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import org.bukkit.Bukkit;
import org.bukkit.Warning;
import org.bukkit.plugin.AuthorNagException;

// Leaf start - Remove Timings
/**
 * @deprecated Timings has been removed
 * Keep this class for plugin compatibility (detect usage)
 */
@Deprecated(forRemoval = true)
public final class CustomTimingsHandler {
    private static final StackWalker WALKER = StackWalker.getInstance(StackWalker.Option.RETAIN_CLASS_REFERENCE);
    // Plugins such as libraries create a handler per listener or task, so the nag is reported once per plugin.
    private static final Set<String> NAGGED = ConcurrentHashMap.newKeySet();

    public CustomTimingsHandler(final String name) {
        nag();
    }

    public void startTiming() {}
    public void stopTiming() {}

    private static void nag() {
        final Warning.WarningState state;
        try {
            state = Bukkit.getWarningState();
        } catch (final RuntimeException e) {
            return; // no server (e.g. unit tests)
        }
        if (state == Warning.WarningState.OFF) {
            return;
        }
        final String plugin = callingPlugin().orElse("unknown plugin");
        if (!NAGGED.add(plugin)) {
            return;
        }
        final String message = "\"" + plugin + "\" uses the deprecated CustomTimingsHandler; timings have been removed, so it does nothing. Please notify the plugin's authors.";
        if (state == Warning.WarningState.ON) {
            Bukkit.getLogger().log(Level.WARNING, message, new AuthorNagException(message));
        } else {
            Bukkit.getLogger().warning(message);
        }
    }

    private static Optional<String> callingPlugin() {
        return WALKER.walk(frames -> frames
            .map(StackWalker.StackFrame::getDeclaringClass)
            .filter(c -> c.getClassLoader() instanceof ConfiguredPluginClassLoader)
            .map(c -> ((ConfiguredPluginClassLoader) c.getClassLoader()).getConfiguration().getName())
            .findFirst());
    }
    // Leaf end - Remove Timings
}
