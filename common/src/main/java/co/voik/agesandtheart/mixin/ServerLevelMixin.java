package co.voik.agesandtheart.mixin;

import co.voik.agesandtheart.age.consequence.Hostility;
import co.voik.agesandtheart.age.phenomena.AgeWeather;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.DifficultyInstance;
import net.minecraft.world.level.saveddata.WeatherData;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Gives an Age its own weather, and lets a wound make the ground around it dangerous.
 *
 * <p><b>26.1 moved the weather <i>schedule</i> onto the server.</b> {@code WeatherData} — the rain, thunder
 * and clear timers with their flags — is one object on {@code MinecraftServer}, and {@code
 * ServerLevel.getWeatherData()} does nothing but return it. Everything a level then <i>does</i> with weather
 * is already per level: {@code rainLevel} and {@code thunderLevel} are fields on {@code Level},
 * {@code isRaining()} and {@code isThundering()} read those and not the schedule, and
 * {@code advanceWeatherCycle} broadcasts its changes with {@code broadcastAll(packet, dimension())}. So the
 * only reason every dimension agrees about the weather is that they all compute from one schedule.
 *
 * <p>Hand an Age its own and the rest follows with no further code: vanilla runs the cycle, moves the
 * timers, interpolates the levels and tells that dimension's players. {@code isRainingAt}, mob spawning,
 * {@code tickThunder}, snow and ice, crops and {@code /weather} all come along, and <b>the client needs
 * nothing at all</b>.
 *
 * <p><b>Why a mixin.</b> {@code setRainLevel} and {@code setThunderLevel} are public, so the <i>state</i>
 * could be overwritten every tick without one — but that fights {@code advanceWeatherCycle} on every tick,
 * sends two packets where one would do, and still cannot own the timers, so the natural cycle and
 * {@code /weather} would stay global. No loader event carries which weather data a level uses, and a mixin
 * on {@code MinecraftServer.getWeatherData} would be broader and would not know the calling level. This
 * method is the narrowest seam there is: two classes in the game read it.
 *
 * <p>Worth knowing: vanilla has <i>every</i> level decrementing the same shared timers, so weather cycles
 * faster the more dimensions are loaded. Taking Ages out of that is, if anything, more correct.
 */
@Mixin(ServerLevel.class)
public abstract class ServerLevelMixin {

    @Inject(method = "getWeatherData", at = @At("HEAD"), cancellable = true)
    private void agesandtheart$ownWeather(CallbackInfoReturnable<WeatherData> callback) {
        WeatherData own = AgeWeather.of((ServerLevel) (Object) this);
        if (own != null) {
            callback.setReturnValue(own);
        }
    }

    /**
     * Local difficulty near a wound (design §5.1).
     *
     * <p><b>Why a mixin.</b> There is no event for this on either loader — NeoForge's
     * {@code DifficultyChangeEvent} fires when the <i>world's</i> difficulty setting changes and knows
     * nothing of a position, and Fabric has nothing at all. Nor is there an object to substitute the way
     * {@code WeatherData} could be: local difficulty is computed on demand and returned by value, so this
     * one method is the only place it exists. Everything downstream — mob equipment, zombie reinforcements,
     * husk and drowned conversion — reads it through here.
     *
     * <p><b>At {@code RETURN}, so vanilla decides first.</b> {@link Hostility} needs what the place would
     * have been to raise it rather than replace it, and injecting at the head would mean recomputing the
     * three terms a wound has no business touching.
     */
    @Inject(method = "getCurrentDifficultyAt", at = @At("RETURN"), cancellable = true)
    private void agesandtheart$harderNearAWound(BlockPos at, CallbackInfoReturnable<DifficultyInstance> callback) {
        DifficultyInstance harder = Hostility.localDifficultyAt((ServerLevel) (Object) this, at, callback.getReturnValue());
        if (harder != null) {
            callback.setReturnValue(harder);
        }
    }
}
