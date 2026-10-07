package co.voik.agesandtheart

import co.voik.agesandtheart.platform.Services
import io.netty.buffer.ByteBuf
import net.minecraft.ChatFormatting
import net.minecraft.network.chat.Component
import net.minecraft.network.codec.ByteBufCodecs
import net.minecraft.network.codec.StreamCodec
import net.minecraft.network.protocol.common.custom.CustomPacketPayload
import net.minecraft.server.network.ConfigurationTask
import java.util.Properties

/**
 * Turns a joining player away when their build of the mod cannot play with the server's, saying what to do
 * about it. The loader asks during connection configuration, before the world is touched.
 *
 * Builds play together when their releases share a [Release.compatibilityLine]: patch and minor releases
 * mix, major ones do not, and while the major version is 0 the minor version is the breaking one (semver's
 * own rule for 0.x). Ephemeris, shipped beside it as its own mod, must match exactly. A different Minecraft
 * version never gets this far; vanilla refuses it first.
 */
object BuildMatch {
    /** This jar's build, `0.3.1+26.3.dev4.abc1234.dirty`, written by `multiloader-common` from git tags. */
    val BUILD: String = readBuild()

    /** What this side is running. */
    val OURS: Builds by lazy { Builds(BUILD, Services.PLATFORM.modVersion(EPHEMERIS_ID) ?: NO_EPHEMERIS) }

    /** The configuration step a joining player waits on until their build has been answered. */
    val TASK = ConfigurationTask.Type("${Constants.MOD_ID}:build_match")

    /** For a client without the mod, which has no lang file of ours, hence plain text. */
    val NOT_INSTALLED: Component = Component.literal(
        "This server runs Ages and the Art.\n\n" +
            "Start Minecraft from the Prism instance you were given, and it will set everything up.",
    )

    enum class Comparison { PLAYS_TOGETHER, CLIENT_IS_BEHIND, CLIENT_IS_AHEAD, UNRELATED }

    /** How a [client] build stands against a [server] one. Builds with no release in them must match exactly. */
    fun compare(server: String, client: String): Comparison {
        val serverRelease = Release.of(server)
        val clientRelease = Release.of(client)
        if (serverRelease == null || clientRelease == null) {
            return if (server == client) Comparison.PLAYS_TOGETHER else Comparison.UNRELATED
        }
        return when {
            serverRelease.compatibilityLine == clientRelease.compatibilityLine -> Comparison.PLAYS_TOGETHER
            clientRelease < serverRelease -> Comparison.CLIENT_IS_BEHIND
            else -> Comparison.CLIENT_IS_AHEAD
        }
    }

    /** Why [client] may not join a server running [server], or null when it may. Plain text, as above. */
    fun refusal(server: Builds, client: Builds): Component? {
        val ephemerisDiffers = server.ephemeris != client.ephemeris
        val modComparison = compare(server.mod, client.mod)
        val comparison = if (modComparison == Comparison.PLAYS_TOGETHER && ephemerisDiffers) {
            Comparison.UNRELATED
        } else {
            modComparison
        }
        val explanation = when (comparison) {
            Comparison.PLAYS_TOGETHER -> return null
            Comparison.CLIENT_IS_BEHIND ->
                "Your copy of Ages and the Art is older than the server's.\n\n" +
                    "Import the newest Prism instance you were sent, and play from that one."
            Comparison.CLIENT_IS_AHEAD ->
                "The server is running an older Ages and the Art than yours.\n\n" +
                    "It hasn't been updated yet, so let whoever runs it know."
            Comparison.UNRELATED ->
                "Your copy of Ages and the Art doesn't match the server's.\n\n" +
                    "Play from the newest Prism instance you were sent.\n" +
                    "If you already are, let whoever runs the server know."
        }
        return Component.literal("$explanation\n\n")
            .append(Component.literal("Yours: $client\nServer: $server").withStyle(ChatFormatting.GRAY))
    }

    private const val EPHEMERIS_ID = "ephemeris"
    private const val NO_EPHEMERIS = "none"

    private fun readBuild(): String {
        val properties = Properties()
        BuildMatch::class.java.getResourceAsStream(BUILD_RESOURCE)?.use(properties::load)
        return properties.getProperty("build") ?: UNKNOWN_BUILD
    }

    private const val BUILD_RESOURCE = "/${Constants.MOD_ID}-build.properties"
    private const val UNKNOWN_BUILD = "unknown"
}

/** The `major.minor.patch` a build was released as, read off the front of the build. */
data class Release(val major: Int, val minor: Int, val patch: Int) : Comparable<Release> {
    /** What two releases must share to play together: the major version, or the minor while major is 0. */
    val compatibilityLine: Pair<Int, Int> get() = if (major == 0) major to minor else major to 0

    override fun compareTo(other: Release): Int = compareValuesBy(this, other, Release::major, Release::minor, Release::patch)

    companion object {
        private val RELEASE = Regex("""^(\d+)\.(\d+)\.(\d+)(\+.*)?$""")

        /** The release in [build], or null when it does not start with one. */
        fun of(build: String): Release? {
            val (major, minor, patch) = RELEASE.matchEntire(build)?.destructured ?: return null
            return Release(major.toInt(), minor.toInt(), patch.toInt())
        }
    }
}

/** This mod's build and Ephemeris' version, as one side of a connection runs them. */
data class Builds(val mod: String, val ephemeris: String) {
    override fun toString() = "$mod (Ephemeris $ephemeris)"

    companion object {
        val STREAM_CODEC: StreamCodec<ByteBuf, Builds> = StreamCodec.composite(
            ByteBufCodecs.STRING_UTF8, Builds::mod,
            ByteBufCodecs.STRING_UTF8, Builds::ephemeris,
            ::Builds,
        )
    }
}

/** The server's builds, sent to a joining client as the question. */
data class ServerBuildPayload(val builds: Builds) : CustomPacketPayload {
    override fun type(): CustomPacketPayload.Type<ServerBuildPayload> = TYPE

    companion object {
        val TYPE = CustomPacketPayload.Type<ServerBuildPayload>("server_build".location())
        val STREAM_CODEC: StreamCodec<ByteBuf, ServerBuildPayload> =
            Builds.STREAM_CODEC.map(::ServerBuildPayload, ServerBuildPayload::builds)
    }
}

/** The client's builds, sent back as the answer. */
data class ClientBuildPayload(val builds: Builds) : CustomPacketPayload {
    override fun type(): CustomPacketPayload.Type<ClientBuildPayload> = TYPE

    companion object {
        val TYPE = CustomPacketPayload.Type<ClientBuildPayload>("client_build".location())
        val STREAM_CODEC: StreamCodec<ByteBuf, ClientBuildPayload> =
            Builds.STREAM_CODEC.map(::ClientBuildPayload, ClientBuildPayload::builds)
    }
}
