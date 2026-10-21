package co.voik.agesandtheart.age

import co.voik.agesandtheart.age.aspect.Aspect
import com.mojang.serialization.Codec
import com.mojang.serialization.DataResult
import com.mojang.serialization.codecs.RecordCodecBuilder

/**
 * What a link into an Age arrives at — `link in jungle`, `link in bastion`, `link in sea`: a part of the world and the
 * members of it named, none meaning any of it.
 */
data class LinkAnchor(val aspect: Aspect, val members: Set<String> = emptySet()) {
    companion object {
        private val ASPECT: Codec<Aspect> = Codec.STRING.comapFlatMap(
            { key -> Aspect.entries.firstOrNull { it.key == key }?.let { DataResult.success(it) } ?: DataResult.error { "'$key' is no part of the world" } },
            Aspect::key,
        )

        val CODEC: Codec<LinkAnchor> = RecordCodecBuilder.create { instance ->
            instance.group(
                ASPECT.fieldOf("aspect").forGetter(LinkAnchor::aspect),
                Codec.STRING.listOf().xmap({ it.toSet() }, { it.sorted() })
                    .optionalFieldOf("members", emptySet()).forGetter(LinkAnchor::members),
            ).apply(instance, ::LinkAnchor)
        }
    }
}
