# ai-ui

Android chat client for LLM providers. Kotlin, Jetpack Compose, Material 3.

## Principles

These are fixed. If a request seems to conflict with one, say so before building it.

1. **Minimal design.** Every element on screen has to earn its place. Prefer removing
   something over adding something. No decorative gradients, badges, or extra chrome.

2. **Android native.** Kotlin, Jetpack Compose, Material 3 and AndroidX only. No
   cross-platform layers or WebViews for UI. Follow platform conventions (system back,
   edge-to-edge, dynamic colour, system typography) instead of inventing custom ones.

3. **Clean UX, like a first-party client from a big company.** One clear primary action
   per screen. Sensible defaults over settings. Advanced options stay out of the main
   path. Consistent spacing, type and wording across screens. No cluttered toolbars,
   option dumps, or power-user toggles on the main surface.

4. **BDD tests with `// given`, `// when`, `// then` comments.** Follow the
   `/write-tests` skill: test behaviour not implementation, one scenario per test,
   real implementations over mocks. Test names read as `situation_expectedOutcome`.
   Omit a section only when it is truly empty. Example:

   ```kotlin
   @Test
   fun plainHttpToPublicHost_isBlockedBeforeAnyConnection() {
       // given
       val client = buildHttpClient()
       val request = Request.Builder().url("http://203.0.113.10/v1/models").build()

       // when
       val error = runCatching { client.newCall(request).execute() }.exceptionOrNull()

       // then
       assertTrue(error is CleartextBlockedException)
   }
   ```

5. **Iterative, evolutionary code and UI.** Build the smallest thing that works, then
   evolve it. No speculative abstractions, layers, or config for needs we don't have
   yet. Refactor when a second real use appears. UI changes are small steps on the
   existing design, not redesigns.

## Commands

- Unit tests: `./gradlew testDebugUnitTest`
- Debug build: `./gradlew assembleDebug`
