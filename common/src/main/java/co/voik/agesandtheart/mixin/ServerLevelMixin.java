package co.voik.agesandtheart.mixin;

import co.voik.agesandtheart.age.phenomena.AgeWeather;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.WeatherData;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Gives an Age its own weather.
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
}
