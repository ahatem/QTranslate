package com.github.ahatem.qtranslate.core.main.domain.usecase

import com.github.ahatem.qtranslate.api.dictionary.DictionaryEntry
import com.github.ahatem.qtranslate.api.imagesearch.ImageResult
import com.github.ahatem.qtranslate.api.language.LanguageCode
import com.github.ahatem.qtranslate.api.plugin.NotificationType
import com.github.ahatem.qtranslate.core.shared.StatusCode
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalCoroutinesApi::class)
class LookupSessionsTest {
    @Test fun `dictionary retains old results while loading and suppresses superseded completion`() = runTest {
        val releases = listOf(CompletableDeferred<Unit>(), CompletableDeferred())
        var count = 0
        var visible = listOf(DictionaryEntry("existing", "", emptyList()))
        val started = mutableListOf<String>()
        val session = DictionaryLookupSession(this, { request, onStarted ->
            val index = count++
            onStarted()
            withContext(NonCancellable) { releases[index].await() }
            DictionaryLookupOutcome(StatusCode.DictionaryReady, NotificationType.INFO,
                listOf(DictionaryEntry(request.word, "", emptyList())), false)
        }, { request, publish -> publish { started += request.word } },
            { outcome, publish -> publish { visible = outcome.entries!! } })
        session.start(DictionaryLookupRequest("old", LanguageCode.ENGLISH, null)); runCurrent()
        assertEquals("existing", visible.single().word)
        session.start(DictionaryLookupRequest("new", LanguageCode.ENGLISH, null)); runCurrent()
        releases[0].complete(Unit); runCurrent()
        assertEquals("existing", visible.single().word)
        releases[1].complete(Unit); runCurrent()
        assertEquals("new", visible.single().word)
        assertEquals(listOf("old", "new"), started)
    }

    @Test fun `image lookup retains old results and cancel suppresses late completion`() = runTest {
        val releases = listOf(CompletableDeferred<Unit>(), CompletableDeferred(), CompletableDeferred())
        var count = 0
        var visible = listOf(ImageResult("existing", "existing"))
        val session = ImageLookupSession(this, { request, onStarted ->
            val index = count++
            onStarted()
            withContext(NonCancellable) { releases[index].await() }
            ImageLookupOutcome(StatusCode.ImageSearchReady, NotificationType.INFO,
                listOf(ImageResult(request.term, request.term)), false)
        }, { _, _ -> }, { outcome, publish -> publish { visible = outcome.results!! } })
        session.start(ImageLookupRequest("old", LanguageCode.ENGLISH)); runCurrent()
        session.start(ImageLookupRequest("new", LanguageCode.ENGLISH)); runCurrent()
        releases[0].complete(Unit); runCurrent()
        assertEquals("existing", visible.single().thumbnailUrl)
        releases[1].complete(Unit); runCurrent()
        assertEquals("new", visible.single().thumbnailUrl)
        session.start(ImageLookupRequest("cancelled", LanguageCode.ENGLISH)); runCurrent()
        session.cancel(); releases[2].complete(Unit); runCurrent()
        assertEquals("new", visible.single().thumbnailUrl)
    }
}
