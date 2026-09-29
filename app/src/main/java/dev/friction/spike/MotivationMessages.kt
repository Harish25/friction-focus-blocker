package dev.friction.spike

/** Original, local-only messages chosen once per pause, independent of photo/timer updates. */
object MotivationMessages {
    private val messages = listOf(
        "Your attention is yours to give.",
        "A small pause can make room for something meaningful.",
        "Take a breath. Choose what matters next.",
        "There is more to this moment than a screen.",
        "Make a little space for the life you want.",
        "You can begin again with this moment.",
        "Let this pause bring you back to what you care about.",
        "Small choices make time for the things you love.",
    )
    fun choose(): String = messages.random()
}
