package io.github.hugo_cacciatori.basedinformant.core.settings

/** Which topics are searched for, through which sources, and how they are reported (ai-call.md section 4). */
enum class AttentionMode { HOTTEST, BALANCED, NICHE }

/**
 * The voice the AI writes in (product.md section 9). [voiceFile] names the file in `core/prompt/voices/`;
 * [OFF] uses the lens voice once lenses exist, and the neutral voice until then.
 */
enum class Persona(val voiceFile: String) {
    OFF("neutral"),
    ROACH_KING("roach-king"),
    DOCTOR_WIFE("doctor-wife"),
    DOG_ZAPPER("dog-zapper"),
}
