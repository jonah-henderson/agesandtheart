package co.voik.agesandtheart.mixin;

import net.minecraft.CrashReport;
import net.minecraft.util.thread.BlockableEventLoop;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Says a delayed crash out loud, at the moment it happens.
 *
 * <p><b>It earned its place the day it was written</b> (2026-09-22): the 26.3 port's generation failure had
 * been reduced to a server that hung with one misleading line in the log, and this turned it into a named
 * exception on a line of ours in the first run — a two-int argument swap in {@code CarvingMask}.
 *
 * <p><b>What it is for.</b> When a chunk generation step throws, {@code GenerationChunkHolder} wraps the
 * throwable in a crash report, hands it to {@link BlockableEventLoop#relayDelayCrash} and returns — without
 * completing that status's future. The crash is meant to be re-thrown the next time the main thread runs a
 * task. But the main thread is typically *blocked inside* {@code getChunk} waiting for the very chunk that
 * just failed, so it never runs another task: the server hangs, the real exception is never printed, and
 * the only thing that reaches the log is the next layer tripping over the status that was never completed
 * — {@code IllegalStateException: Parent chunk missing}, from a stack that contains nothing of ours.
 *
 * <p>So this logs the report where it is raised rather than where it would have been re-thrown. It changes
 * no behaviour: the relay still happens exactly as before.
 */
@Mixin(BlockableEventLoop.class)
public class DelayedCrashMixin {

    private static final Logger AGESANDTHEART$LOG =
            LoggerFactory.getLogger("AgesAndTheArt/delayed-crash");

    @Inject(method = "relayDelayCrash", at = @At("HEAD"))
    private static void agesandtheart$sayItWhereItHappened(CrashReport report, CallbackInfo callback) {
        AGESANDTHEART$LOG.error(
                "DELAYED CRASH, said early by instrumentation — {}", report.getTitle(), report.getException());
    }
}
